package com.iaandroid17.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores owner enrollment clips encrypted with an Android Keystore AES-GCM key.
 * It is storage protection only, NOT speaker verification. Never treat this profile as an identity proof.
 */
class OwnerVoiceProfile(private val context: Context) {
    private val folder = File(context.noBackupFilesDir, "owner_voice").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("owner_voice_profile", Context.MODE_PRIVATE)

    val isRegistered: Boolean get() = (1..3).all { File(folder, "sample_$it.bin").exists() }
    val sampleCount: Int get() = (1..3).count { File(folder, "sample_$it.bin").exists() }
    val registrationLabel: String get() = prefs.getString("label", "") ?: ""

    fun saveEnrollmentClip(slot: Int, pcmOrContainer: ByteArray) {
        require(slot in 1..3)
        require(pcmOrContainer.size in 2048..12_000_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(pcmOrContainer)
        FileOutputStream(File(folder, "sample_$slot.bin")).use { out ->
            out.write(byteArrayOf(cipher.iv.size.toByte()))
            out.write(cipher.iv)
            out.write(encrypted)
        }
        prefs.edit().putString("label", "Владелец").apply()
        VoiceSecurityAudit.record(context, "enrollment_sample_saved", "slot=$slot; material only, not verified identity")
    }

    /** Explicit user reset erases encrypted exemplars and the local key. */
    fun deleteProfile() {
        (1..3).forEach { File(folder, "sample_$it.bin").delete() }
        folder.listFiles()?.forEach { it.delete() }
        prefs.edit().clear().apply()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) { }
        VoiceSecurityAudit.record(context, "owner_profile_deleted", "local voice enrollment reset")
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object { private const val KEY_ALIAS = "ia_owner_voice_profile_v1" }
}
