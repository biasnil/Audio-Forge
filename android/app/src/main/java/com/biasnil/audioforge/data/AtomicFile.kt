package com.biasnil.audioforge.data

import java.io.File

/**
 * Writes [text] to "[file].tmp", then renames it over [file], so a crash
 * mid-write never leaves a truncated file -- the one place every store's
 * save goes through. Returns false if the rename failed (the old file is
 * still there). A failed write throws, after removing the half-written
 * temporary file.
 */
fun writeAtomically(file: File, text: String): Boolean {
    val temp = File(file.parentFile, file.name + ".tmp")
    try {
        temp.writeText(text)
    } catch (e: Exception) {
        temp.delete()
        throw e
    }
    if (temp.renameTo(file)) return true
    temp.delete()
    return false
}

/** [writeAtomically] for bytes (cached covers). */
fun writeAtomically(file: File, bytes: ByteArray): Boolean {
    val temp = File(file.parentFile, file.name + ".tmp")
    try {
        temp.writeBytes(bytes)
    } catch (e: Exception) {
        temp.delete()
        throw e
    }
    if (temp.renameTo(file)) return true
    temp.delete()
    return false
}
