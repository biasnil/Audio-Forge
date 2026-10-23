package com.biasnil.audioforge.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi

/** What Effects -> USB DAC shows. */
sealed interface BitPerfectState {
    /** The option is off (or the phone is older than Android 14). */
    data object Off : BitPerfectState
    /** On, but no plugged-in USB device offers bit-perfect output. */
    data object NoDevice : BitPerfectState
    /** On, but the speed or key is changed, so the sound can't go out untouched. */
    data object Blocked : BitPerfectState
    /** On, but the DAC doesn't offer this song's sample rate / channels bit-perfect. */
    data class Unsupported(val sampleRate: Int, val channels: Int) : BitPerfectState
    /** The song goes to the DAC untouched. */
    data class Active(val sampleRate: Int, val channels: Int) : BitPerfectState
}

/** One of a USB device's mixer setups (from AudioMixerAttributes). */
data class MixerOption(val sampleRate: Int, val channels: Int, val bits: Int, val bitPerfect: Boolean)

/**
 * The bit-perfect setup for a 16-bit song at [sampleRate] with [channels]
 * (the player decodes to 16-bit), or null if the device has none. Only an
 * exact match is bit-perfect: anything else would be resampled or mixed.
 */
fun chooseBitPerfect(options: List<MixerOption>, sampleRate: Int, channels: Int): MixerOption? =
    options.firstOrNull { it.bitPerfect && it.sampleRate == sampleRate && it.channels == channels && it.bits == 16 }

/**
 * Android 14's bit-perfect USB output (AudioMixerAttributes with
 * MIXER_BEHAVIOR_BIT_PERFECT): asks Android to send music to a USB DAC
 * with no resampling, mixing or volume applied. Main thread only.
 */
class BitPerfectOutput(context: Context, onDevicesChanged: () -> Unit) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    /** The device and setup last asked for, so it isn't asked again for every change. */
    private var requested: Pair<Int, MixerOption>? = null

    init {
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.id == requested?.first }) requested = null
                onDevicesChanged()
            }
        }, Handler(Looper.getMainLooper()))
    }

    /** True if a plugged-in USB device offers bit-perfect output at all. */
    fun hasDevice(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && usbDevice() != null

    /**
     * Asks for bit-perfect output of 16-bit [sampleRate] / [channels] audio;
     * true if Android agreed (it's then used for the next audio the player
     * starts writing).
     */
    fun request(sampleRate: Int, channels: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return requestV34(sampleRate, channels)
    }

    /** Back to normal (mixed) output. */
    fun release() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        releaseV34()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun usbDevice(): Pair<AudioDeviceInfo, List<AudioMixerAttributes>>? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
            .firstNotNullOfOrNull { device ->
                val options = audioManager.getSupportedMixerAttributes(device)
                    .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
                if (options.isEmpty()) null else device to options
            }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun requestV34(sampleRate: Int, channels: Int): Boolean {
        val (device, attributes) = usbDevice() ?: return false
        val options = attributes.map { it.toOption() }
        val chosen = chooseBitPerfect(options, sampleRate, channels) ?: return false
        if (requested == device.id to chosen) return true
        val ok = try {
            audioManager.setPreferredMixerAttributes(MUSIC, device, attributes[options.indexOf(chosen)])
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't set bit-perfect output", e)
            false
        }
        requested = if (ok) device.id to chosen else null
        return ok
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun releaseV34() {
        val deviceId = requested?.first ?: return
        requested = null
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId } ?: return
        try {
            audioManager.clearPreferredMixerAttributes(MUSIC, device)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't clear bit-perfect output", e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun AudioMixerAttributes.toOption() = MixerOption(
        sampleRate = format.sampleRate,
        channels = format.channelCount,
        bits = when (format.encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> 0
        },
        bitPerfect = mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
    )

    private companion object {
        const val TAG = "BitPerfectOutput"
        val MUSIC: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
