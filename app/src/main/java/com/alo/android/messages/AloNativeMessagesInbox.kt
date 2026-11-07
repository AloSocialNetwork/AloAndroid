package com.alo.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun MessagesListPanel(
    modifier: Modifier = Modifier,
    conversations: List<AloConversationSummary>,
    activePeerId: Int,
    onSelectConversation: (Int) -> Unit
) {
    var previewConversation by remember { mutableStateOf<AloConversationSummary?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxSize()
            .background(AloNativeAppBackground)
    ) {
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                EmptyPanelBody(
                    title = "Диалогов пока нет",
                    message = "Когда появятся сообщения, они будут здесь."
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AloNativeLayout.pageInset,
                    top = AloNativeLayout.spacingSmall,
                    end = AloNativeLayout.pageInset,
                    bottom = AloBottomBarOverlayPadding + AloNativeLayout.spacingMedium
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(conversations, key = { conversation -> conversation.peerId }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        selected = activePeerId == conversation.peerId,
                        onClick = { onSelectConversation(conversation.peerId) },
                        onLongClick = { previewConversation = conversation }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = previewConversation != null,
            enter = fadeIn(animationSpec = tween(150, easing = FastOutSlowInEasing)) +
                scaleIn(initialScale = 0.985f, animationSpec = tween(170, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(140)) +
                scaleOut(targetScale = 0.985f, animationSpec = tween(140))
        ) {
            previewConversation?.let { conversation ->
                ConversationPreviewOverlay(
                    conversation = conversation,
                    onOpen = {
                        previewConversation = null
                        onSelectConversation(conversation.peerId)
                    },
                    onDismiss = { previewConversation = null }
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: AloConversationSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .background(if (selected) AloNativeAppSurfaceRaised.copy(alpha = 0.42f) else Color.Transparent)
                .heightIn(min = 72.dp)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (conversation.peer.entityType == "saved") {
                SavedMessagesAvatar(size = 52.dp)
            } else {
                AvatarBadge(
                    name = conversation.peer.name,
                    avatarUrl = conversation.peer.avatarUrl,
                    verified = conversation.peer.verified,
                    size = 52.dp,
                    colorSeed = conversation.peer.id.toString(),
                    modifier = Modifier.aloPremiumFrame(if (conversation.roomId == 0 && conversation.peer.entityType !in setOf("channel", "saved")) conversation.peer.id else 0, 52.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Alo Премиум: the name in its colour, then the star or the emoji status (direct chats only).
                        val premiumId = if (conversation.roomId == 0 && conversation.peer.entityType !in setOf("channel", "saved")) conversation.peer.id else 0
                        AloPremiumName(
                            text = conversation.peer.name,
                            userId = premiumId,
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        AloPremiumMark(premiumId, 14.dp)
                        if (conversation.peer.online) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(AloNativeAppSuccess)
                            )
                        }
                    }
                    Text(
                        text = formatMessageTime(conversation.lastAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = AloNativeAppMuted
                    )
                }

                Text(
                    text = conversation.lastBody.ifBlank {
                        when {
                            conversation.peer.entityType == "channel" || conversation.kind == "channel" -> "Канал"
                            conversation.kind == "group" -> "Групповой чат"
                            else -> "Личный чат"
                        }
                    },
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AloNativeAppMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(start = 64.dp),
            thickness = 1.dp,
            color = AloNativeAppSeparator
        )
    }
}

@Composable
internal fun ConversationPreviewOverlay(
    conversation: AloConversationSummary,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .combinedClickable(
                onClick = onDismiss,
                onLongClick = {}
            )
            .padding(horizontal = 18.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .height(420.dp)
                    .clip(RoundedCornerShape(AloNativeLayout.radiusLarge))
            ) {
                ChatWallpaperBackground(modifier = Modifier.matchParentSize())
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp),
                    shape = RoundedCornerShape(AloNativeLayout.radiusMedium),
                    color = AloNativeAppSurface.copy(alpha = 0.74f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.08f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = conversation.peer.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = AloNativeAppText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (conversation.peer.online) "в сети" else "чат",
                            style = MaterialTheme.typography.labelSmall,
                            color = AloNativeAppMuted
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, end = 54.dp, bottom = 16.dp),
                    shape = RoundedCornerShape(AloNativeLayout.radiusMedium),
                    color = AloNativeAppSurfaceRaised.copy(alpha = 0.97f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.09f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = conversation.lastBody.ifBlank {
                                if (conversation.kind == "channel") "Публикаций пока нет" else "Сообщений пока нет"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = AloNativeAppText,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (conversation.lastAt.isNotBlank()) {
                            Text(
                                text = formatMessageTime(conversation.lastAt),
                                modifier = Modifier.align(Alignment.End),
                                style = MaterialTheme.typography.labelSmall,
                                color = AloNativeAppMuted
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(0.72f),
                shape = RoundedCornerShape(AloNativeLayout.radiusLarge),
                color = AloNativeAppSurface.copy(alpha = 0.90f),
                border = androidx.compose.foundation.BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.07f))
            ) {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    ConversationPreviewAction(Icons.Rounded.ArrowForward, "Открыть чат", onOpen)
                }
            }
        }
    }
}

@Composable
private fun ConversationPreviewAction(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(horizontal = 22.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (destructive) AloNativeAppDanger else AloNativeAppText,
            modifier = Modifier.size(23.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (destructive) AloNativeAppDanger else AloNativeAppText
        )
    }
}

@Composable
private fun PreviewMenuDivider() {
    Spacer(
        modifier = Modifier
            .padding(horizontal = 22.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(AloNativeAppSeparator)
    )
}
