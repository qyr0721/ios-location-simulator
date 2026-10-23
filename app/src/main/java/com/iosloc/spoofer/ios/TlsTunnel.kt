package com.iosloc.spoofer.ios

import com.iosloc.spoofer.usb.MuxStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS session carried over a [MuxStream].
 *
 * After StartSession (and for SSL-enabled services like mobile_image_mounter
 * on modern iOS), the existing connection is upgraded to TLS in place using
 * the pair record: the host presents the host certificate signed by the root
 * it generated, and trusts the device certificate signed by the same root.
 */
class TlsTunnel(
    private val stream: MuxStream,
    hostPrivateKey: RSAPrivateKey,
    hostCerts: List<X509Certificate>,
    trustedRoot: X509Certificate
) {
    private val engine: SSLEngine
    private val netLock = Object()
    private val appInLock = Object()
    private val appIn = java.io.PipedInputStream()
    private val appOut = java.io.PipedOutputStream(appIn)
    private val handshaked = Object()
    @Volatile private var handshakeDone = false
    @Volatile private var failure: Throwable? = null
    @Volatile private var closed = false

    private var netFromDevice: ByteBuffer
    private var netToDevice: ByteBuffer

    val plaintextInputStream: InputStream = appIn
    val plaintextOutputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            val src = ByteBuffer.wrap(b, off, len)
            synchronized(netLock) {
                while (src.hasRemaining()) wrapAndSend(src)
            }
        }
    }

    init {
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(
                "host",
                hostPrivateKey,
                KEY_PASS,
                hostCerts.toTypedArray()
            )
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(ks, KEY_PASS)
        }
        val ts = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("root", trustedRoot)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(ts)
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, tmf.trustManagers, null)
        engine = ctx.createSSLEngine().apply {
            useClientMode = true
        }
        val pktSize = engine.session.packetBufferSize.coerceAtLeast(18 * 1024)
        val appSize = engine.session.applicationBufferSize.coerceAtLeast(16 * 1024)
        netFromDevice = ByteBuffer.allocateDirect(pktSize)
        netToDevice = ByteBuffer.allocateDirect(pktSize)
        @Suppress("UNUSED_VARIABLE") val unused = appSize
    }

    fun handshake() {
        engine.beginHandshake()
        // Thread that continuously pulls encrypted records from the device.
        val reader = Thread({
            try {
                val buf = ByteArray(engine.session.packetBufferSize.coerceAtLeast(18 * 1024))
                while (!closed) {
                    val n = stream.read(buf, 0, buf.size)
                    if (n < 0) throw IOException("TLS 读取时连接断开")
                    if (n == 0) continue
                    synchronized(netLock) {
                        if (netFromDevice.remaining() < n) growNetInput(n)
                        netFromDevice.put(buf, 0, n)
                        netFromDevice.flip()
                        processInput()
                        netFromDevice.compact()
                    }
                }
            } catch (t: Throwable) {
                if (!closed) {
                    failure = t
                    synchronized(handshaked) { handshaked.notifyAll() }
                }
            }
        }, "tls-reader").apply { isDaemon = true }
        reader.start()

        synchronized(netLock) { pumpHandshake() }
        synchronized(handshaked) {
            while (!handshakeDone && failure == null) {
                handshaked.wait(15_000)
                if (!handshakeDone && failure == null) throw IOException("TLS 握手超时")
            }
        }
        failure?.let { throw IOException("TLS 握手失败: ${it.message}", it) }
    }

    private fun growNetInput(need: Int) {
        val old = netFromDevice
        old.flip()
        val newBuf = ByteBuffer.allocateDirect(old.capacity() + need.coerceAtLeast(8192) * 2)
        newBuf.put(old)
        netFromDevice = newBuf
    }

    private fun pumpHandshake() {
        var status = engine.handshakeStatus
        while (true) {
            when (status) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netToDevice.clear()
                    val r = engine.wrap(EMPTY, netToDevice)
                    flushNet()
                    status = r.handshakeStatus
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks()
                    status = engine.handshakeStatus
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    processInput()
                    status = engine.handshakeStatus
                    if (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) return // wait for reader
                }
                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                    handshakeDone = true
                    synchronized(handshaked) { handshaked.notifyAll() }
                    return
                }
                else -> return
            }
        }
    }

    private fun processInput() {
        val appSize = engine.session.applicationBufferSize.coerceAtLeast(16 * 1024)
        while (netFromDevice.hasRemaining()) {
            val app = ByteBuffer.allocate(appSize)
            val r = engine.unwrap(netFromDevice, app)
            when (r.status) {
                SSLEngineResult.Status.OK -> {
                    app.flip()
                    if (app.hasRemaining()) {
                        val tmp = ByteArray(app.remaining())
                        app.get(tmp)
                        appOut.write(tmp)
                        appOut.flush()
                    }
                    if (r.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                        continueHandshake(r.handshakeStatus)
                    }
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> return
                SSLEngineResult.Status.CLOSED -> throw IOException("对端关闭了 TLS")
                else -> throw IOException("TLS unwrap 异常: ${r.status}")
            }
            if (r.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                handshakeDone = true
                synchronized(handshaked) { handshaked.notifyAll() }
            }
        }
    }

    private fun continueHandshake(status: SSLEngineResult.HandshakeStatus) {
        var s = status
        while (true) {
            when (s) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val empty = ByteBuffer.allocate(0)
                    netToDevice.clear()
                    engine.wrap(empty, netToDevice)
                    flushNet()
                    s = engine.handshakeStatus
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks()
                    s = engine.handshakeStatus
                }
                else -> {
                    if (s == SSLEngineResult.HandshakeStatus.FINISHED) {
                        handshakeDone = true
                        synchronized(handshaked) { handshaked.notifyAll() }
                    }
                    return
                }
            }
        }
    }

    private fun runDelegatedTasks() {
        var task = engine.delegatedTask
        while (task != null) {
            task.run()
            task = engine.delegatedTask
        }
    }

    private fun wrapAndSend(src: ByteBuffer) {
        netToDevice.clear()
        val r = engine.wrap(src, netToDevice)
        if (r.status != SSLEngineResult.Status.OK) {
            throw IOException("TLS wrap 异常: ${r.status}")
        }
        flushNet()
    }

    private fun flushNet() {
        netToDevice.flip()
        if (netToDevice.hasRemaining()) {
            val tmp = ByteArray(netToDevice.remaining())
            netToDevice.get(tmp)
            stream.write(tmp)
        }
        netToDevice.clear()
    }

    fun close() {
        closed = true
        try { appOut.close() } catch (_: Throwable) { /* ignore */ }
    }

    companion object {
        private val KEY_PASS = CharArray(0)
        private val EMPTY = ByteBuffer.allocate(0)

        fun parsePemCert(pem: String): X509Certificate {
            val cf = CertificateFactory.getInstance("X.509")
            return cf.generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate
        }
    }
}
