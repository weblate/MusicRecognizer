package com.mrsep.musicrecognizer.feature.recognition.domain.model

import kotlin.time.Duration

/**
 * Down below the representation of strategy: 3, 6(S), 8, 12(S), 15, 30.
 *
 *                                   S           S
 *                       |-----|-----|-----|-----|-----|-----|
 * timeline from start   0     3     6     8    12    15    30
 * recording duration          3     6     2    6     3     18     30 -> if sendTotalAtEnd
 *
 *  The audio recordings emits at 3, 6, 8, 12, 15 and 30 seconds after the recording starts.
 *  If splitter(S) is true, the next audio recordings will contain data
 *  from splitter start point (splitter -> next point).
 *
 *  val strategy: List<Step> = listOf(
 *  Step(timestamp = 3.seconds),
 *  Step(timestamp = 6.seconds, splitter = true),
 *  Step(timestamp = 8.seconds),
 *  Step(timestamp = 12.seconds, splitter = true),
 *  Step(timestamp = 15.seconds),
 *  Step(timestamp = 30.seconds))
 */

class AudioRecordingStrategy(
    val steps: List<Step>,
    val sendTotalAtEnd: Boolean,
    val extraTryIndex: Int
) {

    val stepsCount get() = steps.size
    val lastStepIndex get() = steps.lastIndex
    val lastRecordingIndex get() = if (sendTotalAtEnd) lastStepIndex + 1 else lastStepIndex

    /**
     * Properties used to describe the audio recognition step during [AudioRecordingStrategy].
     *
     * @property timestamp specifies duration between the start of the recording and
     * the end of this step (time when the audio recording will be emitted).
     * @property splitter determines whether the given step is a record splitter.
     * If true, the start time of the next recording will be counted from this splitter.
     */

    class Step(
        val timestamp: Duration,
        val splitter: Boolean = false
    )

    class Builder {

        private val steps = mutableListOf<Step>()
        private var sendTotalAtEnd = true
        private var extraTryIndex = -1

        fun addStep(timestamp: Duration) = apply {
            steps.lastOrNull()?.timestamp?.let { lastTimestamp ->
                check(lastTimestamp < timestamp) {
                    "Each strategy step must have timestamp greater than the previous one"
                }
            }
            steps.add(Step(timestamp, false))
        }

        fun addSplitter(startOfExtraTry: Boolean = false) = apply {
            steps.lastOrNull()?.let { lastStep ->
                steps.removeLast()
                steps.add(Step(lastStep.timestamp, true))
                if (startOfExtraTry) extraTryIndex = steps.lastIndex
            }
        }

        fun sendTotalAtEnd(value: Boolean) = apply {
            sendTotalAtEnd = value
        }

        fun build(): AudioRecordingStrategy {
            check(steps.isNotEmpty()) { "AudioRecordingStrategy must have at least 1 step" }
            return AudioRecordingStrategy(
                steps = steps,
                sendTotalAtEnd= sendTotalAtEnd,
                extraTryIndex = extraTryIndex.takeIf { it > 0 } ?: steps.size
            )
        }

    }

    companion object {

        inline fun audioRecognitionStrategy(
            sendTotalAtEnd: Boolean,
            init: Builder.() -> Unit
        ): AudioRecordingStrategy {
            return Builder()
                .apply(init)
                .sendTotalAtEnd(sendTotalAtEnd)
                .build()
        }

        fun Builder.step(timestamp: Duration) {
            addStep(timestamp)
        }
        fun Builder.splitter(startOfExtraTry: Boolean = false) {
            addSplitter(startOfExtraTry)
        }

    }


}