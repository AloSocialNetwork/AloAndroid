package com.alo.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private const val NameMaxLength = 30
private const val BioMaxLength = 70
private const val UsernameMinLength = 3
private const val UsernameMaxLength = 32

/** What the server accepts as an @username: Latin letters, digits and «_». */
internal fun cleanUsernameInput(raw: String): String =
    raw.removePrefix("@").lowercase(Locale.ROOT).filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }.take(UsernameMaxLength)

/** The profile's name, @username and about text (iOS's «Изменить профиль»). */
@Composable
internal fun AloProfileEditSheet(me: AloCurrentUser, onDismiss: () -> Unit, onAction: (AloNativeAppAction) -> Unit) {
    var name by remember(me.id) { mutableStateOf(me.name) }
    var username by remember(me.id) { mutableStateOf(me.username) }
    var bio by remember(me.id) { mutableStateOf(me.bio) }
    var error by remember(me.id) { mutableStateOf<String?>(null) }
    var saving by remember(me.id) { mutableStateOf(false) }
    val nameOk = name.trim().length >= 2
    val usernameOk = username.length >= UsernameMinLength
    val changed = name.trim() != me.name || username != me.username || bio.trim() != me.bio
    AloFormSheet(
        title = "Профиль",
        onDismiss = onDismiss,
        leading = "Отмена",
        trailing = "Сохранить",
        trailingEnabled = !saving && changed && nameOk && usernameOk,
        onTrailing = {
            error = null
            saving = true
            onAction(AloNativeAppAction.UpdateProfileInfo(name, username, bio) { problem ->
                saving = false
                if (problem == null) onDismiss() else error = problem
            })
        },
        fullHeight = false
    ) {
        AloFormSection(header = "Имя", footer = if (nameOk) "${name.length} из $NameMaxLength" else "Имя не короче двух символов.") {
            AloFormTextField(value = name, onChange = { name = it.take(NameMaxLength) }, placeholder = "Имя")
        }
        AloFormSection(header = "Имя пользователя", footer = "От $UsernameMinLength символов: латинские буквы, цифры и «_». По нему вас находят и упоминают.") {
            AloFormTextField(value = username, onChange = { username = cleanUsernameInput(it) }, placeholder = "username")
        }
        AloFormSection(header = "О себе", footer = "${bio.length} из $BioMaxLength") {
            AloFormTextField(value = bio, onChange = { bio = it.take(BioMaxLength) }, placeholder = "Расскажите о себе", minLines = 3, maxLines = 6)
        }
        error?.let {
            Text(it, color = AloNativeAppDanger, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp))
        }
        // The checkmark and the request for one (admin/AloVerification.kt), as in the iPhone app and on the website.
        androidx.compose.foundation.layout.Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp)) {
            AloOwnVerificationCard(userId = me.id, verified = me.verified)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 36.dp))
    }
}
