package com.iosloc.spoofer.ddi

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Loads a DDI bundle from a user-selected Storage Access Framework folder:
 *
 *  iOS 13-16 (modern): the Xcode/device-support "Restore" directory containing
 *    BuildManifest.plist, the *.dmg and the *.trustcache.
 *  iOS <= 12 (legacy): a directory containing Developer.dmg and
 *    Developer.dmg.signature.
 */
object DdiLoader {

    data class Loaded(val bundle: DdiBundle, val description: String)

    fun load(context: Context, treeUri: Uri): Loaded {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("无法打开所选目录")

        var manifest: DocumentFile? = null
        var dmg: DocumentFile? = null
        var trustCache: DocumentFile? = null
        var signature: DocumentFile? = null

        fun walk(f: DocumentFile) {
            f.listFiles().forEach { child ->
                if (child.isDirectory) {
                    walk(child)
                    return@forEach
                }
                val name = child.name?.lowercase() ?: return@forEach
                when {
                    name == "buildmanifest.plist" || name.endsWith("buildmanifest.plist") -> manifest = child
                    name.endsWith(".dmg") -> dmg = child
                    name.endsWith(".trustcache") -> trustCache = child
                    name.endsWith(".signature") || name.endsWith(".dmg.signature") -> signature = child
                }
            }
        }
        walk(root)

        val dmgFile = dmg ?: error("目录中找不到 .dmg 文件")
        val dmgBytes = readAll(context, dmgFile)

        return if (manifest != null) {
            val manifestBytes = readAll(context, manifest!!)
            val tcBytes = trustCache?.let { readAll(context, it) }
                ?: error("现代 DDI 目录中找不到 .trustcache 文件")
            Loaded(
                DdiBundle(
                    legacy = false,
                    dmg = dmgBytes,
                    buildManifest = manifestBytes,
                    trustCache = tcBytes
                ),
                "个性化 DDI（dmg %.1f MB）".format(dmgBytes.size / 1048576.0)
            )
        } else {
            val sigBytes = signature?.let { readAll(context, it) }
                ?: error("传统 DDI 目录中找不到 .signature 文件")
            Loaded(
                DdiBundle(legacy = true, dmg = dmgBytes, signature = sigBytes),
                "传统 DDI（dmg %.1f MB）".format(dmgBytes.size / 1048576.0)
            )
        }
    }

    private fun readAll(context: Context, f: DocumentFile): ByteArray {
        context.contentResolver.openInputStream(f.uri).use { input ->
            requireNotNull(input) { "无法读取 ${f.name}" }
            return input.readBytes()
        }
    }
}
