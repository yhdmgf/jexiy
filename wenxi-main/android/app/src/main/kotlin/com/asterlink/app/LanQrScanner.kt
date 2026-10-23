package com.asterlink.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanOptions
import io.flutter.plugin.common.MethodChannel

class LanQrScanner(private val activity: () -> Activity?) {
    private var pending: MethodChannel.Result? = null

    fun scan(result: MethodChannel.Result) {
        val current = activity()
        if (current == null || current.isFinishing || pending != null) {
            result.error("qr_busy", "无法打开扫码，请返回设备互联页面后重试", null)
            return
        }
        if (!current.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            result.error("qr_camera", "这台设备没有可用相机，请手动输入连接码或设备地址", null)
            return
        }
        val intent = ScanOptions()
            .setCaptureActivity(LanQrScanActivity::class.java)
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("将二维码放入取景框内")
            .setOrientationLocked(false)
            .setBeepEnabled(false)
            .setBarcodeImageEnabled(false)
            .addExtra(Intents.Scan.SHOW_MISSING_CAMERA_PERMISSION_DIALOG, false)
            .createScanIntent(current)
        pending = result
        try {
            current.startActivityForResult(intent, REQUEST_CODE)
        } catch (_: Exception) {
            pending = null
            result.error("qr_camera", "无法打开相机，请重试或手动输入设备地址", null)
        }
    }

    fun activityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val result = pending ?: return true
        pending = null
        when {
            data?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true ->
                result.error("qr_permission", "相机权限未开启，请在系统设置中允许文析助手使用相机，或手动输入设备地址", null)
            resultCode != Activity.RESULT_OK -> result.success(null)
            else -> {
                val text = data?.getStringExtra(Intents.Scan.RESULT)
                if (text.isNullOrBlank() || text.length > 2048) {
                    result.error("qr_invalid", "请扫描文析助手的设备连接二维码或文件分享二维码", null)
                } else {
                    result.success(text)
                }
            }
        }
        return true
    }

    companion object {
        const val REQUEST_CODE = 836
    }
}
