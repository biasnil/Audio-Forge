package com.biasnil.audioforge.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.annotation.RawRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.biasnil.audioforge.BuildConfig
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.ACKNOWLEDGEMENTS
import com.biasnil.audioforge.data.AboutLinks
import com.biasnil.audioforge.data.License
import com.biasnil.audioforge.data.UpdateState
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The About group at the bottom of Settings, laid out like the iOS app's:
 * Report a Problem, Source Code, Terms, Privacy, Acknowledgements, and the
 * version (tap to check for updates).
 */
@Composable
fun AboutSection(onOpenPage: (Page) -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.store.settings.collectAsStateWithLifecycle()
    val update by container.updates.state.collectAsStateWithLifecycle()
    val upToDate = stringResource(R.string.update_up_to_date)
    val failed = stringResource(R.string.update_failed)
    val describe = stringResource(R.string.report_describe)
    val emailCopied = stringResource(R.string.email_copied)

    // The automatic check (at most once a day), when Settings opens.
    LaunchedEffect(Unit) { container.updates.checkIfDue() }

    SectionHeader(stringResource(R.string.settings_about))
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        AboutRow(AppIcons.Feedback, stringResource(R.string.about_report), accent = true) {
            reportProblem(context, describe, emailCopied)
        }
        AboutDivider()
        AboutRow(AppIcons.Code, stringResource(R.string.about_source), accent = true) {
            openLink(context, AboutLinks.REPO_URL)
        }
        AboutDivider()
        AboutRow(AppIcons.Description, stringResource(R.string.about_terms)) { onOpenPage(Page.Terms) }
        AboutDivider()
        AboutRow(AppIcons.PanTool, stringResource(R.string.about_privacy)) { onOpenPage(Page.Privacy) }
        AboutDivider()
        AboutRow(AppIcons.HeartOutline, stringResource(R.string.about_acknowledgements)) { onOpenPage(Page.Acknowledgements) }
        AboutDivider()

        val available = update as? UpdateState.Available
        val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = BuildConfig.UPDATE_CHECK_ENABLED && update != UpdateState.Checking) {
                    if (available != null) {
                        openLink(context, available.url)
                    } else {
                        scope.launch {
                            when (container.updates.check()) {
                                UpdateState.UpToDate -> Toast.makeText(context, upToDate, Toast.LENGTH_SHORT).show()
                                UpdateState.Failed -> Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                                else -> Unit // an update: the row itself says so
                            }
                        }
                    }
                }
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.about_version), modifier = Modifier.weight(1f))
            Text(
                text = when {
                    update == UpdateState.Checking -> stringResource(R.string.update_checking)
                    available != null -> stringResource(R.string.update_available, available.version)
                    else -> version
                },
                color = if (available != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (BuildConfig.UPDATE_CHECK_ENABLED) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.update_auto), modifier = Modifier.weight(1f))
            Switch(
                checked = settings.autoUpdateCheck,
                onCheckedChange = { on -> container.store.update { it.copy(autoUpdateCheck = on) } },
            )
        }
        Text(
            text = stringResource(R.string.update_auto_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** One row of the About card: one TalkBack target, at least 48 dp tall. */
@Composable
private fun AboutRow(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    val color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, color = color, modifier = Modifier.weight(1f))
        if (!accent) Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * Opens an email to the developer with the app, Android and device
 * versions filled in (no logs, file names or library data). The subject
 * and the device block stay in English so every report can be read.
 * With no email app, the address is copied instead.
 */
private fun reportProblem(context: Context, describeLine: String, copiedMessage: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(AboutLinks.SUPPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "AudioForge Android – problem report")
        putExtra(
            Intent.EXTRA_TEXT,
            """
            |$describeLine
            |
            |
            |---
            |App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            |Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            |Device: ${Build.MANUFACTURER} ${Build.MODEL}
            |Language: ${Locale.getDefault().toLanguageTag()}
            """.trimMargin(),
        )
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("email", AboutLinks.SUPPORT_EMAIL))
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }
}

/** Opens [url] in the browser. */
internal fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.no_browser), Toast.LENGTH_SHORT).show()
    }
}

// --- Pages ----------------------------------------------------------------------

/** Terms & Conditions. The English text applies; other languages get a note saying so. */
@Composable
fun TermsPage(onBack: () -> Unit) {
    val english = LocalConfiguration.current.locales[0].language == "en"
    TextPage(
        title = stringResource(R.string.about_terms),
        textRes = R.raw.terms,
        onBack = onBack,
        note = if (english) null else stringResource(R.string.terms_english_applies),
    )
}

/** Privacy, translated (res/raw-*). */
@Composable
fun PrivacyPage(onBack: () -> Unit) {
    TextPage(title = stringResource(R.string.about_privacy), textRes = R.raw.privacy, onBack = onBack)
}

/** What the app is built with; tap a library for its licence, a service for its website. */
@Composable
fun AcknowledgementsPage(onBack: () -> Unit, onOpenPage: (Page) -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = { DetailTopBar(stringResource(R.string.about_acknowledgements), onBack) }) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            ) {
                ACKNOWLEDGEMENTS.forEachIndexed { index, entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val license = entry.license
                                if (license != null) onOpenPage(Page.LicenseText(license.id)) else openLink(context, entry.url)
                            }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name)
                            Text(
                                text = entry.license?.title ?: stringResource(R.string.ack_service_thanks),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (index < ACKNOWLEDGEMENTS.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The full text of a licence, with the libraries that use it. */
@Composable
fun LicenseTextPage(id: String, onBack: () -> Unit) {
    val license = License.entries.firstOrNull { it.id == id } ?: License.Apache2
    val users = ACKNOWLEDGEMENTS.filter { it.license == license }
    TextPage(
        title = license.title,
        textRes = when (license) {
            License.Apache2 -> R.raw.apache_2_0
            License.Lgpl21 -> R.raw.lgpl_2_1
            License.Mit -> R.raw.mit
        },
        onBack = onBack,
        note = users.joinToString("\n") { "${it.name}\n${it.url}" },
    )
}

/**
 * A scrolling page of bundled text (res/raw), so it works offline. A tiny
 * format: a line starting with "# " is a heading, blank lines separate
 * paragraphs, and lines within a paragraph are joined (the licence texts
 * are wrapped for 80-column screens).
 */
@Composable
private fun TextPage(title: String, @RawRes textRes: Int, onBack: () -> Unit, note: String? = null) {
    val context = LocalContext.current
    val blocks = remember(textRes, LocalConfiguration.current) {
        val text = context.resources.openRawResource(textRes).bufferedReader().use { it.readText() }
        parseTextBlocks(text)
    }
    Scaffold(topBar = { DetailTopBar(title, onBack) }) { innerPadding ->
        Column(
            Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (!note.isNullOrEmpty()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            blocks.forEach { block ->
                when (block) {
                    is TextBlock.Heading -> Text(
                        text = block.text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    )
                    is TextBlock.Paragraph -> Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
        }
    }
}

sealed interface TextBlock {
    data class Heading(val text: String) : TextBlock
    data class Paragraph(val text: String) : TextBlock
}

/** "# Title" lines become headings; blank lines end paragraphs; a paragraph's lines are joined with spaces. */
fun parseTextBlocks(text: String): List<TextBlock> {
    val blocks = ArrayList<TextBlock>()
    val paragraph = ArrayList<String>()
    fun endParagraph() {
        if (paragraph.isNotEmpty()) blocks += TextBlock.Paragraph(paragraph.joinToString(" "))
        paragraph.clear()
    }
    for (raw in text.replace("\r\n", "\n").lines()) {
        val line = raw.trim()
        when {
            line.startsWith("# ") -> {
                endParagraph()
                blocks += TextBlock.Heading(line.removePrefix("# ").trim())
            }
            line.isBlank() -> endParagraph()
            else -> paragraph += line
        }
    }
    endParagraph()
    return blocks
}
