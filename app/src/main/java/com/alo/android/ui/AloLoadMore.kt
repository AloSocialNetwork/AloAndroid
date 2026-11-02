package com.alo.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The last row of a list that has more pages: it is only composed when the person has scrolled close to the end, which is the moment
 * to ask for the next page. It asks again when a page arrived and this row is still on screen, and goes away once the list has no more
 * ([hasMore] false). [loaded] is how many rows the list has, so a page that adds some is seen as progress.
 */
internal fun LazyListScope.aloLoadMoreItem(
    key: String,
    hasMore: Boolean,
    isLoading: Boolean,
    loaded: Int,
    onNeedMore: () -> Unit
) {
    if (!hasMore || loaded <= 0) return
    item(key = key) {
        LaunchedEffect(loaded, isLoading) {
            if (!isLoading) onNeedMore()
        }
        Box(Modifier.fillMaxWidth().height(56.dp).padding(8.dp), contentAlignment = Alignment.Center) {
            if (isLoading) CircularProgressIndicator(Modifier.size(22.dp), color = AloNativeAppMuted, strokeWidth = 2.dp)
        }
    }
}
