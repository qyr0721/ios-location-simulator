package com.iosloc.spoofer.ios

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.iosloc.spoofer.ddi.DdiBundle
import com.iosloc.spoofer.ddi.DeveloperImageManager
import com.iosloc.spoofer.location.LocationController
import com.iosloc.spoofer.usb.AppleUsbDevice
import com.iosloc.spoofer.usb.UsbMuxTransport

data class DeviceInfo(
    val udid: String,
    val name: String,
    val productVersion: String,
    val buildVersion: String,
    val model: String
) {
    val majorVersion: Int get() = productVersion.substringBefore('.').toIntOrNull() ?: 0
}

/**
 * High-level session owner: USB discovery -> usbmux transport -> lockdown
 * pairing/session -> DDI mount -> location service.
 */
class DeviceManager(private val context: Context) {

    private var transport: UsbMuxTransport? = null
    private var lockdown: LockdownClient? = null
    private var mounter: DeveloperImageManager? = null
    private var locationController: LocationController? = null

    @Volatile
    var info: DeviceInfo? = null
        private set

    fun listAttachedDevices(): List<UsbDevice> {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usb.deviceList.values.filter { AppleUsbDevice.isAppleDevice(it) }
    }

    fun findFirstAttached(): UsbDevice? = listAttachedDevices().firstOrNull()

    /**
     * Open and pair with the device. [onTrustPrompt] is called when the user
     * must tap "Trust" on the iPhone.
     */
    fun connect(
        device: UsbDevice? = findFirstAttached(),
        onTrustPrompt: () -> Unit = {}
    ): DeviceInfo {
        requireNotNull(device) { "未检测到 iOS 设备，请用 OTG 数据线连接并在系统弹窗中授权" }
        require(AppleUsbDevice.isAppleDevice(device)) { "插入的 USB 设备不是 iPhone" }

        close()
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usb.hasPermission(device)) {
            throw RuntimeException("没有 USB 权限，请先在系统弹窗中允许访问该设备")
        }
        val apple = AppleUsbDevice(device, usb)
        val t = UsbMuxTransport(apple)
        t.start()
        transport = t

        val udid = device.serialNumber?.trim()
            ?: throw RuntimeException("无法读取设备序列号（UDID）")
        val lc = LockdownClient(t, PairStore(context), udid)
        lc.connect()
        lc.ensureSession(onTrustPrompt)
        lockdown = lc

        val i = DeviceInfo(
            udid = udid,
            name = lc.deviceInfo["DeviceName"] ?: "iPhone",
            productVersion = lc.deviceInfo["ProductVersion"] ?: "0",
            buildVersion = lc.deviceInfo["BuildVersion"] ?: "",
            model = lc.deviceInfo["Model"] ?: ""
        )
        info = i
        mounter = DeveloperImageManager(lc)
        locationController = LocationController(lc)
        return i
    }

    /**
     * Ensure the DDI is mounted. The [bundleProvider] is only called when
     * the image is not already mounted.
     */
    fun ensureDeveloperImage(
        bundleProvider: (modern: Boolean) -> DdiBundle?,
        onProgress: (Double) -> Unit = {}
    ) {
        val lc = lockdown ?: error("设备未连接")
        val m = mounter ?: error("设备未连接")
        val info = info!!
        if (info.majorVersion >= 17) {
            throw RuntimeException(
                "iOS 17+ 暂不支持：新版 DDI 需要 CoreDevice/RemoteXPC 隧道（见 README 路线图）。"
            )
        }
        val modern = info.majorVersion >= 13
        if (m.isMounted(modern)) return
        val bundle = bundleProvider(modern)
            ?: throw RuntimeException("需要挂载开发者镜像：请在“镜像”页选择对应 iOS 版本的 DDI 文件")
        val ecid = lc.getValue("UniqueChipID")?.toLongOrNull() ?: 0L
        m.mount(bundle, ecid, onProgress)
    }

    fun location(): LocationController =
        locationController ?: error("设备未连接，请先连接")

    fun close() {
        runCatching { locationController?.close() }
        runCatching { lockdown?.close() }
        runCatching { transport?.close() }
        locationController = null
        lockdown = null
        transport = null
        info = null
    }
}
