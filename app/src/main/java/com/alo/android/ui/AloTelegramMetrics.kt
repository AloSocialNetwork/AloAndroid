package com.alo.android

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sizes read from Telegram Android's source (v12.7.3, `TMessagesProj/src/main/java/org/telegram/ui`),
 * in dp and sp. Each group names the file and class it comes from, so a value can be checked against
 * the original. Where Alo follows the iPhone app instead (back buttons, the settings pages), these are
 * not used; docs/TELEGRAM_ANDROID_METRICS.md lists every value, where it is used and where it is not.
 */
internal object AloTelegramMetrics {

    /** ActionBar/ActionBar.java */
    object ActionBar {
        val Height = 56.dp                 // getCurrentActionBarHeight()
        val BackButtonWidth = 54.dp        // backButtonImageView.measure(dp(54), …)
        val TitleSize = 20.sp              // titleTextView.setTextSize(20); 18 when there is a subtitle
        val TitleWithSubtitleSize = 18.sp
        val SubtitleSize = 14.sp
        val SideInset = 16.dp
    }

    /** Components/ChatAvatarContainer.java: the title block of a chat. */
    object ChatHeader {
        val AvatarSize = 42.dp
        val LeftPadding = 8.dp
        val TextStart = 54.dp              // leftPadding + dp(54)
        val TitleSize = 18.sp
        val SubtitleSize = 14.sp
        val TitleTop = 1.3.dp
        val TitleTopWithoutSubtitle = 11.dp
        val SubtitleTop = 24.dp
    }

    /** Cells/DialogCell.java, three-line layout. */
    object DialogRow {
        val Height = 76.dp
        val AvatarSize = 56.dp
        val AvatarStart = 11.dp
        val TextStart = 78.dp
        val NameSize = 16.sp
        val TimeSize = 12.sp
        val PreviewSize = 15.sp
        val CounterSize = 20.67.dp
        val DividerStart = 72.dp
    }

    /** Cells/ProfileSearchCell.java and Cells/UserCell.java: a person in a list or in search results. */
    object PersonRow {
        val SearchHeight = 60.dp           // setMeasuredDimension(…, dp(60) + separator)
        val SearchAvatarSize = 46.dp       // avatarImage.setRoundRadius(dp(23))
        val CallRowHeight = 56.dp
        val NameSize = 16.sp               // namePaint, bold
        val CallNameSize = 15.sp
        val LeftBaseline = 72.dp           // AndroidUtilities.leftBaseline: where the text starts
        val UserAvatarSize = 46.dp         // UserCell.avatarImageView createFrame(46, 46)
        val UserNameSize = 16.sp
        val UserStatusSize = 15.sp
        val UserNameBoxHeight = 20.dp
        val AdminBadgeSize = 14.sp
        val AdminBadgePadding = 6.dp
    }

    /** Cells/ManageChatUserCell.java (a group's member, request or ban) and UserCell's role pill; 13.0.3. */
    object UserCell {
        val Height = 64.dp
        val AvatarSize = 46.dp
        val AvatarStart = 13.dp            // 7 + avatarPadding 6
        val AvatarTop = 8.dp
        val TextGap = 11.dp                // text at 70dp: 13 + 46 + 11
        val NameSize = 17.sp
        val StatusSize = 14.sp
        val NameTop = 11.5.dp
        val StatusGap = 3.dp               // status at 34.5dp: 11.5 + 20 + 3
        val LineBoxHeight = 20.dp
        val LineHeight = 20.sp
        val DividerStart = 68.dp
        val TagSize = 14.sp                // UserCell.adminTextView
        val TagPadding = 6.dp
        val TagEnd = 23.dp
    }

    /** Cells/UserCell.java with padding 0: a person in a contacts or friends list, or one to pick. */
    object ContactCell {
        val Height = 58.dp
        val AvatarSize = 46.dp
        val AvatarStart = 7.dp
        val AvatarTop = 6.dp
        val TextGap = 11.dp                // text at 64dp: 7 + 46 + 11
        val NameSize = 16.sp
        val StatusSize = 15.sp
        val NameTop = 10.dp
        val StatusGap = 2.dp               // status at 32dp: 10 + 20 + 2
        val DividerStart = 68.dp
    }

    /** Cells/TextSettingsCell, TextCell, TextCheckCell, TextDetailSettingsCell, RadioButtonCell. */
    object SettingsRow {
        val Height = 50.dp                 // TextSettingsCell / TextCell
        val Padding = 21.dp                // TextSettingsCell(context) → padding 21
        val TextSize = 16.sp
        val ValueSize = 16.sp
        val SubtitleSize = 13.sp           // TextCell.subtitleView, TextCheckCell.valueTextView
        val IconSize = 28.dp               // TextSettingsCell imageView
        val IconStart = 21.dp              // TextCell.imageLeft
        val TextAfterIcon = 71.dp          // TextCell.offsetFromImage
        val DetailHeight = 64.dp           // TextDetailSettingsCell
        val DetailTitleTop = 10.dp
        val DetailValueTop = 35.dp
        val SwitchWidth = 37.dp            // checkBox createFrame(37, 20, …, 22, 0, 22, 0)
        val SwitchHeight = 20.dp
        val SwitchEnd = 22.dp
        val ColorIconSize = 29.dp          // TextCheckCell imageView createFrame(29, 29)
        val ColorIconRadius = 9.dp
        val RadioSize = 20.dp
        val DividerStart = 20.dp
    }

    /** Cells/HeaderCell, GraySectionCell, ShadowSectionCell, TextInfoPrivacyCell, DividerCell. */
    object Section {
        val HeaderHeight = 40.dp
        val HeaderPadding = 20.dp
        val HeaderTopMargin = 6.dp
        val HeaderSize = 15.sp             // bold
        val GrayHeight = 32.dp             // GraySectionCell.layerHeight
        val GraySize = 14.sp               // bold
        val ShadowGap = 12.dp              // ShadowSectionCell(context) → size 12
        val InfoPadding = 24.dp            // TextInfoPrivacyCell(context) → padding 24
        val InfoSize = 14.sp
        val InfoTop = 10.dp
        val InfoBottom = 17.dp
        val DividerPadding = 8.dp
    }

    /** ActionBar/ActionBarMenuSubItem.java, ActionBarPopupWindow.java: a pop-up menu. */
    object PopupMenu {
        val ItemHeight = 48.dp
        val ItemPadding = 18.dp
        val ItemTextSize = 16.sp
        val SubtextSize = 13.sp
        val WindowPadding = 8.dp
        val Radius = 12.dp
        val TextAfterIcon = 34.dp
    }

    /** ActionBar/BottomSheet.java and AlertDialog.java. */
    object Sheets {
        val TitleSize = 20.sp              // bold, padding 21 / 6 / 21 / 8
        val TitleStart = 21.dp
        val ItemTextSize = 16.sp
        val ItemSubtitleSize = 14.sp
        val ContainerPadding = 8.dp
        val DialogRadius = 20.dp           // AlertDialog.boundsWithPaddingRoundRect(…, dp(20))
        val DialogPadding = 23.dp
        val DialogButtonSize = 16.sp
        val DialogButtonPadding = 12.dp
        val DialogButtonGap = 8.dp

        // AlertDialog (13.0.3): the window is the screen less 56dp, at most 356dp; title at (24, 19) 20sp with 10 below, text 16sp with 20 below.
        val DialogWindowMargin = 56.dp
        val DialogMaxWidth = 356.dp
        val DialogTextInset = 24.dp
        val DialogTitleTop = 19.dp
        val DialogTitleBottom = 10.dp
        val DialogTextSize = 16.sp
        val DialogTextBottom = 20.dp
        val DialogButtonHeight = 40.dp

        // BottomSheet (13.0.3): items are 48dp, the icon sits in a 56dp box (text from 72dp), without an icon the text starts 16dp in.
        // The top corners are about 24dp (measured on `sheet_shadow_round`).
        val ItemHeight = 48.dp
        val ItemIconBox = 56.dp
        val ItemIconSize = 24.dp
        val ItemTextInset = 16.dp
        val SheetRadius = 24.dp

        // ChatAttachAlert / GlassTabView (13.0.3): the attach sheet rounds by 20, its buttons carry a 24dp icon and a 12sp label.
        val AttachRadius = 20.dp
        val AttachIconSize = 24.dp
        val AttachLabelSize = 12.sp
    }

    /** Components/ChatActivityEnterView.java and Components/chat/ChatInputViewsContainer.java. */
    object Composer {
        val Height = 44.dp                 // DEFAULT_HEIGHT
        val BubbleRadius = 22.dp           // INPUT_BUBBLE_RADIUS
        val KeyboardRadius = 29.dp         // INPUT_KEYBOARD_RADIUS
        val BottomMargin = 9.dp            // INPUT_BUBBLE_BOTTOM
        val ButtonSize = 44.dp             // emoji, attach, record and send buttons
        val EmojiButtonStart = 2.dp
        val TextSize = 18.sp               // messageEditText.setTextSize(18)
        val TextPaddingTop = 11.dp         // messageEditText.setPadding(0, dp(11), …, dp(12))
        val TextPaddingBottom = 12.dp
        val MaxLines = 6
        val LockSize = 36.dp
        val SendButtonWidth = 100.dp
    }

    /** ChatActivity side buttons: Components/chat/layouts/ChatActivitySideControlsButtonsLayout. */
    object SideButtons {
        val Size = 56.dp
        val AttachSize = 50.dp
        val Gap = 10.dp
        val CounterGap = 20.dp
        val ShowDistance = 80.dp
        val ShowDurationMs = 280

        // 13.0.3: the view is 56dp wide, but `ChatActivityBlurredRoundButton` insets its glass by CLICK_ZONE_MARGIN (6dp): the circle is BUTTON_SIZE, 44dp.
        val CircleSize = 44.dp
        val ClickMargin = 6.dp
        // CounterView: radius 11.5 (a 23dp pill), 13sp bold, 5.5dp either side of the text, centred on the circle's top edge.
        val CounterHeight = 23.dp
        val CounterTextSize = 13.sp
        val CounterPadding = 5.5.dp
    }

    /**
     * ProfileActivity.java and Components/ProfileActionsView.java (13.0.3). At rest the avatar is 96dp (`lerp(42, 96, diff)`), the name
     * is 17.5sp bold scaled by 1.12 (about 19.6sp), the status 13.5sp; the actions are tiles of radius 16 with an icon and an 11sp label.
     */
    object Profile {
        val AvatarSize = 96.dp
        val NameSize = 19.6.sp
        val StatusSize = 13.5.sp
        val HeaderHeight = 200.dp          // Alo's own header box: back button, avatar, name and status
        val ActionHeight = 54.dp           // ProfileActionsView: targetHeight 74 less ypadding 12 and top 8
        val ActionRadius = 16.dp
        val ActionIconSize = 24.dp
        val ActionIconTop = 6.dp
        val ActionTextGap = 2.dp
        val ActionTextSize = 11.sp
        val ActionsSideInset = 14.dp       // xpadding
        val ActionsGap = 7.dp              // xpadding / 2
        val BottomButtonRadius = 19.dp

        // Info under the header (`TextDetailCell`): a 16sp value over a 13sp caption; the bio is 16sp.
        val StatValueSize = 16.sp
        val StatLabelSize = 13.sp
        val BioSize = 16.sp
        val MetaSize = 13.sp
        val NameToHandle = 2.dp
        val AvatarToName = 10.dp
        val StatGap = 22.dp
    }

    /** Components/ProfileGalleryView, Cells/ChatMessageCell (see AloNativeMessageBubble). */
    object Message {
        val TextSize = 16.sp
        val TimeSize = 12.sp
        val Radius = 17.dp
        val SmallRadius = 6.dp
        val InsetSide = 11.dp
        val InsetTop = 8.dp
    }
}
