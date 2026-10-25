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

    val sampleSlots: List<Int> get() = VoiceEnrollmentSlots.occupied((1..3).filter { sampleFile(it).isFile && sampleFile(it).length() > 0L }.toSet())
    val sampleCount: Int get() = sampleSlots.size
    val isRegistered: Boolean get() = sampleCount == 3
    fun nextAvailableSlot(): Int? = VoiceEnrollmentSlots.firstAvailable(sampleSlots.toSet())
    fun hasSample(slot: Int): Boolean = slot in 1..3 && sampleFile(slot).isFile && sampleFile(slot).length() > 0L
    fun deleteSample(slot: Int): Boolean {
        require(slot in 1..3) { "Номер образца должен быть 1..3" }
        val file = sampleFile(slot)
        if (!file.exists()) return false
        val deleted = file.delete()
        if (deleted) {
            if (sampleCount == 0) prefs.edit().remove("label").apply()
            VoiceSecurityAudit.record(context, "enrollment_sample_deleted", "slot=$slot")
        }
        return deleted
    }
    private fun sampleFile(slot: Int): File = File(folder, "sample_$slot.bin")
    val registrationLabel: String get() = prefs.getString("label", "") ?: ""

    fun saveEnrollmentClip(slot: Int, pcmOrContainer: ByteArray) {
        require(slot in 1..3)
        require(pcmOrContainer.size in 64_000..384_000) { "Ожидается PCM16 mono 16 кГц длительностью 2–12 секунд" }
        require(!hasSample(slot)) { "Слот $slot уже занят. Сначала удалите этот образец." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(pcmOrContainer)
        FileOutputStream(sampleFile(slot)).use { out ->
            out.write(byteArrayOf(cipher.iv.size.toByte()))
            out.write(cipher.iv)
            out.write(encrypted)
        }
        prefs.edit().putString("label", "Владелец").apply()
        VoiceSecurityAudit.record(context, "enrollment_sample_saved", "slot=$slot; material only, not verified identity")
    }

    /** Explicit user reset erases encrypted exemplars and the local key. */
    fun deleteProfile() {
        (1..3).forEach { sampleFile(it).delete() }
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
