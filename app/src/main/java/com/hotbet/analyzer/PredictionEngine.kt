package com.example.hotbetanalyzer

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Five-range statistical estimator.
 * Probabilities describe historical statistical estimates,
 * not guaranteed outcomes of a random game.
 */
object PredictionEngine {

    data class Forecast(
        val round: Int,
        val range: String,
        val probabilityPercent: Int,
        val probabilities: List<String>
    )

    private const val BUCKET_COUNT = 5
    private const val MODEL_COUNT = 5
    private const val MIN_HISTORY = 10
    private const val MAX_BACKTEST_ORIGINS = 60

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

        return DoubleArray(BUCKET_COUNT) {
            safe[it] / total
        }
    }

    private fun normalizeWeights(values: DoubleArray): DoubleArray {
        val safe = DoubleArray(MODEL_COUNT) {
            max(0.000001, values[it])
        }

        val total = safe.sum()

        return DoubleArray(MODEL_COUNT) {
            safe[it] / total
        }
    }

    // MODEL 1: Overall historical distribution.
    private fun frequencyModel(data: List<Double>): DoubleArray {
        val counts = DoubleArray(BUCKET_COUNT) { 1.0 }

        data.forEach { value ->
            counts[bucket(value)] += 1.0
        }

        return normalize(counts)
    }

    // MODEL 2: Recency-weighted distribution.
    private fun recencyModel(data: List<Double>): DoubleArray {
        val counts = DoubleArray(BUCKET_COUNT) { 0.8 }

        val halfLife = max(8.0, data.size * 0.22)

        data.forEachIndexed { index, value ->
            val age = (data.lastIndex - index).toDouble()
            val weight = exp(-age / halfLife)

            counts[bucket(value)] += weight
        }

        return normalize(counts)
    }

    // MODEL 3: Different windows for different horizons.
    private fun shortTermModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val windowSize = min(
            data.size,
            8 + horizon * 5
        )

        val window = data.takeLast(windowSize)
        val counts = DoubleArray(BUCKET_COUNT) { 1.0 }

        window.forEachIndexed { index, value ->
            val weight = 0.7 +
                0.6 * (index + 1.0) / window.size

            counts[bucket(value)] += weight
        }

        return normalize(counts)
    }

    // MODEL 4: Multi-step transition analysis.
    private fun transitionModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val prior = frequencyModel(data)
        val current = bucket(data.last())

        val counts = DoubleArray(BUCKET_COUNT) {
            prior[it] * 4.0
        }

        for (i in 0 until data.size - horizon) {

            if (bucket(data[i]) == current) {
                val target = bucket(data[i + horizon])
                counts[target] += 1.0
            }
        }

        return normalize(counts)
    }

    // MODEL 5: Two-step sequence matching.
    private fun sequenceModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val prior = frequencyModel(data)

        val counts = DoubleArray(BUCKET_COUNT) {
            prior[it] * 6.0
        }

        if (data.size < 3) {
            return normalize(counts)
        }

        val previous = bucket(data[data.lastIndex - 1])
        val current = bucket(data.last())

        for (origin in 2 until data.size - horizon + 1) {

            val first = bucket(data[origin - 2])
            val second = bucket(data[origin - 1])

            if (first == previous && second == current) {

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
    ): Array<DoubleArray> {

        return arrayOf(
            frequencyModel(data),
            recencyModel(data),
            shortTermModel(data, horizon),
            transitionModel(data, horizon),
            sequenceModel(data, horizon)
        )
    }

    // Walk-forward evaluation without using future observations.
    private fun adaptiveWeights(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val losses = DoubleArray(MODEL_COUNT)
        val tests = IntArray(MODEL_COUNT)

        val firstOrigin = max(
            MIN_HISTORY,
            data.size - MAX_BACKTEST_ORIGINS - horizon + 1
        )

        val lastOrigin = data.size - horizon + 1

        for (origin in firstOrigin until lastOrigin) {

            val training = data.subList(0, origin)
            val targetIndex = origin + horizon - 1

            if (targetIndex !in data.indices) continue

            val actual = bucket(data[targetIndex])

            val predictions = modelPredictions(
                training,
                horizon
            )

            for (model in 0 until MODEL_COUNT) {

                val probability = max(
                    0.000001,
                    predictions[model][actual]
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

        val learned = normalizeWeights(scores)

        // Avoid excessive weight changes from small samples.
        return DoubleArray(MODEL_COUNT) {
            learned[it] * 0.85 + 0.03
        }
    }

    // Combine the five statistical models.
    private fun combinedForecast(
        data: List<Double>,
        horizon: Int,
        weights: DoubleArray
    ): DoubleArray {

        val predictions = modelPredictions(
            data,
            horizon
        )

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

    // Largest remainder method: percentages always total 100.
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

    private fun bestIndex(percentages: List<Int>): Int {
        return percentages.indices.maxByOrNull {
            percentages[it]
        } ?: 0
    }

    // Five separate horizons, each with all five ranges.
    fun forecast(history: List<Double>): List<Forecast> {

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

            val distribution = combinedForecast(
                data,
                horizon,
                weights
            )

            val percentages = toPercentages(distribution)
            val best = bestIndex(percentages)

            // Keep all five categories, not only alternatives.
            val allRanges = ranges.mapIndexed { index, name ->

                val star = if (index == best) "⭐" else ""

                "$star$name: ${percentages[index]}٪"
            }

            results.add(
                Forecast(
                    round = horizon,
                    range = ranges[best],
                    probabilityPercent = percentages[best],
                    probabilities = allRanges
                )
            )
        }

        return results
    }

    // Model contribution report.
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
