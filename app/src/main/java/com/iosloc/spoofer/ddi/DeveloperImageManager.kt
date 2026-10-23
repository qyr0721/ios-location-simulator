package com.iosloc.spoofer.ddi

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.iosloc.spoofer.ios.LockdownClient
import com.iosloc.spoofer.ios.getError
import com.iosloc.spoofer.plist.PlistIO
import com.iosloc.spoofer.plist.PlistChannel
import java.io.ByteArrayInputStream

/** A developer disk image bundle prepared by the UI layer. */
data class DdiBundle(
    /** Legacy (iOS <= 12): true */
    val legacy: Boolean,
    val dmg: ByteArray,
    /** Legacy: .dmg.signature; modern: unused (signature comes from TSS) */
    val signature: ByteArray = ByteArray(0),
    /** Modern (iOS 13-16): BuildManifest.plist bytes */
    val buildManifest: ByteArray? = null,
    /** Modern: LoadableTrustCache .trustcache bytes */
    val trustCache: ByteArray? = null
) {
    fun sizeMb(): Double = dmg.size / 1048576.0
}

/**
 * Mounts the Developer Disk Image via com.apple.mobile.mobile_image_mounter.
 *
 * The simulatelocation service is only available after the DDI is mounted.
 *
 *  - iOS <= 12 : classic "Developer" image, dmg + static signature
 *  - iOS 13-16 : "Personalized" image, TSS ticket (gs.apple.com) + trust cache
 *  - iOS 17+   : requires the CoreDevice/RemoteXPC tunnel (unsupported in v1)
 */
class DeveloperImageManager(private val lockdown: LockdownClient) {

    fun isMounted(modern: Boolean): Boolean {
        val svc = lockdown.startService("com.apple.mobile.mobile_image_mounter")
        try {
            val ch = svc.plistChannel()
            val imageType = if (modern) "Personalized" else "Developer"
            val resp = ch.request(
                PlistIO.dict("Command" to "LookupImage", "ImageType" to imageType)
            )
            val sigs = resp.objectForKey("ImageSignature") as? NSArray
            val hasSig = sigs != null && sigs.getArray().any { it is NSData && it.bytes().isNotEmpty() }
            val mountPath = (resp.objectForKey("MountPath") as? NSString)?.content
            return hasSig || mountPath?.isNotEmpty() == true
        } finally {
            svc.close()
        }
    }

    /**
     * Mount the supplied [bundle]. Progress 0..1 is reported via [onProgress].
     */
    fun mount(bundle: DdiBundle, ecid: Long, onProgress: (Double) -> Unit = {}) {
        if (bundle.legacy) mountLegacy(bundle, onProgress)
        else mountPersonalized(bundle, ecid, onProgress)
    }

    // ---- iOS <= 12 --------------------------------------------------------

    private fun mountLegacy(bundle: DdiBundle, onProgress: (Double) -> Unit) {
        val svc = lockdown.startService("com.apple.mobile.mobile_image_mounter")
        try {
            val ch = svc.plistChannel()
            val ack = ch.request(
                PlistIO.dict(
                    "Command" to "ReceiveBytes",
                    "ImageType" to "Developer",
                    "ImageSize" to bundle.dmg.size,
                    "ImageSignature" to bundle.signature
                )
            )
            expectStatus(ack, "ReceiveBytes", "ReceiveBytesAck")
            uploadRaw(svc, bundle.dmg, onProgress)
            expectStatus(ch.receive(), "ReceiveBytes", "Complete")

            val mounted = ch.request(
                PlistIO.dict(
                    "Command" to "MountImage",
                    "ImageType" to "Developer",
                    "ImageSignature" to bundle.signature
                )
            )
            expectStatus(mounted, "MountImage", "Complete")
            runCatching {
                ch.request(PlistIO.dict("Command" to "Hangup"))
            }
        } finally {
            svc.close()
        }
    }

    // ---- iOS 13-16 --------------------------------------------------------

    private fun mountPersonalized(bundle: DdiBundle, ecid: Long, onProgress: (Double) -> Unit) {
        val manifest = PropertyListParser.parse(
            ByteArrayInputStream(bundle.buildManifest ?: error("缺少 BuildManifest.plist"))
        ) as NSDictionary
        val dmgDigest = TssClient.sha384(bundle.dmg)

        // 1) Try to reuse a manifest the device already has.
        var ticket: ByteArray? = null
        run {
            val svc = lockdown.startService("com.apple.mobile.mobile_image_mounter")
            try {
                val ch = svc.plistChannel()
                val resp = ch.request(
                    PlistIO.dict(
                        "Command" to "QueryPersonalizationManifest",
                        "PersonalizedImageType" to "DeveloperDiskImage",
                        "ImageType" to "DeveloperDiskImage",
                        "ImageSignature" to dmgDigest
                    )
                )
                ticket = (resp.objectForKey("ImageSignature") as? NSData)?.bytes()
            } finally {
                svc.close()
            }
        }

        if (ticket == null) {
            // 2) Need a fresh TSS ticket: reconnect, query nonce + identifiers.
            val svc = lockdown.startService("com.apple.mobile.mobile_image_mounter")
            try {
                val ch = svc.plistChannel()
                val nonceResp = ch.request(
                    PlistIO.dict(
                        "Command" to "QueryNonce",
                        "HostProcessName" to "CoreDeviceService",
                        "PersonalizedImageType" to "DeveloperDiskImage"
                    )
                )
                val nonce = (nonceResp.objectForKey("PersonalizationNonce") as? NSData)?.bytes()
                    ?: error("设备未返回 PersonalizationNonce（设备是否已解锁？）")

                val idsResp = ch.request(
                    PlistIO.dict(
                        "Command" to "QueryPersonalizationIdentifiers",
                        "PersonalizedImageType" to "DeveloperDiskImage"
                    )
                )
                val pi = idsResp.objectForKey("PersonalizationIdentifiers") as NSDictionary
                val ids = TssClient.PersonalizationIdentifiers(
                    boardId = pi.intValue("BoardId"),
                    chipId = pi.intValue("ChipID"),
                    securityDomain = pi.intValue("SecurityDomain"),
                    additional = pi.allKeys().filter { it.startsWith("Ap,") }
                        .associateWith { pi.objectForKey(it) }
                )
                ticket = TssClient.requestTicket(manifest, ids, nonce, ecid)
                svc.close()
            } catch (e: Throwable) {
                svc.close()
                throw e
            }
        }

        // 3) Upload the dmg with the TSS ticket as signature.
        val svc = lockdown.startService("com.apple.mobile.mobile_image_mounter")
        try {
            val ch = svc.plistChannel()
            val ack = ch.request(
                PlistIO.dict(
                    "Command" to "ReceiveBytes",
                    "ImageType" to "Personalized",
                    "ImageSize" to bundle.dmg.size,
                    "ImageSignature" to ticket
                )
            )
            expectStatus(ack, "ReceiveBytes", "ReceiveBytesAck")
            uploadRaw(svc, bundle.dmg, onProgress)
            expectStatus(ch.receive(), "ReceiveBytes", "Complete")

            val trustCache = bundle.trustCache ?: error("缺少 .trustcache 文件")
            val mounted = ch.request(
                PlistIO.dict(
                    "Command" to "MountImage",
                    "ImageType" to "Personalized",
                    "ImageSignature" to ticket,
                    "ImageTrustCache" to trustCache
                )
            )
            expectStatus(mounted, "MountImage", "Complete")
            runCatching { ch.request(PlistIO.dict("Command" to "Hangup")) }
        } finally {
            svc.close()
        }
    }

    private fun uploadRaw(
        svc: com.iosloc.spoofer.ios.ServiceConnection,
        data: ByteArray,
        onProgress: (Double) -> Unit
    ) {
        val chunk = 1 shl 20 // 1 MiB
        var off = 0
        while (off < data.size) {
            val n = minOf(chunk, data.size - off)
            svc.sendRaw(data.copyOfRange(off, off + n))
            off += n
            onProgress(off.toDouble() / data.size)
        }
    }

    private fun expectStatus(resp: NSDictionary, command: String, expected: String) {
        val status = (resp.objectForKey("Status") as? NSString)?.content
        if (status != expected) {
            val err = resp.getError()
            val detail = (resp.objectForKey("DetailedError") as? NSString)?.content
            throw RuntimeException("$command 失败: status=$status error=$err detail=$detail")
        }
    }
}

private fun NSDictionary.intValue(key: String): Int =
    when (val v = objectForKey(key)) {
        is NSNumber -> v.intValue()
        is NSString -> v.content.trim().toIntOrNull() ?: 0
        else -> 0
    }
