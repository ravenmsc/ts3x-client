package com.morsen.ts3xclient

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** App language is explicitly selected; English is the default, including legacy "system" values. */
object AppLanguage {
    private const val PREFS = "ts3x_ui_locale"
    private const val KEY = "language"

    fun languageChoice(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "en")
            ?.takeIf { it in setOf("en", "de", "ru") } ?: "en"

    fun saveLanguage(context: Context, language: String) {
        require(language in setOf("en", "de", "ru"))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
    }

    fun effectiveLanguage(context: Context): String = languageChoice(context)

    fun localizedContext(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(effectiveLanguage(context)))
        return context.createConfigurationContext(config)
    }
}
