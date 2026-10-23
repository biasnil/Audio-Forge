package com.biasnil.audioforge.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.biasnil.audioforge.data.AppStore
import com.biasnil.audioforge.data.OutputDevice
import com.biasnil.audioforge.data.OutputKind
import com.biasnil.audioforge.data.rememberDeviceSound
import com.biasnil.audioforge.data.switchDeviceSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Follows where music would play right now -- speaker, wired, Bluetooth,
 * USB -- and, while "A separate EQ for each device" is on, swaps in that
 * device's EQ and headphone correction when it changes, and keeps its
 * entry up to date as the settings are changed. Main thread only.
 */
class OutputDeviceMonitor(context: Context, private val store: AppStore, scope: CoroutineScope) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _device = MutableStateFlow(currentDevice())
    /** The device music is going to now. */
    val device: StateFlow<OutputDevice> = _device.asStateFlow()

    init {
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = check()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = check()
        }, Handler(Looper.getMainLooper()))
        // A device seen at startup (or when the option is switched on) is remembered right away.
        scope.launch {
            store.settings.collect { settings ->
                val updated = rememberDeviceSound(settings, _device.value)
                if (updated !== settings) store.update { rememberDeviceSound(it, _device.value) }
            }
        }
    }

    private fun check() {
        val now = currentDevice()
        if (now == _device.value) return
        _device.value = now
        store.update { switchDeviceSound(it, now) }
    }

    private fun currentDevice(): OutputDevice {
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audioManager.getAudioDevicesForAttributes(MUSIC).firstOrNull()?.let { info ->
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == info.id }
            }
        } else null
        // Before Android 13 (or if the route isn't known): what Android prefers for media.
        val info = routed ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
            .minByOrNull { priority(it.type) }
        return info?.toOutputDevice() ?: OutputDevice(OutputKind.Speaker, "")
    }

    private fun AudioDeviceInfo.toOutputDevice(): OutputDevice {
        val name = productName?.toString()?.trim().orEmpty()
        val kind = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputKind.Speaker
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG -> OutputKind.Wired
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> OutputKind.Bluetooth
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.Usb
            else -> OutputKind.Other
        }
        // The speaker and wired jacks report the phone's model as their name; that's not useful.
        return OutputDevice(kind, if (kind == OutputKind.Speaker || kind == OutputKind.Wired) "" else name)
    }

    /** Lower first: Bluetooth, then USB, then wired, then the speaker. */
    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID -> 0
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 1
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 2
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 3
        else -> 4
    }

    private companion object {
        val MUSIC: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
