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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.util.Locale
import java.util.regex.Pattern
import java.util.concurrent.atomic.AtomicBoolean

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CaptureService : Service() {

    companion object {

        const val ACTION_START =
            "com.example.hotbetanalyzer.START"

        private const val CHANNEL = "screen_reader"

        private const val MAX_VISIBLE_ROWS = 35

        private const val MAX_NEW_PER_SCAN = 12

        private const val PREFS = "hotbet_evaluation"

        private const val OVERLAP_TOLERANCE = 0.015
    }

    // --------------------------------------------------
    // SCREEN CAPTURE
    // --------------------------------------------------

    private var projection: MediaProjection? = null

    private var display: VirtualDisplay? = null

    private var reader: ImageReader? = null

    private var worker: HandlerThread? = null

    private var handler: Handler? = null

    private val ui = Handler(Looper.getMainLooper())

    private val processing = AtomicBoolean(false)

    private val recognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    @Volatile
    private var lastAt = 0L

    @Volatile
    private var sessionGeneration = 0

    private var noOverlapCount = 0

    // --------------------------------------------------
    // DATABASE
    // --------------------------------------------------

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )

    private var gameDao: GameDao? = null

    @Volatile
    private var activeSessionId = 0L

    private var sessionSequence = 0

    private var writeTail: Job? = null

    // --------------------------------------------------
    // FLOATING WINDOW
    // --------------------------------------------------

    private var overlay: View? = null

    private var windowManager: WindowManager? = null

    private var overlayParams:
        WindowManager.LayoutParams? = null

    private var statusText: TextView? = null

    private var statsText: TextView? = null

    private var details: LinearLayout? = null

    private var collapseButton: TextView? = null

    private var isCompact = false

    // --------------------------------------------------
    // HISTORY
    // --------------------------------------------------

    // Oldest -> newest.
    private val history = mutableListOf<Double>()

    // Newest -> oldest, exactly as displayed on website.
    private var previousVisible = emptyList<Double>()

    private var totalNewRounds = 0

    private var currentForecast:
        PredictionEngine.Forecast? = null

    // --------------------------------------------------
    // EVALUATION
    // --------------------------------------------------

    private var evaluatedRounds = 0

    private var successfulRanges = 0

    private var statusMessage =
        "در انتظار خواندن تاریخچه ضرایب..."

    private val number = Pattern.compile(
        "(?<![0-9])([0-9]{1,5}[.,][0-9]{1,3})(?![0-9])"
    )

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {
                ui.post {
                    stopSelf()
                }
            }
        }

    // --------------------------------------------------
    // CREATE
    // --------------------------------------------------

    override fun onCreate() {

        super.onCreate()

        createChannel()

        loadEvaluationStats()
    }

    // --------------------------------------------------
    // START SERVICE
    // --------------------------------------------------

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action != ACTION_START) {
            return START_NOT_STICKY
        }

        val notification =
            Notification.Builder(this, CHANNEL)
                .setContentTitle(
                    "HOTBET • Phoenix Analyzer • نسخه ۰٫۹٫۵"
                )
                .setContentText(
                    "در حال تحلیل تاریخچه ضرایب"
                )
                .setSmallIcon(
                    R.drawable.ic_stat_capture
                )
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

            statusMessage =
                "مجوز پنجره شناور فعال نیست."

            stopSelf()

            return START_NOT_STICKY
        }

        val code = intent.getIntExtra(
            "resultCode",
            Activity.RESULT_CANCELED
        )

        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) {

            intent.getParcelableExtra(
                "data",
                Intent::class.java
            )

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

        initializeStorageAndBegin(code, data)

        return START_NOT_STICKY
    }

    // --------------------------------------------------
    // LOAD DATABASE BEFORE OCR
    // --------------------------------------------------

    private fun initializeStorageAndBegin(
        code: Int,
        data: Intent
    ) {

        val generation = sessionGeneration

        serviceScope.launch {

            try {

                val dao = GameDatabase
                    .getInstance(applicationContext)
                    .gameDao()

                // Close any session left active by a previous run.
                dao.finishAllActiveSessions()

                // Load all previously saved multipliers.
                val stored = dao.getAllMultipliers()

                // Create a fresh session without deleting old records.
                val newSessionId = dao.createSession(
                    GameSession()
                )

                withContext(Dispatchers.Main) {

                    if (generation != sessionGeneration) {
                        return@withContext
                    }

                    gameDao = dao

                    activeSessionId = newSessionId

                    sessionSequence = 0

                    history.clear()

                    history.addAll(
                        stored.filter {
                            it.isFinite() &&
                                it > 0.0 &&
                                it <= 10000.0
                        }
                    )

                    refreshForecast()

                    statusMessage =
                        "${history.size} ضریب از حافظه دائمی بازیابی شد."

                    showOverlay()

                    try {

                        begin(code, data)

                    } catch (e: Exception) {

                        statusMessage =
                            "خطا در شروع ضبط: ${e.message}"

                        render()

                        stopSelf()
                    }
                }

            } catch (e: Exception) {

                ui.post {

                    statusMessage =
                        "خطا در بازیابی دیتابیس: ${e.message}"

                    render()

                    stopSelf()
                }
            }
        }
    }

    // --------------------------------------------------
    // PERSIST RESULTS
    // --------------------------------------------------

    private fun persistResults(
        values: List<Double>,
        source: String = "OCR"
    ) {

        if (values.isEmpty()) return

        val dao = gameDao ?: return

        val sessionId = activeSessionId

        if (sessionId <= 0L) return

        val firstSequence = sessionSequence + 1

        sessionSequence += values.size

        // Queue database writes in order.
        val previousWrite = writeTail

        writeTail = serviceScope.launch {

            previousWrite?.join()

            values.forEachIndexed { index, multiplier ->

                dao.insertResult(
                    GameResult(
                        sessionId = sessionId,
                        sequenceNumber = firstSequence + index,
                        multiplier = multiplier,
                        source = source
                    )
                )
            }

            dao.refreshSessionCount(sessionId)
        }
    }

    // --------------------------------------------------
    // FLOATING WINDOW
    // --------------------------------------------------

    private fun showOverlay() {

        val wm =
            getSystemService(WINDOW_SERVICE) as WindowManager

        windowManager = wm

        val dp = resources.displayMetrics.density

        fun px(value: Int) =
            (value * dp).toInt()

        val white = Color.WHITE

        val dim = Color.rgb(
            190, 198, 211
        )

        val gold = Color.rgb(
            225, 183, 77
        )

        val panel = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                px(9),
                px(8),
                px(9),
                px(8)
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

            layoutDirection =
                View.LAYOUT_DIRECTION_RTL
        }

        val header = LinearLayout(this).apply {

            orientation = LinearLayout.HORIZONTAL

            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {

            text = "🔥 HOTBET • PHOENIX • نسخه ۰٫۹٫۵"

            textSize = 12f

            setTextColor(gold)

            setTypeface(
                null,
                Typeface.BOLD
            )

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
            LinearLayout.LayoutParams(
                px(8),
                1
            )
        )

        header.addView(close)

        panel.addView(header)

        details = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL
        }

        statusText = TextView(this).apply {

            textSize = 10f

            setTextColor(dim)

            gravity = Gravity.RIGHT

            textDirection =
                View.TEXT_DIRECTION_RTL

            setPadding(
                0, px(7), 0, px(6)
            )
        }

        details!!.addView(statusText)

        val legend = TextView(this).apply {

            text =
                "زیر۲ / ۲-۴ / ۴-۶ / ۶-۱۰ / بالای۱۰"

            textSize = 9f

            setTextColor(dim)

            gravity = Gravity.RIGHT

            textDirection =
                View.TEXT_DIRECTION_RTL

            setPadding(
                px(2), px(3), px(2), px(4)
            )
        }

        details!!.addView(legend)

        statsText = TextView(this).apply {

            textSize = 9f

            setTextColor(white)

            setTypeface(
                null,
                Typeface.BOLD
            )

            gravity = Gravity.RIGHT

            textDirection =
                View.TEXT_DIRECTION_RTL

            setLineSpacing(
                px(2).toFloat(),
                1f
            )

            setPadding(
                px(7),
                px(7),
                px(7),
                px(7)
            )

            background = GradientDrawable().apply {

                setColor(
                    Color.rgb(39, 46, 59)
                )

                cornerRadius = 10 * dp
            }
        }

        details!!.addView(statsText)

        val disclaimer = TextView(this).apply {

            text =
                "برآورد آماری؛ بدون تضمین نتیجه"

            textSize = 9f

            setTextColor(dim)

            gravity = Gravity.RIGHT

            textDirection =
                View.TEXT_DIRECTION_RTL

            setPadding(
                0, px(6), 0, px(3)
            )
        }

        details!!.addView(disclaimer)

        panel.addView(details)

        val instagram = TextView(this).apply {

            text = "◎  @2farzam2"

            textSize = 11f

            setTextColor(gold)

            setTypeface(
                null,
                Typeface.BOLD
            )

            gravity = Gravity.CENTER

            setPadding(
                0, px(7), 0, px(2)
            )
        }

        panel.addView(instagram)

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

            gravity =
                Gravity.TOP or Gravity.START

            x = (
                displayWidth - panelWidth - px(10)
            ).coerceAtLeast(0)

            y = px(70)
        }

        overlayParams = params

        // Drag panel from its header.

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

                            val dx =
                                event.rawX - touchX

                            val dy =
                                event.rawY - touchY

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

            setColor(
                Color.rgb(48, 54, 65)
            )

            cornerRadius = 8 * dp

            setStroke(
                dp.toInt().coerceAtLeast(1),
                Color.rgb(115, 87, 39)
            )
        }
    }

    private fun toggleCompactMode() {

        val currentDetails = details ?: return

        val button = collapseButton ?: return

        val panel = overlay ?: return

        val params = overlayParams ?: return

        isCompact = !isCompact

        currentDetails.visibility =
            if (isCompact) View.GONE
            else View.VISIBLE

        button.text =
            if (isCompact) "+" else "−"

        val dp = resources.displayMetrics.density

        params.width = minOf(

            if (isCompact) {
                (190 * dp).toInt()
            } else {
                (245 * dp).toInt()
            },

            resources.displayMetrics.widthPixels -
                (20 * dp).toInt()
        )

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

        val mgr =
            getSystemService(MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        val mp = mgr.getMediaProjection(
            code,
            data
        ) ?: throw IllegalStateException(
            "دسترسی ضبط صفحه برقرار نشد"
        )

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

        handler = Handler(
            worker!!.looper
        )

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

            // Calibrated capture region for the supplied
            // portrait HotBet mobile layout.

            val left = (
                source.width * 0.10f
            ).toInt().coerceIn(
                0,
                source.width - 1
            )

            val right = (
                source.width * 0.235f
            ).toInt().coerceIn(
                left + 1,
                source.width
            )

            val top = (
                source.height * 0.64f
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

            val prepared = prepareForOcr(
                cropped
            )

            ocrBitmap = prepared

            recognizer.process(

                InputImage.fromBitmap(
                    prepared,
                    0
                )

            ).addOnSuccessListener { result ->

                if (generation != sessionGeneration) {
                    return@addOnSuccessListener
                }

                val candidates =
                    result.textBlocks
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
                                !value.isFinite() ||
                                value <= 0.0 ||
                                value > 10000.0
                            ) {

                                null

                            } else {

                                box.top to value
                            }
                        }
                        .sortedBy {
                            it.first
                        }

                // Remove duplicate OCR lines by vertical position.
                // Repeated multiplier values at different rows remain valid.

                val uniqueRows =
                    mutableListOf<Pair<Int, Double>>()

                candidates.forEach { row ->

                    val last = uniqueRows.lastOrNull()

                    if (
                        last == null ||
                        abs(row.first - last.first) > 8
                    ) {
                        uniqueRows.add(row)
                    }
                }

                val visible = uniqueRows
                    .take(MAX_VISIBLE_ROWS)
                    .map { it.second }

                observe(visible)
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

        } catch (e: Exception) {

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

                statusMessage =
                    "خطا در پردازش تصویر: ${e.message}"

                render()
            }
        }
    }

    // --------------------------------------------------
    // IMAGE PREPARATION
    // --------------------------------------------------

    private fun prepareForOcr(
        source: Bitmap
    ): Bitmap {

        val scaled = Bitmap.createScaledBitmap(

            source,

            source.width * 2,

            source.height * 2,

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

            ).toInt().coerceIn(
                0,
                255
            )

            // Preserve grayscale information instead
            // of applying an aggressive black/white threshold.

            pixels[i] = Color.rgb(
                contrast,
                contrast,
                contrast
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

    // --------------------------------------------------
    // RESET CURRENT SESSION
    // --------------------------------------------------

    private fun resetSession() {

        sessionGeneration++

        history.clear()

        previousVisible = emptyList()

        totalNewRounds = 0

        currentForecast = null

        noOverlapCount = 0

        lastAt = 0L

        processing.set(false)

        activeSessionId = 0L

        sessionSequence = 0

        statusMessage =
            "جلسه جدید؛ در انتظار بازیابی تاریخچه..."

        // IMPORTANT:
        // No database or SharedPreferences records are deleted.
    }

    // --------------------------------------------------
    // FORECAST
    // --------------------------------------------------

    private fun refreshForecast() {

        currentForecast =
            PredictionEngine.forecast(
                history.toList()
            )
    }

    private fun evaluateForecast(
        forecast: PredictionEngine.Forecast,
        actual: Double
    ) {

        val observed =
            PredictionEngine.bucketIndex(actual)

        evaluatedRounds++

        if (forecast.bestBucket == observed) {
            successfulRanges++
        }

        saveEvaluationStats()
    }

    private fun registerNewRound(
        value: Double
    ) {

        // Evaluate the previous one-round estimate first.
        // The actual result is not added to training history
        // until this evaluation has been completed.

        currentForecast?.let {

            evaluateForecast(
                it,
                value
            )
        }

        history.add(value)

        totalNewRounds++

        persistResults(
            listOf(value),
            "OCR"
        )

        // Calculate exactly one new future round.
        refreshForecast()
    }

    // --------------------------------------------------
    // HISTORY COMPARISON
    // --------------------------------------------------

    private fun sameValue(
        first: Double,
        second: Double
    ): Boolean {

        return abs(first - second) <
            OVERLAP_TOLERANCE
    }

    private fun sameHistory(
        first: List<Double>,
        second: List<Double>
    ): Boolean {

        if (first.size != second.size) {
            return false
        }

        return first.indices.all {

            sameValue(
                first[it],
                second[it]
            )
        }
    }

    // Compare stored chronological history with the
    // chronological order of the visible baseline.

    private fun findStoredOverlap(
        stored: List<Double>,
        visibleChronological: List<Double>
    ): Int {

        val maxLength = min(
            stored.size,
            visibleChronological.size
        )

        if (maxLength == 0) return 0

        val required = min(
            3,
            maxLength
        )

        for (length in maxLength downTo required) {

            var matches = true

            for (i in 0 until length) {

                if (
                    !sameValue(
                        stored[stored.size - length + i],
                        visibleChronological[i]
                    )
                ) {

                    matches = false

                    break
                }
            }

            if (matches) {
                return length
            }
        }

        return 0
    }

    private data class OverlapMatch(
        val visibleIndex: Int,
        val length: Int
    )

    // New website results appear at the head of its list.

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
                previous.size
            )
        }

        val maxNew = min(
            MAX_NEW_PER_SCAN,
            current.size - 1
        )

        var best: OverlapMatch? = null

        for (newCount in 0..maxNew) {

            val available =
                current.size - newCount

            val limit = min(
                previous.size,
                available
            )

            val required = min(
                3,
                limit
            )

            if (required <= 0) continue

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

            if (length >= required) {

                val candidate = OverlapMatch(
                    newCount,
                    length
                )

                val old = best

                if (

                    old == null ||

                    candidate.length > old.length ||

                    (
                        candidate.length == old.length &&
                        candidate.visibleIndex < old.visibleIndex
                    )

                ) {

                    best = candidate
                }
            }
        }

        return best
    }

    // --------------------------------------------------
    // OBSERVE NEW OCR RESULTS
    // --------------------------------------------------

    private fun observe(
        visible: List<Double>
    ) {

        if (visible.isEmpty()) {

            statusMessage =
                "ستون انفجار پیدا نشد؛ صفحه تاریخچه را واضح نگه دارید."

            render()

            return
        }

        // First OCR snapshot of this session.

        if (previousVisible.isEmpty()) {

            val chronological =
                visible.asReversed()

            if (history.isEmpty()) {

                // First-ever database initialization.
                // Existing visible results are saved as baseline,
                // not counted as newly observed rounds.

                history.addAll(chronological)

                persistResults(
                    chronological,
                    "OCR_BASELINE"
                )

                statusMessage =
                    "${chronological.size} ضریب اولیه ثبت شد."

            } else {

                // Compare current website history with
                // the last saved database results.

                val overlap = findStoredOverlap(
                    history,
                    chronological
                )

                if (overlap > 0) {

                    val unseen = chronological.drop(
                        overlap
                    )

                    unseen.forEach {
                        registerNewRound(it)
                    }

                    statusMessage = if (unseen.isNotEmpty()) {

                        "${unseen.size} نتیجه جدید از زمان آخرین اجرا بازیابی شد."

                    } else {

                        "تاریخچه قبلی با موفقیت بازیابی شد."
                    }

                } else {

                    // No reliable overlap: do not insert a possibly
                    // duplicated or unrelated baseline.

                    statusMessage =
                        "تاریخچه قبلی بارگذاری شد؛ مبنای جدید OCR تنظیم شد."
                }
            }

            previousVisible = visible.toList()

            refreshForecast()

            render()

            return
        }

        if (
            sameHistory(
                visible,
                previousVisible
            )
        ) {

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

            val newValues = visible
                .take(newCount)
                .asReversed()

            newValues.forEach {

                registerNewRound(it)
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

        val count = history.size

        val accuracy = if (evaluatedRounds > 0) {

            successfulRanges * 100 / evaluatedRounds

        } else {

            0
        }

        val counterText =

            "نمونه تاریخی: $count | " +
            "دور جدید: $totalNewRounds\n" +

            "ارزیابی: $evaluatedRounds | " +
            "تطبیق بازه: $accuracy٪"

        val forecast = currentForecast

        val forecastText = if (forecast == null) {

            "هنوز داده کافی برای تحلیل وجود ندارد.\n" +
            "حداقل ${PredictionEngine.MIN_HISTORY} ضریب معتبر لازم است."

        } else {

            val distribution =
                PredictionEngine.rangeNames
                    .mapIndexed { index, name ->

                        val mark = if (
                            index == forecast.bestBucket
                        ) "⭐" else "•"

                        "$mark $name: ${forecast.probabilities[index]}٪"
                    }
                    .joinToString("\n")

            val weights =
                PredictionEngine.modelNames
                    .mapIndexed { index, name ->

                        "$name: ${forecast.modelWeights[index]}٪"
                    }
                    .joinToString(" | ")

            "برآورد آماری فقط برای دور بعد:\n" +
            "${forecast.range} " +
            "(${forecast.probabilityPercent}٪)\n\n" +

            distribution +

            "\n\nوزن موتورهای تحلیل:\n" +
            weights
        }

        ui.post {

            statusText?.text =
                "$counterText\n$statusMessage"

            statsText?.text =
                forecastText
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

        windowManager = null

        statusText = null

        statsText = null

        collapseButton = null

        details = null

        processing.set(false)
    }

    // --------------------------------------------------
    // DESTROY
    // --------------------------------------------------

    override fun onDestroy() {

        sessionGeneration++

        // Finish the session only after pending database writes.
        val sessionId = activeSessionId

        val dao = gameDao

        val pending = writeTail

        if (sessionId > 0L && dao != null) {

            serviceScope.launch {

                pending?.join()

                dao.finishSession(sessionId)
            }
        }

        stopCaptureResources()

        recognizer.close()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
