package com.alo.android

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/** Telegram's bottom sheet shape (`sheet_shadow_round`): the top corners are about 24dp, not Material's 28. */
internal val AloSheetShape: Shape = RoundedCornerShape(topStart = AloTelegramMetrics.Sheets.SheetRadius, topEnd = AloTelegramMetrics.Sheets.SheetRadius)

/** [ModalBottomSheet] with Telegram's corner radius unless a sheet asks for another one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AloModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = AloSheetShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = androidx.compose.material3.contentColorFor(containerColor),
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        dragHandle = dragHandle,
        content = content
    )
}
