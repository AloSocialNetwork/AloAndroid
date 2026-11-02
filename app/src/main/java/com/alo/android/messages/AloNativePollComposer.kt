package com.alo.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.util.UUID

@Composable
internal fun ChatPollComposerSheet(
    onDismiss: () -> Unit,
    onCreate: (AloPollDefinition, Boolean) -> Unit
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var allowsMultipleAnswers by remember { mutableStateOf(false) }
    var isQuiz by remember { mutableStateOf(false) }
    var correctOptionIndex by remember { mutableStateOf(0) }
    var quizExplanation by remember { mutableStateOf("") }
    var isAnonymous by remember { mutableStateOf(true) }
    var allowsRevoting by remember { mutableStateOf(true) }
    var closeAfterSeconds by remember { mutableStateOf(0) }
    var closeMenuExpanded by remember { mutableStateOf(false) }
    val normalizedOptions = options.map(String::trim)
    val canCreate = question.trim().isNotBlank() &&
        normalizedOptions.size in 2..10 &&
        normalizedOptions.all(String::isNotBlank) &&
        normalizedOptions.map(String::lowercase).distinct().size == normalizedOptions.size
    fun buildDefinition(): AloPollDefinition {
        val pollOptions = normalizedOptions.mapIndexed { index, text ->
            AloPollOption(id = "o${index + 1}", text = text)
        }
        return AloPollDefinition(
            id = UUID.randomUUID().toString().replace("-", "").lowercase(),
            question = question.trim(),
            options = pollOptions,
            allowsMultipleAnswers = allowsMultipleAnswers,
            isQuiz = isQuiz,
            correctOptionIds = if (isQuiz) {
                setOf(pollOptions[correctOptionIndex.coerceIn(pollOptions.indices)].id)
            } else {
                emptySet()
            },
            explanation = if (isQuiz) quizExplanation.trim() else "",
            isAnonymous = isAnonymous,
            allowsRevoting = allowsRevoting,
            closesAt = if (closeAfterSeconds > 0) {
                Instant.ofEpochMilli(
                    System.currentTimeMillis() + closeAfterSeconds * 1_000L
                ).toString()
            } else {
                ""
            }
        )
    }
    fun submit(schedule: Boolean) {
        if (canCreate) onCreate(buildDefinition(), schedule)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .heightIn(max = 720.dp),
            shape = AloSquircle(26.dp),
            color = AloNativeAppSurface,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Отмена", color = AloNativeChatAccentBlue)
                    }
                    Text(
                        text = "Новый опрос",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = AloNativeAppText
                    )
                    TextButton(
                        enabled = canCreate,
                        onClick = { submit(schedule = false) }
                    ) {
                        Text("Создать", fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(300) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Вопрос") },
                    minLines = 2,
                    maxLines = 4,
                    shape = AloSquircle(16.dp)
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AloSquircle(16.dp),
                    color = AloNativeAppBackgroundSecondary
                ) {
                    Column {
                        options.forEachIndexed { index, value ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 10.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isQuiz) {
                                    RadioButton(
                                        selected = correctOptionIndex == index,
                                        onClick = { correctOptionIndex = index }
                                    )
                                }
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { options[index] = it.take(100) },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Вариант ${index + 1}") },
                                    singleLine = true,
                                    shape = AloSquircle(14.dp)
                                )
                                if (options.size > 2) {
                                    IconButton(onClick = {
                                        options.removeAt(index)
                                        correctOptionIndex = correctOptionIndex.coerceAtMost(options.lastIndex)
                                    }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Удалить вариант")
                                    }
                                }
                            }
                            if (index < options.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = if (isQuiz) 54.dp else 12.dp),
                                    color = AloNativeAppBorder.copy(alpha = AloNativeAppBorder.alpha * 0.5f)
                                )
                            }
                        }

                        if (options.size < 10) {
                            TextButton(
                                onClick = { options.add("") },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Text("Добавить вариант", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }

                if (isQuiz) {
                    OutlinedTextField(
                        value = quizExplanation,
                        onValueChange = { quizExplanation = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Пояснение к ответу") },
                        placeholder = { Text("Будет показано после ответа") },
                        minLines = 2,
                        maxLines = 4,
                        shape = AloSquircle(16.dp)
                    )
                }

                PollSettingRow(
                    title = "Анонимное голосование",
                    checked = isAnonymous,
                    onCheckedChange = { isAnonymous = it }
                )
                PollSettingRow(
                    title = "Несколько ответов",
                    checked = allowsMultipleAnswers,
                    enabled = !isQuiz,
                    onCheckedChange = { allowsMultipleAnswers = it }
                )
                PollSettingRow(
                    title = "Можно переголосовать",
                    checked = allowsRevoting,
                    enabled = !isQuiz,
                    onCheckedChange = { allowsRevoting = it }
                )
                PollSettingRow(
                    title = "Режим викторины",
                    checked = isQuiz,
                    onCheckedChange = {
                        isQuiz = it
                        if (it) {
                            allowsMultipleAnswers = false
                            allowsRevoting = false
                        } else {
                            quizExplanation = ""
                        }
                    }
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AloSquircle(16.dp),
                    color = AloNativeAppBackgroundSecondary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Автозакрытие",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = AloNativeAppText
                            )
                            Text(
                                text = "Сервер завершит опрос в заданный срок.",
                                style = MaterialTheme.typography.bodySmall,
                                color = AloNativeAppMuted
                            )
                        }
                        Box {
                            TextButton(onClick = { closeMenuExpanded = true }) {
                                Text(
                                    text = pollCloseDurationLabel(closeAfterSeconds),
                                    color = AloNativeChatAccentBlue,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            AloDropdownMenu(
                                expanded = closeMenuExpanded,
                                onDismissRequest = { closeMenuExpanded = false }
                            ) {
                                pollCloseDurationOptions.forEach { (seconds, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            closeAfterSeconds = seconds
                                            closeMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Button(
                    enabled = canCreate,
                    onClick = { submit(schedule = false) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = AloSquircle(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AloNativeChatAccentBlue,
                        contentColor = Color.White
                    )
                ) {
                    Text("Создать опрос", fontWeight = FontWeight.Bold)
                }
                TextButton(
                    enabled = canCreate,
                    onClick = { submit(schedule = true) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Отправить позже", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private val pollCloseDurationOptions = listOf(
    0 to "Не закрывать",
    60 * 60 to "Через 1 час",
    6 * 60 * 60 to "Через 6 часов",
    24 * 60 * 60 to "Через 1 день",
    3 * 24 * 60 * 60 to "Через 3 дня",
    7 * 24 * 60 * 60 to "Через неделю"
)

private fun pollCloseDurationLabel(seconds: Int): String {
    return pollCloseDurationOptions.firstOrNull { it.first == seconds }?.second
        ?: "Не закрывать"
}

@Composable
private fun PollSettingRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AloSquircle(16.dp),
        color = AloNativeAppBackgroundSecondary
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) AloNativeAppText else AloNativeAppMuted
            )
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}
