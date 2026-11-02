package com.alo.android

import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow

@Composable
internal fun AloLinkedText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val value = remember(text, color) {
        val markup = AloMessageMarkup.parse(text)
        AnnotatedString.Builder(markup.body).apply {
            markup.entities.filter { it.type == "link" }.forEach { entity ->
                val start = entity.offset
                val end = entity.offset + entity.length
                if (start < 0 || start >= end || end > markup.body.length || entity.url.isBlank()) {
                    return@forEach
                }
                addStyle(
                    SpanStyle(color = color, textDecoration = TextDecoration.Underline),
                    start,
                    end
                )
                addStringAnnotation("ALO_URL", entity.url, start, end)
            }
        }.toAnnotatedString()
    }
    ClickableText(
        text = value,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = overflow,
        onClick = { offset ->
            value.getStringAnnotations("ALO_URL", offset, offset)
                .firstOrNull()
                ?.item
                ?.let { url -> openAloOrExternalLink(context, uriHandler, url) }
        }
    )
}
