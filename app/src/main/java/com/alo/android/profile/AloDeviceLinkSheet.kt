package com.alo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** The signed-in device's half of the device link: the server, or a fixture in design previews. */
internal interface AloDeviceLinkService {
    suspend fun start(): AloQrLoginSession
    suspend fun status(session: AloQrLoginSession): AloQrLoginSession
    suspend fun approve(session: AloQrLoginSession)
    suspend fun deny(session: AloQrLoginSession)
}

internal class AloApiDeviceLinkService(private val api: AloApiClient) : AloDeviceLinkService {
    override suspend fun start() = api.startDeviceLink()
    override suspend fun status(session: AloQrLoginSession) = api.getQrLoginStatus(session.id, session.secret)
    override suspend fun approve(session: AloQrLoginSession) = api.approveDeviceLink(session.id, session.secret)
    override suspend fun deny(session: AloQrLoginSession) = api.denyDeviceLink(session.id, session.secret)
}

internal val LocalAloDeviceLink = staticCompositionLocalOf<AloDeviceLinkService?> { null }

private sealed interface AloDeviceLinkState {
    data object Loading : AloDeviceLinkState
    data class Showing(val session: AloQrLoginSession) : AloDeviceLinkState
    data class Confirming(val session: AloQrLoginSession) : AloDeviceLinkState
    data object Approving : AloDeviceLinkState
    data object Approved : AloDeviceLinkState
    data object Denied : AloDeviceLinkState
    data class Failed(val message: String) : AloDeviceLinkState
}

/**
 * «QR для входа», iOS's AloDeviceLinkShowSheet: this device shows a one-time code, a new device scans
 * it from its sign-in screen, and the sign-in happens only after it is confirmed here.
 */
@Composable
internal fun AloDeviceLinkSheet(service: AloDeviceLinkService, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<AloDeviceLinkState>(AloDeviceLinkState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        state = AloDeviceLinkState.Loading
        try {
            val session = service.start()
            val expiresAt = runCatching { Instant.parse(session.expiresAt) }.getOrNull()
            state = AloDeviceLinkState.Showing(session)
            while (true) {
                if (expiresAt != null && Instant.now().isAfter(expiresAt)) error("Срок действия QR-кода истёк. Создайте новый.")
                val current = state
                if (current !is AloDeviceLinkState.Showing && current !is AloDeviceLinkState.Confirming) return@LaunchedEffect
                val status = service.status(session)
                if (status.isExpired) error("Срок действия QR-кода истёк. Создайте новый.")
                if (status.claimed && state is AloDeviceLinkState.Showing) state = AloDeviceLinkState.Confirming(session)
                delay(2_000)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            val current = state
            if (current !is AloDeviceLinkState.Approving && current !is AloDeviceLinkState.Approved && current !is AloDeviceLinkState.Denied) {
                state = AloDeviceLinkState.Failed(error.moderationMessage("Не удалось создать QR-код для входа."))
            }
        }
    }

    AloFormSheet(title = "QR для входа", onDismiss = onDismiss, leading = null, trailing = "Закрыть", onTrailing = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            when (val current = state) {
                AloDeviceLinkState.Loading -> AloDeviceLinkProgress("Создаём QR-код…")
                is AloDeviceLinkState.Showing -> {
                    AloDeviceLinkCode(current.session)
                    AloDeviceLinkBody("На новом устройстве нажмите «Сканировать QR» на экране входа и наведите камеру на этот код.")
                    Text("Код действует 2 минуты. Он одноразовый.", color = AloNativeAppMuted, fontSize = 15.sp, textAlign = TextAlign.Center)
                }
                is AloDeviceLinkState.Confirming -> {
                    Box(Modifier.alpha(0.25f)) { AloDeviceLinkCode(current.session) }
                    Icon(Icons.Rounded.Security, null, tint = AloNativeAppText, modifier = Modifier.size(40.dp))
                    Text("Войти на другом устройстве?", color = AloNativeAppText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    AloDeviceLinkBody("Новое устройство отсканировало код. Подтверждайте, только если вы сами сейчас входите на нём.")
                    AloDeviceLinkButton("Подтвердить вход") {
                        state = AloDeviceLinkState.Approving
                        scope.launch {
                            state = runCatching { service.approve(current.session) }
                                .fold({ AloDeviceLinkState.Approved }, { AloDeviceLinkState.Failed(it.moderationMessage("Не удалось подтвердить вход.")) })
                        }
                    }
                    Text(
                        "Отклонить", color = AloNativeAppDanger, fontSize = 17.sp,
                        modifier = Modifier.heightIn(min = 44.dp).clickable {
                            state = AloDeviceLinkState.Denied
                            scope.launch { runCatching { service.deny(current.session) } }
                        }.padding(vertical = 11.dp)
                    )
                }
                AloDeviceLinkState.Approving -> AloDeviceLinkProgress("Подтверждаем вход…")
                AloDeviceLinkState.Approved -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = AloNativeAppAccent, modifier = Modifier.size(28.dp))
                        Text("Вход подтверждён", color = AloNativeAppText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    AloDeviceLinkBody("Новое устройство сейчас откроет ваш аккаунт.")
                    AloDeviceLinkButton("Готово", onDismiss)
                }
                AloDeviceLinkState.Denied -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Cancel, null, tint = AloNativeAppText, modifier = Modifier.size(28.dp))
                        Text("Вход отклонён", color = AloNativeAppText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                    AloDeviceLinkButton("Закрыть", onDismiss)
                }
                is AloDeviceLinkState.Failed -> {
                    Icon(Icons.Outlined.QrCode2, null, tint = AloNativeAppText, modifier = Modifier.size(46.dp))
                    AloDeviceLinkBody(current.message)
                    AloDeviceLinkButton("Создать новый QR-код") { attempt++ }
                }
            }
        }
    }
}

@Composable
private fun AloDeviceLinkCode(session: AloQrLoginSession) {
    Box(
        Modifier
            .widthIn(max = 268.dp)
            .fillMaxWidth()
            .semantics { contentDescription = "QR-код для входа на другом устройстве" }
    ) {
        AloQrCode(payload = session.qrPayload, modifier = Modifier.fillMaxWidth().height(268.dp).clip(AloSquircle(22.dp)))
    }
}

@Composable
private fun AloDeviceLinkBody(text: String) {
    Text(text, color = AloNativeAppText, fontSize = 17.sp, textAlign = TextAlign.Center)
}

@Composable
private fun AloDeviceLinkProgress(text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 40.dp)) {
        CircularProgressIndicator(color = AloNativeAppMuted, modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
        Text(text, color = AloNativeAppMuted, fontSize = 15.sp)
    }
}

/** iOS's .borderedProminent, .large. */
@Composable
private fun AloDeviceLinkButton(title: String, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 50.dp).clip(RoundedCornerShape(percent = 50)).background(AloNativeAppAccent).clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}
