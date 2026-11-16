package com.example.hotbetanalyzer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Five statistical estimators combined into a calibrated ensemble.
 *
 * Important:
 * - The data must be chronological: oldest -> newest.
 * - This engine estimates historical range frequencies and temporal
 *   associations. It cannot know or guarantee an independent game outcome.
 * - Five horizons are returned. Horizons 2..5 are conditional Markov-style
 *   estimates; when history contains no reliable temporal signal, repeated
 *   ranges are valid and are never artificially diversified.
 */
object PredictionEngine {

    const val MIN_HISTORY = 10
    const val BUCKET_COUNT = 5
    const val MODEL_COUNT = 5
    const val HORIZON_COUNT = 5

    private const val MAX_BACKTEST_ORIGINS = 160
    private const val MAX_TRAINING_HISTORY = 2500
    private const val MIN_CALIBRATION_CASES = 30

    private const val TRANSITION_PRIOR_STRENGTH = 12.0
    private const val SEQUENCE_PRIOR_STRENGTH = 14.0
    private const val WEIGHT_TEMPERATURE = 0.16

    val rangeNames = listOf(
        "0 تا 2",
        "2 تا 4",
        "4 تا 6",
        "6 تا 10",
        "10 به بالا"
    )

    val modelNames = listOf(
        "فراوانی کلی",
        "وزن‌دهی زمانی",
        "روند کوتاه‌مدت",
        "انتقال بازه‌ها",
        "الگوی توالی"
    )

    data class Forecast(
        val round: Int,
        val range: String,
        val probabilityPercent: Int,
        val bestBucket: Int,
        val probabilities: List<Int>,
        val baselineProbabilities: List<Int>,
        val baselineBestBucket: Int,
        val modelWeights: List<Int>,
        val ensembleSharePercent: Int,
        val probabilityGapPercent: Int,
        val backtestCount: Int,
        val calibrationApplied: Boolean,
        val backtestEnsembleHitRatePercent: Int,
        val backtestBaselineHitRatePercent: Int,
        val backtestEnsembleLogLoss: Double,
        val backtestBaselineLogLoss: Double
    )

    private data class ValidationCase(
        val predictions: Array<DoubleArray>,
        val actual: Int
    )

    private data class ModelProfile(
        val weights: DoubleArray,
        val temperatures: DoubleArray,
        val backtestCount: Int,
        val calibrationApplied: Boolean,
        val backtestEnsembleHitRatePercent: Int,
        val backtestBaselineHitRatePercent: Int,
        val backtestEnsembleLogLoss: Double,
        val backtestBaselineLogLoss: Double,
        val ensembleShare: Double
    )

    fun bucketIndex(value: Double): Int {
        return when {
            value < 2.0 -> 0
            value < 4.0 -> 1
            value < 6.0 -> 2
            value < 10.0 -> 3
            else -> 4
        }
    }

    private fun normalize(values: DoubleArray): DoubleArray {
        val safe = DoubleArray(values.size) { index ->
            val value = values[index]
            if (value.isFinite() && value > 0.0) {
                value
            } else {
                0.000001
            }
        }

        val total = safe.sum()
        if (!total.isFinite() || total <= 0.0) {
            return DoubleArray(values.size) { 1.0 / values.size }
        }

        return DoubleArray(values.size) {
            safe[it] / total
        }
    }

    private fun normalizeWeights(values: DoubleArray): DoubleArray {
        return normalize(values)
    }

    // MODEL 1: Overall historical frequency with Laplace smoothing.

    private fun frequencyModel(data: List<Double>): DoubleArray {
        val counts = DoubleArray(BUCKET_COUNT) { 1.0 }

        data.forEach { value ->
            counts[bucketIndex(value)] += 1.0
        }

        return normalize(counts)
    }

    // MODEL 2: Exponentially time-weighted historical frequency.

    private fun recencyModel(data: List<Double>): DoubleArray {
        val counts = DoubleArray(BUCKET_COUNT) { 0.8 }

        val halfLife = max(
            5.0,
            data.size * 0.22
        )

        data.forEachIndexed { index, value ->
            val age = (data.lastIndex - index).toDouble()
            val weight = exp(-age / halfLife)
            counts[bucketIndex(value)] += weight
        }

        return normalize(counts)
    }

    // MODEL 3: Recent-window frequency with a small recency gradient.

    private fun shortTermModel(data: List<Double>): DoubleArray {
        val windowSize = min(
            data.size,
            max(8, data.size / 2)
        )

        val window = data.takeLast(windowSize)
        val counts = DoubleArray(BUCKET_COUNT) { 1.0 }

        window.forEachIndexed { index, value ->
            val weight =
                0.7 + 0.6 * (index + 1.0) / window.size

            counts[bucketIndex(value)] += weight
        }

        return normalize(counts)
    }

    /**
     * First-order transition matrix.
     * Sparse rows are shrunk toward the overall empirical distribution.
     */
    private fun transitionMatrix(
        data: List<Double>
    ): Array<DoubleArray> {
        val prior = frequencyModel(data)

        val counts = Array(BUCKET_COUNT) {
            DoubleArray(BUCKET_COUNT) { target ->
                prior[target] * TRANSITION_PRIOR_STRENGTH
            }
        }

        for (index in 0 until data.lastIndex) {
            val from = bucketIndex(data[index])
            val to = bucketIndex(data[index + 1])
            counts[from][to] += 1.0
        }

        return Array(BUCKET_COUNT) {
            normalize(counts[it])
        }
    }

    // MODEL 4: Multi-step first-order Markov transition estimate.

    private fun transitionModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {
        val matrix = transitionMatrix(data)

        var state = DoubleArray(BUCKET_COUNT)
        state[bucketIndex(data.last())] = 1.0

        repeat(horizon.coerceAtLeast(1)) {
            val next = DoubleArray(BUCKET_COUNT)

            for (from in 0 until BUCKET_COUNT) {
                for (to in 0 until BUCKET_COUNT) {
                    next[to] += state[from] * matrix[from][to]
                }
            }

            state = normalize(next)
        }

        return state
    }

    /**
     * MODEL 5: Second-order sequence model.
     *
     * State is (previous bucket, current bucket). Sparse sequence states
     * back off to the first-order transition row for the current bucket.
     */
    private fun sequenceModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {
        if (data.size < 3) {
            return transitionModel(data, horizon)
        }

        val firstOrder = transitionMatrix(data)

        val sequenceCounts = Array(BUCKET_COUNT * BUCKET_COUNT) { state ->
            val currentBucket = state % BUCKET_COUNT

            DoubleArray(BUCKET_COUNT) { nextBucket ->
                firstOrder[currentBucket][nextBucket] *
                    SEQUENCE_PRIOR_STRENGTH
            }
        }

        for (index in 2 until data.size) {
            val previous = bucketIndex(data[index - 2])
            val current = bucketIndex(data[index - 1])
            val next = bucketIndex(data[index])

            val state = previous * BUCKET_COUNT + current
            sequenceCounts[state][next] += 1.0
        }

        val sequenceTransitions = Array(
            BUCKET_COUNT * BUCKET_COUNT
        ) {
            normalize(sequenceCounts[it])
        }

        var pairState = DoubleArray(
            BUCKET_COUNT * BUCKET_COUNT
        )

        val previous = bucketIndex(data[data.lastIndex - 1])
        val current = bucketIndex(data.last())
        pairState[previous * BUCKET_COUNT + current] = 1.0

        repeat(horizon.coerceAtLeast(1)) {
            val nextPairState = DoubleArray(
                BUCKET_COUNT * BUCKET_COUNT
            )

            for (state in pairState.indices) {
                val mass = pairState[state]
                if (mass <= 0.0) continue

                val currentBucket = state % BUCKET_COUNT
                val nextDistribution = sequenceTransitions[state]

                for (nextBucket in 0 until BUCKET_COUNT) {
                    val nextState =
                        currentBucket * BUCKET_COUNT + nextBucket

                    nextPairState[nextState] +=
                        mass * nextDistribution[nextBucket]
                }
            }

            pairState = normalize(nextPairState)
        }

        val result = DoubleArray(BUCKET_COUNT)

        for (state in pairState.indices) {
            val bucket = state % BUCKET_COUNT
            result[bucket] += pairState[state]
        }

        return normalize(result)
    }

    private fun modelPredictions(
        data: List<Double>,
        horizon: Int
    ): Array<DoubleArray> {
        return arrayOf(
            frequencyModel(data),
            recencyModel(data),
            shortTermModel(data),
            transitionModel(data, horizon),
            sequenceModel(data, horizon)
        )
    }

    private fun applyTemperature(
        probabilities: DoubleArray,
        temperature: Double
    ): DoubleArray {
        val safeTemperature = temperature.coerceIn(0.5, 3.0)

        val logits = DoubleArray(probabilities.size) {
            ln(max(0.000001, probabilities[it])) /
                safeTemperature
        }

        val maxLogit = logits.maxOrNull() ?: 0.0
        val exponentials = DoubleArray(logits.size) {
            exp(logits[it] - maxLogit)
        }

        return normalize(exponentials)
    }

    private fun logLoss(
        probabilities: DoubleArray,
        actual: Int
    ): Double {
        return -ln(
            max(0.000001, probabilities[actual])
        )
    }


    private fun weightsFromPastLosses(
        losses: DoubleArray,
        tests: IntArray
    ): DoubleArray {
        val equalWeights = DoubleArray(MODEL_COUNT) {
            1.0 / MODEL_COUNT
        }

        val usableTests = tests.minOrNull() ?: 0
        if (usableTests <= 0) return equalWeights

        val averageLosses = DoubleArray(MODEL_COUNT) {
            losses[it] / tests[it].coerceAtLeast(1)
        }

        val bestLoss = averageLosses.minOrNull() ?: return equalWeights

        val scores = DoubleArray(MODEL_COUNT) {
            val relativeLoss =
                (averageLosses[it] - bestLoss).coerceAtLeast(0.0)

            exp(
                -(relativeLoss / WEIGHT_TEMPERATURE)
                    .coerceIn(0.0, 20.0)
            )
        }

        val learned = normalizeWeights(scores)
        val confidence =
            usableTests.toDouble() / (usableTests + 50.0)

        return normalizeWeights(
            DoubleArray(MODEL_COUNT) {
                learned[it] * confidence +
                    equalWeights[it] * (1.0 - confidence)
            }
        )
    }

    private fun combineRawPredictions(
        predictions: Array<DoubleArray>,
        weights: DoubleArray
    ): DoubleArray {
        val combined = DoubleArray(BUCKET_COUNT)

        for (model in 0 until MODEL_COUNT) {
            for (bucket in 0 until BUCKET_COUNT) {
                combined[bucket] +=
                    predictions[model][bucket] * weights[model]
            }
        }

        return normalize(combined)
    }

    private fun argmax(probabilities: DoubleArray): Int {
        return probabilities.indices.maxByOrNull {
            probabilities[it]
        } ?: 0
    }

    /**
     * Fit a single temperature per model from walk-forward predictions.
     * The forecasts used for fitting were generated without seeing their
     * corresponding actual outcomes.
     */
    private fun fitTemperature(
        cases: List<ValidationCase>,
        modelIndex: Int
    ): Double {
        if (cases.size < MIN_CALIBRATION_CASES) {
            return 1.0
        }

        var bestTemperature = 1.0
        var bestScore = Double.POSITIVE_INFINITY

        // Search 0.70 .. 2.40 in 0.05 increments.
        for (step in 0..34) {
            val temperature = 0.70 + step * 0.05

            var totalLoss = 0.0

            cases.forEach { sample ->
                val calibrated = applyTemperature(
                    sample.predictions[modelIndex],
                    temperature
                )

                totalLoss += logLoss(
                    calibrated,
                    sample.actual
                )
            }

            val meanLoss = totalLoss / cases.size

            // Mild regularization prevents unstable temperatures
            // from small or noisy validation samples.
            val regularization =
                0.015 * ln(temperature) * ln(temperature)

            val score = meanLoss + regularization

            if (score < bestScore) {
                bestScore = score
                bestTemperature = temperature
            }
        }

        return bestTemperature
    }

    /**
     * Estimate model weights from calibrated walk-forward log loss.
     * We shrink learned weights toward equal weights to limit overfitting.
     */
    private fun buildModelProfile(
        data: List<Double>
    ): ModelProfile {
        val equalWeights = DoubleArray(MODEL_COUNT) {
            1.0 / MODEL_COUNT
        }

        val temperatures = DoubleArray(MODEL_COUNT) {
            1.0
        }

        val firstOrigin = max(
            MIN_HISTORY,
            data.size - MAX_BACKTEST_ORIGINS
        )

        val cases = mutableListOf<ValidationCase>()

        for (origin in firstOrigin until data.size) {
            val trainingStart = max(
                0,
                origin - MAX_TRAINING_HISTORY
            )

            val training = data.subList(
                trainingStart,
                origin
            )

            if (training.size < MIN_HISTORY) continue

            cases.add(
                ValidationCase(
                    predictions = modelPredictions(training, 1),
                    actual = bucketIndex(data[origin])
                )
            )
        }

        if (cases.isEmpty()) {
            return ModelProfile(
                weights = equalWeights,
                temperatures = temperatures,
                backtestCount = 0,
                calibrationApplied = false,
                backtestEnsembleHitRatePercent = 0,
                backtestBaselineHitRatePercent = 0,
                backtestEnsembleLogLoss = 0.0,
                backtestBaselineLogLoss = 0.0,
                ensembleShare = 0.15
            )
        }

        // Every case is a genuine walk-forward prediction:
        // its training data ends before the result being tested.
        if (cases.size >= MIN_CALIBRATION_CASES) {
            for (model in 0 until MODEL_COUNT) {
                temperatures[model] = fitTemperature(
                    cases,
                    model
                )
            }
        }

        val calibratedLosses = DoubleArray(MODEL_COUNT)

        cases.forEach { sample ->
            for (model in 0 until MODEL_COUNT) {
                val calibrated = applyTemperature(
                    sample.predictions[model],
                    temperatures[model]
                )

                calibratedLosses[model] += logLoss(
                    calibrated,
                    sample.actual
                )
            }
        }

        val averageLosses = DoubleArray(MODEL_COUNT) {
            calibratedLosses[it] / cases.size
        }

        val bestLoss = averageLosses.minOrNull() ?: 1.0

        val scores = DoubleArray(MODEL_COUNT) {
            val relativeLoss = (
                averageLosses[it] - bestLoss
            ).coerceAtLeast(0.0)

            exp(
                -(relativeLoss / WEIGHT_TEMPERATURE)
                    .coerceIn(0.0, 20.0)
            )
        }

        val learnedWeights = normalizeWeights(scores)

        val confidence =
            cases.size.toDouble() /
                (cases.size + 50.0)

        val blendedWeights = DoubleArray(MODEL_COUNT) {
            learnedWeights[it] * confidence +
                equalWeights[it] * (1.0 - confidence)
        }

        // Strict prequential comparison:
        // at each validation point, model weights use only earlier cases.
        val onlineLosses = DoubleArray(MODEL_COUNT)
        val onlineTests = IntArray(MODEL_COUNT)

        var ensembleHits = 0
        var baselineHits = 0
        var ensembleLoss = 0.0
        var baselineLoss = 0.0
        val skillDeltas = mutableListOf<Double>()

        cases.forEach { sample ->
            val onlineWeights = weightsFromPastLosses(
                onlineLosses,
                onlineTests
            )

            val onlineEnsemble = combineRawPredictions(
                sample.predictions,
                onlineWeights
            )

            val baseline = sample.predictions[0]

            if (argmax(onlineEnsemble) == sample.actual) {
                ensembleHits++
            }

            if (argmax(baseline) == sample.actual) {
                baselineHits++
            }

            val currentEnsembleLoss = logLoss(
                onlineEnsemble,
                sample.actual
            )

            val currentBaselineLoss = logLoss(
                baseline,
                sample.actual
            )

            ensembleLoss += currentEnsembleLoss
            baselineLoss += currentBaselineLoss
            skillDeltas.add(
                currentBaselineLoss - currentEnsembleLoss
            )

            // Update weights only after this sample has been scored.
            for (model in 0 until MODEL_COUNT) {
                onlineLosses[model] += logLoss(
                    sample.predictions[model],
                    sample.actual
                )
                onlineTests[model]++
            }
        }

        val ensembleShare = if (cases.size < MIN_CALIBRATION_CASES) {
            0.15
        } else {
            val meanSkill = skillDeltas.average()
            val variance = if (skillDeltas.size > 1) {
                skillDeltas.sumOf {
                    val difference = it - meanSkill
                    difference * difference
                } / (skillDeltas.size - 1)
            } else {
                0.0
            }

            val standardError =
                kotlin.math.sqrt(variance / skillDeltas.size)

            // Require evidence beyond ordinary sampling noise before
            // allowing temporal models to dominate the frequency baseline.
            val supportedSkill =
                (meanSkill - standardError).coerceAtLeast(0.0)

            (0.15 + supportedSkill * 2.0)
                .coerceIn(0.15, 0.65)
        }

        return ModelProfile(
            weights = normalizeWeights(blendedWeights),
            temperatures = temperatures,
            backtestCount = cases.size,
            calibrationApplied =
                cases.size >= MIN_CALIBRATION_CASES,
            backtestEnsembleHitRatePercent =
                (ensembleHits * 100.0 / cases.size).toInt(),
            backtestBaselineHitRatePercent =
                (baselineHits * 100.0 / cases.size).toInt(),
            backtestEnsembleLogLoss =
                ensembleLoss / cases.size,
            backtestBaselineLogLoss =
                baselineLoss / cases.size,
            ensembleShare = ensembleShare
        )
    }

    private fun combinePredictions(
        predictions: Array<DoubleArray>,
        profile: ModelProfile,
        data: List<Double>
    ): DoubleArray {
        val ensemble = DoubleArray(BUCKET_COUNT)

        for (model in 0 until MODEL_COUNT) {
            val calibrated = applyTemperature(
                predictions[model],
                profile.temperatures[model]
            )

            for (bucket in 0 until BUCKET_COUNT) {
                ensemble[bucket] +=
                    calibrated[bucket] * profile.weights[model]
            }
        }

        val baseline = frequencyModel(data)

        // If the temporal ensemble has not beaten the simple frequency
        // baseline in walk-forward testing, keep its contribution small.
        return normalize(
            DoubleArray(BUCKET_COUNT) { bucket ->
                ensemble[bucket] * profile.ensembleShare +
                    baseline[bucket] * (1.0 - profile.ensembleShare)
            }
        )
    }

    private fun toPercentages(
        probabilities: DoubleArray
    ): List<Int> {
        val result = probabilities.map {
            (it * 100.0).toInt()
        }.toMutableList()

        val remainders = probabilities.mapIndexed { index, value ->
            index to (value * 100.0 - result[index])
        }

        var remaining = 100 - result.sum()

        val order = remainders.sortedByDescending {
            it.second
        }

        for (entry in order) {
            if (remaining <= 0) break
            result[entry.first]++
            remaining--
        }

        return result
    }

    private fun bestIndex(
        probabilities: DoubleArray,
        data: List<Double>
    ): Int {
        val highest = probabilities.maxOrNull() ?: return 0

        val tied = probabilities.indices.filter {
            abs(probabilities[it] - highest) < 0.0000000001
        }

        if (tied.size == 1) return tied.first()

        val counts = IntArray(BUCKET_COUNT)

        data.forEach {
            counts[bucketIndex(it)]++
        }

        val highestCount = tied.maxOf {
            counts[it]
        }

        return tied.firstOrNull {
            counts[it] == highestCount
        } ?: tied.first()
    }

    /**
     * Returns forecasts for horizons 1..5.
     * The first forecast is the next observed round and is the only one
     * used for live hit-rate evaluation when a new result arrives.
     */
    fun forecastNextFive(
        history: List<Double>
    ): List<Forecast> {
        val data = history.filter {
            it.isFinite() &&
                it > 0.0 &&
                it <= 10000.0
        }

        if (data.size < MIN_HISTORY) {
            return emptyList()
        }

        val profile = buildModelProfile(data)

        val baselineDistribution = frequencyModel(data)
        val baselinePercentages = toPercentages(
            baselineDistribution
        )
        val baselineBest = bestIndex(
            baselineDistribution,
            data
        )

        return (1..HORIZON_COUNT).map { horizon ->
            val predictions = modelPredictions(
                data,
                horizon
            )

            val distribution = combinePredictions(
                predictions,
                profile,
                data
            )

            val percentages = toPercentages(
                distribution
            )

            val best = bestIndex(
                distribution,
                data
            )

            val sorted = distribution.sortedDescending()
            val gap = if (sorted.size >= 2) {
                ((sorted[0] - sorted[1]) * 100.0)
                    .toInt()
                    .coerceAtLeast(0)
            } else {
                0
            }

            Forecast(
                round = horizon,
                range = rangeNames[best],
                probabilityPercent = percentages[best],
                bestBucket = best,
                probabilities = percentages,
                baselineProbabilities = baselinePercentages,
                baselineBestBucket = baselineBest,
                modelWeights = toPercentages(profile.weights),
                ensembleSharePercent =
                    (profile.ensembleShare * 100.0).toInt(),
                probabilityGapPercent = gap,
                backtestCount = profile.backtestCount,
                calibrationApplied = profile.calibrationApplied,
                backtestEnsembleHitRatePercent =
                    profile.backtestEnsembleHitRatePercent,
                backtestBaselineHitRatePercent =
                    profile.backtestBaselineHitRatePercent,
                backtestEnsembleLogLoss =
                    profile.backtestEnsembleLogLoss,
                backtestBaselineLogLoss =
                    profile.backtestBaselineLogLoss
            )
        }
    }

    // Compatibility helper for any code that still needs one forecast.

    fun forecast(
        history: List<Double>
    ): Forecast? {
        return forecastNextFive(history).firstOrNull()
    }
}
