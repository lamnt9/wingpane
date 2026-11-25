package io.wingpane.ui.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** In-app language choice (no activity recreate, so the stream stays connected). */
object AppLanguage {
    const val SYSTEM = "system"
    const val EN = "en"
    const val VI = "vi"
    private const val PREFS = "app"
    private const val KEY = "language"

    fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    fun save(context: Context, language: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
    }
}

/** Resolves string resources in [language] for everything inside [content]. */
@Composable
fun LocalizedContent(language: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(language, base) {
        if (language == AppLanguage.SYSTEM) base else {
            val config = Configuration(base.resources.configuration).apply { setLocale(Locale(language)) }
            val resources: Resources = base.createConfigurationContext(config).resources
            object : ContextWrapper(base) {
                override fun getResources(): Resources = resources
            }
        }
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
    ) { content() }
}

@Composable
fun isVietnameseUi(): Boolean = LocalConfiguration.current.locales[0].language == "vi"
