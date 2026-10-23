package com.iosloc.spoofer.usb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One multiplexed logical connection between the host and a service on the iPhone,
 * equivalent to a usbmuxd "mux_connection" (a TCP like stream over the bulk pipe).
 *
 * The [UsbMuxTransport] owns the wire; this class only tracks sequence numbers,
 * reassembles payloads and exposes blocking IO.
 */
class MuxStream(
    val transport: UsbMuxTransport,
    val sport: Int,
    val dport: Int
) {
    @Volatile
    var state: State = State.CONNECTING
        internal set

    enum class State { CONNECTING, CONNECTED, REFUSED, CLOSED }

    // Sequence numbers (see device.c):
    //  SYN consumes one sequence number on each side.
    @Volatile internal var txSeq = 0L
    @Volatile internal var txAck = 0L
    @Volatile internal var rwWin = 131072L // window we advertise to the device

    private val chunks = ConcurrentLinkedQueue<ByteArray>()
    private var current: ByteArray? = null
    private var currentOff = 0
    private val dataLock = ReentrantLock()
    private val dataCondition = dataLock.newCondition()

    @Volatile
    private var resetReason: String? = null

    fun isConnected(): Boolean = state == State.CONNECTED

    internal fun onSynAck(seq: Long) {
        // Our SYN consumed one seq; the device's SYN consumed one seq.
        txSeq = 1
        txAck = seq + 1
        state = State.CONNECTED
    }

    internal fun onData(seq: Long, payload: ByteArray) {
        // Cumulative ACK: device payloads are delivered in order.
        txAck = seq + payload.size
        if (payload.isNotEmpty()) {
            dataLock.withLock {
                chunks.add(payload)
                dataCondition.signalAll()
            }
        }
    }

    internal fun onReset(reason: String?) {
        resetReason = reason
        state = if (state == State.CONNECTING) State.REFUSED else State.CLOSED
        dataLock.withLock { dataCondition.signalAll() }
    }

    /** Write a full TCP|ACK segment (payload may be split by the caller). */
    fun write(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        if (state != State.CONNECTED) throw IOException("流未连接 (state=$state)")
        // txSeq is advanced per segment inside the transport.
        transport.sendTcpData(this, data, off, len)
    }

    fun read(buf: ByteArray, off: Int, len: Int): Int {
        dataLock.withLock {
            while (true) {
                val cur = current
                if (cur == null) {
                    val next = chunks.poll()
                    if (next == null) {
                        if (state == State.CLOSED) return -1
                        if (state == State.REFUSED) throw IOException("连接被拒绝: ${resetReason ?: ""}")
                        dataCondition.await()
                        continue
                    }
                    current = next
                    currentOff = 0
                    return readLocked(buf, off, len)
                }
                return readLocked(buf, off, len)
            }
        }
    }

    private fun readLocked(buf: ByteArray, off: Int, len: Int): Int {
        val cur = current ?: return -1
        val avail = cur.size - currentOff
        val n = minOf(avail, len)
        System.arraycopy(cur, currentOff, buf, off, n)
        currentOff += n
        if (currentOff >= cur.size) current = null
        return n
    }

    fun readFully(buf: ByteArray, len: Int = buf.size) {
        var off = 0
        while (off < len) {
            val n = read(buf, off, len - off)
            if (n < 0) throw IOException("读取时连接断开 (need=$len got=$off)")
            off += n
        }
    }

    fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        try {
            transport.sendTcpControl(this, UsbMuxTransport.TH_RST)
        } catch (_: Throwable) { /* best effort */ }
        transport.removeStream(this)
        dataLock.withLock { dataCondition.signalAll() }
    }

    fun asInputStream(): InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            if (this@MuxStream.read(b, 0, 1) <= 0) return -1
            return b[0].toInt() and 0xFF
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            this@MuxStream.read(b, off, len)
    }

    fun asOutputStream(): OutputStream = object : OutputStream() {
        override fun write(b: Int) = this@MuxStream.write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) = this@MuxStream.write(b, off, len)
    }
}
