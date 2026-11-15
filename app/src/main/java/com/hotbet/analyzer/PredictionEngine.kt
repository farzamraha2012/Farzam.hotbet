package com.example.hotbetanalyzer

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

object PredictionEngine {

    const val MIN_HISTORY = 10
    const val BUCKET_COUNT = 5
    const val MODEL_COUNT = 5

    private const val MAX_BACKTEST_ORIGINS = 160
    private const val MAX_TRAINING_HISTORY = 2500

    val rangeNames = listOf(
        "0 تا 2",
        "2 تا 4",
        "4 تا 6",
        "6 تا 10",
        "10 به بالا"
    )

    val modelNames = listOf(
        "فراوانی کلی",
        "وزن‌دهی زمانی",
        "روند کوتاه‌مدت",
        "انتقال بازه‌ها",
        "الگوی توالی"
    )

    data class Forecast(
        val round: Int,
        val range: String,
        val probabilityPercent: Int,
        val bestBucket: Int,
        val probabilities: List<Int>,
        val modelWeights: List<Int>
    )

    fun bucketIndex(value: Double): Int {
        return when {
            value < 2.0 -> 0
            value < 4.0 -> 1
            value < 6.0 -> 2
            value < 10.0 -> 3
            else -> 4
        }
    }

    private fun normalize(values: DoubleArray): DoubleArray {

        val safe = DoubleArray(BUCKET_COUNT) {
            val value = values[it]

            if (value.isFinite() && value > 0.0) {
                value
            } else {
                0.000001
            }
        }

        val total = safe.sum()

        return DoubleArray(BUCKET_COUNT) {
            safe[it] / total
        }
    }

    private fun normalizeWeights(
        values: DoubleArray
    ): DoubleArray {

        val safe = DoubleArray(MODEL_COUNT) {
            val value = values[it]

            if (value.isFinite() && value > 0.0) {
                value
            } else {
                0.000001
            }
        }

        val total = safe.sum()

        return DoubleArray(MODEL_COUNT) {
            safe[it] / total
        }
    }

    // MODEL 1: Overall frequency

    private fun frequencyModel(
        data: List<Double>
    ): DoubleArray {

        val counts = DoubleArray(BUCKET_COUNT) {
            1.0
        }

        data.forEach {
            counts[bucketIndex(it)] += 1.0
        }

        return normalize(counts)
    }

    // MODEL 2: Time-decay frequency

    private fun recencyModel(
        data: List<Double>
    ): DoubleArray {

        val counts = DoubleArray(BUCKET_COUNT) {
            0.8
        }

        val halfLife = max(
            5.0,
            data.size * 0.22
        )

        data.forEachIndexed { index, value ->

            val age = (
                data.lastIndex - index
            ).toDouble()

            val weight = exp(-age / halfLife)

            counts[bucketIndex(value)] += weight
        }

        return normalize(counts)
    }

    // MODEL 3: Short-term trend

    private fun shortTermModel(
        data: List<Double>
    ): DoubleArray {

        val windowSize = min(
            data.size,
            max(8, data.size / 2)
        )

        val window = data.takeLast(windowSize)

        val counts = DoubleArray(BUCKET_COUNT) {
            1.0
        }

        window.forEachIndexed { index, value ->

            val weight =
                0.7 + 0.6 *
                (index + 1.0) / window.size

            counts[bucketIndex(value)] += weight
        }

        return normalize(counts)
    }

    // MODEL 4: Transition analysis

    private fun transitionModel(
        data: List<Double>
    ): DoubleArray {

        val prior = frequencyModel(data)

        val current = bucketIndex(data.last())

        val counts = DoubleArray(BUCKET_COUNT) {
            prior[it] * 4.0
        }

        for (i in 0 until data.lastIndex) {

            if (bucketIndex(data[i]) == current) {

                val target = bucketIndex(
                    data[i + 1]
                )

                counts[target] += 1.0
            }
        }

        return normalize(counts)
    }

    // MODEL 5: Two-step sequence analysis

    private fun sequenceModel(
        data: List<Double>
    ): DoubleArray {

        val prior = frequencyModel(data)

        val counts = DoubleArray(BUCKET_COUNT) {
            prior[it] * 6.0
        }

        if (data.size < 3) {
            return normalize(counts)
        }

        val previous = bucketIndex(
            data[data.lastIndex - 1]
        )

        val current = bucketIndex(data.last())

        for (i in 2 until data.size) {

            val first = bucketIndex(data[i - 2])
            val second = bucketIndex(data[i - 1])

            if (
                first == previous &&
                second == current
            ) {

                val target = bucketIndex(data[i])

                counts[target] += 1.0
            }
        }

        return normalize(counts)
    }

    private fun modelPredictions(
        data: List<Double>
    ): Array<DoubleArray> {

        return arrayOf(
            frequencyModel(data),
            recencyModel(data),
            shortTermModel(data),
            transitionModel(data),
            sequenceModel(data)
        )
    }

    // WALK-FORWARD ADAPTIVE LEARNING
    // Historical outcomes are never used to predict themselves.

    private fun adaptiveWeights(
        data: List<Double>
    ): DoubleArray {

        val equalWeights = DoubleArray(MODEL_COUNT) {
            1.0 / MODEL_COUNT
        }

        if (data.size <= MIN_HISTORY) {
            return equalWeights
        }

        val losses = DoubleArray(MODEL_COUNT)
        val tests = IntArray(MODEL_COUNT)

        val firstOrigin = max(
            MIN_HISTORY,
            data.size - MAX_BACKTEST_ORIGINS
        )

        for (origin in firstOrigin until data.size) {

            val trainingStart = max(
                0,
                origin - MAX_TRAINING_HISTORY
            )

            val training = data.subList(
                trainingStart,
                origin
            )

            if (training.size < MIN_HISTORY) {
                continue
            }

            val actual = bucketIndex(
                data[origin]
            )

            val predictions = modelPredictions(
                training
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

        val testCount = tests.minOrNull() ?: 0

        if (testCount == 0) {
            return equalWeights
        }

        val scores = DoubleArray(MODEL_COUNT)

        for (model in 0 until MODEL_COUNT) {

            val averageLoss =
                losses[model] / tests[model]

            scores[model] = exp(-averageLoss)
        }

        val learned = normalizeWeights(scores)

        // More historical tests = more confidence in learned weights.

        val confidence =
            testCount.toDouble() /
            (testCount + 20.0)

        val blended = DoubleArray(MODEL_COUNT) {

            learned[it] * confidence +
                equalWeights[it] * (1.0 - confidence)
        }

        return normalizeWeights(blended)
    }

    // COMBINED ENSEMBLE

    private fun combinedForecast(
        data: List<Double>,
        weights: DoubleArray
    ): DoubleArray {

        val predictions = modelPredictions(data)

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

    // Convert probabilities to percentages totaling exactly 100.

    private fun toPercentages(
        probabilities: DoubleArray
    ): List<Int> {

        val result = probabilities.map {
            (it * 100.0).toInt()
        }.toMutableList()

        val remainders = probabilities.mapIndexed {
            index, value ->

            index to (
                value * 100.0 - result[index]
            )
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

    // Select the best category using UNROUNDED probabilities.
    // This prevents the first category from winning rounded ties.

    private fun bestIndex(
        probabilities: DoubleArray,
        data: List<Double>
    ): Int {

        val highest = probabilities.maxOrNull() ?: return 0

        val tied = probabilities.indices.filter {
            abs(probabilities[it] - highest) < 0.0000000001
        }

        if (tied.size == 1) {
            return tied.first()
        }

        val counts = IntArray(BUCKET_COUNT)

        data.forEach {
            counts[bucketIndex(it)]++
        }

        val highestCount = tied.maxOf {
            counts[it]
        }

        val mostFrequent = tied.filter {
            counts[it] == highestCount
        }

        // Prefer the middle range if it is equally probable
        // and equally frequent with another range.

        if (2 in mostFrequent) {
            return 2
        }

        return mostFrequent.minByOrNull {
            abs(it - 2)
        } ?: 2
    }

    // ONLY ONE FUTURE ROUND

    fun forecast(
        history: List<Double>
    ): Forecast? {

        val data = history.filter {
            it.isFinite() &&
                it > 0.0 &&
                it <= 10000.0
        }

        if (data.size < MIN_HISTORY) {
            return null
        }

        val weights = adaptiveWeights(data)

        val distribution = combinedForecast(
            data,
            weights
        )

        val percentages = toPercentages(
            distribution
        )

        val modelPercentages = toPercentages(
            weights
        )

        val best = bestIndex(
            distribution,
            data
        )

        return Forecast(
            round = 1,
            range = rangeNames[best],
            probabilityPercent = percentages[best],
            bestBucket = best,
            probabilities = percentages,
            modelWeights = modelPercentages
        )
    }
}
