
package com.example.hotbetanalyzer

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Statistical range estimator.
 *
 * This estimates historical frequencies and ranges.
 * It cannot guarantee the next result of a genuinely random game.
 */
object PredictionEngine {

    data class Forecast(
        val round: Int,
        val range: String,
        val probabilityPercent: Int,
        val probabilities: List<String>
    )

    private const val BUCKET_COUNT = 5
    private const val MIN_HISTORY = 10
    private const val MODEL_COUNT = 5
    private const val MAX_BACKTEST_ORIGINS = 60
    private const val PRIOR_STRENGTH = 3.0

    private val ranges = listOf(
        "0 تا 2",
        "2 تا 4",
        "4 تا 6",
        "6 تا 10",
        "10 به بالا"
    )

    private val modelNames = listOf(
        "فراوانی کلی",
        "وزن‌دهی زمانی",
        "روند کوتاه‌مدت",
        "انتقال چندگامی",
        "الگوی توالی"
    )

    private fun bucket(value: Double): Int = when {
        value < 2.0 -> 0
        value < 4.0 -> 1
        value < 6.0 -> 2
        value < 10.0 -> 3
        else -> 4
    }

    private fun normalize(values: DoubleArray): DoubleArray {
        val safe = DoubleArray(BUCKET_COUNT) {
            max(0.000001, values[it])
        }
        val total = safe.sum()
        return DoubleArray(BUCKET_COUNT) { safe[it] / total }
    }

    private fun historicalPrior(data: List<Double>): DoubleArray {
        val counts = DoubleArray(BUCKET_COUNT) { 1.0 }
        data.forEach { counts[bucket(it)] += 1.0 }
        return normalize(counts)
    }

    // MODEL 1: Overall historical distribution.
    private fun frequencyModel(data: List<Double>): DoubleArray {
        val counts = DoubleArray(BUCKET_COUNT) { 1.0 }

        data.forEachIndexed { index, value ->
            val recencyWeight =
                0.85 + 0.30 * (index + 1.0) / data.size

            counts[bucket(value)] += recencyWeight
        }

        return normalize(counts)
    }

    // MODEL 2: Exponential time-decay.
    private fun recencyModel(data: List<Double>): DoubleArray {
        val counts = DoubleArray(BUCKET_COUNT) { 0.75 }

        val halfLife = max(8.0, data.size * 0.22)

        data.forEachIndexed { index, value ->
            val age = (data.lastIndex - index).toDouble()
            val weight = exp(-age / halfLife)

            counts[bucket(value)] += weight
        }

        return normalize(counts)
    }

    // MODEL 3: Horizon-sensitive recent window.
    private fun shortTermModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val windowSize = min(data.size, 8 + horizon * 5)
        val window = data.takeLast(windowSize)

        val counts = DoubleArray(BUCKET_COUNT) { 1.0 }

        window.forEachIndexed { index, value ->
            val weight =
                0.7 + 0.6 * (index + 1.0) / window.size

            counts[bucket(value)] += weight
        }

        return normalize(counts)
    }

    // MODEL 4: Multi-step transition analysis.
    private fun transitionModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val currentBucket = bucket(data.last())
        val prior = historicalPrior(data)

        val counts = DoubleArray(BUCKET_COUNT) {
            prior[it] * PRIOR_STRENGTH
        }

        if (data.size > horizon) {
            for (i in 0 until data.size - horizon) {

                if (bucket(data[i]) == currentBucket) {
                    counts[bucket(data[i + horizon])] += 1.0
                }
            }
        }

        return normalize(counts)
    }

    // MODEL 5: Historical sequence matching.
    private fun sequenceModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val prior = historicalPrior(data)

        val counts = DoubleArray(BUCKET_COUNT) {
            prior[it] * (PRIOR_STRENGTH + 2.0)
        }

        if (data.size < 3) return normalize(counts)

        val previous = bucket(data[data.lastIndex - 1])
        val current = bucket(data.last())

        for (origin in 2 until data.size - horizon + 1) {

            if (
                bucket(data[origin - 2]) == previous &&
                bucket(data[origin - 1]) == current
            ) {

                val targetIndex = origin + horizon - 1

                if (targetIndex in data.indices) {
                    counts[bucket(data[targetIndex])] += 1.0
                }
            }
        }

        return normalize(counts)
    }

    private fun modelPredictions(
        data: List<Double>,
        horizon: Int
    ): Array<DoubleArray> = arrayOf(
        frequencyModel(data),
        recencyModel(data),
        shortTermModel(data, horizon),
        transitionModel(data, horizon),
        sequenceModel(data, horizon)
    )

    // Adaptive model weights using walk-forward evaluation.
    private fun adaptiveWeights(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val losses = DoubleArray(MODEL_COUNT)
        val tests = IntArray(MODEL_COUNT)

        val firstOrigin = max(
            MIN_HISTORY - 1,
            data.size - MAX_BACKTEST_ORIGINS - horizon + 1
        )

        val lastOriginExclusive = data.size - horizon + 1

        for (origin in firstOrigin until lastOriginExclusive) {

            if (origin < MIN_HISTORY - 1) continue

            val training = data.subList(0, origin)

            val targetIndex = origin + horizon - 1

            if (targetIndex !in data.indices) continue

            val actualBucket = bucket(data[targetIndex])

            val predictions = modelPredictions(
                training,
                horizon
            )

            for (model in 0 until MODEL_COUNT) {

                val probability = max(
                    0.000001,
                    predictions[model][actualBucket]
                )

                losses[model] += -ln(probability)
                tests[model]++
            }
        }

        if (tests.all { it == 0 }) {
            return DoubleArray(MODEL_COUNT) {
                1.0 / MODEL_COUNT
            }
        }

        val scores = DoubleArray(MODEL_COUNT)

        for (model in 0 until MODEL_COUNT) {

            val averageLoss = if (tests[model] > 0) {
                losses[model] / tests[model]
            } else {
                2.0
            }

            scores[model] = exp(-averageLoss)
        }

        val normalizedScores = normalizeModelWeights(scores)

        return DoubleArray(MODEL_COUNT) {
            normalizedScores[it] * 0.85 +
                (0.15 / MODEL_COUNT)
        }
    }

    private fun normalizeModelWeights(
        input: DoubleArray
    ): DoubleArray {

        val safe = DoubleArray(MODEL_COUNT) {
            max(0.000001, input[it])
        }

        val total = safe.sum()

        return DoubleArray(MODEL_COUNT) {
            safe[it] / total
        }
    }

    // Combine all five models.
    private fun combinedForecast(
        data: List<Double>,
        horizon: Int,
        weights: DoubleArray
    ): DoubleArray {

        val predictions = modelPredictions(data, horizon)

        val combined = DoubleArray(BUCKET_COUNT)

        for (model in 0 until MODEL_COUNT) {

            for (category in 0 until BUCKET_COUNT) {

                combined[category] +=
                    predictions[model][category] *
                    weights[model]
            }
        }

        return normalize(combined)
    }

    private fun bestIndex(
        probabilities: DoubleArray
    ): Int {

        var best = 0

        for (i in 1 until probabilities.size) {

            if (probabilities[i] > probabilities[best]) {
                best = i
            }
        }

        return best
    }

    private fun toPercentages(
        weights: DoubleArray
    ): List<Int> {

        val result = weights.map {
            (it * 100.0).roundToInt()
        }.toMutableList()

        val difference = 100 - result.sum()

        val largest = result.indices.maxByOrNull {
            result[it]
        } ?: 0

        result[largest] += difference

        return result
    }

    // Generate five separate horizon estimates.
    fun forecast(
        history: List<Double>
    ): List<Forecast> {

        val data = history.filter {
            it.isFinite() &&
                it >= 1.0 &&
                it <= 10000.0
        }

        if (data.size < MIN_HISTORY) {
            return emptyList()
        }

        val results = mutableListOf<Forecast>()

        for (horizon in 1..5) {

            val weights = adaptiveWeights(
                data,
                horizon
            )

            val probabilities = combinedForecast(
                data,
                horizon,
                weights
            )

            val best = bestIndex(probabilities)

            results.add(
                Forecast(
                    round = horizon,
                    range = ranges[best],
                    probabilityPercent =
                        (probabilities[best] * 100.0)
                            .roundToInt(),
                    probabilities = emptyList()
                )
            )
        }

        return results
    }

    // Model weight report for CaptureService.
    fun modelWeightReport(
        history: List<Double>
    ): Map<String, Int> {

        val data = history.filter {
            it.isFinite() &&
                it >= 1.0 &&
                it <= 10000.0
        }

        if (data.size < MIN_HISTORY) {
            return emptyMap()
        }

        val percentages = toPercentages(
            adaptiveWeights(data, 1)
        )

        return modelNames.mapIndexed { index, name ->
            name to percentages[index]
        }.toMap()
    }
}
