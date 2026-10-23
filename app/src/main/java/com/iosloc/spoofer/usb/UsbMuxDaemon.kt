package com.iosloc.spoofer.usb

import android.content.Context
import android.hardware.usb.UsbManager
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSString
import com.iosloc.spoofer.plist.PlistIO
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * A tiny usbmuxd replacement that runs inside the app.
 *
 * The iPhone's USB interface only exposes the usbmux multiplexing protocol. On a
 * desktop the Apple usbmuxd daemon translates the standard usbmuxd client
 * protocol (a TCP socket on port 27015) into that USB protocol. Android has no
 * such daemon, so this class implements the server side:
 *
 *   go-ios process --TCP 127.0.0.1:27015--> UsbMuxDaemon
 *                                          `--USB--> UsbMuxTransport --> iPhone
 *
 * Implemented requests: ListDevices, ReadBUID, ReadPairRecord, SavePairRecord,
 * DeletePairRecord, Connect (which becomes a raw tunnel), Listen.
 */
class UsbMuxDaemon(
    private val context: Context,
    private val log: (String) -> Unit
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private val transportLock = Any()
    private val connectLock = Any()

    private var server: ServerSocket? = null
    private var transport: UsbMuxTransport? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ acceptLoop() }, "usbmuxd-accept").start()
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Throwable) {}
        server = null
        synchronized(transportLock) {
            try { transport?.close() } catch (_: Throwable) {}
            transport = null
        }
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
            server = ss
            log("usbmuxd 已监听 127.0.0.1:$PORT")
            while (running.get()) {
                val client = ss.accept()
                Thread({ handleClient(client) }, "usbmuxd-client").start()
            }
        } catch (t: Throwable) {
            if (running.get()) log("usbmuxd 服务异常: ${t.message}")
        }
    }

    private fun handleClient(socket: Socket) {
        var handedOver = false
        try {
            socket.soTimeout = 0
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

            while (running.get()) {
                val length = try {
                    readIntLe(input)
                } catch (_: EOFException) {
                    return
                } catch (t: Throwable) {
                    log("读取请求头失败: ${t.message}")
                    return
                }
                if (length < 16 || length > MAX_REQUEST) {
                    log("非法 usbmuxd 报文长度 $length")
                    return
                }
                val version = readIntLe(input)
                val request = readIntLe(input)
                val tag = readIntLe(input)
                val payload = ByteArray(length - 16)
                input.readFully(payload)
                if (version != 1) log("警告: usbmuxd 版本=$version request=$request")

                val dict = try {
                    PlistIO.parse(payload) as? NSDictionary
                } catch (t: Throwable) {
                    log("plist 解析失败: ${t.message}")
                    null
                }
                if (dict == null) {
                    send(output, tag, result(1))
                    continue
                }

                val messageType = (dict.objectForKey("MessageType") as? NSString)?.content ?: ""
                if (messageType == "Connect") {
                    val hex = payload.joinToString("") { "%02x".format(it) }
                    android.util.Log.i("UsbMuxDaemon", "Connect raw(${payload.size})=$hex")
                }
                when (messageType) {
                    "ListDevices" -> send(output, tag, listDevices())
                    "ReadBUID" -> send(output, tag, PlistIO.dict("BUID" to buid()))
                    "ReadPairRecord" -> send(output, tag, readPairRecord(dict))
                    "SavePairRecord" -> send(output, tag, savePairRecord(dict))
                    "DeletePairRecord" -> send(output, tag, deletePairRecord(dict))
                    "Listen" -> send(output, tag, result(0))
                    "Connect" -> {
                        if (handleConnect(dict, output, tag, socket)) {
                            handedOver = true
                            return
                        }
                    }
                    else -> {
                        log("未知 usbmuxd 请求: $messageType")
                        send(output, tag, result(1))
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) log("usbmuxd 客户端异常: ${t.message}")
        } finally {
            if (!handedOver) {
                try { socket.close() } catch (_: Throwable) {}
            }
        }
    }

    // ---- usbmuxd requests -------------------------------------------------

    private fun listDevices(): NSDictionary {
        val device = findAppleDevice()
        if (device == null) {
            return PlistIO.dict("DeviceList" to emptyList<NSDictionary>())
        }
        val udid = runCatching { device.serialNumber }.getOrNull() ?: ""
        val props = PlistIO.dict(
            "ConnectionSpeed" to 480_000_000,
            "ConnectionType" to "USB",
            "DeviceID" to DEVICE_ID,
            "LocationID" to 0,
            "ProductID" to device.productId,
            "SerialNumber" to udid
        )
        val entry = PlistIO.dict(
            "DeviceID" to DEVICE_ID,
            "MessageType" to "Attached",
            "Properties" to props
        )
        return PlistIO.dict("DeviceList" to listOf(entry))
    }

    private fun readPairRecord(dict: NSDictionary): NSDictionary {
        val id = stringValue(dict, "PairRecordID") ?: stringValue(dict, "PairRecordId")
        val file = id?.let { pairFile(it) }
        if (file == null || !file.exists()) {
            return result(2) // no pair record
        }
        return PlistIO.dict("PairRecordData" to NSData(file.readBytes()))
    }

    private fun savePairRecord(dict: NSDictionary): NSDictionary {
        val data = dict.objectForKey("PairRecordData") as? NSData ?: return result(1)
        val id = stringValue(dict, "PairRecordID")
            ?: stringValue(dict, "PairRecordId")
            ?: findAppleDevice()?.let { runCatching { it.serialNumber }.getOrNull() }
            ?: return result(2)
        val file = pairFile(id)
        file.parentFile?.mkdirs()
        file.writeBytes(data.bytes())
        log("已保存配对记录: ${file.name} (${data.bytes().size} bytes)")
        return result(0)
    }

    private fun deletePairRecord(dict: NSDictionary): NSDictionary {
        val id = stringValue(dict, "PairRecordID") ?: stringValue(dict, "PairRecordId")
        if (id != null) {
            val f = pairFile(id)
            if (f.exists()) f.delete()
        }
        return result(0)
    }

    /**
     * Open a mux stream for the requested port. On success the socket becomes a
     * raw byte pipe, so the handler returns without closing it.
     */
    private fun handleConnect(
        dict: NSDictionary,
        output: DataOutputStream,
        tag: Int,
        socket: Socket
    ): Boolean {
        val deviceId = (dict.objectForKey("DeviceID") as? NSNumber)?.intValue() ?: DEVICE_ID
        val rawPort = (dict.objectForKey("PortNumber") as? NSNumber)?.intValue() ?: 0
        // go-ios/libusbmuxd send the port in network byte order (see go-ios
        // ios.Lockdownport = 32498 with Ntohs(32498) == 62078), while
        // UsbMuxTransport.connect() expects a host-order port.
        val port = ntohs(rawPort)
        android.util.Log.i(
            "UsbMuxDaemon",
            "Connect parsed device=$deviceId rawPort=$rawPort port=$port"
        )
        if (deviceId != DEVICE_ID || port <= 0 || port > 65535) {
            log("Connect 参数非法 device=$deviceId port=$port")
            send(output, tag, result(2))
            return false
        }

        val t = try {
            ensureTransport()
        } catch (t: Throwable) {
            log("USB mux 启动失败: ${t.message}")
            send(output, tag, result(3))
            return false
        }

        val stream = try {
            synchronized(connectLock) { t.connect(port, 15_000L) }
        } catch (t: Throwable) {
            log("连接端口 $port 失败: ${t.message}")
            send(output, tag, result(3))
            return false
        }

        send(output, tag, result(0))
        log("Connect 已建立: port=$port")
        startPipe(socket, stream)
        return true
    }

    private fun startPipe(socket: Socket, stream: MuxStream) {
        Thread({
            try {
                socket.getInputStream().copyTo(stream.asOutputStream(), 16384)
            } catch (_: Throwable) {
            } finally {
                try { stream.close() } catch (_: Throwable) {}
                try { socket.close() } catch (_: Throwable) {}
            }
        }, "usbmuxd-pipe-up").start()

        Thread({
            try {
                stream.asInputStream().copyTo(socket.getOutputStream(), 16384)
            } catch (_: Throwable) {
            } finally {
                try { socket.close() } catch (_: Throwable) {}
                try { stream.close() } catch (_: Throwable) {}
            }
        }, "usbmuxd-pipe-down").start()
    }

    // ---- USB --------------------------------------------------------------

    private fun ensureTransport(): UsbMuxTransport {
        synchronized(transportLock) {
            transport?.let { existing ->
                if (existing.active) return existing
                try { existing.close() } catch (_: Throwable) {}
                transport = null
            }
            val device = findAppleDevice() ?: throw IOException("未找到 Apple USB 设备")
            if (!usbManager.hasPermission(device)) throw IOException("没有 USB 权限")
            val apple = AppleUsbDevice(device, usbManager)
            val t = UsbMuxTransport(apple)
            log("启动 USB mux (VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)})…")
            t.start()
            log("USB mux 就绪: version=${t.version}")
            transport = t
            return t
        }
    }

    private fun findAppleDevice() =
        usbManager.deviceList.values.firstOrNull { AppleUsbDevice.isAppleDevice(it) }

    /** UDID of the attached iPhone, or null when no device/permission is present. */
    fun udid(): String? =
        findAppleDevice()?.let { runCatching { it.serialNumber }.getOrNull() }

    // ---- helpers ----------------------------------------------------------

    private fun send(output: DataOutputStream, tag: Int, dict: NSDictionary) {
        val xml = PlistIO.toXml(dict)
        output.writeInt(Integer.reverseBytes(16 + xml.size))
        output.writeInt(Integer.reverseBytes(1))
        output.writeInt(Integer.reverseBytes(8))
        output.writeInt(Integer.reverseBytes(tag))
        output.write(xml)
        output.flush()
    }

    private fun result(number: Int): NSDictionary =
        PlistIO.dict("MessageType" to "Result", "Number" to number)

    private fun readIntLe(input: DataInputStream): Int =
        Integer.reverseBytes(input.readInt())

    private fun ntohs(value: Int): Int =
        ((value and 0xFF) shl 8) or ((value shr 8) and 0xFF)

    private fun stringValue(dict: NSDictionary, key: String): String? =
        (dict.objectForKey(key) as? NSString)?.content

    private fun pairFile(udid: String): File =
        File(File(context.filesDir, "pair").apply { mkdirs() }, "$udid.plist")

    private fun buid(): String {
        val f = File(context.filesDir, "buid.txt")
        if (f.exists()) {
            val s = f.readText().trim()
            if (s.isNotEmpty()) return s
        }
        val s = UUID.randomUUID().toString().uppercase()
        f.writeText(s)
        return s
    }

    companion object {
        private const val PORT = 27015
        private const val DEVICE_ID = 1
        private const val MAX_REQUEST = 8 * 1024 * 1024
    }
}
