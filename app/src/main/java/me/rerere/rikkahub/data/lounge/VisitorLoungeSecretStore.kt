package me.rerere.rikkahub.data.lounge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val VISITOR_LOUNGE_SECRET_STORE_NAME = "visitor_lounge_secrets"
private const val VISITOR_LOUNGE_WRAP_KEY_ALIAS = "daddy.visitor.lounge.wrap.v1"
private val Context.visitorLoungeSecretStore by preferencesDataStore(name = VISITOR_LOUNGE_SECRET_STORE_NAME)

data class VisitorLoungeEncryptedSecret(
    val version: Int = 1,
    val iv: String,
    val ciphertext: String,
)

data class VisitorLoungeWrappedBytes(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

interface VisitorLoungeSecretStorage {
    suspend fun read(friendId: String): VisitorLoungeEncryptedSecret?
    suspend fun write(friendId: String, secret: VisitorLoungeEncryptedSecret)
    suspend fun remove(friendId: String)
}

interface VisitorLoungeWrappingCipher {
    fun encrypt(plaintext: ByteArray): VisitorLoungeWrappedBytes
    fun decrypt(wrapped: VisitorLoungeWrappedBytes): ByteArray
}

/**
 * Owns the only persistent copy of a Visitor Key. Callers may use a key only inside [withKey].
 */
class VisitorLoungeSecretStore(
    private val storage: VisitorLoungeSecretStorage,
    private val cipher: VisitorLoungeWrappingCipher,
) {
    constructor(context: Context) : this(
        storage = DataStoreVisitorLoungeSecretStorage(context),
        cipher = AndroidKeystoreVisitorLoungeWrappingCipher(),
    )

    suspend fun put(friendId: String, visitorKey: String) {
        require(friendId.isNotBlank()) { "Friend ID is required" }
        require(visitorKey.isNotBlank()) { "Visitor Key is required" }
        val plaintext = visitorKey.toByteArray(Charsets.UTF_8)
        val wrapped = try {
            cipher.encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        try {
            storage.write(
                friendId,
                VisitorLoungeEncryptedSecret(
                    iv = encode(wrapped.iv),
                    ciphertext = encode(wrapped.ciphertext),
                ),
            )
        } finally {
            wrapped.iv.fill(0)
            wrapped.ciphertext.fill(0)
        }
    }

    suspend fun <T> withKey(friendId: String, block: (String) -> T): T? {
        val record = storage.read(friendId)?.takeIf { it.version == 1 } ?: return null
        val iv = decodeOrNull(record.iv) ?: return null
        val ciphertext = decodeOrNull(record.ciphertext) ?: run {
            iv.fill(0)
            return null
        }
        val plaintext = try {
            cipher.decrypt(VisitorLoungeWrappedBytes(iv, ciphertext))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
        return try {
            block(plaintext.toString(Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    suspend fun remove(friendId: String) = storage.remove(friendId)

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun decodeOrNull(value: String): ByteArray? = runCatching {
        Base64.getUrlDecoder().decode(value)
    }.getOrNull()
}

private class DataStoreVisitorLoungeSecretStorage(context: Context) : VisitorLoungeSecretStorage {
    private val store = context.visitorLoungeSecretStore

    override suspend fun read(friendId: String): VisitorLoungeEncryptedSecret? = store.data.first()[preferenceKey(friendId)]?.let {
        runCatching { Json.decodeFromString<StoredVisitorLoungeSecret>(it) }.getOrNull()?.toRecord()
    }

    override suspend fun write(friendId: String, secret: VisitorLoungeEncryptedSecret) {
        store.edit { preferences ->
            preferences[preferenceKey(friendId)] = Json.encodeToString(StoredVisitorLoungeSecret.from(secret))
        }
    }

    override suspend fun remove(friendId: String) {
        store.edit { preferences -> preferences.remove(preferenceKey(friendId)) }
    }

    private fun preferenceKey(friendId: String) = stringPreferencesKey(
        "visitor_" + Base64.getUrlEncoder().withoutPadding().encodeToString(friendId.toByteArray(Charsets.UTF_8)),
    )
}

internal class AndroidKeystoreVisitorLoungeWrappingCipher : VisitorLoungeWrappingCipher {
    override fun encrypt(plaintext: ByteArray): VisitorLoungeWrappedBytes {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return VisitorLoungeWrappedBytes(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(wrapped: VisitorLoungeWrappedBytes): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wrapped.iv))
        return cipher.doFinal(wrapped.ciphertext)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keyStore.getKey(VISITOR_LOUNGE_WRAP_KEY_ALIAS, null) as? SecretKey) ?: createKey()
    }

    private fun createKey(): SecretKey = KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply {
            init(
                KeyGenParameterSpec.Builder(
                    VISITOR_LOUNGE_WRAP_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }
        .generateKey()
}

@Serializable
private data class StoredVisitorLoungeSecret(
    val version: Int,
    val iv: String,
    val ciphertext: String,
) {
    fun toRecord() = VisitorLoungeEncryptedSecret(version, iv, ciphertext)

    companion object {
        fun from(value: VisitorLoungeEncryptedSecret) = StoredVisitorLoungeSecret(
            version = value.version,
            iv = value.iv,
            ciphertext = value.ciphertext,
        )
    }
}
