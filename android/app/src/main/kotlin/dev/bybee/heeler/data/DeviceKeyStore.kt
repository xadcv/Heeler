package dev.bybee.heeler.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.bybee.heeler.ssh.Ed25519
import dev.bybee.heeler.ssh.Ed25519Identity
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Device Key: one Ed25519 key pair generated on this device, never
 * exported. The Android Keystore cannot sign SSH's Ed25519 challenges itself,
 * so the seed is wrapped at rest by a hardware-backed AES-GCM key that never
 * leaves the Keystore, and unwrapped only into process memory to sign.
 *
 * File layout: 12-byte GCM nonce || ciphertext(seed) || 16-byte tag.
 */
class DeviceKeyStore(context: Context) {
    private val file = File(context.filesDir, "device_key.v1")

    /** The identity SSH authenticates with; created on first use. */
    fun identity(): Ed25519Identity = Ed25519Identity(IDENTITY_NAME, key())

    /** One `authorized_keys` line for the Host's `~/.ssh/authorized_keys`. */
    fun authorizedKeysLine(): String = key().authorizedKeysLine(IDENTITY_NAME)

    /** The fingerprint a Host-side audit prints for this key. */
    fun fingerprint(): String = identity().fingerprint.displayString

    val exists: Boolean get() = file.exists()

    @Synchronized
    private fun key(): Ed25519 {
        val seed = if (file.exists()) unwrap(file.readBytes()) else Ed25519.generateSeed().also { file.writeBytes(wrap(it)) }
        return Ed25519(seed)
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(WRAPPING_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(WRAPPING_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(seed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return cipher.iv + cipher.doFinal(seed)
    }

    private fun unwrap(stored: ByteArray): ByteArray {
        require(stored.size > NONCE_BYTES) { "device key file too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, stored, 0, NONCE_BYTES))
        return cipher.doFinal(stored, NONCE_BYTES, stored.size - NONCE_BYTES)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAPPING_ALIAS = "dev.bybee.heeler.device-key-wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val IDENTITY_NAME = "heeler-android"
    }
}
