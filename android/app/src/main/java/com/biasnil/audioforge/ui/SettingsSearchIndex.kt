package com.biasnil.audioforge.ui

import java.text.Normalizer

/** One searchable setting with its words already in the app's language (see SettingsCatalog). */
data class SearchableSetting(
    val id: String,
    /** SettingsSection.key it lives in ("" = the Settings tab itself: Language). */
    val sectionKey: String,
    val title: String,
    val sectionTitle: String,
    val summary: String,
    /** Words people type for it that aren't in its name ("dark mode", "volume"). */
    val aliases: List<String>,
)

/**
 * Settings search, all on the phone: every word typed has to match the
 * setting's name, section, summary or aliases. A name starting with the
 * words ranks first, then a word of the name, then the aliases, then the
 * rest. No fuzzy matching -- a wrong result is worse than none.
 */
fun searchSettings(settings: List<SearchableSetting>, query: String): List<SearchableSetting> {
    val words = normalizeForSearch(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    val whole = words.joinToString(" ")
    return settings.mapIndexedNotNull { index, setting ->
        val title = normalizeForSearch(setting.title)
        val aliases = setting.aliases.map(::normalizeForSearch)
        val others = listOf(normalizeForSearch(setting.sectionTitle), normalizeForSearch(setting.summary))
        var score = 0
        for (word in words) {
            score += when {
                title.startsWith(word) -> 100
                title.split(' ').any { it.startsWith(word) } -> 80
                aliases.any { it == word || it.startsWith(word) } -> 70
                aliases.any { alias -> alias.split(' ').any { it.startsWith(word) } } -> 60
                title.contains(word) -> 40
                others.any { text -> text.split(' ').any { it.startsWith(word) } } -> 20
                else -> return@mapIndexedNotNull null // every word has to match somewhere
            }
        }
        // The whole phrase as typed ("dark mode") counts extra.
        if (words.size > 1 && (title.contains(whole) || aliases.any { it.contains(whole) })) score += 50
        Triple(setting, score, index)
    }.sortedWith(compareByDescending<Triple<SearchableSetting, Int, Int>> { it.second }.thenBy { it.third }).map { it.first }
}

/** Lower case, no accents, punctuation as spaces: "Réglages – Thème" -> "reglages theme". */
fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("""\p{Mn}+"""), "")
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
