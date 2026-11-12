
package com.example.hotbetanalyzer

import kotlin.math.roundToInt

object PredictionEngine {

    data class Forecast(
        val round: Int,
        val range: String,
        val probabilityPercent: Int
    )

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

    fun forecast(history: List<Double>): List<Forecast> {

        val data = history.filter {
            it.isFinite() && it >= 1.0 && it <= 10000.0
        }

        if (data.size < 10) return emptyList()

        val counts = DoubleArray(5) { 1.0 }

        data.forEachIndexed { index, value ->
            val weight = 0.75 + 0.75 *
                (index + 1).toDouble() / data.size

            counts[bucket(value)] += weight
        }

        val transitions = Array(5) {
            DoubleArray(5) { 1.0 }
        }

        for (i in 0 until data.size - 1) {
            val from = bucket(data[i])
            val to = bucket(data[i + 1])

            val weight = 0.75 + 0.75 *
                (i + 1).toDouble() / data.size

            transitions[from][to] += weight
        }

        val total = counts.sum()

        val baseline = DoubleArray(5) {
            counts[it] / total
        }

        var current = DoubleArray(5) { 0.0 }
        current[bucket(data.last())] = 1.0

        val results = mutableListOf<Forecast>()

        for (round in 1..5) {

            val next = DoubleArray(5)

            for (from in 0..4) {
                val rowTotal = transitions[from].sum()

                for (to in 0..4) {
                    next[to] += current[from] *
                        transitions[from][to] / rowTotal
                }
            }

            val combined = DoubleArray(5) {
                0.65 * baseline[it] +
                0.35 * next[it]
            }

            val sum = combined.sum()

            for (i in 0..4) {
                combined[i] /= sum
            }

            val best = combined.indices.maxBy {
                combined[it]
            }

            results.add(
                Forecast(
                    round = round,
                    range = ranges[best],
                    probabilityPercent =
                        (combined[best] * 100).roundToInt()
                )
            )

            current = combined
        }

        return results
    }
}
