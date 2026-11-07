package com.alo.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** iOS's AloDataStorageSettings: what downloaded media takes, its limit, clearing, and what loads by itself. */
@Composable
internal fun DataStorageSettingsPage() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    remember { AloContentToolsPrefs.load(context) }
    var settings by remember { mutableStateOf(AloMediaCacheSettings.load(context)) }
    var usage by remember { mutableStateOf(AloMediaDiskCache.Usage()) }
    var refresh by remember { mutableStateOf(0) }
    var isCleaning by remember { mutableStateOf(false) }
    var confirmsCleaning by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    // Opening the page and every change of the limit trims the cache to it, then shows what is left.
    LaunchedEffect(refresh) {
        usage = withContext(Dispatchers.IO) { AloMediaDiskCache.clean(context, settings) }
    }

    fun update(next: AloMediaCacheSettings) {
        settings = next
        AloMediaCacheSettings.save(context, next)
        refresh += 1
    }

    if (confirmsCleaning) {
        AlertDialog(
            onDismissRequest = { confirmsCleaning = false },
            title = { Text("Очистить скачанные медиа?") },
            text = { Text("Будут удалены только скачанные копии медиа с этого устройства. Переписки, черновики и неотправленные файлы останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmsCleaning = false
                    isCleaning = true
                    scope.launch {
                        usage = withContext(Dispatchers.IO) {
                            SingletonImageLoader.get(context).let { loader ->
                                loader.diskCache?.clear()
                                loader.memoryCache?.clear()
                            }
                            AloMediaDiskCache.clean(context, settings, all = true)
                        }
                        notice = if (usage.count == 0) "Кеш медиа очищен." else "Кеш очищен. Используемые сейчас файлы сохранены."
                        isCleaning = false
                    }
                }) { Text("Очистить кеш") }
            },
            dismissButton = { TextButton(onClick = { confirmsCleaning = false }) { Text("Отмена") } }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = settingsPageGutter, end = settingsPageGutter, top = LocalSettingsTopInset.current + 8.dp, bottom = 44.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SettingsSection(title = "Использование памяти") {
                SettingsInfoValueRow("Скачанные медиа", aloFileSize(usage.bytes))
                SettingsMenuRow(
                    title = "Максимальный размер",
                    options = AloMediaCacheSettings.SIZE_CHOICES_GB.map { it.toString() to "$it ГБ" },
                    selected = (settings.maximumBytes / AloMediaCacheSettings.GB).toString(),
                    onSelect = { update(settings.copy(maximumBytes = it.toLong() * AloMediaCacheSettings.GB)) }
                )
                SettingsTextButtonRow(
                    title = if (isCleaning) "Очищаем…" else "Очистить кеш медиа",
                    enabled = !isCleaning && usage.count > 0,
                    destructive = true,
                    onClick = { confirmsCleaning = true }
                )
            }
        }
        if (notice.isNotEmpty()) item { SettingsFootnote(notice) }
        item {
            SettingsFootnote(
                "При заполнении лимита удаляются давно неиспользуемые файлы. Сообщения, ключи, черновики и неотправленные вложения не удаляются. Медиа можно скачать снова."
            )
        }
        item {
            SettingsSection(title = "Автозагрузка медиа") {
                SettingsMenuRow(
                    title = "Через Wi‑Fi",
                    options = AloMediaDownloadPreset.entries.map { it.name to it.title },
                    selected = settings.wifi.name,
                    onSelect = { name -> update(settings.copy(wifi = AloMediaDownloadPreset.valueOf(name))) }
                )
                SettingsMenuRow(
                    title = "Через мобильную сеть",
                    options = AloMediaDownloadPreset.entries.map { it.name to it.title },
                    selected = settings.cellular.name,
                    onSelect = { name -> update(settings.copy(cellular = AloMediaDownloadPreset.valueOf(name))) }
                )
            }
        }
        item {
            SettingsFootnote(
                "Загружаются фото, голосовые и небольшие файлы у полученных сообщений, с учётом размера. Большие и неизвестные по размеру файлы скачиваются по нажатию. Режим «Экономия трафика» отключает автозагрузку; уже скачанное доступно без сети. Ваши собственные вложения загружаются всегда."
            )
        }
        item {
            SettingsSection(title = "Перевод и расшифровка") {
                SettingsToggleRow(
                    icon = Icons.Rounded.Translate,
                    title = "Разрешить отправку",
                    subtitle = "Только то сообщение, которое вы выбрали",
                    checked = AloContentToolsPrefs.consent,
                    onCheckedChange = { AloContentToolsPrefs.setConsent(context, it) }
                )
                SettingsTextButtonRow(
                    title = "Удалить сохранённые переводы и расшифровки",
                    destructive = true,
                    onClick = {
                        AloContentToolsPrefs.clearCache(context)
                        notice = "Сохранённые переводы и расшифровки удалены с этого устройства."
                    }
                )
            }
        }
        item {
            SettingsFootnote(
                "Когда вы нажимаете «Перевести» или «Aa», текст или звук этого сообщения уходит на сервер Alo и в сервис перевода или расшифровки; сервер ничего не хранит. Результат остаётся только на этом устройстве."
            )
        }
    }
}

@Composable
private fun SettingsFootnote(text: String) {
    Text(
        text = text,
        color = AloNativeAppMuted,
        fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}
