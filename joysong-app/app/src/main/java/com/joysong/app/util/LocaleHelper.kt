package com.joysong.app.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {

    const val PREF_LANGUAGE = "app_language"
    const val LANG_ENGLISH = "en"
    const val LANG_CHINESE = "zh"

    fun setLocale(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences("joysong_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_LANGUAGE, languageCode).apply()

        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    fun getLocale(context: Context): String {
        val prefs = context.getSharedPreferences("joysong_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_LANGUAGE, LANG_ENGLISH) ?: LANG_ENGLISH
    }

    fun applyLocale(activity: Activity) {
        val lang = getLocale(activity)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(activity.resources.configuration)
        config.setLocale(locale)
        activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
    }
}
