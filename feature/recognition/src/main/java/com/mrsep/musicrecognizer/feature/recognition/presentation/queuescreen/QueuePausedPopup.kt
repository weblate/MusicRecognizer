package com.mrsep.musicrecognizer.feature.recognition.presentation.queuescreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrsep.musicrecognizer.core.strings.R as StringsR

@Composable
internal fun QueuePausedPopup(
    reason: QueuePauseReason,
    onAllowLowBattery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (reason) {
        QueuePauseReason.None -> Unit

        QueuePauseReason.Offline -> QueuePausedCard(
            text = stringResource(StringsR.string.recognition_queue_offline_paused),
            modifier = modifier,
        )

        QueuePauseReason.LowBattery -> QueuePausedCard(
            text = stringResource(StringsR.string.recognition_queue_low_battery_paused),
            action = stringResource(StringsR.string.recognition_queue_low_battery_allow),
            onAction = onAllowLowBattery,
            modifier = modifier,
        )
    }
}

@Composable
private fun QueuePausedCard(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val showAction = action != null && onAction != null
    Card(
        shape = CircleShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = modifier
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 600.dp)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(
                    start = 24.dp,
                    end = if (showAction) 12.dp else 24.dp,
                    top = if (showAction) 8.dp else 16.dp,
                    bottom = if (showAction) 8.dp else 16.dp,
                )
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (action != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.widthIn(max = 120.dp)
                ) {
                    Text(
                        text = action,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
