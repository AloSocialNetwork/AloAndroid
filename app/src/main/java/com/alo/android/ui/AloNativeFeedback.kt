package com.alo.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun InlineErrorPanel(
    message: String,
    onRetry: () -> Unit
) {
    AloPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFA9B7)
            )
            Spacer(modifier = Modifier.width(12.dp))
            GhostTextButton(
                text = "Обновить",
                onClick = onRetry
            )
        }
    }
}

@Composable
internal fun InlineErrorRow(
    message: String,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFFFA9B7)
        )
        Spacer(modifier = Modifier.width(12.dp))
        GhostTextButton(
            text = "Обновить",
            onClick = onRetry
        )
    }
}

@Composable
internal fun GhostTextButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        shape = AloSquircle(16.dp),
        color = AloNativeAppSurfaceRaised,
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.08f))
    ) {
        Box(
            modifier = Modifier
                .clip(AloSquircle(16.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) AloNativeAppText else AloNativeAppMuted
            )
        }
    }
}
