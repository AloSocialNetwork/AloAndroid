package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


enum class AuthStep {
    LoginEmail,
    LoginPassword,
    LoginCode,
    RegisterName,
    RegisterUsername,
    RegisterEmail,
    RegisterPassword,
    VerifyEmailCode,
    QrLogin,
    /** A new device scanned the code a signed-in device shows and waits for its owner to confirm. */
    DeviceLinkWaiting
}

enum class EmailVerificationOrigin {
    Registration,
    ExistingAccount
}

data class AuthUiState(
    val step: AuthStep = AuthStep.LoginEmail,
    val name: String = "",
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val code: String = "",
    val challengeToken: String = "",
    val verificationRequestId: String = "",
    val emailVerificationOrigin: EmailVerificationOrigin = EmailVerificationOrigin.Registration,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    /** Sign-in refused because moderators blocked the account: it can be appealed from here. */
    val isAccountBlockedByModeration: Boolean = false,
    val noticeMessage: String? = null,
    val resendCooldownSec: Int = 0,
    val qrLoginSession: AloQrLoginSession? = null,
    val isQrLoginLoading: Boolean = false,
    /** «Сканировать QR»: the camera is open over the sign-in screen. */
    val isDeviceLinkScannerOpen: Boolean = false,
    /** Accounts saved on this device, offered as «Войти в сохранённый аккаунт». */
    val savedAccounts: List<AloStoredAccount> = emptyList()
)

sealed interface AuthAction {
    data object ChooseLogin : AuthAction
    data object ChooseRegister : AuthAction
    data object ChoosePasskey : AuthAction
    data object ChooseQrLogin : AuthAction
    data object ScanDeviceLink : AuthAction
    data object CloseDeviceLinkScanner : AuthAction
    data class DeviceLinkScanned(val rawPayload: String) : AuthAction
    data object Back : AuthAction
    data object Submit : AuthAction
    data object ResendCode : AuthAction
    data class UpdateName(val value: String) : AuthAction
    data class UpdateUsername(val value: String) : AuthAction
    data class UpdateEmail(val value: String) : AuthAction
    data class UpdatePassword(val value: String) : AuthAction
    data class UpdateCode(val value: String) : AuthAction
    data class SwitchToStoredAccount(val userId: Int) : AuthAction
}

@Composable
fun AuthFlowScreen(
    state: AuthUiState,
    onAction: (AuthAction) -> Unit,
    onAppealAccountBlock: suspend (String) -> Unit = {}
) {
    var isAccountAppealPresented by remember { mutableStateOf(false) }
    var isStoredAccountsPresented by remember { mutableStateOf(false) }
    if (state.isDeviceLinkScannerOpen) {
        AloQrLoginScannerOverlay(
            onScanned = { onAction(AuthAction.DeviceLinkScanned(it)) },
            onDismiss = { onAction(AuthAction.CloseDeviceLinkScanner) },
            hint = "На устройстве, где вы уже вошли в Alo, откройте Настройки → Безопасность → «Показать QR для входа» и наведите камеру на код.",
            secondaryAction = "Показать QR на этом устройстве" to {
                onAction(AuthAction.CloseDeviceLinkScanner)
                onAction(AuthAction.ChooseQrLogin)
            }
        )
        return
    }
    if (isAccountAppealPresented) {
        AloAppealSheet(title = "Обжалование блокировки", onDismiss = { isAccountAppealPresented = false }, onSend = onAppealAccountBlock)
    }
    if (isStoredAccountsPresented) {
        AloStoredAccountsSheet(state.savedAccounts, onDismiss = { isStoredAccountsPresented = false }) { userId ->
            isStoredAccountsPresented = false
            onAction(AuthAction.SwitchToStoredAccount(userId))
        }
    }
    BackHandler(enabled = state.canGoBack) { onAction(AuthAction.Back) }

    // iOS's AuthView: a step at a time, the title large, and the actions held at the bottom.
    AloNaturalLineHeight {
        Column(
            Modifier
                .fillMaxSize()
                .background(AloNativeAppBackground)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .imePadding()
        ) {
            AuthTopBar(state, onBack = { onAction(AuthAction.Back) })
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 104.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp)
            ) {
                AnimatedContent(
                    targetState = state.step,
                    // Telegram's LoginActivity.setPage: the new step pushes the old one off across the
                    // whole width, 300ms on AccelerateDecelerate.
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val motion = tween<IntOffset>(300, easing = AloTelegramAccelerateDecelerate)
                        slideInHorizontally(motion) { if (forward) it else -it } togetherWith
                            slideOutHorizontally(motion) { if (forward) -it else it }
                    },
                    label = "authStep"
                ) { step ->
                    AuthStepContent(state.copy(step = step), onAction)
                }
                AuthStatusMessages(state, onAppeal = { isAccountAppealPresented = true })
            }
            AuthBottomActions(state, onAction, onStoredAccounts = { isStoredAccountsPresented = true })
        }
    }
}

private val AuthStep.isVerification: Boolean
    get() = this == AuthStep.LoginCode || this == AuthStep.VerifyEmailCode

private val AuthStep.isRegistration: Boolean
    get() = this == AuthStep.RegisterName || this == AuthStep.RegisterUsername ||
        this == AuthStep.RegisterEmail || this == AuthStep.RegisterPassword

private val AuthStep.isLogin: Boolean
    get() = this == AuthStep.LoginEmail || this == AuthStep.LoginPassword

private val AuthUiState.canGoBack: Boolean
    get() = step !in setOf(AuthStep.LoginEmail, AuthStep.RegisterName)

/** The steps the dots stand for, and which one is current. */
private val AuthUiState.indicator: Pair<Int, Int>
    get() = when (step) {
        AuthStep.LoginEmail -> 0 to 2
        AuthStep.LoginPassword -> 1 to 2
        AuthStep.RegisterName -> 0 to 4
        AuthStep.RegisterUsername -> 1 to 4
        AuthStep.RegisterEmail -> 2 to 4
        AuthStep.RegisterPassword -> 3 to 4
        else -> 2 to 3
    }

@Composable
private fun AuthTopBar(state: AuthUiState, onBack: () -> Unit) {
    // The same back circle as every other screen: 42dp, 12dp from the edge, centred in the 56dp header row.
    Row(
        Modifier.fillMaxWidth().height(AloScreenChrome.HeaderHeight)
            .padding(start = AloScreenChrome.BackStart, end = AloScreenChrome.SideInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedVisibility(visible = state.canGoBack, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
            AloGlassCircleButton(onClick = onBack, description = "Назад", diameter = AloScreenChrome.BackDiameter)
        }
        Text(
            "Alo",
            modifier = Modifier.padding(start = if (state.canGoBack) 0.dp else 4.dp),
            color = AloNativeAppText, fontSize = 23.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.weight(1f))
        Text(
            when {
                state.step.isVerification -> "Проверка"
                state.step.isRegistration -> "Регистрация"
                else -> "Вход"
            },
            color = AloNativeAppMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AuthStepIndicator(current: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 26.dp else 7.dp, tween(220), label = "authDot")
            Box(
                Modifier.size(width, 7.dp).clip(RoundedCornerShape(percent = 50))
                    .background(if (index == current) AloNativeAppAccent else AloNativeAppText.copy(alpha = 0.16f))
            )
        }
    }
}

@Composable
private fun AuthStepContent(state: AuthUiState, onAction: (AuthAction) -> Unit) {
    val (current, count) = state.indicator
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (state.step != AuthStep.QrLogin && state.step != AuthStep.DeviceLinkWaiting) AuthStepIndicator(current, count)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(state.step.title, color = AloNativeAppText, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
            Text(
                if (state.step.isVerification) state.noticeMessage?.takeIf { it.isNotBlank() } ?: "Мы отправили код на указанную почту."
                else state.step.subtitle,
                color = AloNativeAppMuted, fontSize = 17.sp, lineHeight = 22.sp
            )
        }
        when (state.step) {
            AuthStep.LoginEmail, AuthStep.RegisterEmail -> AuthField(
                state.email, { onAction(AuthAction.UpdateEmail(it)) }, "name@example.com", KeyboardType.Email,
                imeAction = ImeAction.Next, onSubmit = { onAction(AuthAction.Submit) }
            )
            AuthStep.LoginPassword, AuthStep.RegisterPassword -> AuthField(
                state.password, { onAction(AuthAction.UpdatePassword(it)) }, "Пароль", KeyboardType.Password,
                isPassword = true, imeAction = ImeAction.Done, onSubmit = { onAction(AuthAction.Submit) }
            )
            AuthStep.RegisterName -> AuthField(
                state.name, { onAction(AuthAction.UpdateName(it)) }, "Имя", KeyboardType.Text,
                capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next, onSubmit = { onAction(AuthAction.Submit) }
            )
            AuthStep.RegisterUsername -> AuthField(
                state.username, { onAction(AuthAction.UpdateUsername(it)) }, "username", KeyboardType.Ascii,
                imeAction = ImeAction.Next, onSubmit = { onAction(AuthAction.Submit) }
            )
            AuthStep.LoginCode, AuthStep.VerifyEmailCode -> {
                AuthField(
                    state.code, { onAction(AuthAction.UpdateCode(it)) }, "Код из письма", KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done, onSubmit = { onAction(AuthAction.Submit) }
                )
                val canResend = !state.isSubmitting && state.resendCooldownSec == 0
                Text(
                    if (state.resendCooldownSec > 0) "Отправить снова через ${state.resendCooldownSec} с" else "Отправить код снова",
                    color = if (canResend) AloNativeAppAccent else AloNativeAppMuted,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.heightIn(min = 44.dp).clickable(enabled = canResend) { onAction(AuthAction.ResendCode) }.wrapContentHeight()
                )
            }
            AuthStep.QrLogin -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val session = state.qrLoginSession
                when {
                    state.isQrLoginLoading -> CircularProgressIndicator(modifier = Modifier.size(40.dp), color = AloNativeAppMuted)
                    session != null -> AloQrCode(payload = session.qrPayload, modifier = Modifier.size(240.dp))
                }
                Text(
                    "Создать новый QR-код", color = AloNativeAppAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.heightIn(min = 44.dp).clickable { onAction(AuthAction.ChooseQrLogin) }.wrapContentHeight()
                )
            }
            AuthStep.DeviceLinkWaiting -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.errorMessage.isNullOrBlank()) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp), color = AloNativeAppMuted)
                } else {
                    Text(
                        "Сканировать снова", color = AloNativeAppAccent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.heightIn(min = 44.dp).clickable { onAction(AuthAction.ScanDeviceLink) }.wrapContentHeight()
                    )
                }
            }
        }
    }
}

private val AuthStep.title: String
    get() = when (this) {
        AuthStep.RegisterName -> "Как вас зовут?"
        AuthStep.RegisterUsername -> "Выберите имя пользователя"
        AuthStep.RegisterEmail -> "Укажите почту"
        AuthStep.LoginEmail -> "Введите почту"
        AuthStep.RegisterPassword -> "Создайте пароль"
        AuthStep.LoginPassword -> "Введите пароль"
        AuthStep.LoginCode, AuthStep.VerifyEmailCode -> "Введите код"
        AuthStep.QrLogin -> "Вход по QR-коду"
        AuthStep.DeviceLinkWaiting -> "Подтвердите вход на другом устройстве"
    }

private val AuthStep.subtitle: String
    get() = when (this) {
        AuthStep.RegisterName -> "Имя будет видно в профиле, ленте и чатах."
        AuthStep.RegisterUsername -> "Короткий адрес, по которому вас смогут найти."
        AuthStep.RegisterEmail -> "На нее придет код подтверждения."
        AuthStep.LoginEmail -> "Мы найдем аккаунт и отправим код входа."
        AuthStep.RegisterPassword -> "Минимум 8 символов."
        AuthStep.LoginPassword -> "После пароля подтвердим вход кодом из письма."
        AuthStep.LoginCode, AuthStep.VerifyEmailCode -> "Код отправлен на почту."
        AuthStep.QrLogin -> "Отсканируйте этот код в Alo на устройстве, где вы уже вошли: Настройки → Безопасность → «Подключить устройство». Подойдёт и обычная камера телефона."
        AuthStep.DeviceLinkWaiting -> "На устройстве, где вы уже вошли, появится запрос. Он действует 2 минуты."
    }

/** iOS's auth field: 58pt tall, 20pt text, an accent ring while focused. */
@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction,
    onSubmit: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = AloSquircle(15.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(shape)
            .background(AloNativeAppSurface)
            .border(1.25.dp, if (focused) AloNativeAppAccent.copy(alpha = 0.75f) else Color.Transparent, shape)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) Text(placeholder, color = AloNativeAppMuted.copy(alpha = 0.6f), fontSize = 20.sp, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = AloNativeAppText, fontSize = 20.sp),
            cursorBrush = SolidColor(AloNativeAppAccent),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                capitalization = capitalization,
                autoCorrectEnabled = capitalization == KeyboardCapitalization.Words,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(onNext = { onSubmit() }, onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = placeholder }
        )
    }
}

@Composable
private fun AuthStatusMessages(state: AuthUiState, onAppeal: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val notice = state.noticeMessage
        if (!notice.isNullOrBlank() && !state.step.isVerification) {
            AuthStatusRow(notice, AloNativeAppAccent, Icons.Rounded.CheckCircle)
        }
        val error = state.errorMessage
        if (!error.isNullOrBlank()) {
            AuthStatusRow(error, Color(0xFFFF455C), Icons.Rounded.Error)
            if (state.isAccountBlockedByModeration) {
                Text(
                    "Обжаловать блокировку", color = AloNativeAppAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.heightIn(min = 44.dp).clickable(onClick = onAppeal).wrapContentHeight()
                )
            }
        }
    }
}

@Composable
private fun AuthStatusRow(text: String, tint: Color, icon: ImageVector) {
    Row(
        Modifier.fillMaxWidth().clip(AloSquircle(14.dp)).background(AloNativeAppSurface).padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(17.dp))
        Text(text, color = AloNativeAppMuted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AuthBottomActions(state: AuthUiState, onAction: (AuthAction) -> Unit, onStoredAccounts: () -> Unit) {
    val step = state.step
    val canSubmit = when (step) {
        AuthStep.LoginEmail, AuthStep.RegisterEmail -> state.email.contains("@")
        AuthStep.LoginPassword, AuthStep.RegisterPassword -> state.password.length >= 8
        AuthStep.RegisterName -> state.name.isNotBlank()
        AuthStep.RegisterUsername -> state.username.isNotBlank()
        AuthStep.LoginCode, AuthStep.VerifyEmailCode -> state.code.isNotBlank()
        AuthStep.QrLogin, AuthStep.DeviceLinkWaiting -> false
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(AloNativeAppBackground.copy(alpha = 0f), AloNativeAppBackground.copy(alpha = 0.96f), AloNativeAppBackground)))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (step != AuthStep.QrLogin && step != AuthStep.DeviceLinkWaiting) {
            val enabled = canSubmit && !state.isSubmitting
            Box(
                Modifier.fillMaxWidth().height(54.dp).clip(AloSquircle(15.dp))
                    .background(if (enabled) AloNativeAppAccent else AloNativeAppText.copy(alpha = 0.08f))
                    .clickable(enabled = enabled) { onAction(AuthAction.Submit) },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.isSubmitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    Text(
                        when {
                            step.isVerification -> "Продолжить"
                            step == AuthStep.LoginPassword -> "Войти"
                            step == AuthStep.RegisterPassword -> "Создать аккаунт"
                            else -> "Продолжить"
                        },
                        color = if (enabled || state.isSubmitting) Color.White else AloNativeAppMuted.copy(alpha = 0.7f),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        if (!step.isVerification) {
            if (step.isLogin) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AuthTintedButton(Icons.Rounded.Key, "Ключ доступа", Modifier.weight(1f), enabled = !state.isSubmitting) { onAction(AuthAction.ChoosePasskey) }
                    AuthTintedButton(Icons.Rounded.QrCodeScanner, "Сканировать QR", Modifier.weight(1f), enabled = !state.isSubmitting) { onAction(AuthAction.ScanDeviceLink) }
                }
                if (state.savedAccounts.isNotEmpty()) {
                    Text(
                        "Войти в сохранённый аккаунт", color = AloNativeAppAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable(enabled = !state.isSubmitting, onClick = onStoredAccounts).wrapContentHeight()
                    )
                }
            }
            val register = step.isRegistration
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = AloNativeAppMuted)) { append(if (register) "Уже есть аккаунт? " else "Нет аккаунта? ") }
                    withStyle(SpanStyle(color = AloNativeAppAccent)) { append(if (register) "Войти" else "Создать") }
                },
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp)
                    .clickable { onAction(if (register) AuthAction.ChooseLogin else AuthAction.ChooseRegister) }
                    .wrapContentHeight()
            )
        }
    }
}

@Composable
private fun AuthTintedButton(icon: ImageVector, title: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 42.dp).clip(AloSquircle(13.dp)).background(AloNativeAppAccent.copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = AloNativeAppAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(title, color = AloNativeAppAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** iOS's AloAccountSwitcherSheet: the accounts saved on this device. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AloStoredAccountsSheet(accounts: List<AloStoredAccount>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AloFormSheet(title = "Сохранённые аккаунты", onDismiss = onDismiss, leading = null, trailing = "Закрыть", onTrailing = onDismiss, fullHeight = false) {
        AloFormSection {
            accounts.forEachIndexed { index, account ->
                if (index > 0) AloFormDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(account.userId) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AvatarBadge(name = account.displayName, avatarUrl = account.avatarUrl, verified = false, size = 38.dp, colorSeed = account.userId.toString())
                    Column(Modifier.weight(1f)) {
                        Text(account.displayName, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(account.username.takeIf(String::isNotBlank)?.let { "@$it" } ?: "Сохранённый сеанс", color = AloNativeAppMuted, fontSize = 13.sp, maxLines = 1)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AloNativeAppMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
