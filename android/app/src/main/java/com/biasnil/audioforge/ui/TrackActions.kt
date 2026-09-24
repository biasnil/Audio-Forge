package com.biasnil.audioforge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Track
import kotlinx.coroutines.launch

/** What long-pressing a song offers, available anywhere below [AudioForgeApp]. */
class TrackActions(
    /** The long-press menu: Edit tags / Change cover. */
    val showMenu: (Track) -> Unit,
    /** Straight to the image picker (the tag editor's "Change cover" button). */
    val changeCover: (Track) -> Unit,
)

val LocalTrackActions = staticCompositionLocalOf<TrackActions> { error("TrackActions not provided") }

/**
 * The long-press menu, plus the "Change cover" flow: pick an image with the
 * system photo picker (no storage permission needed), then write it into
 * the song -- the desktop's "Change Cover..." button.
 */
@Composable
fun rememberTrackActions(onOpenPage: (Page) -> Unit, showMessage: (String) -> Unit): TrackActions {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val writeFile = rememberTrackFileWriter()

    var menuTrackUri by rememberSaveable { mutableStateOf<String?>(null) }
    var coverTrackUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun trackFor(uri: String): Track? =
        container.library.trackFor(uri) ?: container.playback.state.value.current?.takeIf { it.uri == uri }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { imageUri ->
        val target = coverTrackUri?.let(::trackFor)
        coverTrackUri = null
        if (imageUri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val image = try {
                container.tagEditor.prepareCoverImage(imageUri)
            } catch (e: Exception) {
                showMessage(context.getString(R.string.cover_error_image))
                return@launch
            }
            writeFile(target, { container.tagEditor.writeCover(target, image) }) { error ->
                when (error) {
                    null -> showMessage(context.getString(R.string.cover_changed))
                    WriteCancelled -> Unit
                    else -> showMessage(errorMessage(context, error))
                }
            }
        }
    }
    val changeCover: (Track) -> Unit = { track ->
        coverTrackUri = track.uri
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    menuTrackUri?.let { uri ->
        val track = trackFor(uri)
        if (track == null) {
            menuTrackUri = null
        } else {
            AlertDialog(
                onDismissRequest = { menuTrackUri = null },
                title = { Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                menuTrackUri = null
                                onOpenPage(Page.EditTags(track.uri))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.tags_edit)) }
                        TextButton(
                            onClick = {
                                menuTrackUri = null
                                changeCover(track)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.cover_change)) }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { menuTrackUri = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }

    return remember { TrackActions(showMenu = { menuTrackUri = it.uri }, changeCover = changeCover) }
}
