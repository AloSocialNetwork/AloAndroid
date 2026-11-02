package com.alo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A story link opened after the story has gone, as iOS's AloStoryExpiredSheet: who posted it, and that it expired. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AloStoryExpiredSheet(author: AloEntitySummary?, onOpenProfile: (AloEntitySummary) -> Unit, onDismiss: () -> Unit) {
    AloModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AloNativeAppBackground,
        dragHandle = { Box(Modifier.padding(top = 8.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) }
    ) {
        AloSheetSystemBars()
        AloNaturalLineHeight {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 18.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val ring = AloNativeAppBorder
                Box(Modifier.padding(top = 8.dp)) {
                    Box(
                        Modifier.size(86.dp).drawBehind {
                            drawCircle(ring, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        if (author != null) {
                            AvatarBadge(name = author.name, avatarUrl = author.avatarUrl, verified = false, size = 76.dp, colorSeed = author.id.toString())
                        } else {
                            Box(Modifier.size(76.dp).clip(CircleShape).background(AloNativeAppSurfaceRaised))
                        }
                    }
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).size(28.dp).clip(CircleShape)
                            .background(AloNativeAppMuted).border(2.dp, AloNativeAppBackground, CircleShape),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.Schedule, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
                }
                Text("История истекла", color = AloNativeAppText, fontSize = AloTelegramMetrics.Sheets.TitleSize, fontWeight = FontWeight.Medium)
                Text(
                    author?.let { "${it.name} публиковал(а) эту историю, но она уже исчезла. Истории живут до 48 часов." }
                        ?: "Эта история уже исчезла. Истории живут до 48 часов.",
                    color = AloNativeAppMuted, fontSize = AloTelegramMetrics.Sheets.DialogTextSize, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (author != null) AloPrimaryPillButton("Открыть профиль") { onOpenProfile(author) }
                    Text(
                        "Закрыть", color = AloNativeAppAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onDismiss).wrapContentHeight()
                    )
                }
            }
        }
    }
}
