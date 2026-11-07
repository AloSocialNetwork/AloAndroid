package com.alo.android

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import org.json.JSONObject

// Releases without Alo Премиум (owner, 2026-11-04): same names as the full file, nothing shown, no pages, no payments.

internal data class AloPremiumLook(val emojiStatus: String, val nameColor: String, val avatarFrame: String)
internal data class AloPremiumExtras(val coverGifUrl: String, val musicUrl: String, val musicTitle: String)

internal object AloPremiumDirectory {
    val looks = mutableStateMapOf<Int, AloPremiumLook>()
    val extras = mutableStateMapOf<Int, AloPremiumExtras>()
    val enabled: Boolean get() = false
    fun look(userId: Int): AloPremiumLook? = null
    fun absorb(json: JSONObject) = Unit
}

@Composable
internal fun AloPremiumMark(userId: Int, size: Dp = 15.dp) = Unit

@Composable
internal fun AloPremiumName(
    text: String,
    userId: Int,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    color: Color = AloNativeAppText,
    maxLines: Int = 1,
    lineHeight: TextUnit = TextUnit.Unspecified
) {
    Text(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = color, fontSize = fontSize, fontWeight = fontWeight, lineHeight = lineHeight)
    )
}

internal fun Modifier.aloPremiumFrame(userId: Int, size: Dp, frame: String? = null): Modifier = this

@Composable
internal fun AloAnimatedGif(url: String, modifier: Modifier = Modifier) = Unit

@Composable
internal fun AloProfileMusicButton(url: String, title: String) = Unit

@Composable
internal fun AloPremiumSettingsPage(api: AloApiClient, onOpenLook: () -> Unit, giftRecipient: Pair<Int, String>? = null) = Unit

@Composable
internal fun AloPremiumLookPage(api: AloApiClient, me: AloCurrentUser, onOpenPremium: () -> Unit) = Unit

internal fun aloPremiumUrl(path: String): String = path

internal fun aloPremiumPeerId(conversation: AloConversationSummary): Int = 0
