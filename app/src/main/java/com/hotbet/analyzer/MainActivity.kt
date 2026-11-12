package com.example.hotbetanalyzer

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val requestCode = 404

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30, 48, 30, 30) }
        layout.addView(TextView(this).apply { text = "تحلیل‌گر هات‌بت"; textSize = 26f })
        status = TextView(this).apply { text = "برای شروع، مجوز پنجره شناور و ضبط صفحه لازم است."; textSize = 16f }
        layout.addView(status)
        layout.addView(Button(this).apply {
            text = "۱. فعال کردن پنجره شناور"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else Toast.makeText(this@MainActivity, "مجوز فعال است", Toast.LENGTH_SHORT).show()
            }
        })
        layout.addView(Button(this).apply {
            text = "۲. شروع خواندن صفحه"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    status.text = "ابتدا مجوز پنجره شناور را فعال کنید."
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    startActivityForResult(mgr.createScreenCaptureIntent(), requestCode)
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "توقف خواندن و بستن پنجره"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CaptureService::class.java))
                status.text = "متوقف شد"
            }
        })
        layout.addView(TextView(this).apply {
            text = "پنجره شناور در سمت راست قرار می‌گیرد تا ستون ضرایب را نپوشاند. با هر بار شروع خواندن، آمار جلسه از صفر آغاز می‌شود. ستون تاریخچه ضرایب را در سمت چپ صفحه و بدون پوشش نگه دارید. ثبت ضریب جدید با تطبیق ردیف‌های مشترک انجام می‌شود؛ این برنامه ضریب دور بعد را پیش‌بینی نمی‌کند."
            textSize = 14f
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    @Deprecated("Compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            ContextCompat.startForegroundService(this, service)
            status.text = "در حال خواندن صفحه؛ پنجره شناور فعال می‌شود."
        }
    }
}
