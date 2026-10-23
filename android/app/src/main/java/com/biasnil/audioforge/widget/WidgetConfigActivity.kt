package com.biasnil.audioforge.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.AppLanguage
import com.biasnil.audioforge.AudioForgeApplication
import com.biasnil.audioforge.R
import com.biasnil.audioforge.ui.theme.AudioForgeTheme
import com.biasnil.audioforge.ui.theme.isDark
import com.biasnil.audioforge.ui.theme.themeStyle

/**
 * A widget's own settings (long-press it on the home screen, then its
 * settings / pencil button): its background. Adding a widget doesn't open
 * this (configuration_optional); it starts with Cover colours, or with the
 * background picked in Settings -> Widgets.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, result)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val container = (application as AudioForgeApplication).container
        val widgets = container.widgets

        setContent {
            val settings by container.store.settings.collectAsStateWithLifecycle()
            var choice by remember { mutableStateOf(widgets.backgrounds[widgetId]) }
            AudioForgeTheme(darkTheme = isDark(settings.appearance), style = settings.themeStyle) {
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text(stringResource(R.string.widgets_background)) },
                    text = {
                        Column {
                            WidgetBackground.entries.forEach { option ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .selectable(selected = option == choice, role = Role.RadioButton) { choice = option },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = option == choice, onClick = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(stringResource(option.label))
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            widgets.backgrounds[widgetId] = choice
                            widgets.refresh()
                            setResult(RESULT_OK, result)
                            finish()
                        }) { Text(stringResource(R.string.done)) }
                    },
                    dismissButton = { TextButton(onClick = ::finish) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
    }
}

/** The background's name in Settings -> Widgets and the widget's settings. */
val WidgetBackground.label: Int
    get() = when (this) {
        WidgetBackground.CoverColours -> R.string.widgets_background_cover
        WidgetBackground.Black -> R.string.widgets_background_black
        WidgetBackground.Translucent -> R.string.widgets_background_translucent
    }
