package com.asterlink.app

import android.widget.ImageButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class LanQrScanActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.lan_qr_scanner)
        val root = findViewById<android.view.View>(R.id.lan_qr_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        findViewById<ImageButton>(R.id.lan_qr_cancel).setOnClickListener { finish() }
        return findViewById(R.id.lan_qr_preview)
    }
}
