package com.biasnil.audioforge.ui

import android.app.Activity
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.Track
import kotlinx.coroutines.launch

/** The user backed out of a permission prompt -- nothing to report. */
object WriteCancelled : Exception()

/** Wrong folder picked when re-granting folder access. */
object WrongFolderPicked : Exception()

/**
 * Changes a song's file (tags or cover), with everything that involves:
 *  - Android's "Allow AudioForge to modify this audio file?" prompt for
 *    songs from the phone's library;
 *  - a one-time re-pick of an added folder that only has read access;
 *  - releasing the song if it's playing and resuming it afterwards (the
 *    desktop's writeTrackFile()), then refreshing its tags and cover.
 *
 * Returns a function: write(track, action, onResult). onResult gets null on
 * success, [WriteCancelled] if the user declined, or the error.
 */
@Composable
fun rememberTrackFileWriter(): (Track, suspend () -> Unit, (Throwable?) -> Unit) -> Unit {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    class Pending(val track: Track, val action: suspend () -> Unit, val onResult: (Throwable?) -> Unit)
    var pending by remember { mutableStateOf<Pending?>(null) }
    var askFolderAccess by remember { mutableStateOf(false) }

    val run: (Pending) -> Unit = { job ->
        pending = null
        scope.launch {
            val hold = container.playback.releaseFileForEdit(job.track.uri)
            val result = runCatching { job.action() }
            val updated = container.library.reloadTrack(job.track)
            container.coverArt.invalidate(job.track.uri)
            container.playback.resumeAfterFileEdit(updated, hold)
            job.onResult(result.exceptionOrNull())
        }
    }
    val cancel: () -> Unit = {
        pending?.onResult?.invoke(WriteCancelled)
        pending = null
    }

    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val job = pending
        if (job != null && result.resultCode == Activity.RESULT_OK) run(job) else cancel()
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        val job = pending
        val folder = job?.track?.sourceFolder
        when {
            job == null || folder == null || picked == null -> cancel()
            container.library.grantFolderWriteAccess(picked, folder) -> run(job)
            else -> {
                job.onResult(WrongFolderPicked)
                pending = null
            }
        }
    }

    if (askFolderAccess) {
        AlertDialog(
            onDismissRequest = {
                askFolderAccess = false
                cancel()
            },
            title = { Text(stringResource(R.string.tags_folder_access_title)) },
            text = { Text(stringResource(R.string.tags_folder_access_text)) },
            confirmButton = {
                TextButton(onClick = {
                    askFolderAccess = false
                    folderPicker.launch(pending?.track?.sourceFolder?.let(Uri::parse))
                }) { Text(stringResource(R.string.tags_folder_access_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    askFolderAccess = false
                    cancel()
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    val context = LocalContext.current
    return remember(container) {
        { track, action, onResult ->
            val job = Pending(track, action, onResult)
            pending = job
            val folder = track.sourceFolder
            when {
                folder == null -> {
                    val request = MediaStore.createWriteRequest(context.contentResolver, listOf(Uri.parse(track.uri)))
                    writeRequest.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
                !container.library.hasFolderWriteAccess(folder) -> askFolderAccess = true
                else -> run(job)
            }
        }
    }
}
