package com.phi3chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phi3chat.data.Role
import com.phi3chat.ui.theme.PhiColors

/**
 * A single chat bubble. Assistant and user bubbles mirror each other and cap
 * their width so long answers keep a comfortable line length.
 */
@Composable
fun MessageBubble(
    role: Role,
    text: String,
    isStreaming: Boolean,
    isDark: Boolean,
    tokensPerSecond: Double? = null,
    modifier: Modifier = Modifier,
) {
    val isUser = role == Role.USER
    val bubbleColor = when {
        isUser && isDark -> PhiColors.userBubbleDark
        isUser -> PhiColors.userBubbleLight
        isDark -> PhiColors.assistantBubbleDark
        else -> PhiColors.assistantBubbleLight
    }
    val textColor = if (isUser) PhiColors.onUserBubble else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(
                    topStart = 18.dp,
                    topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp,
                ),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }

            if (isStreaming) {
                StreamingIndicator(modifier = Modifier.padding(start = 8.dp, top = 6.dp))
            }

            if (!isStreaming && tokensPerSecond != null && tokensPerSecond > 0) {
                Text(
                    text = "%.1f tok/s".format(tokensPerSecond),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StreamingIndicator(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Centered placeholder used for the empty chat and for pre-model states. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.invoke()
        }
    }
}

/** Thin progress stripe used while a model is loading. */
@Composable
fun LoadingBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
