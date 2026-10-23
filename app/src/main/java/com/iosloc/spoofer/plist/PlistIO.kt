package com.iosloc.spoofer.plist

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.dd.plist.XMLPropertyListWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Helpers for the lockdown plist protocol.
 *
 * Every message between the host and lockdownd (or a plist service) is a
 * property list prefixed by a 4-byte big-endian length. Lockdownd accepts both
 * XML and binary plists; we write XML (simple, no binary encoder needed) and
 * dd-plist parses either format on the way back.
 */
object PlistIO {

    fun toXml(obj: NSObject): ByteArray {
        val out = ByteArrayOutputStream()
        XMLPropertyListWriter.write(obj, out)
        return out.toByteArray()
    }

    fun parse(data: ByteArray, off: Int = 0, len: Int = data.size - off): NSObject {
        val slice = if (off == 0 && len == data.size) data else data.copyOfRange(off, off + len)
        return PropertyListParser.parse(slice)
    }

    fun dict(vararg kv: Pair<String, Any?>): NSDictionary {
        val d = NSDictionary()
        for ((k, v) in kv) d.put(k, toNs(v))
        return d
    }

    fun toNs(v: Any?): NSObject = when (v) {
        null -> NSString("")
        is NSObject -> v
        is String -> NSString(v)
        is Int -> NSNumber(v)
        is Long -> NSNumber(v)
        is Double -> NSNumber(v)
        is Float -> NSNumber(v.toDouble())
        is Boolean -> NSNumber(v)
        is ByteArray -> NSData(v)
        is List<*> -> NSArray(*v.map { toNs(it) }.toTypedArray())
        is Array<*> -> NSArray(*v.map { toNs(it) }.toTypedArray())
        is Map<*, *> -> {
            val d = NSDictionary()
            v.forEach { (k, vv) -> d.put(k.toString(), toNs(vv)) }
            d
        }
        else -> throw IllegalArgumentException("不支持的 plist 类型: ${v::class.java}")
    }
}

/**
 * Length-prefixed plist channel over an arbitrary stream pair.
 */
class PlistChannel(private val input: InputStream, private val output: OutputStream) {

    @Synchronized
    fun send(obj: NSObject) {
        val xml = PlistIO.toXml(obj)
        val hdr = ByteArray(4)
        val len = xml.size
        hdr[0] = ((len shr 24) and 0xFF).toByte()
        hdr[1] = ((len shr 16) and 0xFF).toByte()
        hdr[2] = ((len shr 8) and 0xFF).toByte()
        hdr[3] = (len and 0xFF).toByte()
        output.write(hdr)
        output.write(xml)
        output.flush()
    }

    fun receive(): NSDictionary {
        val hdr = ByteArray(4)
        readFully(input, hdr)
        val len = ((hdr[0].toInt() and 0xFF) shl 24) or
            ((hdr[1].toInt() and 0xFF) shl 16) or
            ((hdr[2].toInt() and 0xFF) shl 8) or
            (hdr[3].toInt() and 0xFF)
        if (len <= 0 || len > MAX_PAYLOAD) throw RuntimeException("非法 plist 长度: $len")
        val buf = ByteArray(len)
        readFully(input, buf)
        val obj = PlistIO.parse(buf)
        if (obj !is NSDictionary) throw RuntimeException("期望 NSDictionary，得到 ${obj.javaClass}")
        return obj
    }

    fun request(obj: NSObject): NSDictionary {
        send(obj)
        return receive()
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.IOException("读取 plist 时连接断开")
            off += n
        }
    }

    companion object {
        private const val MAX_PAYLOAD = 16 * 1024 * 1024
    }
}
