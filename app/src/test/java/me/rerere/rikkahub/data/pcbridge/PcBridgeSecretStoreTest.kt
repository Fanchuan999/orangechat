package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PcBridgeSecretStoreTest {
    @Test
    fun `stored preference never contains plaintext relay token`() = runBlocking {
        val storage = FakePcBridgeSecureRecordStorage()
        val store = PcBridgeSecretStore(storage, FakeWrappingCipher())

        store.save(credentialsWithToken("relay-token-value"))

        assertFalse(storage.rawValue().contains("relay-token-value"))
        assertEquals("phone-main", store.load()!!.phoneDeviceId)
    }

    @Test
    fun `load rejects public device IDs that do not match wrapped credentials`() = runBlocking {
        val storage = FakePcBridgeSecureRecordStorage()
        val store = PcBridgeSecretStore(storage, FakeWrappingCipher())
        store.save(credentialsWithToken("relay-token-value"))
        storage.replacePhoneDeviceId("phone-tampered")

        assertNull(store.load())
    }

    @Test
    fun `credential records without a pending marker are confirmed by default`() = runBlocking {
        val storage = FakePcBridgeSecureRecordStorage()
        val store = PcBridgeSecretStore(storage, FakeWrappingCipher())

        store.save(credentialsWithToken("relay-token-value"))

        assertFalse(store.load()!!.pendingConfirmation)
    }

    private fun credentialsWithToken(relayToken: String) = PcBridgeCredentials(
        endpoint = "https://project.supabase.co/functions/v1/daddy-pc-bridge",
        bridgeId = "bridge-main",
        phoneDeviceId = "phone-main",
        pcDeviceId = "pc-main",
        relayToken = relayToken,
        envelopeKey = ByteArray(32) { 7 },
    )
}

private class FakePcBridgeSecureRecordStorage : PcBridgeSecureRecordStorage {
    private var record: PcBridgeEncryptedRecord? = null

    override suspend fun read(): PcBridgeEncryptedRecord? = record

    override suspend fun write(record: PcBridgeEncryptedRecord) {
        this.record = record
    }

    override suspend fun clear() {
        record = null
    }

    fun replacePhoneDeviceId(value: String) {
        record = requireNotNull(record).copy(phoneDeviceId = value)
    }

    fun rawValue(): String = record?.let {
        "${it.version}|${it.endpoint}|${it.bridgeId}|${it.phoneDeviceId}|${it.pcDeviceId}|${it.iv}|${it.ciphertext}"
    }.orEmpty()
}

private class FakeWrappingCipher : PcBridgeWrappingCipher {
    override fun encrypt(plaintext: ByteArray): PcBridgeWrappedBytes = PcBridgeWrappedBytes(
        iv = byteArrayOf(1, 2, 3),
        ciphertext = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(),
    )

    override fun decrypt(wrapped: PcBridgeWrappedBytes): ByteArray =
        wrapped.ciphertext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
}
