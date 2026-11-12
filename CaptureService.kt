package com.example.hotbetanalyzer

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.regex.Pattern

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.example.hotbetanalyzer.START"
        private const val CHANNEL = "screen_reader"
    }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val ui = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastAt = 0L
    private var lastText = ""
    private var busy = false
    private var overlay: View? = null
    private var windowManager: WindowManager? = null
    private var resultText: TextView? = null
    private val values = mutableListOf<Double>()
    private val pattern = Pattern.compile("(?<![0-9])([0-9]{1,4}[.,][0-9]{1,2})(?![0-9])")
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { ui.post { stopSelf() } }
    }

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("تحلیل‌گر هات‌بت فعال است")
            .setContentText("ضبط صفحه و نمایش آمار شناور؛ برای توقف وارد برنامه شوید")
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(71, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(71, notification)
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val code = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java)
                   else intent.getParcelableExtra<Intent>("data")
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        try { showOverlay(); begin(code, data) } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val dp = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14*dp).toInt(), (10*dp).toInt(), (14*dp).toInt(), (12*dp).toInt())
            background = GradientDrawable().apply { setColor(Color.argb(235, 24, 32, 46)); cornerRadius = 18*dp }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply { text = "● تحلیل‌گر | جابه‌جایی"; setTextColor(Color.WHITE); textSize = 14f; setTypeface(null, Typeface.BOLD) }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "  ×  "; textSize = 24f; setTextColor(Color.WHITE)
            setOnClickListener { stopSelf() }
        })
        panel.addView(header)
        resultText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; text = "در انتظار شناسایی ضرایب..." }
        panel.addView(resultText)
        val params = WindowManager.LayoutParams(
            (280*dp).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (16*dp).toInt(); y = (100*dp).toInt() }
        header.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0; private var startY = 0; private var touchX = 0f; private var touchY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { startX=params.x; startY=params.y; touchX=event.rawX; touchY=event.rawY; return true }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (startX + event.rawX-touchX).toInt().coerceAtLeast(0)
                        params.y = (startY + event.rawY-touchY).toInt().coerceAtLeast(0)
                        try { wm.updateViewLayout(panel, params) } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })
        wm.addView(panel, params)
        overlay = panel
    }

    private fun begin(code: Int, data: Intent) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mgr.getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(projectionCallback, ui)
        val dm = resources.displayMetrics
        worker = HandlerThread("ocr-worker").also { it.start() }
        handler = Handler(worker!!.looper)
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (busy || now-lastAt < 1500L) { image.close(); return@setOnImageAvailableListener }
            lastAt = now
            busy = true
            recognize(image)
        }, handler)
        display = mp.createVirtualDisplay("HotBetReader", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
    }

    private fun recognize(image: Image) {
        var bitmap: Bitmap? = null
        try {
            val p = image.planes[0]
            val paddedWidth = p.rowStride / p.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(p.buffer)
            bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            image.close()
            val captured = bitmap
            recognizer.process(InputImage.fromBitmap(captured, 0))
                .addOnSuccessListener { result ->
                    val found = mutableListOf<Pair<Int, Double>>()
                    result.textBlocks.flatMap { it.lines }.forEach { line ->
                        val matcher = pattern.matcher(line.text)
                        while (matcher.find()) {
                            val value = matcher.group(1)?.replace(',', '.')?.toDoubleOrNull()
                            if (value != null && value in 1.0..10000.0) found.add((line.boundingBox?.top ?: 0) to value)
                        }
                    }
                    val snapshot = found.sortedBy { it.first }.map { it.second }
                    val fingerprint = snapshot.joinToString("|")
                    if (snapshot.isNotEmpty() && fingerprint != lastText) {
                        lastText = fingerprint
                        values.clear(); values.addAll(snapshot.takeLast(100))
                        render()
                    }
                }
                .addOnCompleteListener { captured.recycle(); busy = false }
        } catch (_: Exception) {
            try { image.close() } catch (_: Exception) {}
            bitmap?.recycle(); busy = false
        }
    }

    private fun render() {
        val recent = values.takeLast(100)
        val count2 = recent.count { it >= 2.0 }
        val count3 = recent.count { it >= 3.0 }
        val average = if (recent.isEmpty()) "—" else String.format(Locale.US, "%.2f", recent.average())
        val recentText = recent.takeLast(8).asReversed().joinToString(" · ") { String.format(Locale.US, "%.2fx", it) }
        ui.post {
            resultText?.text = "تعداد: ${recent.size}    ۲x+: $count2    ۳x+: $count3\nمیانگین: $average\nآخرین: $recentText"
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "خواندن صفحه", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        reader?.setOnImageAvailableListener(null, null)
        display?.release(); reader?.close()
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        projection?.stop()
        worker?.quitSafely()
        try { overlay?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlay = null
        recognizer.close()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
