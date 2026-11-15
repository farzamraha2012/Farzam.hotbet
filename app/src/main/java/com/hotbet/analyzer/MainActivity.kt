
package com.example.hotbetanalyzer

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val requestCode = 404

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 48, 30, 30)
        }

        layout.addView(
            TextView(this).apply {
                text = "HOTBET • نسخه ۰٫۹٫۴"
                textSize = 25f
            }
        )

        layout.addView(
            TextView(this).apply {
                text = "Farzam.kh  •  @2farzam2"
                textSize = 14f
            }
        )

        status = TextView(this).apply {
            text = "برای شروع، مجوز پنجره شناور و ضبط صفحه لازم است."
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }
        layout.addView(status)

        layout.addView(
            Button(this).apply {
                text = "۱. فعال کردن پنجره شناور"
                setOnClickListener {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "مجوز پنجره شناور فعال است",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )

        layout.addView(
            Button(this).apply {
                text = "۲. شروع تحلیل تاریخچه"
                setOnClickListener {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        status.text = "ابتدا مجوز پنجره شناور را فعال کنید."
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } else {
                        val manager = getSystemService(
                            MEDIA_PROJECTION_SERVICE
                        ) as MediaProjectionManager

                        startActivityForResult(
                            manager.createScreenCaptureIntent(),
                            requestCode
                        )
                    }
                }
            }
        )

        layout.addView(
            Button(this).apply {
                text = "توقف تحلیل و بستن پنجره"
                setOnClickListener {
                    stopService(
                        Intent(
                            this@MainActivity,
                            CaptureService::class.java
                        )
                    )
                    status.text = "تحلیل متوقف شد؛ تاریخچه دائمی باقی می‌ماند."
                }
            }
        )

        layout.addView(
            TextView(this).apply {
                text = """
                    • فقط دور بعدی که هنوز شروع نشده تحلیل می‌شود.
                    • پنج بازه ضریب با درصد احتمال جداگانه نمایش داده می‌شوند.
                    • موتور یادگیری تطبیقی، عملکرد پنج روش آماری را با نتایج واقعی تاریخچه ارزیابی می‌کند.
                    • تاریخچه در پایگاه داده محلی ذخیره می‌شود و با شروع مجدد بازیابی خواهد شد.
                    • پردازش OCR و تحلیل روی گوشی انجام می‌شود؛ اتصال API لازم نیست.
                    • درصدها برآورد آماری هستند و تضمین‌کننده نتیجه بازی نیستند.
                """.trimIndent()
                textSize = 14f
                setPadding(0, 22, 0, 0)
            }
        )

        setContentView(
            ScrollView(this).apply {
                addView(layout)
            }
        )
    }

    @Deprecated("Compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (
            requestCode == this.requestCode &&
            resultCode == RESULT_OK &&
            data != null
        ) {
            val service = Intent(
                this,
                CaptureService::class.java
            ).apply {
                action = CaptureService.ACTION_START
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }

            ContextCompat.startForegroundService(this, service)
            status.text = "در حال بازیابی تاریخچه و آماده‌سازی تحلیل..."
        }
    }
}
