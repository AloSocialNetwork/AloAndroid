# Alo Android

Приложение соцсети и мессенджера «ало» для Android (Kotlin, Jetpack Compose). Код приложения для iPhone:
[AloSocialNetwork/AloiOS](https://github.com/AloSocialNetwork/AloiOS).

Готовая сборка: https://test.xn--80awj.online/downloads/android/latest.apk (Android 7.0 и новее, только 64-битные телефоны).
Установленное приложение обновляется само.

## Что готово

- Нативный Android-клиент на Jetpack Compose
- Пошаговая email-авторизация и регистрация
- Нативные экраны ленты, поиска, сообщений, уведомлений и профиля
- Комментарии, лайки, репосты и открытие медиа
- Реальная отправка chat-вложений через backend `/api/uploads` и `/api/messages/send`
- Голосовые сообщения с локальной записью и playback внутри bubble
- Видео-кружки как реальные video-вложения с preview
- Разрешения для камеры и микрофона
- Loading-state вместо “сломанных” экранов при недоступном backend

## Как приложение подключается к серверу

Адрес API централизован в `app/src/main/java/com/alo/android/AloServerConfiguration.kt`.
Для debug-сборки на физическом устройстве меняется только
`localDevelopmentHost` в этом классе; release получает HTTPS-адрес из
`aloBaseUrl`/`aloPublicDomain`.

Дальше он пробрасывается в `BuildConfig.ALO_BASE_URL` через `app/build.gradle.kts`, а `MainActivity` создаёт `AloApiClient` с этим адресом. Все запросы идут в тот же backend `Alo`, что и сайт, через `/api/...`. Сессия cookie-based, cookies хранит Android `CookieManager`.

`10.0.2.2` работает только для Android Emulator и указывает на этот Mac. Для
эмулятора укажи это значение в том же `localDevelopmentHost`.

## Сборка

1. Открой папку проекта в Android Studio (нужны JDK 17 и Android SDK 36).
2. Для push-уведомлений скопируй `alo-local.properties.example` в `alo-local.properties` и впиши данные своего
   проекта Firebase. Без этого файла приложение собирается и работает, только без FCM.
3. Запусти Android Emulator или подключи телефон и нажми `Run`.

Из терминала: `./gradlew assembleDebug`. Release-сборку подписывает владелец сервиса своим ключом, поэтому
APK, собранный самостоятельно, не встанет поверх установленного приложения.

## Если нужен физический Android

Открой `AloServerConfiguration.kt` и замени:

`private const val localDevelopmentHost = "192.168.0.18"`

на актуальный LAN-адрес backend, например:

`private const val localDevelopmentHost = "192.168.0.15"`

После этого сделай `Sync Project with Gradle Files` и заново запусти приложение.
Для release URL задаётся отдельно через `aloBaseUrl`, но debug-сборка не должна
получать IP из Gradle или из экранов приложения.

## Карта геопозиции

Экран выбора точки использует MapLibre Native. По умолчанию стиль загружается
из OpenFreeMap, а адрес стиля можно заменить без изменения кода через Gradle:

`./gradlew assembleDebug -PaloMapStyleUrl=https://example.com/style.json`

Логотип и атрибуция карты остаются видимыми. Для production рекомендуется
указать собственный или договорной tile/style endpoint с подходящими лимитами.

## Лицензия

GNU GPL v3 (файл `LICENSE`), как и у приложения для iPhone. Сквозное шифрование выполняет libsignal
(AGPL-3.0), остальные библиотеки перечислены в `THIRD_PARTY_NOTICES.md`.

## Важно

- Для production лучше использовать `https`.
- Если backend и сайт живут на одном домене, указывай корень сайта.
- Если API вынесен отдельно, указывай адрес API-хоста.
