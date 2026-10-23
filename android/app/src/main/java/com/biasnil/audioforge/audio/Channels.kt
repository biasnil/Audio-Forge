package com.biasnil.audioforge.audio

/**
 * Effects -> Listening: mono (both ears get the same mix -- for one
 * earbud, or hearing in one ear) and left/right balance. Shared by both
 * players' processors like [EqualizerSettings].
 */
class ChannelSettings {
    @Volatile
    var mono = false
        private set

    /** -1 = left only, 0 = centre, 1 = right only. */
    @Volatile
    var balance = 0f
        private set

    fun update(mono: Boolean, balance: Float) {
        this.mono = mono
        this.balance = balance.coerceIn(-1f, 1f)
    }

    val isNeutral: Boolean get() = !mono && balance == 0f
}

/**
 * Mono, then balance, on the first two channels of [frame]. Balance turns
 * the other side down (it never turns a side up, so it can't clip).
 */
fun applyChannels(frame: DoubleArray, mono: Boolean, balance: Float) {
    if (frame.size < 2) return
    if (mono) {
        val mid = (frame[0] + frame[1]) * 0.5
        frame[0] = mid
        frame[1] = mid
    }
    if (balance > 0f) frame[0] *= (1f - balance).toDouble()
    if (balance < 0f) frame[1] *= (1f + balance).toDouble()
}
