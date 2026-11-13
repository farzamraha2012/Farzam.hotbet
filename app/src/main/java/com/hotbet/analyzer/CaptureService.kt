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
import kotlin.math.abs

class CaptureService : Service() {

    companion object {
        const val ACTION_START =
            "com.example.hotbetanalyzer.START"

        private const val CHANNEL = "screen_reader"
        private const val MAX_SAVED = 300
        private const val MAX_VISIBLE_ROWS = 35
        private const val MAX_NEW_PER_SCAN = 12
        private const val FORECAST_SIZE = 5
        private const val PREFS = "hotbet_phoenix_stats"
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
    private var overlayParams: WindowManager.LayoutParams? = null

    private var statusText: TextView? = null
    private var statsText: TextView? = null
    private var collapseButton: TextView? = null
    private var details: LinearLayout? = null

    private var isCompact = false

    // History is always oldest -> newest.
    private val history = mutableListOf<Double>()

    private var forecasts =
        emptyList<PredictionEngine.Forecast>()

    private var completedForecastRounds = 0
    private var forecastActive = false
    private var totalNewRounds = 0

    // Website order: newest -> oldest.
    private var previousVisible = emptyList<Double>()

    private var statusMessage =
        "در انتظار خواندن تاریخچه ضرایب..."

    private var evaluatedRounds = 0
    private var successfulRanges = 0

    private var modelReport: Map<String, Int> = emptyMap()

    private val number = Pattern.compile(
        "(?<![0-9])([0-9]{1,5}[.,][0-9]{1,2})(?![0-9])"
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
        loadEvaluationStats()
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
            .setContentTitle("HOTBET • Phoenix Analyzer")
            .setContentText("در حال تحلیل تاریخچه ضرایب")
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
        } catch (e: Exception) {
            statusMessage = "خطا در شروع سرویس: ${e.message}"
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
                px(9), px(8), px(9), px(8)
            )

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(29, 35, 47),
                    Color.rgb(12, 16, 25)
                )
            ).apply {
                cornerRadius = 14 * dp
                setStroke(
                    px(1),
                    Color.rgb(115, 87, 39)
                )
            }

            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // HEADER

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "🔥 HOTBET • تحلیل‌گر"
            textSize = 12f
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

        collapseButton = TextView(this).apply {

            text = "−"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(white)

            setPadding(
                px(8), px(1), px(8), px(1)
            )

            background = buttonBackground(dp)

            setOnClickListener {
                toggleCompactMode()
            }
        }

        val close = TextView(this).apply {

            text = "×"
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(white)

            setPadding(
                px(8), px(1), px(8), px(1)
            )

            background = buttonBackground(dp)

            setOnClickListener {
                stopSelf()
            }
        }

        header.addView(collapseButton)

        header.addView(
            View(this),
            LinearLayout.LayoutParams(px(8), 1)
        )

        header.addView(close)

        panel.addView(header)

        // DETAILS

        details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        statusText = TextView(this).apply {

            textSize = 10f
            setTextColor(dim)

            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL

            setPadding(
                0, px(7), 0, px(6)
            )
        }

        details!!.addView(statusText)

        val legend = TextView(this).apply {

            text = "زیر۲ / ۲-۴ / ۴-۶ / ۶-۱۰ / بالای۱۰"

            textSize = 9f
            setTextColor(dim)

            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL

            setPadding(
                px(2), px(3), px(2), px(4)
            )
        }

        details!!.addView(legend)

        statsText = TextView(this).apply {

            textSize = 9f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)

            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL

            setLineSpacing(px(2).toFloat(), 1f)

            setPadding(
                px(7), px(7), px(7), px(7)
            )

            background = GradientDrawable().apply {
                setColor(Color.rgb(39, 46, 59))
                cornerRadius = 10 * dp
            }
        }

        details!!.addView(statsText)

        val disclaimer = TextView(this).apply {

            text = "برآورد آماری؛ بدون تضمین نتیجه"

            textSize = 9f
            setTextColor(dim)

            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL

            setPadding(
                0, px(6), 0, px(3)
            )
        }

        details!!.addView(disclaimer)

        panel.addView(details)

        // INSTAGRAM: Always visible.

        val instagram = TextView(this).apply {

            text = "◎  @2farzam2"

            textSize = 11f
            setTextColor(gold)
            setTypeface(null, Typeface.BOLD)

            gravity = Gravity.CENTER

            setPadding(
                0, px(7), 0, px(2)
            )
        }

        panel.addView(instagram)

        val displayWidth =
            resources.displayMetrics.widthPixels

        val expandedWidth = minOf(
            px(245),
            displayWidth - px(20)
        )

        val params = WindowManager.LayoutParams(

            expandedWidth,

            WindowManager.LayoutParams.WRAP_CONTENT,

            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,

            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,

            PixelFormat.TRANSLUCENT

        ).apply {

            gravity = Gravity.TOP or Gravity.START

            x = (
                displayWidth - expandedWidth - px(10)
            ).coerceAtLeast(0)

            y = px(70)
        }

        overlayParams = params

        // DRAG USING HEADER.
        // Returning true on DOWN is necessary to receive MOVE events.

        header.setOnTouchListener(
            object : View.OnTouchListener {

                private var startX = 0
                private var startY = 0

                private var touchX = 0f
                private var touchY = 0f

                private var moved = false

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

                            moved = false

                            true
                        }

                        MotionEvent.ACTION_MOVE -> {

                            val dx = event.rawX - touchX
                            val dy = event.rawY - touchY

                            if (
                                abs(dx) > px(4) ||
                                abs(dy) > px(4)
                            ) {
                                moved = true
                            }

                            if (moved) {

                                val maxX = (
                                    displayWidth - params.width
                                ).coerceAtLeast(0)

                                val maxY = (
                                    resources.displayMetrics.heightPixels -
                                        px(80)
                                ).coerceAtLeast(0)

                                params.x = (
                                    startX + dx
                                ).toInt().coerceIn(0, maxX)

                                params.y = (
                                    startY + dy
                                ).toInt().coerceIn(0, maxY)

                                updateOverlay()
                            }

                            true
                        }

                        MotionEvent.ACTION_UP -> true

                        MotionEvent.ACTION_CANCEL -> true

                        else -> false
                    }
                }
            }
        )

        wm.addView(panel, params)

        overlay = panel

        render()
    }

    private fun buttonBackground(
        dp: Float
    ): GradientDrawable {

        return GradientDrawable().apply {

            setColor(Color.rgb(48, 54, 65))

            cornerRadius = 8 * dp

            setStroke(
                (dp).toInt().coerceAtLeast(1),
                Color.rgb(115, 87, 39)
            )
        }
    }

    private fun toggleCompactMode() {

        val currentDetails = details ?: return
        val button = collapseButton ?: return
        val params = overlayParams ?: return

        val panel = overlay ?: return

        isCompact = !isCompact

        currentDetails.visibility =
            if (isCompact) {
                View.GONE
            } else {
                View.VISIBLE
            }

        // Minus when expanded, plus when compact.
        button.text = if (isCompact) "+" else "−"

        val dp = resources.displayMetrics.density

        val width = if (isCompact) {
            (190 * dp).toInt()
        } else {
            (245 * dp).toInt()
        }

        params.width = minOf(
            width,
            resources.displayMetrics.widthPixels - (20 * dp).toInt()
        )

        params.x = params.x.coerceAtLeast(0)

        try {
            windowManager?.updateViewLayout(
                panel,
                params
            )
        } catch (_: Exception) {
        }
    }

    private fun updateOverlay() {

        try {
            val panel = overlay ?: return
            val params = overlayParams ?: return

            windowManager?.updateViewLayout(
                panel,
                params
            )
        } catch (_: Exception) {
        }
    }

    // --------------------------------------------------
    // SCREEN CAPTURE
    // --------------------------------------------------

    private fun begin(
        code: Int,
        data: Intent
    ) {

        val mgr = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        val mp = mgr.getMediaProjection(code, data)

        projection = mp

        mp.registerCallback(
            projectionCallback,
            ui
        )

        val dm = resources.displayMetrics

        worker = HandlerThread(
            "ocr-worker"
        ).also {
            it.start()
        }

        handler = Handler(worker!!.looper)

        reader = ImageReader.newInstance(
            dm.widthPixels,
            dm.heightPixels,
            PixelFormat.RGBA_8888,
            3
        )

        reader!!.setOnImageAvailableListener(
            { r ->

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

            },
            handler
        )

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

            padded.copyPixelsFromBuffer(
                plane.buffer
            )

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
                ?: throw IllegalStateException(
                    "صفحه قابل خواندن نیست"
                )

            // Improved crop:
            // Include the top of the history table.
            // The previous 64% crop missed several visible rows.

            val left = (
                source.width * 0.08f
            ).toInt().coerceIn(
                0,
                source.width - 1
            )

            val right = (
                source.width * 0.28f
            ).toInt().coerceIn(
                left + 1,
                source.width
            )

            val top = (
                source.height * 0.49f
            ).toInt().coerceIn(
                0,
                source.height - 1
            )

            val bottom = (
                source.height * 0.995f
            ).toInt().coerceIn(
                top + 1,
                source.height
            )

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
                InputImage.fromBitmap(
                    prepared,
                    0
                )
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

                            val matcher = number.matcher(
                                line.text
                            )

                            if (!matcher.find()) {
                                return@mapNotNull null
                            }

                            val value = matcher.group(1)
                                ?.replace(',', '.')
                                ?.toDoubleOrNull()

                            if (
                                value == null ||
                                value !in 1.0..10000.0
                            ) {
                                null
                            } else {
                                box.top to value
                            }
                        }
                        .sortedBy { it.first }

                        // Remove duplicate OCR lines
                        // with almost identical vertical positions.

                        .distinctBy {
                            it.first / 18
                        }
                        .take(MAX_VISIBLE_ROWS)

                    observe(
                        rows.map { it.second }
                    )
                }
                .addOnFailureListener {

                    if (generation == sessionGeneration) {

                        statusMessage =
                            "خطای تشخیص متن؛ تاریخچه را واضح نگه دارید."

                        render()
                    }
                }
                .addOnCompleteListener {

                    try {
                        prepared.recycle()
                    } catch (_: Exception) {
                    }

                    try {
                        cropped.recycle()
                    } catch (_: Exception) {
                    }

                    if (generation == sessionGeneration) {
                        processing.set(false)
                    }
                }

        } catch (_: Exception) {

            try {
                image.close()
            } catch (_: Exception) {
            }

            try {
                fullBitmap?.recycle()
            } catch (_: Exception) {
            }

            try {
                ocrBitmap?.recycle()
            } catch (_: Exception) {
            }

            try {
                cropBitmap?.recycle()
            } catch (_: Exception) {
            }

            if (generation == sessionGeneration) {
                processing.set(false)
            }
        }
    }

    private fun prepareForOcr(
        source: Bitmap
    ): Bitmap {

        val scaled = Bitmap.createScaledBitmap(
            source,
            source.width * 3,
            source.height * 3,
            true
        )

        val width = scaled.width
        val height = scaled.height

        val pixels = IntArray(
            width * height
        )

        scaled.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
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

            val value = if (contrast < 215) {
                0
            } else {
                255
            }

            pixels[i] = Color.rgb(
                value,
                value,
                value
            )
        }

        val output = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        output.setPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        scaled.recycle()

        return output
    }

    // --------------------------------------------------
    // EVALUATION STATISTICS
    // --------------------------------------------------

    private fun loadEvaluationStats() {

        val prefs = getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        )

        evaluatedRounds = prefs.getInt(
            "evaluated",
            0
        )

        successfulRanges = prefs.getInt(
            "successful",
            0
        )
    }

    private fun saveEvaluationStats() {

        getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        )
            .edit()
            .putInt(
                "evaluated",
                evaluatedRounds
            )
            .putInt(
                "successful",
                successfulRanges
            )
            .apply()
    }

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

        // Evaluation history is intentionally preserved.

        statusMessage =
            "جلسه جدید؛ در انتظار خواندن تاریخچه..."
    }

    // --------------------------------------------------
    // CLEANUP
    // --------------------------------------------------

    private fun stopCaptureResources() {

        reader?.setOnImageAvailableListener(
            null,
            null
        )

        try {
            display?.release()
        } catch (_: Exception) {
        }

        display = null

        try {
            reader?.close()
        } catch (_: Exception) {
        }

        reader = null

        try {
            projection?.unregisterCallback(
                projectionCallback
            )
        } catch (_: Exception) {
        }

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null

        worker?.quitSafely()

        worker = null
        handler = null

        try {
            overlay?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }

        overlay = null
        overlayParams = null

        statusText = null
        statsText = null
        collapseButton = null
        details = null

        processing.set(false)
    }

    // --------------------------------------------------
    // HISTORY MATCHING
    // --------------------------------------------------

    private data class OverlapMatch(
        val visibleIndex: Int,
        val length: Int
    )

    private fun sameValue(
        first: Double,
        second: Double
    ): Boolean {
        return abs(first - second) < 0.005
    }

    private fun sameHistory(
        first: List<Double>,
        second: List<Double>
    ): Boolean {

        if (first.size != second.size) {
            return false
        }

        return first.indices.all {
            sameValue(first[it], second[it])
        }
    }

    private fun findHeadOverlap(
        previous: List<Double>,
        current: List<Double>
    ): OverlapMatch? {

        if (
            previous.isEmpty() ||
            current.isEmpty()
        ) {
            return null
        }

        if (sameHistory(previous, current)) {

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
                sameValue(
                    previous[length],
                    current[newCount + length]
                )
            ) {
                length++
            }

            val required = minOf(
                3,
                minOf(
                    previous.size,
                    available
                )
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
    // FORECAST AND EVALUATION
    // --------------------------------------------------

    private fun rangeIndex(
        range: String
    ): Int {

        return when (range) {
            "0 تا 2" -> 0
            "2 تا 4" -> 1
            "4 تا 6" -> 2
            "6 تا 10" -> 3
            "10 به بالا" -> 4
            else -> -1
        }
    }

    private fun bucket(
        value: Double
    ): Int {

        return when {
            value < 2.0 -> 0
            value < 4.0 -> 1
            value < 6.0 -> 2
            value < 10.0 -> 3
            else -> 4
        }
    }

    private fun evaluateForecast(
        forecast: PredictionEngine.Forecast,
        actual: Double
    ) {

        val predicted = rangeIndex(
            forecast.range
        )

        val observed = bucket(actual)

        if (predicted < 0) return

        evaluatedRounds++

        if (predicted == observed) {
            successfulRanges++
        }

        saveEvaluationStats()
    }

    private fun startForecastCycle() {

        val result = PredictionEngine.forecast(
            history.toList()
        )

        if (result.size != FORECAST_SIZE) {

            forecasts = emptyList()
            forecastActive = false

            completedForecastRounds = 0

            modelReport = emptyMap()

            return
        }

        forecasts = result

        completedForecastRounds = 0
        forecastActive = true

        modelReport =
            PredictionEngine.modelWeightReport(
                history.toList()
            )
    }

    private fun registerNewRound(
        value: Double
    ) {

        if (
            forecastActive &&
            completedForecastRounds < forecasts.size
        ) {

            evaluateForecast(
                forecasts[completedForecastRounds],
                value
            )

            completedForecastRounds++
        }

        history.add(value)

        while (history.size > MAX_SAVED) {
            history.removeAt(0)
        }

        totalNewRounds++

        if (
            !forecastActive &&
            history.size >= 10
        ) {

            startForecastCycle()
            return
        }

        if (!forecastActive) return

        if (
            completedForecastRounds >= FORECAST_SIZE
        ) {
            startForecastCycle()
        }
    }

    // --------------------------------------------------
    // OBSERVE NEW RESULTS
    // --------------------------------------------------

    private fun observe(
        visible: List<Double>
    ) {

        if (visible.isEmpty()) {

            statusMessage =
                "ستون انفجار پیدا نشد؛ تاریخچه را واضح نگه دارید."

            render()
            return
        }

        // INITIAL HISTORY:
        // Seed the engine with all currently visible rows.
        // Website order is newest -> oldest.
        // Internal order must be oldest -> newest.

        if (previousVisible.isEmpty()) {

            previousVisible = visible.toList()

            val initialHistory = visible.asReversed()

            history.clear()
            history.addAll(initialHistory)

            while (history.size > MAX_SAVED) {
                history.removeAt(0)
            }

            noOverlapCount = 0

            statusMessage =
                "${history.size} ضریب اولیه خوانده شد."

            if (history.size >= 10) {
                startForecastCycle()
            }

            render()
            return
        }

        if (sameHistory(visible, previousVisible)) {

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
                    "مبنای تاریخچه تازه شد؛ تطبیق مجدد در حال انجام است."

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

        val cycleText = if (forecastActive) {

            "$completedForecastRounds از 5"

        } else {

            "در انتظار تکمیل 10 نمونه"
        }

        val accuracy = if (evaluatedRounds > 0) {

            successfulRanges * 100 / evaluatedRounds

        } else {

            0
        }

        val counterText =
            "نمونه: ${history.size} | " +
            "دور جدید: $totalNewRounds\n" +
            "چرخه: $cycleText | " +
            "ارزیابی: $evaluatedRounds | " +
            "تطبیق بازه: $accuracy٪"

        val forecastText = if (forecasts.isEmpty()) {

            "هنوز داده کافی برای تحلیل وجود ندارد."

        } else {

            val predictionLines = forecasts
                .take(FORECAST_SIZE)
                .mapIndexed { index, forecast ->

                    val mark =
                        if (index < completedForecastRounds) {
                            "✓"
                        } else {
                            "○"
                        }

                    val distribution =
                        forecast.probabilities.joinToString("  •  ")

                    "$mark دور ${forecast.round}: " +
                    "${forecast.range} " +
                    "(${forecast.probabilityPercent}٪)\n" +
                    distribution
                }
                .joinToString("\n\n")

            val weightsText = if (modelReport.isEmpty()) {

                ""

            } else {

                "\n\nوزن موتورهای تحلیل:\n" +
                    modelReport.entries.joinToString(" | ") {
                        "${it.key}: ${it.value}٪"
                    }
            }

            predictionLines + weightsText
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

        if (Build.VERSION.SDK_INT < 26) return

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

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
