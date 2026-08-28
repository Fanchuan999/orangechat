package me.rerere.rikkahub.data.lounge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisitorLoungeSecretStoreTest {
    @Test
    fun `public friend records have no field for a visitor key`() {
        assertFalse(
            FriendPublicRecord::class.java.declaredFields.any { field ->
                field.name.contains("key", ignoreCase = true) || field.name.contains("secret", ignoreCase = true)
            },
        )
    }

    @Test
    fun `encrypting the same visitor key twice creates different stored ciphertext`() = runBlocking {
        val storage = FakeVisitorLoungeSecretStorage()
        val store = VisitorLoungeSecretStore(storage, FakeVisitorLoungeCipher("primary"))

        store.put("friend-1", "visitor-key")
        val first = storage.requireRecord().ciphertext
        store.put("friend-1", "visitor-key")
        val second = storage.requireRecord().ciphertext

        assertNotEquals(first, second)
        assertFalse(storage.raw().contains("visitor-key"))
    }

    @Test
    fun `wrong wrapping cipher cannot reveal a stored visitor key`() = runBlocking {
        val storage = FakeVisitorLoungeSecretStorage()
        VisitorLoungeSecretStore(storage, FakeVisitorLoungeCipher("primary")).put("friend-1", "visitor-key")
        val wrongStore = VisitorLoungeSecretStore(storage, FakeVisitorLoungeCipher("other"))

        assertNull(wrongStore.withKey("friend-1") { it })
    }

    @Test
    fun `visitor key exists only in the withKey callback scope`() = runBlocking {
        val storage = FakeVisitorLoungeSecretStorage()
        val store = VisitorLoungeSecretStore(storage, FakeVisitorLoungeCipher("primary"))
        store.put("friend-1", "visitor-key")

        assertEquals("visitor-key", store.withKey("friend-1") { key -> key })
    }
}

private class FakeVisitorLoungeSecretStorage : VisitorLoungeSecretStorage {
    private val records = mutableMapOf<String, VisitorLoungeEncryptedSecret>()

    override suspend fun read(friendId: String): VisitorLoungeEncryptedSecret? = records[friendId]

    override suspend fun write(friendId: String, secret: VisitorLoungeEncryptedSecret) {
        records[friendId] = secret
    }

    override suspend fun remove(friendId: String) {
        records.remove(friendId)
    }

    fun requireRecord(): VisitorLoungeEncryptedSecret = requireNotNull(records["friend-1"])

    fun raw(): String = records.values.joinToString("|") { "${it.iv}|${it.ciphertext}" }
}

private class FakeVisitorLoungeCipher(
    private val alias: String,
) : VisitorLoungeWrappingCipher {
    private var sequence = 0

    override fun encrypt(plaintext: ByteArray): VisitorLoungeWrappedBytes {
        val nonce = (++sequence).toByte()
        val prefix = "$alias:".toByteArray(Charsets.UTF_8)
        return VisitorLoungeWrappedBytes(
            iv = byteArrayOf(nonce),
            ciphertext = prefix + plaintext.map { byte -> (byte.toInt() xor nonce.toInt()).toByte() }.toByteArray(),
        )
    }

    override fun decrypt(wrapped: VisitorLoungeWrappedBytes): ByteArray {
        val prefix = "$alias:".toByteArray(Charsets.UTF_8)
        require(wrapped.ciphertext.copyOfRange(0, prefix.size).contentEquals(prefix))
        return wrapped.ciphertext.copyOfRange(prefix.size, wrapped.ciphertext.size)
            .map { byte -> (byte.toInt() xor wrapped.iv.single().toInt()).toByte() }
            .toByteArray()
    }
}
