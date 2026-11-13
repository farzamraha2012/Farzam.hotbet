
package com.example.hotbetanalyzer

import kotlin.math.exp
import kotlin.math.roundToInt

object PredictionEngine {

    data class Forecast(
        val round: Int,
        val range: String,
        val probabilityPercent: Int
    )

    private const val BUCKET_COUNT = 5
    private const val MIN_HISTORY = 10
    private const val RECENT_WINDOW = 30

    private val ranges = listOf(
        "0 تا 2",
        "2 تا 4",
        "4 تا 6",
        "6 تا 10",
        "10 به بالا"
    )

    private fun bucket(value: Double): Int = when {
        value < 2.0 -> 0
        value < 4.0 -> 1
        value < 6.0 -> 2
        value < 10.0 -> 3
        else -> 4
    }

    private fun normalize(values: DoubleArray): DoubleArray {
        val total = values.sum()

        if (total <= 0.0) {
            return DoubleArray(BUCKET_COUNT) {
                1.0 / BUCKET_COUNT
            }
        }

        return DoubleArray(BUCKET_COUNT) {
            values[it] / total
        }
    }

    // Model 1: Overall historical frequency
    private fun frequencyModel(data: List<Double>): DoubleArray {
        val counts = DoubleArray(BUCKET_COUNT) { 1.0 }

        data.forEach {
            counts[bucket(it)] += 1.0
        }

        return normalize(counts)
    }

    // Model 2: Recent history frequency
    private fun recentModel(data: List<Double>): DoubleArray {
        val recent = data.takeLast(RECENT_WINDOW)
        val counts = DoubleArray(BUCKET_COUNT) { 1.0 }

        recent.forEach {
            counts[bucket(it)] += 1.0
        }

        return normalize(counts)
    }

    // Model 3: Exponentially weighted frequency
    private fun weightedModel(data: List<Double>): DoubleArray {
        val counts = DoubleArray(BUCKET_COUNT) { 1.0 }

        data.forEachIndexed { index, value ->
            val age = (data.size - 1 - index).toDouble()
            val weight = exp(-age / 25.0)

            counts[bucket(value)] += weight
        }

        return normalize(counts)
    }

    // Build a smoothed transition matrix
    private fun transitionMatrix(
        data: List<Double>
    ): Array<DoubleArray> {

        val matrix = Array(BUCKET_COUNT) {
            DoubleArray(BUCKET_COUNT) { 1.0 }
        }

        for (i in 0 until data.size - 1) {
            val from = bucket(data[i])
            val to = bucket(data[i + 1])

            matrix[from][to] += 1.0
        }

        return Array(BUCKET_COUNT) { row ->
            normalize(matrix[row])
        }
    }

    // Model 4: Historical transition model
    private fun transitionModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val matrix = transitionMatrix(data)
        var current = DoubleArray(BUCKET_COUNT)

        current[bucket(data.last())] = 1.0

        repeat(horizon) {
            val next = DoubleArray(BUCKET_COUNT)

            for (from in 0 until BUCKET_COUNT) {
                for (to in 0 until BUCKET_COUNT) {
                    next[to] +=
                        current[from] * matrix[from][to]
                }
            }

            current = normalize(next)
        }

        return current
    }

    // Model 5: Recent transition model
    private fun recentTransitionModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val recent = data.takeLast(RECENT_WINDOW)
        val matrix = transitionMatrix(recent)

        var current = DoubleArray(BUCKET_COUNT)
        current[bucket(data.last())] = 1.0

        repeat(horizon) {
            val next = DoubleArray(BUCKET_COUNT)

            for (from in 0 until BUCKET_COUNT) {
                for (to in 0 until BUCKET_COUNT) {
                    next[to] +=
                        current[from] * matrix[from][to]
                }
            }

            current = normalize(next)
        }

        return current
    }

    private fun combinedModel(
        data: List<Double>,
        horizon: Int
    ): DoubleArray {

        val model1 = frequencyModel(data)
        val model2 = recentModel(data)
        val model3 = weightedModel(data)
        val model4 = transitionModel(data, horizon)
        val model5 = recentTransitionModel(data, horizon)

        val result = DoubleArray(BUCKET_COUNT)

        for (i in 0 until BUCKET_COUNT) {
            result[i] =
                model1[i] * 0.15 +
                model2[i] * 0.20 +
                model3[i] * 0.20 +
                model4[i] * 0.25 +
                model5[i] * 0.20
        }

        return normalize(result)
    }

    fun forecast(history: List<Double>): List<Forecast> {

        val data = history.filter {
            it.isFinite() && it >= 1.0 && it <= 10000.0
        }

        if (data.size < MIN_HISTORY) {
            return emptyList()
        }

        val results = mutableListOf<Forecast>()

        for (round in 1..5) {

            val probabilities = combinedModel(data, round)

            val bestIndex = probabilities.indices.maxByOrNull {
                probabilities[it]
            } ?: 0

            val confidence =
                (probabilities[bestIndex] * 100.0)
                    .roundToInt()
                    .coerceIn(0, 100)

            results.add(
                Forecast(
                    round = round,
                    range = ranges[bestIndex],
                    probabilityPercent = confidence
                )
            )
        }

        return results
    }
}
