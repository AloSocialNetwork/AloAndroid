package com.alo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Checkmarks (the same as the website and the iPhone app, AloVerificationViews.swift): a person asks for one for their
 * page (settings → profile), staff with the «Верификация» right review the requests in «Модерация → Галочки», and may
 * also give or take one from a profile's «⋮» without any request. A pending request of the same page is closed with
 * the same outcome.
 */
data class AloVerificationRequest(
    val id: Int,
    val requesterName: String,
    val requesterUsername: String,
    val targetType: String,
    val targetId: Int,
    val targetTitle: String,
    val note: String,
    val status: String,
    val reviewedNote: String,
    val createdAt: String
) {
    companion object {
        fun fromJson(json: JSONObject): AloVerificationRequest {
            val requester = json.optJSONObject("requester") ?: JSONObject()
            return AloVerificationRequest(
                id = json.optInt("id"),
                requesterName = requester.optString("name"),
                requesterUsername = requester.optString("username"),
                targetType = json.optString("targetType"),
                targetId = json.optInt("targetId"),
                targetTitle = json.optString("targetTitle"),
                note = json.optString("note"),
                status = json.optString("status").ifBlank { "pending" },
                reviewedNote = json.optString("reviewedNote"),
                createdAt = json.optString("createdAt")
            )
        }
    }
}

/** The rights of the signed-in account in the moderation panel ("verification", "reports"…), for menus outside it. */
internal val LocalAloStaffRights = staticCompositionLocalOf<Set<String>> { emptySet() }

/** Called after staff gave or took a checkmark, so the open profile shows it at once: (type, id, verified). */
internal val LocalAloVerifiedChanged = staticCompositionLocalOf<(String, Int, Boolean) -> Unit> { { _, _, _ -> } }

/** Settings → profile: what the page has, and the request for a checkmark. */
@Composable
internal fun AloOwnVerificationCard(userId: Int, verified: Boolean) {
    val service = LocalAloModeration.current ?: return
    val scope = rememberCoroutineScope()
    var request by remember { mutableStateOf<AloVerificationRequest?>(null) }
    var asking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    suspend fun reload() {
        request = runCatching { service.myVerificationRequests() }.getOrNull()
            ?.firstOrNull { it.targetType == "user" && it.targetId == userId }
    }
    LaunchedEffect(userId) { reload() }
    val pending = request?.status == "pending"
    val rejected = request?.status == "rejected"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Галочка", color = AloNativeAppMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
        Column(
            Modifier.fillMaxWidth().clip(AloSquircle(18.dp)).background(AloNativeAppSurface).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Verified, null, tint = if (verified) AloNativeAppAccent else AloNativeAppMuted, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            verified -> "Страница подтверждена"
                            pending -> "Заявка на рассмотрении"
                            rejected -> "Заявка отклонена"
                            else -> "Подтверждённая страница"
                        },
                        color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when {
                            verified -> "Рядом с вашим именем стоит галочка."
                            pending -> "Ответ придёт в уведомлениях."
                            rejected && request?.reviewedNote?.isNotBlank() == true -> "Причина: ${request?.reviewedNote}"
                            rejected -> "Можно подать заявку ещё раз."
                            else -> "Галочка показывает, что страница принадлежит именно вам."
                        },
                        color = AloNativeAppMuted, fontSize = 13.sp
                    )
                }
                if (!verified && !pending && !asking) {
                    TextButton(onClick = { asking = true }) {
                        Text(if (rejected) "Подать снова" else "Подать заявку", color = AloNativeAppAccent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (asking) {
                Text("Расскажите модераторам, кто вы, и дайте ссылки, если есть.", color = AloNativeAppMuted, fontSize = 14.sp)
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    placeholder = { Text("Например: я автор канала на YouTube, ссылка…") }
                )
                if (error.isNotEmpty()) Text(error, color = AloNativeAppDanger, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { asking = false; error = "" }) { Text("Отмена", color = AloNativeAppMuted) }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = !sending && note.trim().length >= 3,
                        onClick = {
                            sending = true
                            scope.launch {
                                try {
                                    service.requestVerification("user", userId, note.trim())
                                    asking = false
                                    note = ""
                                    error = ""
                                    reload()
                                } catch (failure: Exception) {
                                    error = failure.moderationMessage("Не удалось отправить заявку. Проверьте подключение.")
                                } finally {
                                    sending = false
                                }
                            }
                        }
                    ) { Text(if (sending) "Отправка…" else "Отправить заявку", color = AloNativeAppAccent, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/** «Модерация → Галочки»: requests waiting for a decision, and the ones already decided. */
@Composable
internal fun AloVerificationQueue(service: AloModerationService, onNotice: (String) -> Unit, onCount: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("pending") }
    var items by remember { mutableStateOf<List<AloVerificationRequest>?>(null) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(setOf<Int>()) }
    var rejecting by remember { mutableStateOf<AloVerificationRequest?>(null) }
    var rejectNote by remember { mutableStateOf("") }
    suspend fun load() {
        try {
            items = service.verificationQueue(status)
            error = ""
            if (status == "pending") onCount(items.orEmpty().size)
        } catch (failure: Exception) {
            error = failure.moderationMessage("Не удалось загрузить заявки.")
        }
    }
    LaunchedEffect(status) { items = null; load() }
    fun decide(item: AloVerificationRequest, approve: Boolean, note: String) {
        busy = busy + item.id
        scope.launch {
            try {
                service.reviewVerification(item.id, approve, note.trim())
                onNotice(if (approve) "Галочка выдана" else "Заявка отклонена")
                rejecting = null
                load()
            } catch (failure: Exception) {
                onNotice(failure.moderationMessage("Не удалось сохранить решение."))
            } finally {
                busy = busy - item.id
            }
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().clip(AloSquircle(12.dp)).background(AloNativeAppSurface).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            listOf("pending" to "Ожидают", "approved" to "Одобрены", "rejected" to "Отклонены").forEach { (key, title) ->
                Box(
                    Modifier.weight(1f).clip(AloSquircle(10.dp))
                        .background(if (status == key) AloNativeAppSurfaceRaised else Color.Transparent)
                        .clickable { status = key }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text(title, color = AloNativeAppText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        val list = items
        when {
            list == null && error.isEmpty() -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AloNativeAppAccent, modifier = Modifier.size(28.dp))
            }
            error.isNotEmpty() -> Text(error, color = AloNativeAppMuted, fontSize = 15.sp)
            list.isNullOrEmpty() -> Text(
                if (status == "pending") "Новых заявок на галочку нет." else "Ничего с таким исходом.",
                color = AloNativeAppMuted, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
            )
            else -> list.forEach { item ->
                Column(
                    Modifier.fillMaxWidth().clip(AloSquircle(18.dp)).background(AloNativeAppSurface).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (item.targetType == "channel") Icons.Rounded.Campaign else Icons.Rounded.Person, null, tint = AloNativeAppAccent, modifier = Modifier.size(20.dp))
                        Text(item.targetTitle.ifBlank { item.requesterName }, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                    }
                    Text(
                        if (item.targetType == "channel") "Сообщество · заявка от ${item.requesterName}"
                        else "Страница ${if (item.requesterUsername.isBlank()) item.requesterName else "@${item.requesterUsername}"}",
                        color = AloNativeAppMuted, fontSize = 13.sp
                    )
                    if (item.note.isNotBlank()) Text(item.note, color = AloNativeAppText, fontSize = 15.sp)
                    if (item.status == "pending") {
                        if (rejecting?.id == item.id) {
                            OutlinedTextField(
                                value = rejectNote,
                                onValueChange = { rejectNote = it.take(500) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Причина (её увидит автор)") }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.weight(1f).height(44.dp).clip(AloSquircle(12.dp)).background(AloNativeAppAccent)
                                    .clickable(enabled = item.id !in busy) { decide(item, approve = true, note = "") },
                                contentAlignment = Alignment.Center
                            ) { Text("Выдать галочку", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                            Box(
                                Modifier.weight(1f).height(44.dp).clip(AloSquircle(12.dp)).background(AloNativeAppSurfaceRaised)
                                    .clickable(enabled = item.id !in busy) {
                                        if (rejecting?.id == item.id) decide(item, approve = false, note = rejectNote)
                                        else { rejecting = item; rejectNote = "" }
                                    },
                                contentAlignment = Alignment.Center
                            ) { Text(if (rejecting?.id == item.id) "Отклонить с причиной" else "Отклонить", color = AloNativeAppDanger, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    } else if (item.reviewedNote.isNotBlank()) {
                        Text("Решение: ${item.reviewedNote}", color = AloNativeAppMuted, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.width(1.dp))
    }
}
