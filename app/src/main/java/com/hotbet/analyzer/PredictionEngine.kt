
package com.example.hotbetanalyzer

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.roundToInt

object PredictionEngine {

    data class Forecast(
        val round: Int,
        val range: String,
        val probabilityPercent: Int,
        val probabilities: List<String>
    )

    private const val BUCKETS = 5
    private const val MODEL_COUNT = 5
    private const val MIN_HISTORY = 10

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
        "انتقال توالی",
        "شرایط مشابه گذشته"
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

    private fun validHistory(
        history: List<Double>
    ): List<Double> {
        return history.filter {
            it.isFinite() && it >= 1.0 && it <= 10000.0
        }
    }

    // MODEL 1: Overall frequency.

    private fun frequencyModel(
        data: List<Double>
    ): DoubleArray {

        val counts = DoubleArray(BUCKETS) { 1.0 }

        data.forEachIndexed { index, value ->
            val recency =
                (index + 1.0) / data.size

            counts[bucket(value)] += 0.5 + recency
        }

        return normalize(counts)
    }

    // MODEL 2: Recent short-term behavior.

    private fun shortTermModel(
        data: List<Double>
    ): DoubleArray {

        val window = data.takeLast(
            minOf(12, data.size)
        )

        val counts = DoubleArray(BUCKETS) { 1.0 }

        window.forEachIndexed { index, value ->

            val weight =
                1.0 + (index + 1.0) / window.size

            counts[bucket(value)] += weight
        }

        return normalize(counts)
    }

    // MODEL 3: Long-term behavior.

    private fun longTermModel(
        data: List<Double>
    ): DoubleArray {

        val window = data.takeLast(
            minOf(80, data.size)
        )

        val counts = DoubleArray(BUCKETS) { 1.0 }

        window.forEachIndexed { index, value ->

            val weight =
                0.75 + 0.5 *
                    (index + 1.0) / window.size

            counts[bucket(value)] += weight
        }

        return normalize(counts)
    }

    // MODEL 4: Historical transitions.

    private fun transitionModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val transitions = Array(BUCKETS) {
            DoubleArray(BUCKETS) { 1.0 }
        }

        val lastBucket = bucket(data.last())

        for (i in 0 until data.size - horizon) {

            if (bucket(data[i]) != lastBucket) {
                continue
            }

            val target = bucket(data[i + horizon])

            val recency =
                (i + 1.0) / data.size

            transitions[lastBucket][target] +=
                0.5 + recency
        }

        return normalize(transitions[lastBucket])
    }

    // MODEL 5: Similar historical situations.
    // Finds previous values close to the latest value
    // and examines the following historical result.

    private fun similarSituationModel(
        data: List<Double>
    ): DoubleArray {

        val counts = DoubleArray(BUCKETS) { 1.0 }

        if (data.size < 2) {
            return normalize(counts)
        }

        val currentLog = ln(data.last())

        for (i in 0 until data.size - 1) {

            val previousLog = ln(data[i])

            val distance = abs(
                currentLog - previousLog
            )

            val similarity = exp(
                -distance / 0.65
            )

            val recency =
                0.5 + 0.5 * (i + 1.0) / data.size

            val weight = similarity * recency

            val nextBucket = bucket(data[i + 1])

            counts[nextBucket] += weight
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
            4 -> similarSituationModel(data)
            else -> frequencyModel(data)
        }
    }

    // Adaptive weights using walk-forward backtesting.
    // No future observations are used in training.

    private fun adaptiveWeights(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val losses = DoubleArray(MODEL_COUNT)
        val tests = IntArray(MODEL_COUNT)

        val lastOrigin = data.size - horizon - 1

        if (lastOrigin < 6) {
            return DoubleArray(MODEL_COUNT) {
                1.0 / MODEL_COUNT
            }
        }

        val firstOrigin = max(
            6,
            lastOrigin - 79
        )

        for (origin in firstOrigin..lastOrigin) {

            val training = data.subList(
                0,
                origin + 1
            )

            val actualIndex = origin + horizon
            val actual = bucket(data[actualIndex])

            for (model in 0 until MODEL_COUNT) {

                val probabilities = modelPrediction(
                    training,
                    model,
                    horizon
                )

                val loss = -ln(
                    max(
                        0.000001,
                        probabilities[actual]
                    )
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
                ln(BUCKETS.toDouble())
            }

            rawWeights[model] = exp(
                -averageLoss.coerceIn(0.0, 10.0)
            )
        }

        val total = rawWeights.sum()

        if (total <= 0.0) {
            return DoubleArray(MODEL_COUNT) {
                1.0 / MODEL_COUNT
            }
        }

        val learned = DoubleArray(MODEL_COUNT) {
            rawWeights[it] / total
        }

        // A small uniform component prevents one model
        // from dominating due to a limited test sample.

        return DoubleArray(MODEL_COUNT) {
            0.85 * learned[it] +
                0.15 / MODEL_COUNT
        }
    }

    // Combine all five models.

    private fun combinedForecast(
        data: List<Double>,
        horizon: Int,
        weights: DoubleArray
    ): DoubleArray {

        val combined = DoubleArray(BUCKETS)

        for (model in 0 until MODEL_COUNT) {

            val prediction = modelPrediction(
                data,
                model,
                horizon
            )

            for (category in 0 until BUCKETS) {

                combined[category] +=
                    prediction[category] * weights[model]
            }
        }

        return normalize(combined)
    }

    private fun percentageList(
        probabilities: DoubleArray
    ): List<Int> {

        val percentages = probabilities.map {
            (it * 100).roundToInt()
        }.toMutableList()

        val difference = 100 - percentages.sum()

        val largest = percentages.indices.maxBy {
            percentages[it]
        }

        percentages[largest] += difference

        return percentages
    }

    // Public forecasting interface.

    fun forecast(
        history: List<Double>
    ): List<Forecast> {

        val data = validHistory(history)

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

            val percentages = percentageList(
                probabilities
            )

            val best = probabilities.indices.maxBy {
                probabilities[it]
            }

            // Always display the complete distribution.
            // Do not force different ranges artificially.

            val distribution = ranges.indices.map { index ->

                "${ranges[index]}: ${percentages[index]}٪"
            }

            results.add(
                Forecast(
                    round = horizon,
                    range = ranges[best],
                    probabilityPercent = percentages[best],
                    probabilities = distribution
                )
            )
        }

        return results
    }

    // Report the average weight of each model
    // across all five forecast horizons.

    fun modelWeightReport(
        history: List<Double>
    ): Map<String, Int> {

        val data = validHistory(history)

        if (data.size < MIN_HISTORY) {
            return emptyMap()
        }

        val average = DoubleArray(MODEL_COUNT)

        for (horizon in 1..5) {

            val weights = adaptiveWeights(
                data,
                horizon
            )

            for (model in 0 until MODEL_COUNT) {
                average[model] += weights[model] / 5.0
            }
        }

        val result = modelNames.mapIndexed { index, name ->

            name to (
                average[index] * 100
            ).roundToInt()

        }.toMutableList()

        val difference = 100 - result.sumOf {
            it.second
        }

        val largest = result.indices.maxBy {
            result[it].second
        }

        result[largest] = result[largest].first to
            (result[largest].second + difference)

        return result.toMap()
    }
}
