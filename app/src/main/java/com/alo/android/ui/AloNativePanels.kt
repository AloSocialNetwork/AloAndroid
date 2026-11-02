package com.alo.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun EmptyPanelBody(
    title: String,
    message: String
) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = AloNativeAppText
        )
        Text(
            text = message,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = AloNativeAppMuted
        )
    }
}

@Composable
internal fun EmptyNativeState(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        AloPanel(
            modifier = Modifier.fillMaxWidth(),
            paddingValues = PaddingValues(horizontal = 16.dp, vertical = 18.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = AloNativeAppText
            )
            Text(
                text = message,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = AloNativeAppMuted
            )
            Button(
                onClick = onAction,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .height(40.dp),
                shape = AloSquircle(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AloNativeAppAccent,
                    contentColor = AloNativeAppButtonText
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun AloPanel(
    modifier: Modifier = Modifier,
    paddingValues: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val isLightSurface = MaterialTheme.colorScheme.background.luminance() > 0.5f
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AloSquircle(22.dp),
        color = AloNativeAppSurface.copy(alpha = if (isLightSurface) 0.96f else 0.84f),
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(paddingValues),
            content = content
        )
    }
}
