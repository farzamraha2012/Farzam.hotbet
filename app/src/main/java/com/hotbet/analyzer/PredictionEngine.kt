
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
        val transitions = Array(5) {
            DoubleArray(5) { 0.5 }
        }

        // وزن بیشتر برای داده های جدیدتر
        data.forEachIndexed { index, value ->
            val weight = 1.0 + index.toDouble() /
                data.size.toDouble()

            counts[bucket(value)] += weight
        }

        // بررسی انتقال بین بازه های متوالی
        for (i in 0 until data.size - 1) {
            val from = bucket(data[i])
            val to = bucket(data[i + 1])
            transitions[from][to] += 1.0
        }

        val total = counts.sum()
        val baseline = DoubleArray(5) {
            counts[it] / total
        }

        var current = DoubleArray(5) { 0.0 }
        current[bucket(data.last())] = 1.0

        val results = mutableListOf<Forecast>()

        // تخمین پنج دور آینده
        for (round in 1..5) {

            val next = DoubleArray(5)

            for (from in 0..4) {
                val rowTotal = transitions[from].sum()

                for (to in 0..4) {
                    next[to] += current[from] *
                        transitions[from][to] / rowTotal
                }
            }

            // ترکیب فراوانی تاریخی و الگوی انتقال
            val combined = DoubleArray(5) {
                0.80 * baseline[it] + 0.20 * next[it]
            }

            val sum = combined.sum()

            for (i in 0..4) {
                combined[i] /= sum
            }

            val best = combined.indices.maxByOrNull {
                combined[it]
            } ?: 0

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
