package com.alo.android

import android.content.Context

/** One block of Telegram's emoji panel: a header, a tab icon and the emoji under it. */
internal class AloEmojiCategory(
    val id: String,
    val title: String,
    /** Words that find every emoji of the category in search. */
    val keywords: String,
    emoji: String
) {
    val items: List<String> = emoji.split(' ').filter { it.isNotBlank() }
}

internal object AloEmojiCatalog {
    val categories: List<AloEmojiCategory> = listOf(
        AloEmojiCategory(
            "smileys", "Смайлики и люди", "смайлы лица люди эмоции жесты руки сердца faces people",
            "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 🥲 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🤧 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👹 👺 👻 👽 👾 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾 🙈 🙉 🙊 " +
                "👋 🤚 ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 👂 👃 🧠 👀 👅 👄 " +
                "👶 🧒 👦 👧 🧑 👱 👨 🧔 👩 🧓 👴 👵 🙍 🙎 🙅 🙆 💁 🙋 🧏 🙇 🤦 🤷 👮 💂 👷 🤴 👸 🦸 🦹 🧙 🧚 🧛 🧜 🧝 🧞 🧟 💆 💇 🚶 🏃 💃 🕺 " +
                "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟"
        ),
        AloEmojiCategory(
            "nature", "Животные и природа", "животные природа цветы погода звери animals nature weather",
            "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🦂 🐢 🐍 🦎 🐙 🦑 🦐 🦀 🐠 🐟 🐬 🐳 🐋 🦈 🐊 🐅 🐆 🦓 🐘 🦏 🐪 🐫 🦒 🐃 🐂 🐄 🐎 🐖 🐏 🐑 🐐 🦌 🐕 🐩 🐈 🐓 🦃 🕊️ 🐇 🐁 🐀 🦔 " +
                "🌵 🎄 🌲 🌳 🌴 🌱 🌿 ☘️ 🍀 🎍 🍃 🍂 🍁 🍄 🌾 💐 🌷 🌹 🥀 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌜 🌚 🌕 🌖 🌗 🌘 🌑 🌒 🌓 🌔 🌙 ⭐ 🌟 ✨ ⚡ 🔥 🌈 ☀️ ⛅ ☁️ 🌧️ ⛈️ ❄️ ☃️ ⛄ 💧 💦 ☔ 🌊"
        ),
        AloEmojiCategory(
            "food", "Еда и напитки", "еда напитки фрукты овощи food drink fruit",
            "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅 🥔 🍠 🥐 🥯 🍞 🥖 🧀 🥚 🍳 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍥 🥠 🍢 🍡 🍧 🍨 🍦 🥧 🧁 🍰 🎂 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🌰 🥜 🍯 🥛 🍼 ☕ 🍵 🧃 🥤 🍶 🍺 🍻 🥂 🍷 🥃 🍸 🍹 🍾 🥄 🍴"
        ),
        AloEmojiCategory(
            "activity", "Активности", "спорт игры музыка награды activities sport games music",
            "⚽ 🏀 🏈 ⚾ 🥎 🎾 🏐 🏉 🎱 🏓 🏸 🥅 🏒 🏑 🏏 ⛳ 🏹 🎣 🥊 🥋 🎽 ⛸️ 🛹 🛷 ⛷️ 🏂 🏋️ 🤼 🤸 🤺 🤾 🏌️ 🏇 🧘 🏄 🏊 🤽 🚣 🧗 🚴 🎪 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🎲 🎯 🎳 🎮 🎰 🧩 🏆 🥇 🥈 🥉 🏅 🎖️"
        ),
        AloEmojiCategory(
            "travel", "Путешествия", "путешествия транспорт места здания travel transport places",
            "🚗 🚕 🚙 🚌 🚎 🏎️ 🚓 🚑 🚒 🚐 🚚 🚛 🚜 🛵 🏍️ 🚲 🛴 🚨 🚔 🚍 🚘 🚖 🚡 🚠 🚟 🚃 🚋 🚞 🚝 🚄 🚅 🚈 🚂 🚆 🚇 🚊 🚉 ✈️ 🛫 🛬 🛩️ 💺 🚁 🚀 🛸 ⛵ 🚤 🛥️ 🛳️ ⛴️ 🚢 ⚓ ⛽ 🚧 🚦 🚥 🗺️ 🗿 🗽 🗼 🏰 🏯 🏟️ 🎡 🎢 🎠 ⛲ ⛱️ 🏖️ 🏝️ 🏜️ 🌋 ⛰️ 🏔️ 🗻 🏕️ ⛺ 🏠 🏡 🏘️ 🏗️ 🏢 🏬 🏣 🏤 🏥 🏦 🏨 🏪 🏫 🏩 💒 🏛️ ⛪ 🕌 🛕 🕍"
        ),
        AloEmojiCategory(
            "objects", "Предметы", "предметы вещи техника деньги objects things tech money",
            "⌚ 📱 📲 💻 ⌨️ 🖥️ 🖨️ 🖱️ 🕹️ 💽 💾 💿 📀 📷 📸 📹 🎥 📞 ☎️ 📟 📠 📺 📻 🎙️ ⏰ ⏳ 📡 🔋 🔌 💡 🔦 🕯️ 💸 💵 💴 💶 💷 💰 💳 💎 ⚖️ 🔧 🔨 ⚒️ 🛠️ ⛏️ 🔩 ⚙️ 🧰 🧲 💣 🧨 🔪 🗡️ ⚔️ 🛡️ 🔮 📿 🔭 🔬 💊 💉 🧬 🧪 🌡️ 🧹 🧺 🚽 🚿 🛁 🧼 🧽 🧴 🛎️ 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🖼️ 🛍️ 🛒 🎁 🎈 🎏 🎀 🎊 🎉 🏮 ✉️ 📩 📦 📮 📜 📃 📄 📑 📊 📈 📉 📆 📅 📇 📋 📁 📂 📰 📓 📔 📒 📕 📗 📘 📙 📚 📖 🔖 🔗 📎 ✂️ 📌 📍 🔒 🔓 🔏 🔐 ✏️ ✒️ 🖊️ 🖋️ 🖌️ 🖍️ 📝 🔍 🔎"
        ),
        AloEmojiCategory(
            "symbols", "Символы", "символы знаки стрелки цвета symbols signs arrows",
            "💯 ✅ ☑️ ✔️ ❌ ❎ ➕ ➖ ➗ ✖️ ♾️ ‼️ ⁉️ ❓ ❔ ❕ ❗ 〰️ ™️ ©️ ®️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🟤 🔺 🔻 🔸 🔹 🔶 🔷 🔳 🔲 ▪️ ▫️ ◾ ◽ ◼️ ◻️ 🟥 🟧 🟨 🟩 🟦 🟪 ⬛ ⬜ 🟫 ♈ ♉ ♊ ♋ ♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ⛎ 🔀 🔁 🔂 ▶️ ⏩ ⏭️ ⏯️ ◀️ ⏪ ⏮️ 🔼 ⏫ 🔽 ⏬ ⏸️ ⏹️ ⏺️ ⏏️ 🎦 🔅 🔆 📶 📳 📴 ♻️ ⚜️ 🔱 📛 🔰 ⭕ 💠 🔘 🔠 🔡 🔢 🔣 🔤 🅰️ 🆎 🅱️ 🆑 🆒 🆓 ℹ️ 🆔 Ⓜ️ 🆕 🆖 🅾️ 🆗 🅿️ 🆘 🆙 🆚"
        )
    )

    /** Words that find a single emoji (Russian and English); the rest are found through their category. */
    private val keywords: Map<String, String> = mapOf(
        "😀" to "улыбка радость smile happy", "😂" to "смех слёзы смешно laugh tears", "🤣" to "ржу смех rofl",
        "😍" to "любовь влюблён heart eyes", "🥰" to "любовь милый love", "😘" to "поцелуй kiss", "😊" to "улыбка милый blush",
        "😎" to "круто очки cool", "🤔" to "думаю вопрос think", "😭" to "плачу слёзы cry", "😢" to "грусть слеза sad",
        "😡" to "злость злой angry", "🥳" to "праздник вечеринка party", "😴" to "сон спать sleep", "🤯" to "взрыв мозга шок mind",
        "😱" to "страх ужас scream", "🙄" to "глаза закатил roll", "😉" to "подмигнул wink", "🥺" to "пожалуйста умоляю please",
        "👍" to "лайк да хорошо ок like yes", "👎" to "дизлайк нет плохо dislike no", "👏" to "аплодисменты браво clap",
        "🙏" to "спасибо пожалуйста молитва thanks pray", "🤝" to "договорились рукопожатие сделка deal handshake",
        "💪" to "сила мышцы strong", "👌" to "окей отлично ok", "✌️" to "мир победа peace", "🤞" to "удача luck fingers",
        "👋" to "привет пока hello wave", "🙌" to "ура hooray", "👀" to "глаза смотрю eyes", "🫶" to "любовь сердце руки",
        "❤️" to "сердце любовь heart love", "🧡" to "оранжевое сердце", "💛" to "жёлтое сердце", "💚" to "зелёное сердце",
        "💙" to "синее сердце", "💜" to "фиолетовое сердце", "🖤" to "чёрное сердце", "💔" to "разбитое сердце broken",
        "🔥" to "огонь fire hot", "✨" to "блеск магия sparkle", "⭐" to "звезда star", "🎉" to "праздник конфетти party",
        "💯" to "сто отлично hundred", "✅" to "готово галочка check done", "❌" to "нет крест cross", "⚡" to "молния lightning",
        "☀️" to "солнце sun", "🌈" to "радуга rainbow", "🌹" to "роза цветок rose", "🐶" to "собака пёс dog", "🐱" to "кот кошка cat",
        "🦊" to "лиса fox", "🐻" to "медведь bear", "☕" to "кофе coffee", "🍕" to "пицца pizza", "🍔" to "бургер burger",
        "🍰" to "торт пирожное cake", "🎂" to "день рождения торт birthday", "🍺" to "пиво beer", "🍷" to "вино wine",
        "⚽" to "футбол мяч football", "🏆" to "кубок победа trophy", "🎮" to "игра game", "🚗" to "машина авто car",
        "✈️" to "самолёт plane", "🏠" to "дом home", "📱" to "телефон phone", "💻" to "ноутбук laptop", "🎁" to "подарок gift",
        "💡" to "идея лампа idea", "🔑" to "ключ key", "📷" to "камера фото camera", "🎧" to "наушники музыка headphones"
    )

    private val all: List<Pair<String, AloEmojiCategory>> =
        categories.flatMap { category -> category.items.map { it to category } }

    /** Emoji for a search: by their own words, or by the words of their category. */
    fun search(query: String): List<String> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return all.asSequence()
            .filter { (emoji, category) ->
                emoji.contains(needle) ||
                    keywords[emoji]?.lowercase()?.contains(needle) == true ||
                    category.title.lowercase().contains(needle) ||
                    category.keywords.lowercase().split(' ').any { it.startsWith(needle) }
            }
            .map { it.first }
            .distinct()
            .toList()
    }
}

/** The emoji a person used last, shown first in the panel (Telegram's «Недавние»). */
internal object AloEmojiRecents {
    private const val PREFS = "alo-android"
    private const val KEY = "emoji_recents_v1" // gitleaks:allow (a preferences key name, not a secret)
    const val LIMIT = 32

    fun load(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.split(' ')?.filter { it.isNotBlank() }?.take(LIMIT).orEmpty()

    fun use(context: Context, emoji: String): List<String> {
        val next = (listOf(emoji) + load(context).filterNot { it == emoji }).take(LIMIT)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next.joinToString(" ")).apply()
        return next
    }
}

/** Removes the last visible character from [text], keeping emoji built of several code points whole (a backspace key). */
internal fun aloDropLastGrapheme(text: String): String {
    if (text.isEmpty()) return text
    val iterator = java.text.BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val start = iterator.preceding(text.length)
    return if (start == java.text.BreakIterator.DONE) "" else text.substring(0, start)
}
