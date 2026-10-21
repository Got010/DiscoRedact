package app.discoredact.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.discoredact.core.executor.CredentialCodec
import app.discoredact.core.executor.CredentialStatus
import app.discoredact.core.executor.CredentialStore
import app.discoredact.core.executor.HeaderSet
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The header set, AES-GCM under an Android Keystore key (spec section 7). The key never leaves the Keystore; the
 * ciphertext lives in a private file that backup and device transfer exclude. Nothing here is ever logged, and
 * every failure reads as "no credentials" rather than an error carrying content (CLAUDE.md rule 9).
 */
class KeystoreCredentialStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) : CredentialStore {
    private val file = File(context.applicationContext.filesDir, FILE)
    private val lock = Any()

    override fun load(): HeaderSet? = synchronized(lock) {
        val bytes = try {
            if (!file.exists()) return null
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        if (bytes.size <= IV_BYTES) return null
        val plain = try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false) ?: return null, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        } catch (_: Exception) {
            return null
        }
        CredentialCodec.decode(plain).also { plain.fill(0) }
    }

    /** Encrypts and writes the set; a new IV every time. */
    fun save(headers: HeaderSet) = synchronized(lock) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        val iv = cipher.iv
        check(iv.size == IV_BYTES)
        val plain = CredentialCodec.encode(headers)
        val out = iv + cipher.doFinal(plain)
        plain.fill(0)
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) {
            file.writeBytes(out)
            tmp.delete()
        }
    }

    /** Deletes the ciphertext and the key. */
    fun wipe() = synchronized(lock) {
        file.delete()
        File(file.parentFile, "$FILE.tmp").delete()
        try {
            keyStore().deleteEntry(ALIAS)
        } catch (_: Exception) {
        }
    }

    fun status(): CredentialStatus? = load()?.let { CredentialStatus(it.capturedAt, clock() - it.capturedAt) }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        val ks = keyStore()
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        const val FILE = "credentials.bin"
        private const val ALIAS = "discoredact.credentials"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
