
package com.example.hotbetanalyzer

import kotlin.math.sqrt
import kotlin.math.roundToInt

object PredictionEngine {

    data class Forecast(
        val round: Int,
        val range: String,
        val probabilityPercent: Int,
        val probabilities: List<Int>
    )

    private val ranges = listOf(
        "0 تا 2",
        "2 تا 4",
        "4 تا 6",
        "6 تا 10",
        "10 به بالا"
    )

    private const val BUCKET_COUNT = 5
    private const val MAX_ROUNDS = 5

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
            return DoubleArray(BUCKET_COUNT) { 1.0 / BUCKET_COUNT }
        }

        return DoubleArray(BUCKET_COUNT) {
            values[it] / total
        }
    }

    private fun toPercent(values: DoubleArray): List<Int> {
        val result = values.map { (it * 100).roundToInt() }.toMutableList()

        // Keep the displayed probabilities equal to exactly 100%.
        val difference = 100 - result.sum()
        val largest = result.indices.maxBy { result[it] }

        result[largest] += difference

        return result
    }

    fun forecast(history: List<Double>): List<Forecast> {

        val data = history.filter {
            it.isFinite() && it >= 1.0 && it <= 10000.0
        }

        if (data.size < 10) return emptyList()

        // 1. Calculate weighted historical frequencies.
        val counts = DoubleArray(BUCKET_COUNT) { 1.5 }

        data.forEachIndexed { index, value ->
            val recency = (index + 1).toDouble() / data.size
            val weight = 0.5 + 1.0 * recency

            counts[bucket(value)] += weight
        }

        val baseline = normalize(counts)

        // 2. Calculate recent frequencies separately.
        val recentData = data.takeLast(minOf(20, data.size))
        val recentCounts = DoubleArray(BUCKET_COUNT) { 1.0 }

        recentData.forEachIndexed { index, value ->
            val weight = 0.5 + (index + 1).toDouble() /
                recentData.size

            recentCounts[bucket(value)] += weight
        }

        val recent = normalize(recentCounts)

        // 3. Build a smoothed transition matrix.
        val transitions = Array(BUCKET_COUNT) {
            DoubleArray(BUCKET_COUNT) { 1.5 }
        }

        for (i in 0 until data.size - 1) {
            val from = bucket(data[i])
            val to = bucket(data[i + 1])

            val recency = (i + 1).toDouble() / data.size
            val weight = 0.5 + recency

            transitions[from][to] += weight
        }

        // Normalize every transition row.
        val transitionProbabilities = Array(BUCKET_COUNT) { row ->
            normalize(transitions[row])
        }

        // 4. Calculate five separate forecast horizons.
        val results = mutableListOf<Forecast>()

        for (round in 1..MAX_ROUNDS) {

            var current = DoubleArray(BUCKET_COUNT) { 0.0 }
            current[bucket(data.last())] = 1.0

            // Apply the transition model for this specific horizon.
            repeat(round) {
                val next = DoubleArray(BUCKET_COUNT)

                for (from in 0 until BUCKET_COUNT) {
                    for (to in 0 until BUCKET_COUNT) {
                        next[to] += current[from] *
                            transitionProbabilities[from][to]
                    }
                }

                current = normalize(next)
            }

            // Longer horizons rely less on the immediate transition.
            val transitionWeight = 0.28 / sqrt(round.toDouble())
            val recentWeight = 0.12 / round.toDouble()
            val baselineWeight = 1.0 -
                transitionWeight - recentWeight

            val combined = DoubleArray(BUCKET_COUNT) { index ->
                baseline[index] * baselineWeight +
                    recent[index] * recentWeight +
                    current[index] * transitionWeight
            }

            val probabilities = normalize(combined)
            val best = probabilities.indices.maxBy {
                probabilities[it]
            }

            val percentages = toPercent(probabilities)

            results.add(
                Forecast(
                    round = round,
                    range = ranges[best],
                    probabilityPercent = percentages[best],
                    probabilities = percentages
                )
            )
        }

        return results
    }
}
