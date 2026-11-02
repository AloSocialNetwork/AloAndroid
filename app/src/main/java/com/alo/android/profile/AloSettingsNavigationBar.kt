package com.alo.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * The top of a settings-style page: the standard glass back circle (the same 42dp circle, 12dp from the edge and centred in the same 56dp row as the
 * chat's, the profile's and the search lists', so it stays put when pages change), a centred 17sp title and room for actions at the 16dp inset.
 */
@Composable
internal fun AloSettingsNavigationBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {}
) {
    Box(modifier = modifier.fillMaxWidth().height(AloScreenChrome.HeaderHeight)) {
        AloScreenBackButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
            tag = "alo.settings.back"
        )
        Text(
            title,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = AloScreenChrome.TitleSideReserve)
                .testTag("alo.settings.title")
                .semantics { heading() },
            color = AloNativeAppText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            Modifier.align(Alignment.CenterEnd).padding(end = AloScreenChrome.SideInset),
            contentAlignment = Alignment.CenterEnd
        ) { actions() }
    }
}
