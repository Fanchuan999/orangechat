package me.rerere.rikkahub.data.pcbridge

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CancellationException

class PcBridgePairingServiceTest {
    private val nowMillis = 1_800_000_000_000L
    private val validCode = invitationCode(
        """{"version":2,"endpoint":"https://project.supabase.co/functions/v1/daddy-pc-bridge",""" +
            """"bridgeId":"bridge_123","pcDeviceId":"pc_456","pcPublicKey":"""" +
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE-ISiHsBNFE6rfX6KQSfgXEoY5av4bK-y" +
            "xm2ZWT8yNBnDttb6YxL997EOi7l8TydqBHJ6T-KNQ4V2pRtKjQY3mQ" +
            """","pairingSecret":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8","expiresAt":${nowMillis + 60_000L}}""",
    )

    @Test
    fun `successful pair join keeps persisted credentials`() = runBlocking {
        val storage = ServiceRecordStorage()
        val service = service(storage, RecordingTransport("""{"paired":true}"""))

        service.updateInvitationCode(validCode)
        service.confirmPairing()

        assertTrue(service.state.value is PcBridgeUiState.Paired)
        assertFalse(PcBridgeSecretStore(storage, ServiceWrappingCipher()).load()!!.pendingConfirmation)
    }

    @Test
    fun `local save failure prevents pair join`() = runBlocking {
        val storage = ServiceRecordStorage(failWrite = true)
        val transport = RecordingTransport("""{"paired":true}""")
        val service = service(storage, transport)

        service.updateInvitationCode(validCode)
        service.confirmPairing()

        assertTrue(service.state.value is PcBridgeUiState.Unavailable)
        assertEquals(0, transport.callCount)
        assertNull(PcBridgeSecretStore(storage, ServiceWrappingCipher()).load())
    }

    @Test
    fun `explicit rejected pair join clears pending credentials`() = runBlocking {
        val storage = ServiceRecordStorage()
        val service = service(storage, RecordingTransport("""{"paired":false}"""))

        service.updateInvitationCode(validCode)
        service.confirmPairing()

        assertTrue(service.state.value is PcBridgeUiState.Unavailable)
        assertNull(PcBridgeSecretStore(storage, ServiceWrappingCipher()).load())
        assertEquals(1, storage.clearCount)
    }

    @Test
    fun `transport failure after persistence retains credentials for recovery`() = runBlocking {
        val storage = ServiceRecordStorage()
        val transport = RecordingTransport("""{"paired":true}""").apply { failure = true }
        val service = service(storage, transport)

        service.updateInvitationCode(validCode)
        service.confirmPairing()

        assertTrue(service.state.value is PcBridgeUiState.PendingRecovery)
        assertNotNull(PcBridgeSecretStore(storage, ServiceWrappingCipher()).load())
        assertEquals(0, storage.clearCount)
    }

    @Test
    fun `stored pending credential whose refresh fails enters pending recovery`() = runBlocking {
        val storage = ServiceRecordStorage()
        val store = PcBridgeSecretStore(storage, ServiceWrappingCipher())
        store.save(testCredentials(pendingConfirmation = true))
        val transport = RecordingTransport("""{"status":{"state":"active"}}""").apply { failure = true }
        val service = service(storage, transport)

        service.refreshStatus()

        assertTrue(service.state.value is PcBridgeUiState.PendingRecovery)
    }

    @Test
    fun `abandon pending pairing clears only local pending record without relay request`() = runBlocking {
        val storage = ServiceRecordStorage()
        PcBridgeSecretStore(storage, ServiceWrappingCipher()).save(testCredentials(pendingConfirmation = true))
        val transport = RecordingTransport("""{"status":{"state":"active"}}""")
        val service = service(storage, transport)

        service.abandonPendingPairing()

        assertTrue(service.state.value is PcBridgeUiState.Unpaired)
        assertNull(PcBridgeSecretStore(storage, ServiceWrappingCipher()).load())
        assertEquals(1, storage.clearCount)
        assertEquals(0, transport.callCount)
    }

    @Test
    fun `abandon pending pairing never clears a confirmed credential`() = runBlocking {
        val storage = ServiceRecordStorage()
        PcBridgeSecretStore(storage, ServiceWrappingCipher()).save(testCredentials(pendingConfirmation = false))
        val transport = RecordingTransport("""{"status":{"state":"active"}}""")
        val service = service(storage, transport)

        service.abandonPendingPairing()

        assertNotNull(PcBridgeSecretStore(storage, ServiceWrappingCipher()).load())
        assertEquals(0, storage.clearCount)
        assertEquals(0, transport.callCount)
    }

    @Test
    fun `active refresh confirms a pending credential`() = runBlocking {
        val storage = ServiceRecordStorage()
        val store = PcBridgeSecretStore(storage, ServiceWrappingCipher())
        store.save(testCredentials(pendingConfirmation = true))
        val service = service(storage, RecordingTransport("""{"status":{"state":"active"}}"""))

        service.refreshStatus()

        assertTrue(service.state.value is PcBridgeUiState.Paired)
        assertFalse(store.load()!!.pendingConfirmation)
    }

    @Test
    fun `invitation state never exposes invitation or pairing secret`() {
        val service = service(ServiceRecordStorage(), RecordingTransport("""{"paired":true}"""))

        service.updateInvitationCode(validCode)

        val renderedState = service.state.value.toString()
        assertTrue(service.state.value is PcBridgeUiState.InvitationDraft)
        assertFalse(renderedState.contains("DADDY-PC2:"))
        assertFalse(renderedState.contains("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"))
    }

    @Test
    fun `quoted paired result does not persist credentials`() = runBlocking {
        val storage = ServiceRecordStorage()
        val service = service(storage, RecordingTransport("""{"paired":"true"}"""))

        service.updateInvitationCode(validCode)
        service.confirmPairing()

        assertTrue(service.state.value is PcBridgeUiState.Unavailable)
        assertNull(PcBridgeSecretStore(storage, ServiceWrappingCipher()).load())
    }

    @Test
    fun `unlink retains credentials when relay call fails`() = runBlocking {
        val storage = ServiceRecordStorage()
        val transport = RecordingTransport("""{"paired":true}""")
        val service = service(storage, transport)
        service.updateInvitationCode(validCode)
        service.confirmPairing()
        transport.failure = true

        service.unlink()

        assertNotNull(PcBridgeSecretStore(storage, ServiceWrappingCipher()).load())
        assertTrue(service.state.value is PcBridgeUiState.Unavailable)
    }

    @Test
    fun `quoted revocation result retains credentials`() = runBlocking {
        val storage = ServiceRecordStorage()
        val transport = RecordingTransport("""{"paired":true}""")
        val service = service(storage, transport)
        service.updateInvitationCode(validCode)
        service.confirmPairing()
        transport.response = """{"revoked":"true"}"""

        service.unlink()

        assertNotNull(PcBridgeSecretStore(storage, ServiceWrappingCipher()).load())
        assertTrue(service.state.value is PcBridgeUiState.Unavailable)
    }

    @Test
    fun `service rethrows coroutine cancellation`() {
        val storage = ServiceRecordStorage()
        val transport = RecordingTransport("""{"paired":true}""")
        val service = service(storage, transport)
        runBlocking {
            service.updateInvitationCode(validCode)
            service.confirmPairing()
        }
        transport.cancel = true

        assertThrows(CancellationException::class.java) {
            runBlocking { service.unlink() }
        }
        assertNotNull(runBlocking { PcBridgeSecretStore(storage, ServiceWrappingCipher()).load() })
    }

    private fun service(storage: ServiceRecordStorage, transport: RecordingTransport) = PcBridgePairingService(
        secretStore = PcBridgeSecretStore(storage, ServiceWrappingCipher()),
        relayClient = PcBridgeRelayClient(transport, nowMillis = { nowMillis }, nonceFactory = { "nonce-main" }),
        phoneDeviceIdFactory = { "phone-main" },
        nowMillis = { nowMillis },
    )

    private fun invitationCode(json: String): String =
        "DADDY-PC2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private fun testCredentials(pendingConfirmation: Boolean) = PcBridgeCredentials(
        endpoint = "https://project.supabase.co/functions/v1/daddy-pc-bridge",
        bridgeId = "bridge-main",
        phoneDeviceId = "phone-main",
        pcDeviceId = "pc-main",
        relayToken = "relay-token-main",
        envelopeKey = ByteArray(32) { 7 },
        pendingConfirmation = pendingConfirmation,
    )
}

private class ServiceRecordStorage(
    private val failWrite: Boolean = false,
) : PcBridgeSecureRecordStorage {
    private var record: PcBridgeEncryptedRecord? = null
    var clearCount = 0
        private set
    override suspend fun read() = record
    override suspend fun write(record: PcBridgeEncryptedRecord) {
        if (failWrite) error("local save failed")
        this.record = record
    }
    override suspend fun clear() {
        clearCount += 1
        record = null
    }
}

private class ServiceWrappingCipher : PcBridgeWrappingCipher {
    override fun encrypt(plaintext: ByteArray) = PcBridgeWrappedBytes(
        byteArrayOf(1),
        plaintext.map { (it.toInt() xor 0x55).toByte() }.toByteArray(),
    )

    override fun decrypt(wrapped: PcBridgeWrappedBytes) =
        wrapped.ciphertext.map { (it.toInt() xor 0x55).toByte() }.toByteArray()
}

private class RecordingTransport(
    var response: String,
) : PcBridgeRelayTransport {
    var failure = false
    var cancel = false
    var callCount = 0
        private set

    override suspend fun post(endpoint: HttpUrl, body: String, headers: Map<String, String>): String {
        callCount += 1
        if (cancel) throw CancellationException("cancelled")
        if (failure) error("network unavailable")
        return response
    }
}
