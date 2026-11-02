package com.alo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** All the reactions the chats accept; a tap makes one the favorite (what a double tap on a message sends in every app). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoriteReactionSheet(current: String, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    AloModalBottomSheet(onDismissRequest = onDismiss, containerColor = AloNativeAppSurface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Любимая реакция", color = AloNativeAppText, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text(
                "Её ставит двойное нажатие на сообщение в iPhone, Android и на сайте.",
                color = AloNativeAppMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )
            aloMessageReactionEmojis.chunked(8).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { emoji ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (emoji == current) AloNativeAppAccent.copy(alpha = 0.28f) else Color.Transparent)
                                .clickable { onChoose(emoji); onDismiss() }
                                .semantics { contentDescription = emoji },
                            contentAlignment = Alignment.Center
                        ) { Text(emoji, fontSize = 24.sp) }
                    }
                    repeat(8 - row.size) { Spacer(Modifier.size(40.dp)) }
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}
