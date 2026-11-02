package com.alo.android

import androidx.compose.ui.unit.dp

/**
 * Where a chat's overlays and its first message start, measured from the top of the chat's content (below the status bar).
 * The header's floating bar is an 8dp inset plus 42dp circles, so it ends 49dp down; before 23.10 the pinned bar stood
 * 27dp below it and the list began 69dp below it, which pushed everything down.
 */
internal object AloChatOverlayMetrics {
    val HeaderBottom = 49.dp

    /** The pinned bar lines up with the header's circles (12dp from the screen edge), as Telegram's capsules share one 7dp inset. */
    val SideInset = 12.dp

    /**
     * The pinned bar and the forum selector sit this far under the header. Telegram 13.0.3 leaves 7dp between its 46dp header capsules and the
     * 48dp pinned capsule (`topPanelLayout` padding 7dp, `translationY = actionBar bottom - 5dp`); our 42dp capsules and 46dp bar are smaller,
     * and 6dp read as glued together, so the gap is 10dp.
     */
    val OverlayGap = 10.dp

    /** The pinned bar is 46dp tall; the list and the forum selector start this far under its top. */
    val PinnedBlock = 52.dp

    /** The voice player bar is 48dp tall; while a voice message plays, the list and the floating date start this far under its top. */
    val VoiceBlock = 54.dp
    val OverlayTop = HeaderBottom + OverlayGap

    /** The first message (or its date) starts this far under the header, or under the overlays below it. */
    val ListGap = 13.dp
    val ListTop = HeaderBottom + ListGap

    /** The jump-to-latest button sits this far above the composer (Telegram's page-down button: a small gap, not a gutter; the composer's own 7dp padding adds to it). */
    val JumpButtonGap = 2.dp

    /** With the keyboard up the header is the same, but the list gives up 14dp (as it did before). */
    val ListTopWithKeyboard = ListTop - 14.dp
}
