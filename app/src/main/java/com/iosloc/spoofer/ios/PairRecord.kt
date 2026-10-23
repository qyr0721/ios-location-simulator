package com.iosloc.spoofer.ios

import android.content.Context
import java.io.File
import java.util.Base64
import java.util.Properties
import java.util.UUID

/**
 * Locally persisted pairing record for one iPhone (keyed by UDID).
 *
 * On a desktop this is stored by usbmuxd under /var/lib/lockdown; here the app
 * plays the host role itself, so the record lives in the app's private storage.
 */
data class PairRecord(
    val udid: String,
    val hostId: String,
    val systemBuid: String,
    val wifiMac: String,
    val rootCertPem: String,
    val hostCertPem: String,
    val deviceCertPem: String,
    val rootPrivateKeyPem: String,
    val hostPrivateKeyPem: String,
    val escrowBag: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is PairRecord && other.udid == udid
    override fun hashCode(): Int = udid.hashCode()
}

class PairStore(context: Context) {

    private val dir = File(context.filesDir, "pair_records").apply { mkdirs() }
    private val buidFile = File(context.filesDir, "host_buid.txt")

    /** Stable host BUID; generated once like usbmuxd does. */
    fun getOrCreateBuid(): String {
        if (buidFile.exists()) return buidFile.readText().trim()
        val buid = UUID.randomUUID().toString().uppercase()
        buidFile.writeText(buid)
        return buid
    }

    fun get(udid: String): PairRecord? {
        val f = File(dir, "$udid.properties")
        if (!f.exists()) return null
        val p = Properties()
        f.inputStream().use { p.load(it) }
        return PairRecord(
            udid = p.getProperty("udid"),
            hostId = p.getProperty("hostId"),
            systemBuid = p.getProperty("systemBuid"),
            wifiMac = p.getProperty("wifiMac"),
            rootCertPem = p.getProperty("rootCertPem"),
            hostCertPem = p.getProperty("hostCertPem"),
            deviceCertPem = p.getProperty("deviceCertPem"),
            rootPrivateKeyPem = p.getProperty("rootPrivateKeyPem"),
            hostPrivateKeyPem = p.getProperty("hostPrivateKeyPem"),
            escrowBag = Base64.getDecoder().decode(p.getProperty("escrowBag", ""))
        )
    }

    fun save(record: PairRecord) {
        val p = Properties()
        p.setProperty("udid", record.udid)
        p.setProperty("hostId", record.hostId)
        p.setProperty("systemBuid", record.systemBuid)
        p.setProperty("wifiMac", record.wifiMac)
        p.setProperty("rootCertPem", record.rootCertPem)
        p.setProperty("hostCertPem", record.hostCertPem)
        p.setProperty("deviceCertPem", record.deviceCertPem)
        p.setProperty("rootPrivateKeyPem", record.rootPrivateKeyPem)
        p.setProperty("hostPrivateKeyPem", record.hostPrivateKeyPem)
        p.setProperty("escrowBag", Base64.getEncoder().encodeToString(record.escrowBag))
        File(dir, "${record.udid}.properties").outputStream().use { p.store(it, "pair record") }
    }

    fun allUdids(): List<String> =
        dir.listFiles { f -> f.name.endsWith(".properties") }?.map { it.name.removeSuffix(".properties") }
            ?: emptyList()
}
