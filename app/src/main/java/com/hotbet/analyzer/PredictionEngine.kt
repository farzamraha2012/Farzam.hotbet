
package com.example.hotbetanalyzer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Offline adaptive one-step forecasting engine.
 *
 * Five statistical experts are combined. The fifth expert compares the actual
 * multiplier values in recent sequences (not only their five broad buckets),
 * so changes inside a bucket can still affect the learned distribution.
 *
 * All expert weights are recalculated with walk-forward log-loss using only
 * observations that occurred before each historical target.
 */
object PredictionEngine {

    data class Forecast(
        val bucketIndex: Int,
        val range: String,
        val probabilityPercent: Double,
        val probabilities: List<Double>,
        val modelWeights: List<Double>,
        val sampleCount: Int
    )

    private const val BUCKET_COUNT = 5
    private const val MODEL_COUNT = 5
    private const val MIN_HISTORY = 10
    private const val MAX_BACKTEST_ORIGINS = 100
    private const val EPSILON = 1e-9

    val rangeLabels = listOf(
        "0 تا کمتر از 2",
        "2 تا کمتر از 4",
        "4 تا کمتر از 6",
        "6 تا کمتر از 10",
        "10 به بالا"
    )

    val modelNames = listOf(
        "فراوانی کلی",
        "وزن‌دهی زمانی",
        "روند کوتاه‌مدت",
        "انتقال حالت",
        "تطبیق توالی عددی"
    )

    fun bucketIndex(value: Double): Int = when {
        value < 2.0 -> 0
        value < 4.0 -> 1
        value < 6.0 -> 2
        value < 10.0 -> 3
        else -> 4
    }

    fun forecast(history: List<Double>): Forecast? {
        val data = history.filter {
            it.isFinite() && it >= 0.0 && it <= 10000.0
        }

        if (data.size < MIN_HISTORY) return null

        val weights = adaptiveWeights(data)
        val experts = modelPredictions(data)
        val combined = DoubleArray(BUCKET_COUNT)

        for (model in 0 until MODEL_COUNT) {
            for (bucket in 0 until BUCKET_COUNT) {
                combined[bucket] += experts[model][bucket] * weights[model]
            }
        }

        val percentages = toPercentages(normalize(combined))
        val best = percentages.indices.maxByOrNull { percentages[it] } ?: 0

        return Forecast(
            bucketIndex = best,
            range = rangeLabels[best],
            probabilityPercent = percentages[best],
            probabilities = percentages,
            modelWeights = toPercentages(weights),
            sampleCount = data.size
        )
    }

    private fun normalize(values: DoubleArray): DoubleArray {
        val safe = DoubleArray(BUCKET_COUNT) {
            max(EPSILON, values[it])
        }
        val total = safe.sum()
        return DoubleArray(BUCKET_COUNT) { safe[it] / total }
    }

    private fun normalizeModelWeights(values: DoubleArray): DoubleArray {
        val safe = DoubleArray(MODEL_COUNT) {
            max(EPSILON, values[it])
        }
        val total = safe.sum()
        return DoubleArray(MODEL_COUNT) { safe[it] / total }
    }

    private fun frequencyModel(data: List<Double>): DoubleArray {
        val counts = DoubleArray(BUCKET_COUNT) { 0.65 }
        data.forEach { counts[bucketIndex(it)] += 1.0 }
        return normalize(counts)
    }

    private fun recencyModel(data: List<Double>): DoubleArray {
        val counts = DoubleArray(BUCKET_COUNT)
        val halfLife = max(7.0, data.size * 0.14)

        data.forEachIndexed { index, value ->
            val age = (data.lastIndex - index).toDouble()
            counts[bucketIndex(value)] += exp(-age / halfLife)
        }

        val prior = frequencyModel(data)
        for (bucket in 0 until BUCKET_COUNT) {
            counts[bucket] += prior[bucket] * 4.0
        }
        return normalize(counts)
    }

    private fun shortTermModel(data: List<Double>): DoubleArray {
        val window = data.takeLast(min(30, data.size))
        val counts = DoubleArray(BUCKET_COUNT)

        window.forEachIndexed { index, value ->
            val recencyWeight = 0.75 + 0.75 * (index + 1.0) / window.size
            counts[bucketIndex(value)] += recencyWeight
        }

        val prior = frequencyModel(data)
        for (bucket in 0 until BUCKET_COUNT) {
            counts[bucket] += prior[bucket] * 3.5
        }
        return normalize(counts)
    }

    private fun transitionModel(data: List<Double>): DoubleArray {
        val prior = recencyModel(data)
        val counts = DoubleArray(BUCKET_COUNT)
        val latest = bucketIndex(data.last())

        for (index in 0 until data.lastIndex) {
            if (bucketIndex(data[index]) == latest) {
                counts[bucketIndex(data[index + 1])] += 1.0
            }
        }

        for (bucket in 0 until BUCKET_COUNT) {
            counts[bucket] += prior[bucket] * 5.0
        }
        return normalize(counts)
    }

    private fun numericSequenceModel(data: List<Double>): DoubleArray {
        val prior = recencyModel(data)
        val order = if (data.size >= 30) 3 else 2
        val currentContext = data.takeLast(order)
        val currentBuckets = currentContext.map(::bucketIndex)
        val counts = DoubleArray(BUCKET_COUNT)

        val recencyHalfLife = max(10.0, data.size * 0.25)
        val similarityBandwidth = 0.30

        for (targetIndex in order until data.size) {
            val start = targetIndex - order
            var bucketContextMatches = true
            var logDistance = 0.0

            for (offset in 0 until order) {
                val historicalValue = data[start + offset]

                if (bucketIndex(historicalValue) != currentBuckets[offset]) {
                    bucketContextMatches = false
                    break
                }

                logDistance += abs(
                    ln(1.0 + historicalValue) -
                    ln(1.0 + currentContext[offset])
                )
            }

            if (!bucketContextMatches) continue

            val averageDistance = logDistance / order
            val similarityWeight = exp(-averageDistance / similarityBandwidth)
            val age = (data.lastIndex - targetIndex).toDouble()
            val recencyWeight = exp(-age / recencyHalfLife)

            counts[bucketIndex(data[targetIndex])] +=
                similarityWeight * recencyWeight
        }

        val evidence = counts.sum()

        val priorStrength = when {
            evidence >= 5.0 -> 3.0
            evidence >= 2.0 -> 5.0
            else -> 8.0
        }

        for (bucket in 0 until BUCKET_COUNT) {
            counts[bucket] += prior[bucket] * priorStrength
        }

        return normalize(counts)
    }

    private fun modelPredictions(data: List<Double>): Array<DoubleArray> {
        return arrayOf(
            frequencyModel(data),
            recencyModel(data),
            shortTermModel(data),
            transitionModel(data),
            numericSequenceModel(data)
        )
    }

    private fun adaptiveWeights(data: List<Double>): DoubleArray {
        val losses = DoubleArray(MODEL_COUNT)
        val tests = IntArray(MODEL_COUNT)

        val firstOrigin = max(
            MIN_HISTORY,
            data.size - MAX_BACKTEST_ORIGINS
        )

        for (origin in firstOrigin until data.size) {
            val training = data.subList(0, origin)
            val actual = bucketIndex(data[origin])
            val predictions = modelPredictions(training)

            for (model in 0 until MODEL_COUNT) {
                losses[model] +=
                    -ln(max(EPSILON, predictions[model][actual]))
                tests[model]++
            }
        }

        if (tests.all { it == 0 }) {
            return DoubleArray(MODEL_COUNT) { 1.0 / MODEL_COUNT }
        }

        val scores = DoubleArray(MODEL_COUNT)

        for (model in 0 until MODEL_COUNT) {
            val averageLoss = if (tests[model] > 0) {
                losses[model] / tests[model]
            } else {
                3.0
            }

            scores[model] = exp(-averageLoss)
        }

        val learned = normalizeModelWeights(scores)

        return normalizeModelWeights(
            DoubleArray(MODEL_COUNT) {
                learned[it] * 0.90 + 0.02
            }
        )
    }

    private fun toPercentages(probabilities: DoubleArray): List<Double> {
        val units = probabilities
            .map { (it * 1000.0).toInt() }
            .toMutableList()

        val remainders = probabilities.mapIndexed { index, value ->
            index to (value * 1000.0 - units[index])
        }

        var remaining = 1000 - units.sum()

        for ((index, _) in remainders.sortedByDescending { it.second }) {
            if (remaining <= 0) break
            units[index]++
            remaining--
        }

        return units.map { it / 10.0 }
    }
}
