
package com.example.hotbetanalyzer

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

object PredictionEngine {

    data class Forecast(
        val round: Int,
        val range: String,
        val probabilityPercent: Int,
        val probabilities: List<String>
    )

    private const val BUCKETS = 5
    private const val MIN_HISTORY = 10
    private const val MODEL_COUNT = 5
    private const val SMOOTHING = 1.5

    private val ranges = listOf(
        "0 تا 2",
        "2 تا 4",
        "4 تا 6",
        "6 تا 10",
        "10 به بالا"
    )

    private val modelNames = listOf(
        "فراوانی کلی",
        "روند کوتاه‌مدت",
        "روند بلندمدت",
        "توالی و انتقال",
        "وزن‌دهی نمایی"
    )

    private fun bucket(value: Double): Int = when {
        value < 2.0 -> 0
        value < 4.0 -> 1
        value < 6.0 -> 2
        value < 10.0 -> 3
        else -> 4
    }

    private fun normalize(input: DoubleArray): DoubleArray {
        val safe = DoubleArray(BUCKETS) {
            max(0.000001, input[it])
        }

        val total = safe.sum()

        return DoubleArray(BUCKETS) {
            safe[it] / total
        }
    }

    private fun smoothedCounts(
        data: List<Double>
    ): DoubleArray {
        val counts = DoubleArray(BUCKETS) {
            SMOOTHING
        }

        data.forEach {
            counts[bucket(it)] += 1.0
        }

        return counts
    }

    // MODEL 1: Overall historical distribution
    private fun frequencyModel(
        data: List<Double>
    ): DoubleArray {

        return normalize(smoothedCounts(data))
    }

    // MODEL 2: Short-term trend
    private fun shortTermModel(
        data: List<Double>
    ): DoubleArray {

        val window = data.takeLast(minOf(15, data.size))
        val counts = DoubleArray(BUCKETS) {
            SMOOTHING
        }

        window.forEachIndexed { index, value ->

            val weight = 0.65 +
                0.70 * (index + 1.0) / window.size

            counts[bucket(value)] += weight
        }

        return normalize(counts)
    }

    // MODEL 3: Long-term distribution
    private fun longTermModel(
        data: List<Double>
    ): DoubleArray {

        val window = data.takeLast(minOf(100, data.size))
        val counts = DoubleArray(BUCKETS) {
            SMOOTHING
        }

        window.forEachIndexed { index, value ->

            val weight = 0.85 +
                0.30 * (index + 1.0) / window.size

            counts[bucket(value)] += weight
        }

        return normalize(counts)
    }

    // MODEL 4: Conditional transition probabilities
    private fun transitionModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val counts = DoubleArray(BUCKETS) {
            SMOOTHING
        }

        if (data.size <= horizon) {
            return normalize(counts)
        }

        val current = bucket(data.last())

        var observations = 0

        for (i in 0 until data.size - horizon) {

            if (bucket(data[i]) != current) continue

            val target = bucket(data[i + horizon])

            counts[target] += 1.0
            observations++
        }

        // Reduce the effect of weak transition samples.
        val reliability = observations.toDouble() /
            (observations + 12.0)

        val conditional = normalize(counts)
        val baseline = frequencyModel(data)

        return normalize(
            DoubleArray(BUCKETS) {
                conditional[it] * reliability +
                    baseline[it] * (1.0 - reliability)
            }
        )
    }

    // MODEL 5: Exponentially weighted history
    private fun exponentialModel(
        data: List<Double>
    ): DoubleArray {

        val counts = DoubleArray(BUCKETS) {
            SMOOTHING
        }

        val decay = 0.94

        data.forEachIndexed { index, value ->

            val age = data.size - 1 - index
            val weight = decay.pow(age.toDouble())

            counts[bucket(value)] += weight
        }

        return normalize(counts)
    }

    private fun modelPrediction(
        data: List<Double>,
        model: Int,
        horizon: Int
    ): DoubleArray {

        return when (model) {
            0 -> frequencyModel(data)
            1 -> shortTermModel(data)
            2 -> longTermModel(data)
            3 -> transitionModel(data, horizon)
            4 -> exponentialModel(data)
            else -> frequencyModel(data)
        }
    }

    // Walk-forward evaluation:
    // Each target is predicted using only earlier observations.
    private fun adaptiveWeights(
        data: List<Double>
    ): DoubleArray {

        if (data.size < 20) {
            return DoubleArray(MODEL_COUNT) {
                1.0 / MODEL_COUNT
            }
        }

        val losses = DoubleArray(MODEL_COUNT)
        val tests = IntArray(MODEL_COUNT)

        val start = max(10, data.size - 80)

        for (targetIndex in start until data.size) {

            val training = data.subList(0, targetIndex)
            val actual = bucket(data[targetIndex])

            for (model in 0 until MODEL_COUNT) {

                val probabilities = modelPrediction(
                    training,
                    model,
                    1
                )

                val loss = -ln(
                    max(0.000001, probabilities[actual])
                )

                losses[model] += loss
                tests[model]++
            }
        }

        val rawWeights = DoubleArray(MODEL_COUNT)

        for (model in 0 until MODEL_COUNT) {

            val averageLoss = if (tests[model] > 0) {
                losses[model] / tests[model]
            } else {
                1.5
            }

            rawWeights[model] = exp(
                -averageLoss.coerceIn(0.0, 10.0)
            )
        }

        val total = rawWeights.sum()

        val learned = DoubleArray(MODEL_COUNT) {
            rawWeights[it] / total
        }

        // Regularize weights to avoid overreacting
        // to a small number of historical tests.
        return DoubleArray(MODEL_COUNT) {
            learned[it] * 0.70 +
                (0.30 / MODEL_COUNT)
        }
    }

    // Combine all five models into one decision.
    private fun combinedForecast(
        data: List<Double>,
        horizon: Int,
        weights: DoubleArray
    ): DoubleArray {

        val combined = DoubleArray(BUCKETS)

        for (model in 0 until MODEL_COUNT) {

            val probabilities = modelPrediction(
                data,
                model,
                horizon
            )

            for (category in 0 until BUCKETS) {

                combined[category] +=
                    probabilities[category] * weights[model]
            }
        }

        return normalize(combined)
    }

    // Largest-remainder rounding:
    // Percentages always add up to exactly 100.
    private fun percentageList(
        probabilities: DoubleArray
    ): List<Int> {

        val exact = probabilities.map {
            it * 100.0
        }

        val result = exact.map {
            it.toInt()
        }.toMutableList()

        var remaining = 100 - result.sum()

        val ranking = exact.indices.sortedByDescending {
            exact[it] - result[it]
        }

        var index = 0

        while (remaining > 0) {
            result[ranking[index % BUCKETS]]++
            remaining--
            index++
        }

        return result
    }

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

        val weights = adaptiveWeights(data)
        val results = mutableListOf<Forecast>()

        for (horizon in 1..5) {

            val probabilities = combinedForecast(
                data,
                horizon,
                weights
            )

            val percentages = percentageList(
                probabilities
            )

            val best = percentages.indices.maxBy {
                probabilities[it]
            }

            // Show ALL five probability ranges.
            val allRanges = ranges.indices.map { index ->
                "${ranges[index]}: ${percentages[index]}٪"
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

        val weights = adaptiveWeights(data)

        val percentages = percentageList(weights)

        return modelNames.mapIndexed { index, name ->
            name to percentages[index]
        }.toMap()
    }
}
