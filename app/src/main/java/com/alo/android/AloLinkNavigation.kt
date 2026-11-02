package com.alo.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.UriHandler

internal fun openAloOrExternalLink(
    context: Context,
    uriHandler: UriHandler,
    rawUrl: String
) {
    if (AloDeepLinkTarget.parse(rawUrl) == null) {
        runCatching { uriHandler.openUri(rawUrl) }
        return
    }

    val intent = Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(rawUrl)
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { runCatching { uriHandler.openUri(rawUrl) } }
}
