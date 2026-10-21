package com.biasnil.audioforge.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.R
import com.biasnil.audioforge.widget.WidgetBackground
import com.biasnil.audioforge.widget.WidgetStyle
import com.biasnil.audioforge.widget.label

/**
 * Settings -> Widgets: every home-screen widget style (all free), each with
 * a live preview of what's playing and an Add button that asks the launcher
 * to place it. The background picked here goes to the widgets added from
 * here; each widget's own settings on the home screen can change it later.
 */
@Composable
fun WidgetsPage(onBack: () -> Unit) {
    val widgets = LocalAppContainer.current.widgets
    val snapshot by widgets.snapshot.collectAsStateWithLifecycle()
    var background by rememberSaveable { mutableStateOf(WidgetBackground.DEFAULT) }
    var pinFailed by rememberSaveable { mutableStateOf(false) }
    val canPin = remember { widgets.canPin }

    Scaffold(topBar = { DetailTopBar(stringResource(R.string.widgets), onBack) }) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.widgets_intro),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (!canPin || pinFailed) {
                Text(
                    text = stringResource(R.string.widgets_pin_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(12.dp),
                )
            }

            SectionHeader(stringResource(R.string.widgets_background))
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                val options = WidgetBackground.entries
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = background == option,
                        onClick = { background = option },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(stringResource(option.label), maxLines = 1) }
                }
            }
            Text(
                text = stringResource(R.string.widgets_background_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            BoxWithConstraints(Modifier.padding(horizontal = 16.dp)) {
                // Home-screen cells: a quarter of the width, at most about a phone's cell height.
                val cellWidth = maxWidth / 4
                val cellHeight = minOf(cellWidth, 88.dp)
                Column {
                    WidgetStyle.entries.forEach { style ->
                        val name = stringResource(style.title)
                        Row(Modifier.padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = stringResource(R.string.widgets_size, style.columns, style.rows) + "  •  " +
                                        stringResource(style.description),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (canPin) {
                                Spacer(Modifier.width(12.dp))
                                FilledTonalButton(onClick = { pinFailed = !widgets.requestPin(style, background) }) {
                                    Text(stringResource(R.string.widgets_add))
                                }
                            }
                        }
                        WidgetPreview(
                            style = style,
                            background = background,
                            name = name,
                            width = cellWidth * style.columns,
                            height = cellHeight * style.rows,
                            key = snapshot,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The widget itself: the same RemoteViews the home screen gets, inflated
 * here. Redrawn when what's playing changes; its buttons don't react
 * (a preview, not a remote control).
 */
@Composable
private fun WidgetPreview(style: WidgetStyle, background: WidgetBackground, name: String, width: Dp, height: Dp, key: Any) {
    val widgets = LocalAppContainer.current.widgets
    val views by produceState<RemoteViews?>(null, style, background, key) {
        value = widgets.preview(style, background)
    }
    val description = stringResource(R.string.widgets_preview_of, name)
    Box(Modifier.size(width, height)) {
        AndroidView(
            factory = { context ->
                FrameLayout(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            },
            update = { frame ->
                val remote = views ?: return@AndroidView
                frame.removeAllViews()
                frame.addView(remote.apply(frame.context, frame))
            },
            modifier = Modifier.fillMaxSize(),
        )
        // On top, so touches stop here instead of reaching the widget's buttons.
        Box(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(Unit) {}
        )
    }
    Spacer(Modifier.height(4.dp))
}

private val WidgetStyle.title: Int
    get() = when (this) {
        WidgetStyle.Mini -> R.string.widget_style_mini
        WidgetStyle.Lite -> R.string.widget_style_lite
        WidgetStyle.Classic -> R.string.widget_style_classic
        WidgetStyle.Simple -> R.string.widget_style_simple
        WidgetStyle.Standard -> R.string.widget_style_standard
        WidgetStyle.Stylish -> R.string.widget_style_stylish
        WidgetStyle.Practical -> R.string.widget_style_practical
        WidgetStyle.FeatureRich -> R.string.widget_style_feature_rich
    }

private val WidgetStyle.description: Int
    get() = when (this) {
        WidgetStyle.Mini -> R.string.widget_desc_mini
        WidgetStyle.Lite -> R.string.widget_desc_lite
        WidgetStyle.Classic -> R.string.widget_desc_classic
        WidgetStyle.Simple -> R.string.widget_desc_simple
        WidgetStyle.Standard -> R.string.widget_desc_standard
        WidgetStyle.Stylish -> R.string.widget_desc_stylish
        WidgetStyle.Practical -> R.string.widget_desc_practical
        WidgetStyle.FeatureRich -> R.string.widget_desc_feature_rich
    }
