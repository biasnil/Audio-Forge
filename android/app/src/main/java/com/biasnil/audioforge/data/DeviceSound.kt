package com.biasnil.audioforge.data

/** What kind of output the music is going to; decides the name shown for it. */
enum class OutputKind { Speaker, Wired, Bluetooth, Usb, Other }

/**
 * Where the music is playing: the phone speaker, wired headphones, or a
 * Bluetooth / USB device by its product name (a car is a Bluetooth device
 * too). [key] identifies it across connections.
 */
data class OutputDevice(val kind: OutputKind, val productName: String) {
    val key: String
        get() = when (kind) {
            OutputKind.Speaker -> "speaker"
            OutputKind.Wired -> "wired"
            OutputKind.Bluetooth -> "bt:$productName"
            OutputKind.Usb -> "usb:$productName"
            OutputKind.Other -> "other:$productName"
        }
}

/**
 * The sound settings remembered for one output device when "A separate
 * EQ for each device" is on: the EQ and the headphone correction.
 */
data class DeviceSound(
    val device: OutputDevice,
    val eqEnabled: Boolean,
    val eqBandGainsDb: List<Float>,
    val eqPostGainDb: Float,
    val headphoneCorrection: HeadphoneProfile?,
)

/** The live settings, as they'd be remembered for [device]. */
fun AppSettings.soundFor(device: OutputDevice) = DeviceSound(
    device = device,
    eqEnabled = eqEnabled,
    eqBandGainsDb = eqBandGainsDb,
    eqPostGainDb = eqPostGainDb,
    headphoneCorrection = headphoneCorrection,
)

/**
 * Keeps [device]'s entry in step with the live settings: call it after any
 * change while [device] is playing. Unchanged settings come back as is.
 */
fun rememberDeviceSound(settings: AppSettings, device: OutputDevice): AppSettings {
    if (!settings.deviceSoundEnabled) return settings
    val sound = settings.soundFor(device)
    if (settings.deviceSounds[device.key] == sound) return settings
    return settings.copy(deviceSounds = settings.deviceSounds + (device.key to sound))
}

/**
 * The music moved to [device]: its remembered settings become the live
 * ones. A device seen for the first time keeps the current settings (and
 * remembers them from now on).
 */
fun switchDeviceSound(settings: AppSettings, device: OutputDevice): AppSettings {
    if (!settings.deviceSoundEnabled) return settings
    val saved = settings.deviceSounds[device.key] ?: return rememberDeviceSound(settings, device)
    return settings.copy(
        eqEnabled = saved.eqEnabled,
        eqBandGainsDb = saved.eqBandGainsDb,
        eqPostGainDb = saved.eqPostGainDb,
        headphoneCorrection = saved.headphoneCorrection,
    )
}
