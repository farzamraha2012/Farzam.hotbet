
package com.example.hotbetanalyzer

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

object PredictionEngine {

    data class Forecast(
        val round: Int,
        val range: String,
        val probabilityPercent: Int,
        val probabilities: List<String>
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

    private fun normalize(values: DoubleArray): DoubleArray {
        val total = values.sum()

        if (total <= 0.0) {
            return DoubleArray(5) { 0.2 }
        }

        return DoubleArray(5) {
            values[it] / total
        }
    }

    fun forecast(history: List<Double>): List<Forecast> {

        val data = history.filter {
            it.isFinite() && it >= 1.0 && it <= 10000.0
        }

        if (data.size < 10) return emptyList()

        val results = mutableListOf<Forecast>()

        for (horizon in 1..5) {

            val halfLife = 3.0 + horizon * 2.5

            val frequency = DoubleArray(5) { 0.5 }

            data.forEachIndexed { index, value ->

                val age = (data.lastIndex - index).toDouble()

                val weight = exp(
                    -ln(2.0) * age / halfLife
                )

                frequency[bucket(value)] += weight
            }

            val baseline = normalize(frequency)

            val currentBucket = bucket(data.last())

            val conditional = DoubleArray(5) {
                0.5 + baseline[it] * 2.0
            }

            for (i in 0 until data.size - horizon) {

                val from = bucket(data[i])

                if (from != currentBucket) continue

                val to = bucket(data[i + horizon])

                val age = (
                    data.lastIndex - (i + horizon)
                ).toDouble()

                val weight = exp(
                    -ln(2.0) * age / halfLife
                )

                conditional[to] += weight
            }

            val conditionalDistribution = normalize(conditional)

            val combined = DoubleArray(5) { index ->
                0.60 * baseline[index] +
                0.40 * conditionalDistribution[index]
            }

            val probabilities = normalize(combined)

            val topThree = probabilities.indices
                .sortedByDescending { probabilities[it] }
                .take(3)

            val probabilityList = topThree.map { index ->

                val percent = (
                    probabilities[index] * 100
                ).roundToInt()

                "${ranges[index]} (${percent}٪)"
            }

            val bestIndex = topThree.first()

            results.add(
                Forecast(
                    round = horizon,
                    range = ranges[bestIndex],
                    probabilityPercent = (
                        probabilities[bestIndex] * 100
                    ).roundToInt(),
                    probabilities = probabilityList
                )
            )
        }

        return results
    }
}
