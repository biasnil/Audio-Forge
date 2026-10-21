package com.biasnil.audioforge

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own language (Settings -> Language), separate from the phone's.
 *
 * Android 13+ keeps the choice itself (LocaleManager), recreates the
 * screens when it changes, and also offers it in the phone's
 * Settings -> Apps -> Language. Android 12 has no such thing, so there the
 * choice is saved in a small SharedPreferences file and applied to each
 * Activity, Service and the Application through [localized].
 *
 * Without AppCompat on purpose: its per-app language helper needs an
 * AppCompat activity and theme.
 */
object AppLanguage {

    /** The languages the app is translated into, as BCP 47 tags (res/values-fr, values-b+zh+Hans...). */
    val TAGS: List<String> = listOf("en", "fr", "zh-Hans", "zh-Hant", "ms")

    /** Each language's name in that language. Never translated. */
    fun nativeName(tag: String): String = when (tag) {
        "en" -> "English"
        "fr" -> "Français"
        "zh-Hans" -> "简体中文"
        "zh-Hant" -> "繁體中文"
        "ms" -> "Bahasa Melayu"
        else -> tag
    }

    /** The chosen language (one of [TAGS]), or null for the phone's language. */
    fun current(context: Context): String? {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Read from the system every time: the user can change it in the phone's settings too.
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) null else locales[0].toLanguageTag()
        } else {
            prefs(context).getString(KEY_TAG, null)
        }
        return tag?.let(::supportedTag)
    }

    /** Switches the whole app to [tag] (null = follow the phone) and redraws [activity] in it. */
    fun set(activity: Activity, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            return // the system saves it and recreates the activity
        }
        // commit(), not apply(): the recreated activity reads it straight away.
        prefs(activity).edit().apply { if (tag == null) remove(KEY_TAG) else putString(KEY_TAG, tag) }.commit()
        // The Application's resources are used by code outside the screens;
        // switch them too so they don't wait for the next app start.
        val app = activity.applicationContext
        val locales = tag?.let { LocaleList(Locale.forLanguageTag(it)) } ?: Resources.getSystem().configuration.locales
        val config = Configuration(app.resources.configuration)
        config.setLocales(locales)
        @Suppress("DEPRECATION") // the only way to change a live Application's resources on Android 12
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
        Locale.setDefault(locales[0])
        activity.recreate()
    }

    /** [base] with the app language applied (Android 12 only; 13+ does this itself). */
    fun localized(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY_TAG, null)?.let(::supportedTag) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale) // so Locale.getDefault() (dates, problem reports) agrees
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /**
     * One of [TAGS] for a tag the system may hand back in another form:
     * "fr-FR" -> "fr", "zh-TW" / "zh-HK" -> "zh-Hant", "zh-CN" -> "zh-Hans".
     */
    internal fun supportedTag(tag: String): String? {
        val locale = Locale.forLanguageTag(tag)
        val language = locale.language
        if (language == "zh") {
            return when {
                locale.script == "Hant" -> "zh-Hant"
                locale.script == "Hans" -> "zh-Hans"
                locale.country in setOf("TW", "HK", "MO") -> "zh-Hant"
                else -> "zh-Hans"
            }
        }
        return TAGS.firstOrNull { it == language }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "app_locale"
    private const val KEY_TAG = "tag"
}
