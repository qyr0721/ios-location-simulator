package com.iosloc.spoofer.location

import com.iosloc.spoofer.ios.LockdownClient
import com.iosloc.spoofer.ios.ServiceConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * com.apple.dt.simulatelocation wire frames, identical to
 * libimobiledevice idevicesetlocation.c and go-ios simlocation:
 *
 *   set  : [0x00000000][len lat][lat ascii][len lon][lon ascii]   (all BE)
 *   reset: [0x00000001]
 */
object LocationProtocol {
    private const val MODE_SET = 0
    private const val MODE_RESET = 1

    fun setFrame(lat: Double, lon: Double): ByteArray {
        val latStr = "%.6f".format(lat)
        val lonStr = "%.6f".format(lon)
        val buf = ByteBuffer.allocate(4 + 4 + latStr.length + 4 + lonStr.length)
            .order(ByteOrder.BIG_ENDIAN)
        buf.putInt(MODE_SET)
        buf.putInt(latStr.length)
        buf.put(latStr.toByteArray())
        buf.putInt(lonStr.length)
        buf.put(lonStr.toByteArray())
        return buf.array()
    }

    fun resetFrame(): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(MODE_RESET).array()
}

/**
 * Keeps a persistent service connection open for continuous route updates
 * and reconnects transparently if the service drops it.
 */
class LocationController(private val lockdown: LockdownClient) {

    private var svc: ServiceConnection? = null

    @Synchronized
    private fun ensureService() {
        if (svc == null) {
            svc = lockdown.startService("com.apple.dt.simulatelocation")
        }
    }

    @Synchronized
    fun set(lat: Double, lon: Double) {
        val frame = LocationProtocol.setFrame(lat, lon)
        try {
            ensureService()
            svc!!.sendRaw(frame)
        } catch (t: Throwable) {
            // Re-open the service once on failure (e.g. service timed out).
            runCatching { svc?.close() }
            svc = null
            ensureService()
            svc!!.sendRaw(frame)
        }
    }

    @Synchronized
    fun reset() {
        try {
            ensureService()
            svc!!.sendRaw(LocationProtocol.resetFrame())
        } catch (t: Throwable) {
            runCatching { svc?.close() }
            svc = null
            ensureService()
            svc!!.sendRaw(LocationProtocol.resetFrame())
        }
    }

    @Synchronized
    fun close() {
        runCatching { svc?.close() }
        svc = null
    }
}
