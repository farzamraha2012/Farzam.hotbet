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
import android.content.pm.ServiceInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.regex.Pattern
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale
import kotlin.math.abs

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.hotbetanalyzer.START"

        private const val CHANNEL = "screen_reader"
        private const val MAX_VISIBLE_ROWS = 35
        private const val MAX_NEW_PER_SCAN = 12
        private const val PREFS = "hotbet_phoenix_stats"
    }

    // ------------------------------------------
    // DATABASE AND COROUTINES
    // ------------------------------------------

    private val databaseScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private val databaseMutex = Mutex()

    @Volatile
    private var databaseSessionId: Long? = null

    private var databaseSequence = 0

    private val gameDatabase by lazy {
        GameDatabase.getInstance(applicationContext)
    }

    /**
     * Persists every accepted result. There is deliberately no record cap:
     * Room remains the permanent history store; the in-memory list mirrors it.
     */
    private fun persistResults(
        values: List<Double>,
        source: String
    ) {
        val safeValues = values.filter {
            it.isFinite() && it in 0.0..10000.0
        }
        if (safeValues.isEmpty()) return

        databaseScope.launch {
            try {
                databaseMutex.withLock {
                    val dao = gameDatabase.gameDao()
                    var sessionId = databaseSessionId

                    if (sessionId == null) {
                        sessionId = dao.createSession(GameSession())
                        databaseSessionId = sessionId
                        databaseSequence = 0
                    }

                    safeValues.forEach { multiplier ->
                        databaseSequence++
                        dao.insertResult(
                            GameResult(
                                sessionId = sessionId,
                                sequenceNumber = databaseSequence,
                                multiplier = multiplier,
                                source = source,
                                recordedAt = System.currentTimeMillis()
                            )
                        )
                    }
                    dao.refreshSessionCount(sessionId)
                }
            } catch (e: Exception) {
                ui.post {
                    statusMessage =
                        "خطا در ذخیره تاریخچه: ${e.javaClass.simpleName}"
                    render()
                }
            }
        }
    }

    /**
     * Rebuilds the complete chronological history from every Room session.
     * Baseline rows from a later app launch are merged against the previous
     * history tail, preventing the same visible rows from being appended again.
     */
    private suspend fun restoreStoredHistory(): List<Double> {
        val dao = gameDatabase.gameDao()
        dao.finishAllActiveSessions()

        val sessions = dao.getAllSessions()
        val merged = mutableListOf<Double>()

        for (session in sessions) {
            val rows = dao.getSessionResults(session.id)
            val baseline = rows
                .filter { it.source == "OCR_BASELINE" }
                .map { it.multiplier }
                .filter { it.isFinite() && it in 0.0..10000.0 }

            val newResults = rows
                .filter { it.source != "OCR_BASELINE" }
                .map { it.multiplier }
                .filter { it.isFinite() && it in 0.0..10000.0 }

            if (baseline.isNotEmpty()) {
                if (merged.isEmpty()) {
                    merged.addAll(baseline)
                } else {
                    val overlap = suffixPrefixOverlap(merged, baseline)
                    if (overlap > 0) {
                        merged.addAll(baseline.drop(overlap))
                    } else {
                        // No common rows: likely a new game/table session.
                        merged.addAll(baseline)
                    }
                }
            }

            merged.addAll(newResults)
        }

        return merged
    }

    /**
     * Restore Room before starting MediaProjection/OCR. This avoids a race in
     * which the first OCR baseline overwrites the in-memory training history.
     */
    private fun startAfterDatabaseRestore(
        resultCode: Int,
        data: Intent
    ) {
        val generation = sessionGeneration

        databaseScope.launch {
            try {
                val restored = databaseMutex.withLock {
                    val dao = gameDatabase.gameDao()
                    val historyFromDisk = restoreStoredHistory()

                    val newSessionId = dao.createSession(GameSession())
                    databaseSessionId = newSessionId
                    databaseSequence = 0

                    historyFromDisk
                }

                ui.post {
                    if (generation != sessionGeneration) return@post

                    history.clear()
                    history.addAll(restored)
                    totalNewRounds = 0

                    val savedFingerprint = getSharedPreferences(
                        PREFS,
                        MODE_PRIVATE
                    ).getString("pending_fingerprint", null)

                    if (
                        pendingForecast == null ||
                        savedFingerprint != historyFingerprint(history)
                    ) {
                        updateForecast()
                    }

                    statusMessage = if (history.isEmpty()) {
                        "تاریخچه‌ای ذخیره نشده؛ در انتظار خواندن مبنای اولیه..."
                    } else {
                        "${history.size} ضریب از حافظه دائمی بازیابی شد."
                    }

                    try {
                        showOverlay()
                        begin(resultCode, data)
                        render()
                    } catch (e: Exception) {
                        statusMessage = "خطا در شروع سرویس: ${e.message}"
                        render()
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    if (generation == sessionGeneration) {
                        statusMessage =
                            "خطا در بازیابی تاریخچه: ${e.javaClass.simpleName}"
                        render()
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun historyFingerprint(values: List<Double>): String {
        val tail = values.takeLast(8).joinToString(",") {
            String.format(Locale.US, "%.3f", it)
        }
        return "${values.size}:$tail"
    }

    private fun updateForecast() {
        pendingForecast = PredictionEngine.forecast(history)
        savePendingForecast(pendingForecast)
    }

    private fun savePendingForecast(
        forecast: PredictionEngine.Forecast?
    ) {
        val editor = getSharedPreferences(
            PREFS,
            MODE_PRIVATE
        ).edit()

        if (forecast == null) {
            editor.remove("pending_range")
            editor.remove("pending_probabilities")
            editor.remove("pending_weights")
            editor.remove("pending_samples")
            editor.remove("pending_fingerprint")
        } else {
            editor.putString("pending_range", forecast.range)
            editor.putString(
                "pending_probabilities",
                forecast.probabilities.joinToString(",") {
                    String.format(Locale.US, "%.1f", it)
                }
            )
            editor.putString(
                "pending_weights",
                forecast.modelWeights.joinToString(",") {
                    String.format(Locale.US, "%.1f", it)
                }
            )
            editor.putInt("pending_samples", forecast.sampleCount)
            editor.putString(
                "pending_fingerprint",
                historyFingerprint(history)
            )
        }

        editor.apply()
    }

    private fun readPendingForecast(): PredictionEngine.Forecast? {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val range = prefs.getString("pending_range", null) ?: return null

        val probabilities = prefs.getString(
            "pending_probabilities",
            null
        )?.split(",")?.mapNotNull { it.toDoubleOrNull() } ?: return null

        val weights = prefs.getString(
            "pending_weights",
            null
        )?.split(",")?.mapNotNull { it.toDoubleOrNull() } ?: return null

        if (probabilities.size != 5 || weights.size != 5) return null

        val best = probabilities.indices.maxByOrNull {
            probabilities[it]
        } ?: return null

        return PredictionEngine.Forecast(
            bucketIndex = best,
            range = range,
            probabilityPercent = probabilities[best],
            probabilities = probabilities,
            modelWeights = weights,
            sampleCount = prefs.getInt("pending_samples", 0)
        )
    }

    /**
     * Finishes the current database session.
     */
    private fun finishDatabaseSession() {

        databaseScope.launch {
            try {
                databaseMutex.withLock {

                    val sessionId = databaseSessionId
                        ?: return@withLock

                    gameDatabase.gameDao().finishSession(
                        sessionId
                    )

                    databaseSessionId = null
                    databaseSequence = 0
                }

            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------
    // SCREEN CAPTURE
    // ------------------------------------------

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

    // ------------------------------------------
    // FLOATING WINDOW
    // ------------------------------------------

    private var overlay: View? = null
    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var statusText: TextView? = null
    private var statsText: TextView? = null
    private var collapseButton: TextView? = null
    private var details: LinearLayout? = null

    private var isCompact = false

    // ------------------------------------------
    // ANALYSIS DATA
    // ------------------------------------------

    // Oldest -> newest
    private val history = mutableListOf<Double>()

    // Website order: newest -> oldest
    private var previousVisible = emptyList<Double>()

    // Exactly one pending prediction: the next round only.
    private var pendingForecast: PredictionEngine.Forecast? = null

    private var totalNewRounds = 0
    private var evaluatedRounds = 0
    private var successfulRanges = 0

    private var statusMessage =
        "در انتظار خواندن تاریخچه ضرایب..."
    private val number = Pattern.compile(
        "(?<![0-9])([0-9]{1,5}[.,][0-9]{1,2})(?![0-9])"
    )

    private val projectionCallback =
        object : MediaProjection.Callback() {

            override fun onStop() {
                ui.post {
                    stopSelf()
                }
            }
        }

    // ------------------------------------------
    // SERVICE LIFECYCLE
    // ------------------------------------------

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

        val notification = Notification.Builder(
            this,
            CHANNEL
        )
            .setContentTitle("HOTBET • Phoenix Analyzer")
            .setContentText("در حال تحلیل تاریخچه ضرایب")
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                71,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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
        startAfterDatabaseRestore(code, data)

        return START_NOT_STICKY
    }

    // ------------------------------------------
    // FLOATING WINDOW
    // ------------------------------------------

    private fun showOverlay() {

        val wm = getSystemService(
            WINDOW_SERVICE
        ) as WindowManager

        windowManager = wm

        val dp = resources.displayMetrics.density

        fun px(value: Int) = (value * dp).toInt()

        val white = Color.WHITE
        val dim = Color.rgb(190, 198, 211)
        val gold = Color.rgb(225, 183, 77)

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

            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

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
                px(8),
                px(1),
                px(8),
                px(1)
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
                px(8),
                px(1),
                px(8),
                px(1)
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

        details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        statusText = TextView(this).apply {

            textSize = 10f
            setTextColor(dim)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL

            setPadding(
                0,
                px(7),
                0,
                px(6)
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
                px(2),
                px(3),
                px(2),
                px(4)
            )
        }

        details!!.addView(legend)

        statsText = TextView(this).apply {

            textSize = 9f
            setTextColor(white)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.RIGHT
            textDirection = View.TEXT_DIRECTION_RTL

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
                0,
                px(6),
                0,
                px(3)
            )
        }

        details!!.addView(disclaimer)

        panel.addView(details)

        val instagram = TextView(this).apply {

            text = "◎  @2farzam2"
            textSize = 11f
            setTextColor(gold)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER

            setPadding(
                0,
                px(7),
                0,
                px(2)
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

            gravity = Gravity.TOP or Gravity.START

            x = (
                displayWidth - panelWidth - px(10)
            ).coerceAtLeast(0)

            y = px(70)
        }

        overlayParams = params

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

    private fun buttonBackground(dp: Float): GradientDrawable {

        return GradientDrawable().apply {

            setColor(Color.rgb(48, 54, 65))

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
            if (isCompact) View.GONE else View.VISIBLE

        button.text = if (isCompact) "+" else "−"

        val dp = resources.displayMetrics.density

        val width = if (isCompact) {
            (190 * dp).toInt()
        } else {
            (245 * dp).toInt()
        }

        params.width = minOf(
            width,
            resources.displayMetrics.widthPixels -
                (20 * dp).toInt()
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

    // ------------------------------------------
    // SCREEN CAPTURE
    // ------------------------------------------

    private fun begin(
        code: Int,
        data: Intent
    ) {

        val mgr = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        val mp = mgr.getMediaProjection(
            code,
            data
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

    // ------------------------------------------
    // OCR
    // ------------------------------------------

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

            // Existing calibrated capture region.
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
                                value !in 0.0..10000.0
                            ) {
                                null
                            } else {
                                box.top to value
                            }
                        }
                        .sortedBy { it.first }
                        .distinctBy { it.first / 18 }
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
                    "خطا در پردازش تصویر"

                render()
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

    // ------------------------------------------
    // EVALUATION STATISTICS
    // ------------------------------------------

    private fun loadEvaluationStats() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        evaluatedRounds = prefs.getInt("evaluated", 0)
        successfulRanges = prefs.getInt("successful", 0)
        pendingForecast = readPendingForecast()
    }

    private fun saveEvaluationStats() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putInt("evaluated", evaluatedRounds)
            .putInt("successful", successfulRanges)
            .apply()
    }

    private fun resetSession() {
        sessionGeneration++

        history.clear()
        previousVisible = emptyList()

        totalNewRounds = 0
        noOverlapCount = 0
        lastAt = 0L

        processing.set(false)

        // Keep the persisted pending forecast until the restored history
        // fingerprint tells us whether it is still the correct next-round call.
        statusMessage = "در حال بازیابی تاریخچه دائمی..."
    }

    // ------------------------------------------
    // HISTORY MATCHING
    // ------------------------------------------

    private data class OverlapMatch(
        val visibleIndex: Int,
        val length: Int
    )

    /**
     * Finds the longest overlap between the end of stored chronological
     * history and the beginning of a newly read chronological baseline.
     */
    private fun suffixPrefixOverlap(
        stored: List<Double>,
        baseline: List<Double>
    ): Int {
        val limit = minOf(stored.size, baseline.size)
        val required = minOf(3, limit)
        for (length in limit downTo required) {
            var matches = true
            for (index in 0 until length) {
                if (!sameValue(
                        stored[stored.size - length + index],
                        baseline[index]
                    )
                ) {
                    matches = false
                    break
                }
            }
            if (matches) return length
        }
        return 0
    }

    private fun sameValue(
        a: Double,
        b: Double
    ): Boolean {

        return abs(a - b) < 0.015
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
                minOf(
                    previous.size,
                    current.size
                )
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
                            candidate.visibleIndex <
                            oldBest.visibleIndex
                        )
                ) {
                    best = candidate
                }
            }
        }

        return best
    }

    // ------------------------------------------
    // FORECAST AND EVALUATION
    // ------------------------------------------

    private fun evaluateForecast(
        forecast: PredictionEngine.Forecast,
        actual: Double
    ) {
        if (!actual.isFinite() || actual !in 0.0..10000.0) return

        evaluatedRounds++
        if (forecast.bucketIndex == PredictionEngine.bucketIndex(actual)) {
            successfulRanges++
        }
        saveEvaluationStats()
    }

    /**
     * One actual result closes the previous one-step forecast, is appended
     * permanently, and immediately produces a fresh forecast for the next
     * not-yet-started round.
     */
    private fun registerNewRounds(
        values: List<Double>,
        source: String = "OCR"
    ) {
        val accepted = values.filter {
            it.isFinite() && it in 0.0..10000.0
        }
        if (accepted.isEmpty()) return

        // Keep chronological order for both learning and Room sequence numbers.
        accepted.forEach { value ->
            pendingForecast?.let { evaluateForecast(it, value) }
            history.add(value)
            totalNewRounds++
            updateForecast()
        }

        // Save the whole OCR batch in one ordered database operation.
        persistResults(accepted, source)
    }

    // ------------------------------------------
    // OBSERVE NEW RESULTS
    // ------------------------------------------

    private fun observe(visibleInput: List<Double>) {
        val visible = visibleInput.filter {
            it.isFinite() && it in 0.0..10000.0
        }

        if (visible.isEmpty()) {
            statusMessage =
                "ستون تاریخچه پیدا نشد؛ ستون ضرایب را واضح و بدون پوشش نگه دارید."
            render()
            return
        }

        // First OCR scan after launch: reconcile the visible rows with the
        // persistent Room history instead of replacing the in-memory training history.
        if (previousVisible.isEmpty()) {
            previousVisible = visible.toList()
            val chronological = visible.asReversed()

            if (history.isEmpty()) {
                history.addAll(chronological)
                persistResults(chronological, "OCR_BASELINE")
                updateForecast()
                statusMessage =
                    "${chronological.size} ضریب اولیه ثبت شد؛ تحلیل از تاریخچه آغاز شد."
            } else {
                val overlap = suffixPrefixOverlap(history, chronological)

                if (overlap > 0) {
                    val missed = chronological.drop(overlap)
                    registerNewRounds(missed)

                    statusMessage = if (missed.isNotEmpty()) {
                        "${missed.size} نتیجه‌ای که هنگام توقف برنامه ثبت شده بود بازیابی شد."
                    } else {
                        "تاریخچه دائمی بازیابی و با صفحه تطبیق داده شد."
                    }
                } else {
                    // A non-overlapping visible sequence is treated as a new
                    // table/game baseline. The previous history is preserved.
                    pendingForecast = null
                    savePendingForecast(null)

                    history.addAll(chronological)
                    persistResults(chronological, "OCR_BASELINE")
                    updateForecast()

                    statusMessage =
                        "${chronological.size} ضریب از یک تاریخچه جدید به مجموعه دائمی اضافه شد."
                }
            }

            render()
            return
        }

        if (sameHistory(visible, previousVisible)) {
            statusMessage = "اسکن انجام شد؛ نتیجه جدیدی ثبت نشد."
            render()
            return
        }

        val overlap = findHeadOverlap(previousVisible, visible)

        if (overlap == null || overlap.visibleIndex > MAX_NEW_PER_SCAN) {
            noOverlapCount++

            if (noOverlapCount >= 3) {
                previousVisible = visible.toList()
                noOverlapCount = 0

                val chronological = visible.asReversed()
                val storedOverlap = suffixPrefixOverlap(history, chronological)

                if (storedOverlap > 0) {
                    val missed = chronological.drop(storedOverlap)
                    registerNewRounds(missed)
                    statusMessage =
                        "${missed.size} نتیجه پس از تطبیق مجدد ثبت شد."
                } else {
                    pendingForecast = null
                    savePendingForecast(null)
                    history.addAll(chronological)
                    // This is a mid-session append, not the session's initial baseline.
                    persistResults(chronological, "OCR")
                    updateForecast()
                    statusMessage =
                        "ردیف‌های صفحه تغییر کرده بود؛ مبنای تازه ثبت شد و تاریخچه قبلی حفظ شد."
                }
            } else {
                statusMessage =
                    "در حال تطبیق ردیف‌های مشترک تاریخچه..."
            }

            render()
            return
        }

        noOverlapCount = 0
        val newCount = overlap.visibleIndex

        if (newCount > 0) {
            // The website shows newest -> oldest; register results oldest -> newest.
            val newValues = visible.take(newCount).asReversed()
            registerNewRounds(newValues)
        }

        previousVisible = visible.toList()

        statusMessage = if (newCount > 0) {
            "$newCount نتیجه جدید ثبت شد؛ پیش‌بینی دور بعد به‌روزرسانی شد."
        } else {
            "تاریخچه تطبیق داده شد؛ نتیجه جدیدی ثبت نشد."
        }

        render()
    }
            registerNewRounds(newValues)
        }

        previousVisible = visible.toList()

        statusMessage = if (newCount > 0) {
            "$newCount نتیجه جدید ثبت شد؛ پیش‌بینی دور بعد به‌روزرسانی شد."
        } else {
            "تاریخچه تطبیق داده شد؛ نتیجه جدیدی ثبت نشد."
        }

        render()
    }

    // ------------------------------------------
    // DISPLAY
    // ------------------------------------------

    private fun render() {
        val accuracy = if (evaluatedRounds > 0) {
            successfulRanges * 100.0 / evaluatedRounds
        } else {
            0.0
        }

        val counterText =
            "تاریخچه دائمی: ${history.size} | " +
            "دور جدید این اجرا: $totalNewRounds\n" +
            "ارزیابی: $evaluatedRounds | " +
            "تطبیق تاریخی: ${String.format(Locale.US, "%.1f", accuracy)}٪\n" +
            statusMessage

        val forecastText = pendingForecast?.let { forecast ->
            val probabilities = PredictionEngine.rangeLabels
                .mapIndexed { index, label ->
                    "$label: ${String.format(Locale.US, "%.1f", forecast.probabilities[index])}٪"
                }
                .joinToString("\n")

            val weights = PredictionEngine.modelNames
                .mapIndexed { index, name ->
                    "$name ${String.format(Locale.US, "%.1f", forecast.modelWeights[index])}٪"
                }
                .joinToString(" • ")

            "پیش‌بینی فقط برای دور بعدی:\n" +
                "${forecast.range} — " +
                "${String.format(Locale.US, "%.1f", forecast.probabilityPercent)}٪\n\n" +
                "احتمال پنج بازه:\n$probabilities\n\n" +
                "وزن یادگیری پنج مدل:\n$weights\n" +
                "مبنای تحلیل: ${forecast.sampleCount} ضریب ذخیره‌شده"
        } ?: if (history.size < 10) {
            "برای شروع تحلیل تطبیقی، حداقل ۱۰ ضریب معتبر لازم است.\n" +
                "نمونه فعلی: ${history.size}"
        } else {
            "در حال محاسبه پیش‌بینی دور بعد..."
        }

        ui.post {
            statusText?.text = counterText
            statsText?.text = forecastText
        }
    }

    // ------------------------------------------
    // NOTIFICATION
    // ------------------------------------------

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

    // ------------------------------------------
    // CLEANUP
    // ------------------------------------------

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

    override fun onDestroy() {

        sessionGeneration++

        stopCaptureResources()

        // Finish the current Room session.
        finishDatabaseSession()

        recognizer.close()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
