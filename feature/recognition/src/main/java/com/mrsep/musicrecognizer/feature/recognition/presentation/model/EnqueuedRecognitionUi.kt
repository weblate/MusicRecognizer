package com.mrsep.musicrecognizer.feature.recognition.presentation.model

import androidx.compose.runtime.Immutable
import com.mrsep.musicrecognizer.core.common.util.AppDateTimeFormatter
import com.mrsep.musicrecognizer.core.domain.recognition.model.EnqueuedRecognition
import com.mrsep.musicrecognizer.core.domain.recognition.model.RecognitionWorkStatus
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Immutable
internal data class EnqueuedRecognitionUi(
    val id: Int,
    val title: String,
    val creationDateShort: String,
    val creationDateLong: String,
    val recordingFile: File,
    val status: RecognitionWorkStatus,
    val result: RemoteRecognitionResultUi?,
    val resultDateLong: String?
)

internal fun EnqueuedRecognition.toUi(
    dateTimeFormatter: AppDateTimeFormatter
): EnqueuedRecognitionUi {
    val creationZonedTime = creationDate.atZone(ZoneId.systemDefault())
    return EnqueuedRecognitionUi(
        id = id,
        title = title,
        creationDateShort = dateTimeFormatter.formatRelativeToToday(creationZonedTime),
        creationDateLong = creationZonedTime.format(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        ),
        recordingFile = sampleFile,
        status = workStatus,
        result = result?.toUi(),
        resultDateLong = resultDate?.atZone(ZoneId.systemDefault())?.format(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        ),
    )
}
