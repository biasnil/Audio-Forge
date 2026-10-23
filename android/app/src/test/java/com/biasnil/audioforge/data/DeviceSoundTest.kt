package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSoundTest {

    private val speaker = OutputDevice(OutputKind.Speaker, "")
    private val buds = OutputDevice(OutputKind.Bluetooth, "Galaxy Buds2 Pro")
    private val car = OutputDevice(OutputKind.Bluetooth, "MY CAR")
    private val bass = List(AppSettings.EQ_BAND_COUNT) { if (it < 2) 6f else 0f }
    private val flat = List(AppSettings.EQ_BAND_COUNT) { 0f }

    @Test
    fun keysTellDevicesApart() {
        assertEquals("speaker", speaker.key)
        assertEquals("bt:Galaxy Buds2 Pro", buds.key)
        assertTrue(buds.key != car.key)
        assertTrue(OutputDevice(OutputKind.Usb, "Galaxy Buds2 Pro").key != buds.key)
    }

    @Test
    fun doesNothingWhileOff() {
        val settings = AppSettings(eqEnabled = true, eqBandGainsDb = bass)
        assertSame(settings, rememberDeviceSound(settings, buds))
        assertSame(settings, switchDeviceSound(settings, car))
    }

    @Test
    fun eachDeviceGetsItsOwnEq() {
        var settings = AppSettings(deviceSoundEnabled = true, eqEnabled = true, eqBandGainsDb = bass)
        settings = rememberDeviceSound(settings, buds) // bass on the buds

        settings = switchDeviceSound(settings, car) // new device: keeps the current EQ...
        assertEquals(bass, settings.eqBandGainsDb)
        settings = settings.copy(eqEnabled = false, eqBandGainsDb = flat) // ...then the car is set flat
        settings = rememberDeviceSound(settings, car)

        settings = switchDeviceSound(settings, buds)
        assertTrue(settings.eqEnabled)
        assertEquals(bass, settings.eqBandGainsDb)

        settings = switchDeviceSound(settings, car)
        assertEquals(false, settings.eqEnabled)
        assertEquals(flat, settings.eqBandGainsDb)
        assertEquals(listOf(buds.key, car.key), settings.deviceSounds.keys.toList())
    }

    @Test
    fun headphoneCorrectionFollowsTheDevice() {
        val hd650 = HeadphoneProfile("Sennheiser HD 650", "oratory1990", "over-ear", -6.1,
            listOf(ParametricFilter(ParametricFilter.Type.Peaking, 1000.0, 1.0, 1.0)))
        var settings = AppSettings(deviceSoundEnabled = true, headphoneCorrection = hd650)
        settings = rememberDeviceSound(settings, OutputDevice(OutputKind.Wired, ""))
        settings = switchDeviceSound(settings, speaker).copy(headphoneCorrection = null)
        settings = rememberDeviceSound(settings, speaker)
        assertEquals(null, settings.headphoneCorrection)
        settings = switchDeviceSound(settings, OutputDevice(OutputKind.Wired, ""))
        assertEquals(hd650, settings.headphoneCorrection)
    }

    @Test
    fun rememberingTwiceChangesNothing() {
        val settings = rememberDeviceSound(AppSettings(deviceSoundEnabled = true), speaker)
        assertSame(settings, rememberDeviceSound(settings, speaker))
    }
}
