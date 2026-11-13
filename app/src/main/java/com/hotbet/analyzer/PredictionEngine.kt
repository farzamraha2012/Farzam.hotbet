package com.example.hotbetanalyzer

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
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

    private val ranges = listOf(
        "0 تا 2",
        "2 تا 4",
        "4 تا 6",
        "6 تا 10",
        "10 به بالا"
    )

    private val modelNames = listOf(
        "توزیع آماری",
        "روند کوتاه‌مدت",
        "روند بلندمدت",
        "توالی و انتقال",
        "ارزیابی تطبیقی"
    )

    private fun bucket(value: Double): Int = when {
        value < 2.0 -> 0
        value < 4.0 -> 1
        value < 6.0 -> 2
        value < 10.0 -> 3
        else -> 4
    }

    private fun normalize(input: DoubleArray): DoubleArray {
        val values = DoubleArray(BUCKETS) {
            max(0.000001, input[it])
        }

        val total = values.sum()

        return DoubleArray(BUCKETS) {
            values[it] / total
        }
    }

    // ENGINE 1: Overall historical frequency
    private fun frequencyModel(
        data: List<Double>
    ): DoubleArray {

        val counts = DoubleArray(BUCKETS) { 1.0 }

        data.forEachIndexed { index, value ->
            val recency = (index + 1.0) / data.size
            counts[bucket(value)] += 0.5 + recency
        }

        return normalize(counts)
    }

    // ENGINE 2: Short-term trend
    private fun shortTermModel(
        data: List<Double>
    ): DoubleArray {

        val window = data.takeLast(minOf(12, data.size))
        val counts = DoubleArray(BUCKETS) { 1.0 }

        window.forEachIndexed { index, value ->
            counts[bucket(value)] += 1.0 + index.toDouble() /
                window.size
        }

        return normalize(counts)
    }

    // ENGINE 3: Long-term trend
    private fun longTermModel(
        data: List<Double>
    ): DoubleArray {

        val window = data.takeLast(minOf(80, data.size))
        val counts = DoubleArray(BUCKETS) { 1.0 }

        window.forEachIndexed { index, value ->
            val weight = 0.75 + 0.5 *
                (index + 1.0) / window.size

            counts[bucket(value)] += weight
        }

        return normalize(counts)
    }

    // ENGINE 4: Sequence and transition analysis
    private fun transitionModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val current = bucket(data.last())

        val transitions = Array(BUCKETS) {
            DoubleArray(BUCKETS) { 1.0 }
        }

        val limit = data.size - horizon

        for (i in 0 until limit) {

            if (bucket(data[i]) != current) continue

            val target = bucket(data[i + horizon])

            transitions[current][target] += 1.0
        }

        return normalize(transitions[current])
    }

    // Predict one step using a model and historical data.
    private fun modelForBacktest(
        data: List<Double>,
        model: Int
    ): DoubleArray {

        return when (model) {
            0 -> frequencyModel(data)
            1 -> shortTermModel(data)
            2 -> longTermModel(data)
            else -> transitionModel(data, 1)
        }
    }

    // ENGINE 5: Walk-forward historical evaluation.
    // Every prediction uses only data available before its target.
    private fun adaptiveWeights(
        data: List<Double>
    ): DoubleArray {

        val losses = DoubleArray(4)
        val tests = IntArray(4)

        val start = max(6, data.size - 80)

        for (targetIndex in start until data.size) {

            val training = data.subList(0, targetIndex)
            val actual = bucket(data[targetIndex])

            for (model in 0..3) {

                val probabilities = modelForBacktest(
                    training,
                    model
                )

                val loss = -ln(
                    max(0.000001, probabilities[actual])
                )

                losses[model] += loss
                tests[model]++
            }
        }

        val weights = DoubleArray(4)

        for (model in 0..3) {

            val averageLoss = if (tests[model] > 0) {
                losses[model] / tests[model]
            } else {
                1.0
            }

            // Prevent unstable weights from a small sample.
            weights[model] = exp(-averageLoss)
        }

        val total = weights.sum()

        return DoubleArray(4) {
            weights[it] / total
        }
    }

    // Combine the four forecasting models.
    private fun combinedForecast(
        data: List<Double>,
        horizon: Int,
        weights: DoubleArray
    ): DoubleArray {

        val models = arrayOf(
            frequencyModel(data),
            shortTermModel(data),
            longTermModel(data),
            transitionModel(data, horizon)
        )

        val combined = DoubleArray(BUCKETS)

        for (model in 0..3) {
            for (category in 0 until BUCKETS) {
                combined[category] +=
                    models[model][category] * weights[model]
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

        // Correct rounding so all five percentages total 100.
        val difference = 100 - percentages.sum()

        val largest = percentages.indices.maxBy {
            percentages[it]
        }

        percentages[largest] += difference

        return percentages
    }

    fun forecast(
        history: List<Double>
    ): List<Forecast> {

        val data = history.filter {
            it.isFinite() && it >= 1.0 && it <= 10000.0
        }

        if (data.size < MIN_HISTORY) {
            return emptyList()
        }

        // Evaluate model performance before combining predictions.
        val weights = adaptiveWeights(data)

        val results = mutableListOf<Forecast>()

        for (horizon in 1..5) {

            val probabilities = combinedForecast(
                data,
                horizon,
                weights
            )

            val percentages = percentageList(probabilities)

            val ranking = probabilities.indices.sortedByDescending {
                probabilities[it]
            }

            val best = ranking.first()

            // Show only the second and third ranges here.
            // The best range is already shown in the main title.
            val alternatives = ranking.drop(1).take(2).map {
                index ->
                "${ranges[index]} (${percentages[index]}٪)"
            }

            results.add(
                Forecast(
                    round = horizon,
                    range = ranges[best],
                    probabilityPercent = percentages[best],
                    probabilities = alternatives
                )
            )
        }

        return results
    }

    // Diagnostic information for future development.
    fun modelWeightReport(
        history: List<Double>
    ): Map<String, Int> {

        val data = history.filter {
            it.isFinite() && it >= 1.0 && it <= 10000.0
        }

        if (data.size < MIN_HISTORY) return emptyMap()

        val weights = adaptiveWeights(data)

        return modelNames.mapIndexed { index, name ->
            val weight = if (index < 4) {
                (weights[index] * 100).roundToInt()
            } else {
                0
            }

            name to weight
        }.toMap()
    }
}
