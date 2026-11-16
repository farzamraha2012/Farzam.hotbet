package com.example.hotbetanalyzer

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
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

                text = "HOTBET PHOENIX • نسخه ۰٫۹٫۶"

                textSize = 23f
            }
        )

        layout.addView(
            TextView(this).apply {

                text = "سازنده: Farzam.kh"

                textSize = 16f
            }
        )

        layout.addView(
            TextView(this).apply {

                text = "Instagram: @2farzam2"

                textSize = 14f
            }
        )

        status = TextView(this).apply {

            text =
                "برای شروع، مجوز پنجره شناور و ضبط صفحه لازم است."

            textSize = 16f

            setPadding(0, 25, 0, 20)
        }

        layout.addView(status)

        layout.addView(
            Button(this).apply {

                text = "۱. فعال کردن پنجره شناور"

                setOnClickListener {

                    if (
                        !Settings.canDrawOverlays(
                            this@MainActivity
                        )
                    ) {

                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse(
                                    "package:$packageName"
                                )
                            )
                        )

                    } else {

                        Toast.makeText(
                            this@MainActivity,
                            "مجوز فعال است",
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

                    if (
                        !Settings.canDrawOverlays(
                            this@MainActivity
                        )
                    ) {

                        status.text =
                            "ابتدا مجوز پنجره شناور را فعال کنید."

                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse(
                                    "package:$packageName"
                                )
                            )
                        )

                    } else {

                        val manager =
                            getSystemService(
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

                text = "۳. توقف تحلیل و بستن پنجره"

                setOnClickListener {

                    stopService(
                        Intent(
                            this@MainActivity,
                            CaptureService::class.java
                        )
                    )

                    status.text = "تحلیل متوقف شد."
                }
            }
        )

        layout.addView(
            TextView(this).apply {

                text =
                    """
                    قابلیت‌های نسخه ۰٫۹٫۶

                    • خواندن ستون تاریخچه ضرایب با OCR
                    • ذخیره دائمی اطلاعات در دیتابیس
                    • پنج موتور تحلیل آماری
                    • برآورد آماری پنج افق آینده
                    • کالیبراسیون احتمالات با آزمون گذشته
                    • مقایسه با فراوانی پایه تاریخی
                    • ارزیابی دقت بازه و کیفیت احتمال‌ها
                    • حفظ اطلاعات پس از بستن برنامه

                    این نرم‌افزار نتیجه قطعی بازی را پیش‌بینی نمی‌کند.
                    """.trimIndent()

                textSize = 14f

                setPadding(0, 25, 0, 10)
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

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

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

                putExtra(
                    "resultCode",
                    resultCode
                )

                putExtra(
                    "data",
                    data
                )
            }

            ContextCompat.startForegroundService(
                this,
                service
            )

            status.text =
                "در حال بازیابی تاریخچه و شروع تحلیل..."
        }
    }
}
