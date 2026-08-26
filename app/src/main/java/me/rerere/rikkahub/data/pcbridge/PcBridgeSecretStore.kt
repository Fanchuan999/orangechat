package me.rerere.rikkahub.data.pcbridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

private const val PC_BRIDGE_PAIRING_STORE_NAME = "pc_bridge_pairing"
private const val PC_BRIDGE_WRAP_KEY_ALIAS = "daddy.pc.bridge.wrap.v1"
private val Context.pcBridgePairingStore by preferencesDataStore(name = PC_BRIDGE_PAIRING_STORE_NAME)

data class PcBridgeCredentials(
    val endpoint: String,
    val bridgeId: String,
    val phoneDeviceId: String,
    val pcDeviceId: String,
    val relayToken: String,
    val envelopeKey: ByteArray,
)

data class PcBridgeEncryptedRecord(
    val version: Int,
    val endpoint: String,
    val bridgeId: String,
    val phoneDeviceId: String,
    val pcDeviceId: String,
    val iv: String,
    val ciphertext: String,
)

data class PcBridgeWrappedBytes(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

interface PcBridgeSecureRecordStorage {
    suspend fun read(): PcBridgeEncryptedRecord?
    suspend fun write(record: PcBridgeEncryptedRecord)
    suspend fun clear()
}

interface PcBridgeWrappingCipher {
    fun encrypt(plaintext: ByteArray): PcBridgeWrappedBytes
    fun decrypt(wrapped: PcBridgeWrappedBytes): ByteArray
}

class PcBridgeSecretStore(
    private val storage: PcBridgeSecureRecordStorage,
    private val cipher: PcBridgeWrappingCipher,
    private val json: Json = Json,
) {
    constructor(context: Context) : this(
        storage = DataStorePcBridgeSecureRecordStorage(context),
        cipher = AndroidKeystorePcBridgeWrappingCipher(),
    )

    suspend fun save(credentials: PcBridgeCredentials) {
        require(credentials.envelopeKey.size == 32) { "PC bridge envelope key must be 32 bytes" }
        PcBridgeEndpointPolicy.requireExactRelayEndpoint(credentials.endpoint)
        val serialized = json.encodeToString(
            PcBridgePrivateRecord(
                relayToken = credentials.relayToken,
                envelopeKey = PcBridgeCrypto.encodeBase64Url(credentials.envelopeKey),
            ),
        ).toByteArray(Charsets.UTF_8)
        val wrapped = try {
            cipher.encrypt(serialized)
        } finally {
            serialized.fill(0)
        }
        try {
            storage.write(
                PcBridgeEncryptedRecord(
                    version = 1,
                    endpoint = credentials.endpoint,
                    bridgeId = credentials.bridgeId,
                    phoneDeviceId = credentials.phoneDeviceId,
                    pcDeviceId = credentials.pcDeviceId,
                    iv = PcBridgeCrypto.encodeBase64Url(wrapped.iv),
                    ciphertext = PcBridgeCrypto.encodeBase64Url(wrapped.ciphertext),
                ),
            )
        } finally {
            wrapped.iv.fill(0)
            wrapped.ciphertext.fill(0)
        }
    }

    suspend fun load(): PcBridgeCredentials? {
        val record = storage.read() ?: return null
        if (record.version != 1) return null
        return try {
            PcBridgeEndpointPolicy.requireExactRelayEndpoint(record.endpoint)
            val iv = PcBridgeCrypto.decodeBase64Url(record.iv)
            val ciphertext = PcBridgeCrypto.decodeBase64Url(record.ciphertext)
            val plaintext = try {
                cipher.decrypt(PcBridgeWrappedBytes(iv, ciphertext))
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
            try {
                val privateRecord = json.decodeFromString<PcBridgePrivateRecord>(plaintext.toString(Charsets.UTF_8))
                val envelopeKey = PcBridgeCrypto.decodeBase64Url(privateRecord.envelopeKey)
                require(envelopeKey.size == 32) { "Invalid PC bridge envelope key" }
                PcBridgeCredentials(
                    endpoint = record.endpoint,
                    bridgeId = record.bridgeId,
                    phoneDeviceId = record.phoneDeviceId,
                    pcDeviceId = record.pcDeviceId,
                    relayToken = privateRecord.relayToken,
                    envelopeKey = envelopeKey,
                )
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun clear() = storage.clear()
}

private class DataStorePcBridgeSecureRecordStorage(context: Context) : PcBridgeSecureRecordStorage {
    private val store = context.pcBridgePairingStore
    private val recordKey = stringPreferencesKey("encrypted_record")

    override suspend fun read(): PcBridgeEncryptedRecord? = store.data.first()[recordKey]?.let {
        runCatching { Json.decodeFromString<PcBridgeStoredRecord>(it) }.getOrNull()?.toPublicRecord()
    }

    override suspend fun write(record: PcBridgeEncryptedRecord) {
        store.edit { preferences ->
            preferences[recordKey] = Json.encodeToString(PcBridgeStoredRecord.from(record))
        }
    }

    override suspend fun clear() {
        store.edit { preferences -> preferences.remove(recordKey) }
    }
}

private class AndroidKeystorePcBridgeWrappingCipher : PcBridgeWrappingCipher {
    override fun encrypt(plaintext: ByteArray): PcBridgeWrappedBytes {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return PcBridgeWrappedBytes(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(wrapped: PcBridgeWrappedBytes): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, wrapped.iv))
        return cipher.doFinal(wrapped.ciphertext)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keyStore.getKey(PC_BRIDGE_WRAP_KEY_ALIAS, null) as? SecretKey) ?: createKey()
    }

    private fun createKey(): SecretKey = KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply {
            init(
                KeyGenParameterSpec.Builder(
                    PC_BRIDGE_WRAP_KEY_ALIAS,
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
private data class PcBridgePrivateRecord(
    val relayToken: String,
    val envelopeKey: String,
)

@Serializable
private data class PcBridgeStoredRecord(
    val version: Int,
    val endpoint: String,
    val bridgeId: String,
    val phoneDeviceId: String,
    val pcDeviceId: String,
    val iv: String,
    val ciphertext: String,
) {
    fun toPublicRecord() = PcBridgeEncryptedRecord(
        version,
        endpoint,
        bridgeId,
        phoneDeviceId,
        pcDeviceId,
        iv,
        ciphertext,
    )

    companion object {
        fun from(value: PcBridgeEncryptedRecord) = PcBridgeStoredRecord(
            value.version,
            value.endpoint,
            value.bridgeId,
            value.phoneDeviceId,
            value.pcDeviceId,
            value.iv,
            value.ciphertext,
        )
    }
}
