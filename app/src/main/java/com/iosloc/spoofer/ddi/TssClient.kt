package com.iosloc.spoofer.ddi

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.iosloc.spoofer.plist.PlistIO
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * Personalization ticket request to Apple's TSS server for iOS 13+ Developer
 * Disk Images, a Kotlin port of go-ios ios/imagemounter/tss.go.
 *
 * The DDI's BuildManifest.plist describes trusted components; the device
 * supplies nonce / identifiers; Apple returns the Img4 ticket (the
 * "personalization manifest" / signature) needed to mount the image.
 */
object TssClient {

    private const val TSS_URL = "https://gs.apple.com/TSS/controller?action=2"

    data class PersonalizationIdentifiers(
        val boardId: Int,
        val chipId: Int,
        val securityDomain: Int,
        val additional: Map<String, NSObject>
    )

    /**
     * @param buildManifest parsed BuildManifest.plist from the DDI Restore folder
     * @param dmgBytes raw dmg bytes (to sha384)
     */
    fun requestTicket(
        buildManifest: NSDictionary,
        ids: TssClient.PersonalizationIdentifiers,
        nonce: ByteArray,
        ecid: Long
    ): ByteArray {
        val identity = findIdentity(buildManifest, ids.boardId, ids.chipId)
        val manifest = identity.objectForKey("Manifest") as NSDictionary
        val rules = extractRules(manifest)

        val params = PlistIO.dict(
            "@ApImg4Ticket" to true,
            "@BBTicket" to true,
            "@HostPlatformInfo" to "mac",
            "@VersionInfo" to "libauthinstall-1104.0.9",
            "@UUID" to UUID.randomUUID().toString(),
            "ApBoardID" to ids.boardId,
            "ApChipID" to ids.chipId,
            "ApECID" to ecid,
            "ApNonce" to nonce,
            "ApProductionMode" to true,
            "ApSecurityDomain" to ids.securityDomain,
            "ApSecurityMode" to true,
            "SepNonce" to ByteArray(20),
            "UID_MODE" to false
        )

        val tssConditions = PlistIO.dict(
            "ApProductionMode" to true,
            "ApSecurityMode" to true,
            "ApSupportsImg4" to true
        )

        for (key in manifest.allKeys()) {
            val entry = manifest.objectForKey(key) as? NSDictionary ?: continue
            val trusted = (entry.objectForKey("Trusted") as? NSNumber)?.boolValue() ?: false
            if (!trusted) continue
            val entryParams = PlistIO.dict(
                "Digest" to ((entry.objectForKey("Digest") as? NSData)?.bytes() ?: ByteArray(0)),
                "Trusted" to true
            )
            entry.objectForKey("EPRO")?.let { if (it is NSNumber) entryParams.put("EPRO", it) }
            entry.objectForKey("ESEC")?.let { if (it is NSNumber) entryParams.put("ESEC", it) }
            applyRules(entryParams, tssConditions, rules)

            if (key == "PersonalizedDMG" || key == "PersonalizedDmg") {
                val name = (entry.objectForKey("Name") as? NSString)?.content
                entryParams.put("Name", name?.takeIf { it.isNotEmpty() } ?: "DeveloperDiskImage")
            }
            params.put(key, entryParams)
        }

        ids.additional.forEach { (k, v) -> params.put(k, v) }

        return post(params)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractRules(manifest: NSDictionary): List<NSDictionary> {
        val tc = manifest.objectForKey("LoadableTrustCache") as? NSDictionary ?: return emptyList()
        val info = tc.objectForKey("Info") as? NSDictionary ?: return emptyList()
        val arr = info.objectForKey("RestoreRequestRules") as? NSArray ?: return emptyList()
        return arr.getArray().map { it as NSDictionary }
    }

    private fun applyRules(entry: NSDictionary, conditions: NSDictionary, rules: List<NSDictionary>) {
        for (rule in rules) {
            val cond = rule.objectForKey("Conditions") as? NSDictionary ?: continue
            if (!conditionsMet(cond, conditions)) continue
            val actions = rule.objectForKey("Actions") as? NSDictionary ?: continue
            for (key in actions.allKeys()) {
                val v = actions.objectForKey(key)
                if (v is NSNumber && v.intValue() == 255) continue
                entry.put(key, v)
            }
        }
    }

    private fun conditionsMet(cond: NSDictionary, params: NSDictionary): Boolean {
        for (key in cond.allKeys()) {
            val want = cond.objectForKey(key)
            val gotKey = when (key) {
                "ApRawProductionMode", "ApCurrentProductionMode" -> "ApProductionMode"
                "ApRawSecurityMode" -> "ApSecurityMode"
                "ApRequiresImage4" -> "ApSupportsImg4"
                "ApDemotionPolicyOverride" -> "DemotionPolicy"
                "ApInRomDFU" -> "ApInRomDFU"
                else -> return false
            }
            val got = params.objectForKey(gotKey) ?: return false
            if (!plistEquality(got, want)) return false
        }
        return true
    }

    private fun plistEquality(a: NSObject, b: NSObject): Boolean =
        when {
            a is NSNumber && b is NSNumber -> a.boolValue() == b.boolValue()
            a is NSString && b is NSString -> a.content == b.content
            else -> false
        }

    private fun findIdentity(manifest: NSDictionary, boardId: Int, chipId: Int): NSDictionary {
        val arr = manifest.objectForKey("BuildIdentities") as? NSArray
            ?: throw RuntimeException("BuildManifest 缺少 BuildIdentities")
        for (o in arr.getArray()) {
            val id = o as NSDictionary
            val b = hexToInt((id.objectForKey("ApBoardID") as NSString).content)
            val c = hexToInt((id.objectForKey("ApChipID") as NSString).content)
            if (b == boardId && c == chipId) return id
        }
        throw RuntimeException("DDI BuildManifest 中找不到与 BoardId=$boardId ChipId=$chipId 匹配的身份")
    }

    private fun hexToInt(s: String): Int =
        s.replace("0x", "", ignoreCase = true).toInt(16)

    private fun post(params: NSDictionary): ByteArray {
        val body = PlistIO.toXml(params)
        val conn = (URL(TSS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "text/x-apple-plist")
        }
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        if (code != 200) throw RuntimeException("TSS 服务器返回 HTTP $code")
        val text = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        val status = extractField(text, "STATUS")?.toIntOrNull() ?: -1
        if (status != 0) {
            throw RuntimeException("TSS 拒绝 (status=$status): ${extractField(text, "MESSAGE")}")
        }
        var plistText = extractField(text, "REQUEST_STRING")
            ?: throw RuntimeException("TSS 响应缺少 REQUEST_STRING")
        if (!plistText.trimStart().startsWith("<?xml") && !plistText.trimStart().startsWith("bplist")) {
            plistText = java.net.URLDecoder.decode(plistText, "UTF-8")
        }
        val resp = PropertyListParser.parse(plistText.toByteArray()) as NSDictionary
        return (resp.objectForKey("ApImg4Ticket") as? NSData)?.bytes()
            ?: throw RuntimeException("TSS 响应缺少 ApImg4Ticket")
    }

    private fun extractField(body: String, key: String): String? {
        val idx = body.indexOf("$key=")
        if (idx < 0) return null
        var tail = body.substring(idx + key.length + 1)
        val amp = tail.indexOf('&')
        if (amp >= 0) tail = tail.substring(0, amp)
        return tail
    }

    fun sha384(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-384").digest(data)
}
