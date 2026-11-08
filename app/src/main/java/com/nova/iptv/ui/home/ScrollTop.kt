package com.nova.iptv.ui.home

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState

/**
 * Smooth return to the top even from far down: jump to a few items away first
 * (so we don't animate through hundreds of rows), then glide the rest.
 */
suspend fun LazyListState.smoothScrollToTop() {
    if (firstVisibleItemIndex > 6) scrollToItem(6)
    animateScrollToItem(0)
}

suspend fun LazyGridState.smoothScrollToTop() {
    if (firstVisibleItemIndex > 18) scrollToItem(18)
    animateScrollToItem(0)
}
