package com.iosloc.spoofer.usb

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Low level Apple USB transport.
 *
 * iOS exposes a vendor specific USB interface
 *   bInterfaceClass = 0xFF, bInterfaceSubClass = 0xFE, bInterfaceProtocol = 2
 * with two bulk endpoints (IN / OUT). The host talks the multiplexing
 * protocol implemented by usbmuxd (see libimobiledevice/usbmuxd src/device.c).
 *
 * This class only owns the raw bulk pipes. Framing / multiplexing is handled
 * by [UsbMuxTransport].
 */
class AppleUsbDevice(
    val device: UsbDevice,
    private val usbManager: UsbManager
) {
    private var connection: UsbDeviceConnection? = null
    private var iface: UsbInterface? = null
    private var epOut: UsbEndpoint? = null
    private var epIn: UsbEndpoint? = null

    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    var maxPacketSize: Int = 512
        private set

    val serialNumber: String?
        get() = device.serialNumber

    fun open() {
        // Some Android kernels hold the interface for a moment after the USB
        // permission dialog, and some ROMs require a release+reopen before
        // claim succeeds. Retry with backoff.
        var lastError = "未知错误"
        repeat(4) { attempt ->
            val conn = usbManager.openDevice(device)
            if (conn == null) {
                lastError = "openDevice 返回 null（USB 权限未授予或设备被占用）"
                Log.w(TAG, "openDevice null, attempt=$attempt")
                Thread.sleep(300L * (attempt + 1))
                return@repeat
            }
            try {
                // The iPhone exposes several USB configurations. The usbmux
                // interface (FF/FE/2) lives in a *non-default* configuration, so
                // claiming it without selecting that configuration fails with
                // EBUSY. Walk every configuration, switch to it, then claim its
                // vendor interface.
                val candidates = buildCandidates()
                Log.i(TAG, "candidates: " + candidates.joinToString { c ->
                    "cfg=${c.config.id} iface=${c.intf.id} class=0x${c.intf.interfaceClass.toString(16)} " +
                        "sub=0x${c.intf.interfaceSubclass.toString(16)} proto=${c.intf.interfaceProtocol}"
                })
                var currentConfig = -1
                for (cand in candidates) {
                    if (currentConfig != cand.config.id) {
                        val ok = try {
                            conn.setConfiguration(cand.config)
                        } catch (t: Throwable) {
                            Log.w(TAG, "setConfiguration(id=${cand.config.id}) 异常: ${t.message}")
                            false
                        }
                        Log.i(TAG, "setConfiguration(id=${cand.config.id}) -> $ok")
                        currentConfig = cand.config.id
                    }
                    // Try a non-forced claim first (cleaner), then force.
                    if (claimWith(conn, cand.intf, force = false) || claimWith(conn, cand.intf, force = true)) {
                        val (out, inn) = bulkEndpoints(cand.intf)
                        if (out != null && inn != null) {
                            this.iface = cand.intf
                            this.connection = conn
                            epOut = out
                            epIn = inn
                            maxPacketSize = inn.maxPacketSize.coerceAtLeast(out.maxPacketSize)
                            Log.i(TAG, "claim 成功: cfg=${cand.config.id} iface=${cand.intf.id} " +
                                "class=0x${cand.intf.interfaceClass.toString(16)} " +
                                "sub=0x${cand.intf.interfaceSubclass.toString(16)} proto=${cand.intf.interfaceProtocol} " +
                                "epIn=0x${inn.address.toString(16)} epOut=0x${out.address.toString(16)} mps=$maxPacketSize")
                            return
                        }
                    }
                }
                lastError = "claimInterface 失败（共 ${candidates.size} 个候选接口，均无法占用）"
                Log.w(TAG, "claim failed on attempt=$attempt; descriptors:\n${dumpDescriptors()}")
                try { conn.close() } catch (_: Throwable) { /* ignore */ }
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                try { conn.close() } catch (_: Throwable) { /* ignore */ }
            }
            Thread.sleep(400L * (attempt + 1))
        }
        throw IOException(
            "$lastError。\n设备描述符:\n${dumpDescriptors()}\n" +
            "排查：1) 换原装/带数据功能的数据线；2) 用带供电的 OTG 转接头；" +
            "3) 若有其他 App 占用 iPhone（相册传输/USB 调试类），先强制停止；" +
            "4) 保持 iPhone 解锁，并在 设置-面容ID与密码-USB配件 中允许配件访问。"
        )
    }

    private data class Candidate(val config: UsbConfiguration, val intf: UsbInterface)

    private fun buildCandidates(): List<Candidate> {
        val list = mutableListOf<Candidate>()
        for (ci in 0 until device.configurationCount) {
            val cfg = device.getConfiguration(ci) ?: continue
            for (ii in 0 until cfg.interfaceCount) {
                val intf = cfg.getInterface(ii)
                if (intf.interfaceClass == 0xFF && hasBulkPair(intf)) {
                    list.add(Candidate(cfg, intf))
                }
            }
        }
        // Exact usbmux interface first, then any other vendor bulk interface.
        return list.sortedBy {
            if (it.intf.interfaceSubclass == 0xFE && it.intf.interfaceProtocol == 2) 0 else 1
        }
    }

    private fun claimWith(conn: UsbDeviceConnection, intf: UsbInterface, force: Boolean): Boolean =
        try {
            if (conn.claimInterface(intf, force)) {
                true
            } else {
                Log.w(TAG, "claimInterface(${intf.id}, force=$force) 返回 false")
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "claimInterface(${intf.id}, force=$force) 异常: ${t.message}")
            false
        }

    private fun bulkEndpoints(intf: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
        var out: UsbEndpoint? = null
        var inn: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type != android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if ((ep.address and 0x80) == 0x80) inn = ep else out = ep
        }
        return out to inn
    }

    private fun hasBulkPair(intf: UsbInterface): Boolean {
        val (o, i) = bulkEndpoints(intf)
        return o != null && i != null
    }

    /** Full descriptor dump for the failure message / logcat. */
    private fun dumpDescriptors(): String {
        val sb = StringBuilder()
        sb.append("VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)} ")
        sb.append("configCount=${device.configurationCount} ifaceCount=${device.interfaceCount}\n")
        for (ci in 0 until device.configurationCount) {
            val cfg = device.getConfiguration(ci) ?: continue
            sb.append("config[$ci] id=${cfg.id} ifaces=${cfg.interfaceCount}\n")
            for (ii in 0 until cfg.interfaceCount) {
                val f = cfg.getInterface(ii)
                sb.append("  iface[${f.id}] class=0x${f.interfaceClass.toString(16)} ")
                sb.append("sub=0x${f.interfaceSubclass.toString(16)} proto=${f.interfaceProtocol} eps=${f.endpointCount}\n")
                for (j in 0 until f.endpointCount) {
                    val ep = f.getEndpoint(j)
                    val dir = if ((ep.address and 0x80) == 0x80) "IN" else "OUT"
                    sb.append("    ep 0x${ep.address.toString(16)} $dir type=${ep.type} mps=${ep.maxPacketSize}\n")
                }
            }
        }
        return sb.toString()
    }

    /**
     * Bulk write, appending a Zero Length Packet when the transfer length is an exact
     * multiple of the endpoint max packet size (the device waits for a ZLP to know a
     * transfer ended, mirroring usbmuxd usb.c usb_send).
     */
    fun writeBulk(data: ByteArray, len: Int = data.size) {
        val conn = connection ?: throw IOException("设备未连接")
        val ep = epOut ?: throw IOException("端点未就绪")
        synchronized(writeLock) {
            var off = 0
            while (off < len) {
                val n = conn.bulkTransfer(ep, data, off, len - off, USB_TIMEOUT_MS)
                if (n < 0) throw IOException("bulk write 失败: $n")
                off += n
            }
            if (len % maxPacketSize == 0 && len > 0) {
                conn.bulkTransfer(ep, ByteArray(0), 0, 0, USB_TIMEOUT_MS)
            }
        }
    }

    /**
     * Bulk read of up to [size] bytes. Returns number of bytes actually read,
     * or -1 on timeout / failure.
     */
    fun readBulk(buf: ByteArray, size: Int = buf.size, timeout: Int = USB_TIMEOUT_MS): Int {
        val conn = connection ?: throw IOException("设备未连接")
        val ep = epIn ?: throw IOException("端点未就绪")
        return conn.bulkTransfer(ep, buf, size, timeout)
    }

    fun isClosed(): Boolean = closed.get()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            iface?.let { connection?.releaseInterface(it) }
        } catch (_: Throwable) { /* ignore */ }
        try {
            connection?.close()
        } catch (_: Throwable) { /* ignore */ }
        connection = null
        iface = null
    }

    companion object {
        private const val TAG = "AppleUsb"
        const val USB_TIMEOUT_MS = 10_000

        const val APPLE_VID = 0x05AC
        private const val PID_RANGE_LOW = 0x1290
        private const val PID_RANGE_MAX = 0x12AF
        private const val PID_T2 = 0x8600

        const val IF_CLASS = 0xFF
        const val IF_SUBCLASS = 0xFE
        const val IF_PROTOCOL = 2

        /**
         * On USB-C iPhones the usbmux interface has 3 endpoints (the extra one is an
         * interrupt endpoint); we only require the two bulk endpoints.
         */
        fun findAppleInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == IF_CLASS &&
                    intf.interfaceSubclass == IF_SUBCLASS &&
                    intf.interfaceProtocol == IF_PROTOCOL
                ) {
                    var bulkIn = 0
                    var bulkOut = 0
                    for (j in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(j)
                        if (ep.type != android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                        if ((ep.address and 0x80) == 0x80) bulkIn++ else bulkOut++
                    }
                    if (bulkIn >= 1 && bulkOut >= 1) return intf
                }
            }
            return null
        }

        fun isAppleDevice(device: UsbDevice): Boolean {
            if (device.vendorId != APPLE_VID) return false
            val pid = device.productId
            return pid in PID_RANGE_LOW..PID_RANGE_MAX || pid == PID_T2
        }
    }
}
