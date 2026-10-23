package com.iosloc.spoofer.ios

import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.iosloc.spoofer.plist.PlistChannel
import com.iosloc.spoofer.plist.PlistIO
import com.iosloc.spoofer.usb.MuxStream
import com.iosloc.spoofer.usb.UsbMuxTransport
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * lockdownd client: QueryType, GetValue, Pair (trust dialog), StartSession
 * (TLS upgrade) and StartService. Protocol order follows go-ios ios/lockdown.go,
 * ios/pair.go and ios/startsession.go.
 */
class LockdownClient(
    private val transport: UsbMuxTransport,
    private val pairStore: PairStore,
    val udid: String
) {
    private val label = "android.iosloc"

    private lateinit var stream: MuxStream
    private lateinit var input: InputStream
    private lateinit var output: OutputStream
    private lateinit var channel: PlistChannel
    private var tls: TlsTunnel? = null

    var record: PairRecord? = null
        private set
    val deviceInfo = LinkedHashMap<String, String>()

    fun connect() {
        stream = transport.connect(UsbMuxTransport.PORT_LOCKDOWN)
        input = stream.asInputStream()
        output = stream.asOutputStream()
        channel = PlistChannel(input, output)

        val qt = channel.request(PlistIO.dict("Request" to "QueryType"))
        val type = (qt.objectForKey("Type") as? NSString)?.content
        check(type == "com.apple.mobile.lockdown") { "意外的 lockdown 类型: $type" }

        listOf(
            "UniqueDeviceID", "ProductVersion", "BuildVersion", "DeviceName",
            "Model", "HardwareModel", "WiFiAddress", "SerialNumber"
        ).forEach { key ->
            getValue(key)?.let { deviceInfo[key] = it }
        }
    }

    fun getValue(key: String, domain: String? = null): String? {
        val req = mutableMapOf<String, Any?>(
            "Request" to "GetValue",
            "Key" to key,
            "Label" to label
        )
        if (domain != null) req["Domain"] = domain
        val resp = channel.request(PlistIO.dict(*req.map { it.key to it.value }.toTypedArray()))
        resp.getError()?.let { if (it.isNotEmpty() && it != "KeyNotFound") return null }
        return when (val v = resp.objectForKey("Value")) {
            is NSString -> v.content
            is NSData -> String(v.bytes())
            else -> v?.toString()
        }
    }

    private fun getValueBytes(key: String): ByteArray {
        val resp = channel.request(
            PlistIO.dict("Request" to "GetValue", "Key" to key, "Label" to label)
        )
        val v = resp.objectForKey("Value")
        if (v is NSData) return v.bytes()
        if (v is NSString) return v.content.toByteArray()
        throw RuntimeException("无法获取 $key: ${v?.javaClass}")
    }

    /**
     * Make sure we have a pair record and an active TLS session.
     * [onTrustPrompt] is invoked when the phone is showing the trust dialog.
     */
    fun ensureSession(onTrustPrompt: () -> Unit = {}): PairRecord {
        var rec = pairStore.get(udid)
        if (rec != null) {
            try {
                startSession(rec)
                this.record = rec
                return rec
            } catch (e: Throwable) {
                // Saved record is invalid (reset privacy settings etc.) -> re-pair.
            }
        }
        rec = pair(onTrustPrompt)
        startSession(rec)
        this.record = rec
        return rec
    }

    /**
     * Run the Pair exchange. The user must tap "Trust" and enter the passcode
     * on the iPhone; the device keeps answering PairingDialogResponsePending
     * until that happens.
     */
    fun pair(onTrustPrompt: () -> Unit = {}): PairRecord {
        val publicKeyPem = String(getValueBytes("DevicePublicKey"))
        val wifiMac = getValue("WiFiAddress") ?: ""
        val buid = pairStore.getOrCreateBuid()
        val hostId = UUID.randomUUID().toString().uppercase()

        val id = PairingCrypto.createIdentity(publicKeyPem)
        val pairRecord = PlistIO.dict(
            "DeviceCertificate" to id.deviceCertPem,
            "HostCertificate" to id.hostCertPem,
            "RootCertificate" to id.rootCertPem,
            "SystemBUID" to buid,
            "HostID" to hostId
        )
        var prompted = false
        val deadline = System.currentTimeMillis() + 120_000
        var resp: NSDictionary
        while (true) {
            resp = channel.request(
                PlistIO.dict(
                    "Label" to label,
                    "ProtocolVersion" to "2",
                    "Request" to "Pair",
                    "PairRecord" to pairRecord,
                    "PairingOptions" to PlistIO.dict("ExtendedPairingErrors" to true)
                )
            )
            val error = resp.getError()
            if (error.isNullOrEmpty() || error == "Success") break
            if (error == "PairingDialogResponsePending" || error == "PairingDialogResponsePendingErrors") {
                if (!prompted) {
                    prompted = true
                    onTrustPrompt()
                }
                if (System.currentTimeMillis() > deadline) {
                    throw RuntimeException("等待手机端“信任此电脑”超时")
                }
                Thread.sleep(2000)
                continue
            }
            throw RuntimeException("配对失败: $error")
        }

        val escrow = (resp.objectForKey("EscrowBag") as? NSData)?.bytes()
            ?: throw RuntimeException("配对响应缺少 EscrowBag（设备是否已锁屏？）")
        val rec = PairRecord(
            udid = udid,
            hostId = hostId,
            systemBuid = buid,
            wifiMac = wifiMac,
            rootCertPem = id.rootCertPem,
            hostCertPem = id.hostCertPem,
            deviceCertPem = id.deviceCertPem,
            rootPrivateKeyPem = id.rootPrivateKeyPem,
            hostPrivateKeyPem = id.hostPrivateKeyPem,
            escrowBag = escrow
        )
        pairStore.save(rec)
        return rec
    }

    private fun startSession(rec: PairRecord) {
        val resp = channel.request(
            PlistIO.dict(
                "Label" to label,
                "ProtocolVersion" to "2",
                "Request" to "StartSession",
                "HostID" to rec.hostId,
                "SystemBUID" to rec.systemBuid
            )
        )
        val error = resp.getError()
        if (!error.isNullOrEmpty() && error != "Success") {
            throw RuntimeException("StartSession 失败: $error")
        }
        if (resp.boolForKey("EnableSessionSSL")) {
            enableTls(rec)
        }
    }

    private fun enableTls(rec: PairRecord) {
        val hostKey = PairingCrypto.parseRsaPrivateKeyFromPem(rec.hostPrivateKeyPem)
        val hostCert = PairingCrypto.parseX509(rec.hostCertPem)
        val rootCert = PairingCrypto.parseX509(rec.rootCertPem)
        val tunnel = TlsTunnel(stream, hostKey, listOf(hostCert, rootCert), rootCert)
        tunnel.handshake()
        tls = tunnel
        input = tunnel.plaintextInputStream
        output = tunnel.plaintextOutputStream
        channel = PlistChannel(input, output)
    }

    /**
     * Start a lockdown service and return a raw [ServiceConnection] on the
     * freshly opened mux stream (with TLS if the device requires it).
     */
    fun startService(name: String): ServiceConnection {
        val rec = record ?: throw RuntimeException("尚未 StartSession")
        val resp = channel.request(
            PlistIO.dict(
                "Label" to label,
                "Request" to "StartService",
                "Service" to name
            )
        )
        val error = resp.getError()
        if (!error.isNullOrEmpty() && error != "Success") {
            throw RuntimeException("StartService($name) 失败: $error（开发者镜像是否已挂载？）")
        }
        val port = (resp.objectForKey("Port") as? NSNumber)?.intValue()
            ?: throw RuntimeException("StartService 响应缺少 Port")
        val enableSsl = resp.boolForKey("EnableServiceSSL")

        val svcStream = transport.connect(port)
        var svcInput: InputStream = svcStream.asInputStream()
        var svcOutput: OutputStream = svcStream.asOutputStream()
        if (enableSsl) {
            val hostKey = PairingCrypto.parseRsaPrivateKeyFromPem(rec.hostPrivateKeyPem)
            val hostCert = PairingCrypto.parseX509(rec.hostCertPem)
            val rootCert = PairingCrypto.parseX509(rec.rootCertPem)
            val tunnel = TlsTunnel(svcStream, hostKey, listOf(hostCert, rootCert), rootCert)
            tunnel.handshake()
            svcInput = tunnel.plaintextInputStream
            svcOutput = tunnel.plaintextOutputStream
        }
        return ServiceConnection(name, svcStream, svcInput, svcOutput, enableSsl)
    }

    fun close() {
        try { tls?.close() } catch (_: Throwable) { /* ignore */ }
        try { stream.close() } catch (_: Throwable) { /* ignore */ }
    }
}

class ServiceConnection(
    val name: String,
    private val stream: MuxStream,
    val input: InputStream,
    val output: OutputStream,
    val isSsl: Boolean
) {
    fun plistChannel() = PlistChannel(input, output)

    fun sendRaw(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    fun close() {
        try { stream.close() } catch (_: Throwable) { /* ignore */ }
    }
}

/** Pull "Error" out of a lockdown response; returns null when absent. */
fun NSDictionary.getError(): String? =
    (objectForKey("Error") as? NSString)?.content?.takeIf { it.isNotEmpty() }

/** Plist booleans come back as NSNumber; strings like "true" must not be guessed. */
fun NSDictionary.boolForKey(key: String): Boolean =
    (objectForKey(key) as? NSNumber)?.boolValue() == true
