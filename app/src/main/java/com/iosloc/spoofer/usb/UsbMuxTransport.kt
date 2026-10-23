package com.iosloc.spoofer.usb

import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * usbmux over USB protocol v2, a direct Kotlin port of the subset of
 * libimobiledevice/usbmuxd 1.1.1 src/device.c that a single host needs:
 *
 *   1. version handshake (host proposes 2.0, device replies, host sends SETUP 0x07);
 *   2. TCP-like multiplexed streams: SYN -> SYN/ACK -> ACK, data, RST.
 *
 * No usbmuxd daemon is needed - the Android phone itself is the host, and it
 * talks to the Apple device over the bulk endpoints claimed from UsbManager.
 */
class UsbMuxTransport(private val usb: AppleUsbDevice) {

    private val running = AtomicBoolean(false)
    private val writerLock = ReentrantLock()
    private val streamLock = ReentrantLock()
    private val streams = HashMap<Int, MuxStream>() // keyed by sport
    private val nextSport = AtomicInteger(1)
    private val reader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbmux-reader").apply { isDaemon = true }
    }

    @Volatile
    var version: Int = 0
        private set

    @Volatile
    var active: Boolean = false
        private set

    private var muxTxSeq = 0
    private var muxRxSeq = 0xFFFF

    @Volatile
    var onClosed: (() -> Unit)? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        usb.open()
        // We propose protocol 2.0 with the legacy 8-byte header for the version packet.
        sendVersionProbe()
        reader.execute { readLoop() }
        // Wait for handshake.
        val deadline = System.currentTimeMillis() + 5000
        while (!active && System.currentTimeMillis() < deadline) {
            if (!running.get()) throw IOException("握手失败：设备未响应")
            Thread.sleep(20)
        }
        if (!active) throw IOException("握手超时：设备未响应 version 报文")
    }

    private fun sendVersionProbe() {
        val buf = ByteBuffer.allocate(8 + 12).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(PROTO_VERSION)
        buf.putInt(8 + 12) // length
        buf.putInt(2)      // major
        buf.putInt(0)      // minor
        buf.putInt(0)      // padding
        usb.writeBulk(buf.array())
    }

    /** Open a logical stream to [dport] on the device (e.g. 62078 lockdown). */
    fun connect(dport: Int, timeoutMs: Long = 8000): MuxStream {
        val sport = nextSport.getAndIncrement().coerceAtLeast(1)
        val stream = MuxStream(this, sport, dport)
        streamLock.withLock { streams[sport] = stream }
        sendTcpControl(stream, TH_SYN)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (stream.state == MuxStream.State.CONNECTING && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        if (stream.state == MuxStream.State.CONNECTING) {
            removeStream(stream)
            throw IOException("连接端口 $dport 超时（SYN 无响应）")
        }
        if (stream.state != MuxStream.State.CONNECTED) {
            removeStream(stream)
            throw IOException("连接端口 $dport 被拒绝")
        }
        return stream
    }

    private fun readLoop() {
        val mru = ByteArray(USB_MRU)
        var assembly: ByteArray? = null
        var assemblyLen = 0
        try {
            while (running.get()) {
                val n = usb.readBulk(mru)
                if (n <= 0) {
                    if (running.get()) Log.w(TAG, "bulk read returned $n")
                    continue
                }
                var data: ByteArray
                var len: Int
                if (assemblyLen > 0) {
                    val merged = ByteArray(assemblyLen + n)
                    System.arraycopy(assembly!!, 0, merged, 0, assemblyLen)
                    System.arraycopy(mru, 0, merged, assemblyLen, n)
                    assembly = merged
                    assemblyLen = merged.size
                    val declared = declaredLength(merged)
                    if (n < USB_MRU || declared == assemblyLen) {
                        data = merged
                        len = assemblyLen
                        assembly = null
                        assemblyLen = 0
                    } else {
                        continue
                    }
                } else {
                    val declared = declaredLength(mru)
                    if (n == USB_MRU && declared > n) {
                        assembly = mru.copyOf(n)
                        assemblyLen = n
                        continue
                    }
                    data = mru
                    len = n
                }
                if (declaredLength(data) != len) {
                    Log.w(TAG, "报文长度不匹配: declared=${declaredLength(data)} actual=$len，丢弃")
                    continue
                }
                dispatch(data, len)
            }
        } catch (e: Throwable) {
            if (running.get()) Log.e(TAG, "read loop died", e)
        } finally {
            close()
        }
    }

    private fun declaredLength(buf: ByteArray): Int =
        ((buf[4].toInt() and 0xFF) shl 24) or
            ((buf[5].toInt() and 0xFF) shl 16) or
            ((buf[6].toInt() and 0xFF) shl 8) or
            (buf[7].toInt() and 0xFF)

    private fun dispatch(buf: ByteArray, len: Int) {
        val protocol = readIntBE(buf, 0)
        when (protocol) {
            PROTO_VERSION -> handleVersion(buf, len)
            PROTO_CONTROL -> {
                val hdrSize = if (version >= 2) 16 else 8
                val payload = if (len > hdrSize) buf.copyOfRange(hdrSize, len) else ByteArray(0)
                if (payload.isNotEmpty()) Log.i(TAG, "设备控制消息 type=${payload[0].toInt()}")
            }
            PROTO_TCP -> handleTcp(buf, len)
            else -> Log.w(TAG, "未知协议 $protocol")
        }
    }

    private fun handleVersion(buf: ByteArray, len: Int) {
        // Before the handshake we use the legacy 8-byte header, so the version
        // header is at offset 8.
        val off = 8
        if (len < off + 12) return
        val major = readIntBE(buf, off)
        val minor = readIntBE(buf, off + 4)
        if (major != 2 && major != 1) {
            Log.e(TAG, "设备返回未知协议版本 $major.$minor")
            running.set(false)
            return
        }
        version = major
        if (major >= 2) {
            muxTxSeq = 0
            muxRxSeq = 0xFFFF
            sendRawPacket(PROTO_SETUP, null, byteArrayOf(0x07))
        }
        active = true
        Log.i(TAG, "usbmux 协议版本 $major.$minor，设备就绪")
    }

    private fun handleTcp(buf: ByteArray, len: Int) {
        val hdrSize = if (version >= 2) 16 else 8
        if (len < hdrSize + 20) return
        val off = hdrSize
        val incomingDport = readU16BE(buf, off + 2) // th_dport: our sport
        val incomingSport = readU16BE(buf, off + 0)  // th_sport: device side port
        val seq = readIntBE(buf, off + 4).toLong() and 0xFFFFFFFFL
        val ack = readIntBE(buf, off + 8).toLong() and 0xFFFFFFFFL
        val flags = buf[off + 13].toInt() and 0xFF
        val payloadOff = off + 20
        val payloadLen = len - payloadOff
        val payload = if (payloadLen > 0) buf.copyOfRange(payloadOff, len) else ByteArray(0)

        val stream = streamLock.withLock { streams[incomingDport] }
        if (stream == null) {
            if (flags and TH_RST == 0) {
                // Answer with an anonymous RST, just like usbmuxd.
                sendAnonymousRst(incomingDport, incomingSport, seq)
            }
            return
        }
        when (stream.state) {
            MuxStream.State.CONNECTING -> {
                if (flags == (TH_SYN or TH_ACK)) {
                    stream.onSynAck(seq)
                    sendTcpControl(stream, TH_ACK)
                } else {
                    if (flags and TH_RST != 0) {
                        stream.onReset(String(payload))
                    } else {
                        stream.onReset("非预期握手响应 flags=0x${flags.toString(16)}")
                    }
                    removeStream(stream)
                }
            }
            MuxStream.State.CONNECTED -> {
                if (flags and TH_RST != 0) {
                    stream.onReset(String(payload))
                    removeStream(stream)
                    return
                }
                if (payloadLen > 0) {
                    stream.onData(seq, payload)
                }
                // ACK every inbound segment promptly.
                sendTcpControl(stream, TH_ACK)
            }
            else -> { /* closed */ }
        }
    }

    internal fun sendTcpData(stream: MuxStream, data: ByteArray, off: Int, len: Int) {
        var remaining = len
        var pos = off
        while (remaining > 0) {
            val chunk = minOf(remaining, MAX_PAYLOAD)
            // buildTcpHeader stamps stream.txSeq; advance per segment so that a
            // multi-segment write carries consecutive sequence numbers.
            val th = buildTcpHeader(stream, TH_ACK, chunk)
            val seg = ByteBuffer.allocate(th.size + chunk).order(ByteOrder.BIG_ENDIAN)
            seg.put(th)
            seg.put(data, pos, chunk)
            sendRawPacket(PROTO_TCP, seg.array(), null)
            stream.txSeq += chunk
            pos += chunk
            remaining -= chunk
        }
    }

    internal fun sendTcpControl(stream: MuxStream, flags: Int) {
        val th = buildTcpHeader(stream, flags, 0)
        sendRawPacket(PROTO_TCP, th, null)
    }

    private fun sendAnonymousRst(sport: Int, dport: Int, ack: Long) {
        val th = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        th.putShort(sport.toShort())
        th.putShort(dport.toShort())
        th.putInt(0)
        th.putInt(ack.toInt())
        th.put((5 shl 4).toByte())
        th.put(TH_RST.toByte())
        th.putShort(0)
        th.putShort(0)
        th.putShort(0)
        sendRawPacket(PROTO_TCP, th.array(), null)
    }

    private fun buildTcpHeader(stream: MuxStream, flags: Int, payloadLen: Int): ByteArray {
        val th = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        th.putShort(stream.sport.toShort())
        th.putShort(stream.dport.toShort())
        var seq = stream.txSeq
        if (flags and TH_SYN != 0) seq = 0
        th.putInt(seq.toInt())
        th.putInt(stream.txAck.toInt())
        th.put((5 shl 4).toByte())
        th.put(flags.toByte())
        th.putShort((stream.rwWin / 256).toInt().toShort())
        th.putShort(0) // checksum
        th.putShort(0) // urgent
        return th.array()
    }

    private fun sendRawPacket(protocol: Int, header: ByteArray?, payload: ByteArray?) {
        val hdrLen = when (protocol) {
            PROTO_VERSION -> 12
            PROTO_SETUP -> 0
            PROTO_TCP -> 20
            PROTO_CONTROL -> 0
            else -> 0
        }
        val payloadLen = payload?.size ?: 0
        val headerLen = header?.size ?: 0
        val total = if (protocol == PROTO_VERSION) 8 + 12 else 16 + headerLen + payloadLen
        val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        if (protocol == PROTO_VERSION) {
            // legacy 8-byte header (only used for the initial probe)
            buf.putInt(protocol); buf.putInt(total)
        } else {
            buf.putInt(protocol)
            buf.putInt(total)
            buf.putInt(MUX_MAGIC)
            if (protocol == PROTO_SETUP) {
                muxTxSeq = 0
                muxRxSeq = 0xFFFF
            }
            buf.putShort(muxTxSeq.toShort())
            buf.putShort(muxRxSeq.toShort())
            muxTxSeq++
        }
        if (header != null) buf.put(header)
        if (payload != null) buf.put(payload)
        writerLock.withLock { usb.writeBulk(buf.array()) }
    }

    internal fun removeStream(stream: MuxStream) {
        streamLock.withLock { streams.remove(stream.sport) }
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        streamLock.withLock {
            streams.values.forEach { it.onReset("传输关闭") }
            streams.clear()
        }
        active = false
        try { usb.close() } catch (_: Throwable) { /* ignore */ }
        reader.shutdownNow()
        onClosed?.invoke()
    }

    private fun readIntBE(buf: ByteArray, off: Int): Int =
        (((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF))

    private fun readU16BE(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    companion object {
        private const val TAG = "UsbMux"

        private const val USB_MRU = 16384
        private const val USB_MTU = 3 * 16384
        // Keep every outgoing segment inside a single USB bulk URB (16384), this
        // avoids depending on host-controller fragmentation behavior.
        private const val MAX_PAYLOAD = USB_MRU - 16 - 20 // 16348

        const val PROTO_VERSION = 0
        const val PROTO_CONTROL = 1
        const val PROTO_SETUP = 2
        const val PROTO_TCP = 6

        const val TH_FIN = 0x01
        const val TH_SYN = 0x02
        const val TH_RST = 0x04
        const val TH_ACK = 0x10
        const val MUX_MAGIC = 0xfeedface.toInt()

        const val PORT_LOCKDOWN = 62078
    }
}
