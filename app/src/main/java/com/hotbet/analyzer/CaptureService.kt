
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
import java.util.regex.Pattern
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {

    companion object {
        const val ACTION_START =
            "com.example.hotbetanalyzer.START"

        private const val CHANNEL = "screen_reader"
        private const val MAX_SAVED = 300
        private const val MAX_VISIBLE_ROWS = 35
        private const val MAX_NEW_PER_SCAN = 12
        private const val FORECAST_SIZE = 5
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var worker: HandlerThread? = null
    private var handler: Handler? = null

    private val ui = Handler(Looper.getMainLooper())

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    @Volatile
    private var lastAt = 0L

    private val processing = AtomicBoolean(false)

    @Volatile
    private var sessionGeneration = 0

    private var noOverlapCount = 0

    private var overlay: View? = null
    private var windowManager: WindowManager? = null

    private var statusText: TextView? = null
    private var statsText: TextView? = null

    // Oldest -> newest
    private val history = mutableListOf<Double>()

    private var forecasts =
        emptyList<PredictionEngine.Forecast>()

    // Completed rounds in the current five-round cycle.
    private var completedForecastRounds = 0

    private var forecastActive = false

    // Total newly detected results in this session.
    private var totalNewRounds = 0

    // Newest -> oldest, as displayed by the website.
    private var previousVisible = emptyList<Double>()

    private var statusMessage =
        "در انتظار خواندن تاریخچه ضرایب..."

    private val number = Pattern.compile(
        "(?<![0-9])([0-9]{1,4}[.,][0-9]{1,2})(?![0-9])"
    )

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                ui.post { stopSelf() }
            }
        }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action != ACTION_START) {
            return START_NOT_STICKY
        }

        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("HOTBET • تحلیل‌گر فعال")
            .setContentText("در حال خواندن تاریخچه ضرایب")
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                71,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(71, notification)
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val code = intent.getIntExtra(
            "resultCode",
            Activity.RESULT_CANCELED
        )

        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            intent.getParcelableExtra<Intent>("data")
        }

        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection != null || overlay != null) {
            stopCaptureResources()
        }

        resetSession()

        try {
            showOverlay()
            begin(code, data)
        } catch (_: Exception) {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    // --------------------------------------------------
    // FLOATING WINDOW
    // --------------------------------------------------

    private fun showOverlay() {

        val wm = getSystemService(WINDOW_SERVICE)
            as WindowManager

        windowManager = wm

        val dp = resources.displayMetrics.density
        fun px(value: Int) = (value * dp).toInt()

        val white = Color.WHITE
        val dim = Color.rgb(190, 198, 211)
        val gold = Color.rgb(225, 183, 77)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(
                px(10), px(9), px(10), px(9)
            )

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(29, 35, 47),
                    Color.rgb(12, 16, 25)
                )
            ).apply {
                cornerRadius = 14 * dp
                setStroke(px(1), Color.rgb(115, 87, 39))
            }

            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "HOTBET • پنج دور آینده"
            textSize = 13f
            setTextColor(gold)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.RIGHT
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val collapse = TextView(this).apply {
            text = "−"
            textSize = 21f
            setTextColor(white)
            setPadding(px(5), 0, px(5), 0)
        }

        val close = TextView(this).apply {
            text = "×"
            textSize = 23f
            setTextColor(white)
            setPadding(px(5), 0, px(3), 0)
            setOnClickListener { stopSelf() }
        }

        header.addView(collapse)
        header.addView(close)
        panel.addView(header)

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        collapse.setOnClickListener {
            details.visibility =
                if (details.visibility == View.VISIBLE) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

            collapse.text =
                if (details.visibility == View.VISIBLE) "−" else "+"
        }

        statusText = TextView(this).apply {
            textSize = 10f
            setTextColor(dim)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(0, px(7), 0, px(7))
        }

        details.addView(statusText)

        val legend = TextView(this).apply {
            text = "زیر۲ / ۲-۴ / ۴-۶ / ۶-۱۰ / بالای۱۰"
            textSize = 9f
            setTextColor(dim)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(px(3), px(4), px(3), px(4))
        }

        details.addView(legend)

        statsText = TextView(this).apply {
            textSize = 10f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setLineSpacing(px(3).toFloat(), 1f)
            setPadding(px(8), px(8), px(8), px(8))

            background = GradientDrawable().apply {
                setColor(Color.rgb(39, 46, 59))
                cornerRadius = 11 * dp
            }
        }

        details.addView(statsText)

        val disclaimer = TextView(this).apply {
            text = "احتمال‌های آماری هستند؛ تضمین نتیجه نیستند."
            textSize = 9f
            setTextColor(dim)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(0, px(7), 0, px(3))
        }

        details.addView(disclaimer)
        panel.addView(details)

        val displayWidth =
            resources.displayMetrics.widthPixels

        val panelWidth = minOf(
            px(245),
            displayWidth - px(20)
        )

        val params = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayWidth - panelWidth - px(10))
                .coerceAtLeast(0)
            y = px(70)
        }

        header.setOnTouchListener(
            object : View.OnTouchListener {

                private var startX = 0
                private var startY = 0
                private var touchX = 0f
                private var touchY = 0f

                override fun onTouch(
                    v: View,
                    event: MotionEvent
                ): Boolean {

                    return when (event.action) {

                        MotionEvent.ACTION_DOWN -> {
                            startX = params.x
                            startY = params.y
                            touchX = event.rawX
                            touchY = event.rawY
                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            params.x = (
                                startX + event.rawX - touchX
                            ).toInt().coerceAtLeast(0)

                            params.y = (
                                startY + event.rawY - touchY
                            ).toInt().coerceAtLeast(0)

                            try {
                                wm.updateViewLayout(panel, params)
                            } catch (_: Exception) {
                            }

                            true
                        }

                        MotionEvent.ACTION_UP -> true
                        else -> false
                    }
                }
            }
        )

        wm.addView(panel, params)
        overlay = panel

        render()
    }

    // --------------------------------------------------
    // SCREEN CAPTURE
    // --------------------------------------------------

    private fun begin(code: Int, data: Intent) {

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager

        val mp = mgr.getMediaProjection(code, data)
        projection = mp

        mp.registerCallback(projectionCallback, ui)

        val dm = resources.displayMetrics

        worker = HandlerThread("ocr-worker").also {
            it.start()
        }

        handler = Handler(worker!!.looper)

        reader = ImageReader.newInstance(
            dm.widthPixels,
            dm.heightPixels,
            PixelFormat.RGBA_8888,
            3
        )

        reader!!.setOnImageAvailableListener({ r ->

            val image = r.acquireLatestImage()
                ?: return@setOnImageAvailableListener

            val now = System.currentTimeMillis()

            if (
                now - lastAt < 600L ||
                !processing.compareAndSet(false, true)
            ) {
                image.close()
                return@setOnImageAvailableListener
            }

            lastAt = now
            recognize(image)

        }, handler)

        display = mp.createVirtualDisplay(
            "HotBetReader",
            dm.widthPixels,
            dm.heightPixels,
            dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler
        )
    }

    // --------------------------------------------------
    // OCR
    // --------------------------------------------------

    private fun recognize(image: Image) {

        val generation = sessionGeneration

        var fullBitmap: Bitmap? = null
        var cropBitmap: Bitmap? = null
        var ocrBitmap: Bitmap? = null

        try {

            val plane = image.planes[0]

            val paddedWidth =
                plane.rowStride / plane.pixelStride

            val padded = Bitmap.createBitmap(
                paddedWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )

            padded.copyPixelsFromBuffer(plane.buffer)

            fullBitmap = Bitmap.createBitmap(
                padded,
                0,
                0,
                image.width,
                image.height
            )

            padded.recycle()
            image.close()

            val source = fullBitmap
                ?: throw IllegalStateException("صفحه قابل خواندن نیست")

            // History column on the supplied mobile website.
            val left = (source.width * 0.10f)
                .toInt().coerceIn(0, source.width - 1)

            val right = (source.width * 0.235f)
                .toInt().coerceIn(left + 1, source.width)

            val top = (source.height * 0.64f)
                .toInt().coerceIn(0, source.height - 1)

            val bottom = (source.height * 0.995f)
                .toInt().coerceIn(top + 1, source.height)

            val cropped = Bitmap.createBitmap(
                source,
                left,
                top,
                right - left,
                bottom - top
            )

            cropBitmap = cropped

            source.recycle()
            fullBitmap = null

            val prepared = prepareForOcr(cropped)
            ocrBitmap = prepared

            recognizer.process(
                InputImage.fromBitmap(prepared, 0)
            )
                .addOnSuccessListener { result ->

                    if (generation != sessionGeneration) {
                        return@addOnSuccessListener
                    }

                    val rows = result.textBlocks
                        .flatMap { it.lines }
                        .mapNotNull { line ->

                            val box = line.boundingBox
                                ?: return@mapNotNull null

                            val matcher = number.matcher(line.text)

                            if (!matcher.find()) {
                                return@mapNotNull null
                            }

                            val value = matcher.group(1)
                                ?.replace(',', '.')
                                ?.toDoubleOrNull()

                            if (
                                value == null ||
                                value !in 1.0..1000.0
                            ) {
                                null
                            } else {
                                box.top to value
                            }
                        }
                        .sortedBy { it.first }
                        .take(MAX_VISIBLE_ROWS)

                    observe(rows.map { it.second })
                }
                .addOnFailureListener {

                    if (generation == sessionGeneration) {
                        statusMessage =
                            "خطای تشخیص متن؛ تاریخچه را واضح نگه دارید."
                        render()
                    }
                }
                .addOnCompleteListener {

                    try { prepared.recycle() } catch (_: Exception) {}
                    try { cropped.recycle() } catch (_: Exception) {}

                    if (generation == sessionGeneration) {
                        processing.set(false)
                    }
                }

        } catch (_: Exception) {

            try { image.close() } catch (_: Exception) {}
            try { fullBitmap?.recycle() } catch (_: Exception) {}
            try { ocrBitmap?.recycle() } catch (_: Exception) {}
            try { cropBitmap?.recycle() } catch (_: Exception) {}

            if (generation == sessionGeneration) {
                processing.set(false)
            }
        }
    }

    private fun prepareForOcr(source: Bitmap): Bitmap {

        val scaled = Bitmap.createScaledBitmap(
            source,
            source.width * 3,
            source.height * 3,
            true
        )

        val width = scaled.width
        val height = scaled.height

        val pixels = IntArray(width * height)

        scaled.getPixels(
            pixels, 0, width,
            0, 0, width, height
        )

        for (i in pixels.indices) {

            val color = pixels[i]

            val gray = (
                Color.red(color) * 299 +
                Color.green(color) * 587 +
                Color.blue(color) * 114
            ) / 1000

            val contrast = (
                (gray - 128) * 1.6f + 128f
            ).toInt().coerceIn(0, 255)

            val value = if (contrast < 215) 0 else 255

            pixels[i] = Color.rgb(value, value, value)
        }

        val output = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        output.setPixels(
            pixels, 0, width,
            0, 0, width, height
        )

        scaled.recycle()

        return output
    }

    // --------------------------------------------------
    // SESSION RESET
    // --------------------------------------------------

    private fun resetSession() {

        sessionGeneration++

        history.clear()
        previousVisible = emptyList()

        forecasts = emptyList()

        completedForecastRounds = 0
        totalNewRounds = 0

        forecastActive = false
        noOverlapCount = 0

        lastAt = 0L
        processing.set(false)

        getSharedPreferences(
            "captured_history",
            MODE_PRIVATE
        ).edit().clear().apply()

        statusMessage =
            "جلسه جدید؛ در انتظار خواندن تاریخچه..."
    }

    // --------------------------------------------------
    // CLEANUP
    // --------------------------------------------------

    private fun stopCaptureResources() {

        reader?.setOnImageAvailableListener(null, null)

        try { display?.release() } catch (_: Exception) {}
        display = null

        try { reader?.close() } catch (_: Exception) {}
        reader = null

        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {}

        try { projection?.stop() } catch (_: Exception) {}
        projection = null

        worker?.quitSafely()
        worker = null
        handler = null

        try {
            overlay?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {}

        overlay = null
        statusText = null
        statsText = null

        processing.set(false)
    }

    // --------------------------------------------------
    // HISTORY MATCHING
    // --------------------------------------------------

    private data class OverlapMatch(
        val visibleIndex: Int,
        val length: Int
    )

    private fun findHeadOverlap(
        previous: List<Double>,
        current: List<Double>
    ): OverlapMatch? {

        if (previous.isEmpty() || current.isEmpty()) {
            return null
        }

        if (current == previous) {
            return OverlapMatch(
                0,
                minOf(previous.size, current.size)
            )
        }

        val maxNew = minOf(
            MAX_NEW_PER_SCAN,
            current.size - 1
        )

        var best: OverlapMatch? = null

        for (newCount in 0..maxNew) {

            val available = current.size - newCount

            val limit = minOf(
                previous.size,
                available
            )

            var length = 0

            while (
                length < limit &&
                previous[length] == current[newCount + length]
            ) {
                length++
            }

            val required = minOf(
                3,
                minOf(previous.size, available)
            )

            if (length >= required) {

                val candidate = OverlapMatch(
                    newCount,
                    length
                )

                val oldBest = best

                if (
                    oldBest == null ||
                    candidate.length > oldBest.length ||
                    (
                        candidate.length == oldBest.length &&
                        candidate.visibleIndex < oldBest.visibleIndex
                    )
                ) {
                    best = candidate
                }
            }
        }

        return best
    }

    // --------------------------------------------------
    // FORECAST CYCLE
    // --------------------------------------------------

    private fun startForecastCycle() {

        val result = PredictionEngine.forecast(
            history.toList()
        )

        if (result.size != FORECAST_SIZE) {
            forecasts = emptyList()
            forecastActive = false
            completedForecastRounds = 0
            return
        }

        forecasts = result
        completedForecastRounds = 0
        forecastActive = true
    }

    private fun registerNewRound(value: Double) {

        history.add(value)

        while (history.size > MAX_SAVED) {
            history.removeAt(0)
        }

        totalNewRounds++

        // Start the first forecast only after collecting
        // ten valid results.
        if (!forecastActive && history.size >= 10) {
            startForecastCycle()
            return
        }

        if (!forecastActive) return

        completedForecastRounds++

        // When all five rounds are completed,
        // calculate a completely new five-round cycle.
        if (completedForecastRounds >= FORECAST_SIZE) {
            startForecastCycle()
        }
    }

    // --------------------------------------------------
    // OBSERVE NEW RESULTS
    // --------------------------------------------------

    private fun observe(visible: List<Double>) {

        if (visible.isEmpty()) {
            statusMessage =
                "ستون انفجار پیدا نشد؛ تاریخچه را واضح نگه دارید."
            render()
            return
        }

        if (previousVisible.isEmpty()) {

            previousVisible = visible.toList()
            noOverlapCount = 0

            statusMessage =
                "مبنای اولیه: ${visible.size} ضریب؛ " +
                "شمارش از دور بعد شروع می‌شود."

            render()
            return
        }

        if (visible == previousVisible) {

            statusMessage =
                "اسکن انجام شد؛ نتیجه جدیدی ثبت نشد."

            render()
            return
        }

        val overlap = findHeadOverlap(
            previousVisible,
            visible
        )

        if (
            overlap == null ||
            overlap.visibleIndex > MAX_NEW_PER_SCAN
        ) {

            noOverlapCount++

            if (noOverlapCount >= 3) {

                previousVisible = visible.toList()
                noOverlapCount = 0

                statusMessage =
                    "مبنای تاریخچه تازه شد؛ داده جدید ثبت نشد."

            } else {

                statusMessage =
                    "در انتظار تطبیق ردیف‌های مشترک..."
            }

            render()
            return
        }

        noOverlapCount = 0

        val newCount = overlap.visibleIndex

        if (newCount > 0) {

            // Process each newly detected result individually.
            // This prevents skipped completion marks when
            // several rows appear between OCR scans.
            val newValues = visible
                .take(newCount)
                .asReversed()

            newValues.forEach { value ->
                registerNewRound(value)
            }
        }

        previousVisible = visible.toList()

        statusMessage = if (newCount > 0) {
            "$newCount نتیجه جدید ثبت شد."
        } else {
            "تاریخچه به‌روزرسانی شد؛ نتیجه جدیدی ثبت نشد."
        }

        render()
    }

    // --------------------------------------------------
    // DISPLAY
    // --------------------------------------------------

    private fun render() {

        val cycleText =
            if (forecastActive) {
                "$completedForecastRounds از 5"
            } else {
                "در انتظار تکمیل 10 نمونه"
            }

        val counterText =
            "نمونه: ${history.size}  |  " +
            "دور جدید: $totalNewRounds  |  " +
            "چرخه: $cycleText"

        val forecastText = if (forecasts.isEmpty()) {

            "هنوز داده کافی برای تحلیل وجود ندارد."

        } else {

            forecasts.take(FORECAST_SIZE)
                .mapIndexed { index, forecast ->

                    val mark =
                        if (index < completedForecastRounds) {
                            "✓"
                        } else {
                            "○"
                        }

                    val distribution =
                        forecast.probabilities.joinToString("·")

                    "$mark دور ${forecast.round}: " +
                    "${forecast.range} " +
                    "(${forecast.probabilityPercent}٪)\n" +
                    "$distribution"
                }
                .joinToString("\n")
        }

        ui.post {

            statusText?.text =
                "$counterText\n$statusMessage"

            statsText?.text = forecastText
        }
    }

    // --------------------------------------------------
    // NOTIFICATION
    // --------------------------------------------------

    private fun createChannel() {

        val channel = NotificationChannel(
            CHANNEL,
            "خواندن صفحه",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(channel)
    }

    // --------------------------------------------------
    // DESTROY
    // --------------------------------------------------

    override fun onDestroy() {

        sessionGeneration++

        stopCaptureResources()

        recognizer.close()

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
