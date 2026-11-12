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
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.regex.Pattern
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads visible game history locally through OCR. It does not access any game API
 * and never attempts to infer the next outcome from unrelated past outcomes.
 * Screen-region heuristics are calibrated for the supplied portrait screenshot.
 */
class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.example.hotbetanalyzer.START"
        private const val CHANNEL = "screen_reader"
        private const val MAX_SAVED = 300
        private const val MAX_VISIBLE_ROWS = 35
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val ui = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var lastAt = 0L
    private val processing = AtomicBoolean(false)
    @Volatile private var sessionGeneration = 0
    private var noOverlapCount = 0
    private var overlay: View? = null
    private var windowManager: WindowManager? = null
    private var statusText: TextView? = null
    private var statsText: TextView? = null

    // Chronological: oldest -> newest. Updated only when overlapping OCR snapshots confirm new rows.
    private val history = mutableListOf<Double>()
    // Visual order on the website: newest -> oldest.
    private var previousVisible: List<Double> = emptyList()
    private var statusMessage = "در انتظار خواندن تاریخچه ضرایب..."
    private val number = Pattern.compile("(?<![0-9])([0-9]{1,4}[.,][0-9]{1,2})(?![0-9])")
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { ui.post { stopSelf() } }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("تحلیل‌گر هات‌بت فعال است")
            .setContentText("خواندن تاریخچه ضرایب روی صفحه؛ برای توقف وارد برنامه شوید")
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(71, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(71, notification)

        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val code = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra("data", Intent::class.java)
        else intent.getParcelableExtra<Intent>("data")
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf(); return START_NOT_STICKY
        }
        // If the user starts again while an old capture service is still alive,
        // tear it down first so the new run always gets a clean session.
        if (projection != null || overlay != null) stopCaptureResources()
        resetSession()
        try {
            showOverlay()
            begin(code, data)
        } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val dp = resources.displayMetrics.density
        fun toPx(x: Int) = (x * dp).toInt()
        val white = Color.WHITE
        val dimWhite = Color.rgb(206, 221, 233)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(12), toPx(10), toPx(12), toPx(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(242, 24, 32, 46))
                cornerRadius = 18 * dp
            }
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "تحلیل‌گر ضرایب  •  جابه‌جایی"
            textSize = 14f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.RIGHT
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val collapse = TextView(this).apply {
            text = "  −  "
            textSize = 24f
            setTextColor(white)
            setOnClickListener {
                details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                text = if (details.visibility == View.VISIBLE) "  −  " else "  +  "
            }
        }
        header.addView(collapse)
        header.addView(TextView(this).apply {
            text = "  ×  "
            textSize = 24f
            setTextColor(white)
            setOnClickListener { stopSelf() }
        })
        panel.addView(header)

        statusText = TextView(this).apply {
            textSize = 12f
            setTextColor(dimWhite)
            text = statusMessage
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
        }
        statsText = TextView(this).apply {
            textSize = 14f
            setTextColor(white)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(0, toPx(6), 0, toPx(6))
        }
        details.addView(statusText)
        details.addView(statsText)
        details.addView(TextView(this).apply {
            text = "آمار فقط مربوط به همین جلسه است؛ با شروع دوباره از صفر آغاز می‌شود. ضریب دور بعد قابل پیش‌بینی قطعی نیست."
            textSize = 11f
            setTextColor(dimWhite)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
        })
        panel.addView(details)

        val displayWidth = resources.displayMetrics.widthPixels
        // Keep the floating panel on the right so it does not cover the multiplier history column.
        val panelWidth = minOf(toPx(280), displayWidth - toPx(24))
        val params = WindowManager.LayoutParams(
            panelWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayWidth - panelWidth - toPx(12)).coerceAtLeast(0)
            y = toPx(70)
        }
        header.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                return when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (startX + event.rawX - touchX).toInt().coerceAtLeast(0)
                        params.y = (startY + event.rawY - touchY).toInt().coerceAtLeast(0)
                        try { wm.updateViewLayout(panel, params) } catch (_: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> true
                    else -> false
                }
            }
        })
        wm.addView(panel, params)
        overlay = panel
        render()
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
            if (now - lastAt < 800L || !processing.compareAndSet(false, true)) {
                image.close(); return@setOnImageAvailableListener
            }
            lastAt = now
            recognize(image)
        }, handler)
        display = mp.createVirtualDisplay(
            "HotBetReader", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
        )
    }

    private fun recognize(image: Image) {
        val generation = sessionGeneration
        var fullBitmap: Bitmap? = null
        var cropBitmap: Bitmap? = null
        try {
            val plane = image.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            fullBitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            image.close()

            // The supplied HotBet mobile layout places the multiplier column on the left,
            // below the graph. OCR only this region to reduce work and avoid unrelated numbers.
            val source = fullBitmap ?: throw IllegalStateException("صفحه قابل خواندن نیست")
            val left = (source.width * 0.07f).toInt().coerceIn(0, source.width - 1)
            val right = (source.width * 0.34f).toInt().coerceIn(left + 1, source.width)
            val top = (source.height * 0.35f).toInt().coerceIn(0, source.height - 1)
            val cropped = Bitmap.createBitmap(source, left, top, right - left, source.height - top)
            cropBitmap = cropped
            source.recycle()
            fullBitmap = null

            recognizer.process(InputImage.fromBitmap(cropped, 0))
                .addOnSuccessListener { result ->
                    if (generation != sessionGeneration) return@addOnSuccessListener
                    // The crop is already restricted to the history multiplier column.
                    val visible = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        val matcher = number.matcher(line.text)
                        if (!matcher.find()) return@mapNotNull null
                        val value = matcher.group(1)?.replace(',', '.')?.toDoubleOrNull()
                        if (value == null || value !in 1.0..1000.0) null
                        else box.top to value
                    }.sortedBy { it.first }.take(MAX_VISIBLE_ROWS).map { it.second }
                    observe(visible)
                }
                .addOnFailureListener {
                    if (generation == sessionGeneration) {
                        statusMessage = "خطا در تشخیص متن صفحه؛ صفحه تاریخچه را واضح نگه دارید."
                        render()
                    }
                }
                .addOnCompleteListener {
                    try { cropped.recycle() } catch (_: Exception) {}
                    if (generation == sessionGeneration) processing.set(false)
                }
        } catch (_: Exception) {
            try { image.close() } catch (_: Exception) {}
            try { fullBitmap?.recycle() } catch (_: Exception) {}
            try { cropBitmap?.recycle() } catch (_: Exception) {}
            if (generation == sessionGeneration) processing.set(false)
        }
    }

    private fun resetSession() {
        sessionGeneration++
        history.clear()
        previousVisible = emptyList()
        noOverlapCount = 0
        lastAt = 0L
        processing.set(false)
        // Remove data saved by v0.3; v0.4.1 keeps only results observed after this session starts.
        getSharedPreferences("captured_history", MODE_PRIVATE).edit().clear().apply()
        statusMessage = "جلسه جدید؛ در انتظار خواندن تاریخچه ضرایب..."
    }

    private fun stopCaptureResources() {
        reader?.setOnImageAvailableListener(null, null)
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        worker?.quitSafely()
        worker = null
        handler = null
        try { overlay?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlay = null
        statusText = null
        statsText = null
        processing.set(false)
    }

    private data class OverlapMatch(
        val previousIndex: Int,
        val visibleIndex: Int,
        val length: Int
    )

    private fun findOverlap(previous: List<Double>, current: List<Double>): OverlapMatch? {
        var best: OverlapMatch? = null
        for (previousIndex in previous.indices) {
            for (visibleIndex in current.indices) {
                var length = 0
                while (previousIndex + length < previous.size &&
                    visibleIndex + length < current.size &&
                    previous[previousIndex + length] == current[visibleIndex + length]) {
                    length++
                }
                if (length >= 2 && (best == null || length > best.length ||
                    (length == best.length && visibleIndex < best.visibleIndex))) {
                    best = OverlapMatch(previousIndex, visibleIndex, length)
                }
            }
        }
        return best
    }

    private fun observe(visible: List<Double>) {
        if (visible.isEmpty()) {
            statusMessage = "تاریخچه پیدا نشد؛ ستون ضرایب را کامل و بدون پوشش روی صفحه نگه دارید."
            render()
            return
        }

        if (previousVisible.isEmpty()) {
            // The website already shows results from before the user started this session.
            // Use them only as an OCR baseline; count only rounds appearing after this point.
            previousVisible = visible.toList()
            noOverlapCount = 0
            statusMessage = "${visible.size} ضریب موجود فقط به‌عنوان مبنا شناسایی شد؛ آمار جلسه از دور بعد ثبت می‌شود."
            render()
            return
        }

        if (visible == previousVisible) {
            statusMessage = "${visible.size} ضریب روی صفحه دیده شد؛ مورد جدیدی ثبت نشد."
            render()
            return
        }

        val overlap = findOverlap(previousVisible, visible)
        // A large jump usually means the user scrolled or the page layout changed.
        // Do not count it as new rounds; re-anchor after several consistent scans.
        if (overlap == null || overlap.visibleIndex > 6) {
            noOverlapCount++
            if (noOverlapCount >= 3) {
                previousVisible = visible.toList()
                noOverlapCount = 0
                statusMessage = "چیدمان تاریخچه تغییر کرد؛ مبنا تازه شد، داده جدیدی ثبت نشد."
            } else {
                statusMessage = "${visible.size} ضریب دیده شد؛ در انتظار تطبیق ردیف‌های مشترک برای ثبت دقیق..."
            }
            render()
            return
        }

        noOverlapCount = 0
        val newCount = overlap.visibleIndex
        if (newCount > 0) {
            history.addAll(visible.take(newCount).asReversed())
            while (history.size > MAX_SAVED) history.removeAt(0)
        }
        previousVisible = visible.toList()
        statusMessage = if (newCount > 0) {
            "${visible.size} ضریب قابل مشاهده؛ $newCount ضریب جدید ثبت شد."
        } else {
            "تاریخچه به‌روزرسانی شد؛ ضریب جدیدی در ابتدای فهرست نبود."
        }
        render()
    }

    private fun render() {
        val recent = history.takeLast(MAX_SAVED)
        val count = recent.size
        val ordered = recent.sorted()
        val median = if (count == 0) 0.0 else if (count % 2 == 1) ordered[count / 2]
            else (ordered[count / 2 - 1] + ordered[count / 2]) / 2.0
        val above2 = recent.count { it >= 2.0 }
        val above3 = recent.count { it >= 3.0 }
        fun fmt(value: Double) = String.format(Locale.US, "%.2f", value)
        val recentText = recent.asReversed().take(6).joinToString("  •  ") { "${fmt(it)}×" }
        val text = if (count == 0) "هنوز ضریبی ثبت نشده است." else
            "تعداد ثبت‌شده: $count\n" +
            "آخرین ضریب ثبت‌شده: ${fmt(recent.last())}×\n" +
            "میانه ضرایب: ${fmt(median)}×\n" +
            "در نمونه گذشته، ۲× یا بیشتر: $above2 از $count\n" +
            "در نمونه گذشته، ۳× یا بیشتر: $above3 از $count\n" +
            "۶ ضریب اخیر: $recentText"
        ui.post {
            statusText?.text = statusMessage
            statsText?.text = text
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "خواندن صفحه", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        sessionGeneration++
        stopCaptureResources()
        recognizer.close()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
