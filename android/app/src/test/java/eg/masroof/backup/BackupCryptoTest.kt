package eg.masroof.backup

import eg.masroof.data.backup.BackupHeader
import eg.masroof.data.backup.PassphraseKdf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

class BackupCryptoTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun rejects(
        file: ByteArray,
        passphrase: String = PASS,
    ) {
        try {
            BackupTestDecryptor.decrypt(file, passphrase, enforceBounds = true)
            fail("the file must not decrypt")
        } catch (_: BackupRejected) {
            // expected: the same refusal for a wrong passphrase and for a damaged file
        }
    }

    /** A bounds-valid but cheap header (19 MiB, one pass) so the 39 header flips and the other tamper cases stay fast. */
    private fun validHeader() = BackupHeader(1, 1, 19 * 1024, 1, 1, ByteArray(16) { (it * 3).toByte() }, 1, 0)

    private fun bigBackup(): ByteArray {
        // 4.5 MiB of incompressible receipt bytes: several 1 MiB AEAD segments.
        val receipt = ByteArray(4_500_000).also { Random(5).nextBytes(it) }
        val ref =
            eg.masroof.data.backup
                .BackupReceiptRef("r1", "r1.jpg", receipt.size.toLong())
        val env = environment(db = FakeBackupDb(receiptRefs = listOf(ref)), files = filesOf("r1.jpg" to receipt))
        return writeBackup(env, header = validHeader()).first
    }

    @Test
    fun `REQ-SEC-066 Argon2id matches the RFC 9106 test vector`() {
        // RFC 9106 section 5.3: password 32x01, salt 16x02, secret 8x03, associated data 12x04, m=32 KiB, t=3, p=4, 32-byte tag.
        val tag =
            PassphraseKdf.argon2id(
                password = ByteArray(32) { 1 },
                salt = ByteArray(16) { 2 },
                memoryKiB = 32,
                iterations = 3,
                parallelism = 4,
                outLength = 32,
                secret = ByteArray(8) { 3 },
                additional = ByteArray(12) { 4 },
            )
        assertArrayEquals(hex("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"), tag)
    }

    @Test
    fun `REQ-SEC-066 the file starts with the MASROOFB header, big-endian, with the ADR-0009 parameters`() {
        val header = BackupHeader.fresh()
        val (file, _) = writeBackup(environment(), header = header)
        val b = ByteBuffer.wrap(file)
        assertEquals("MASROOFB", String(file, 0, 8, Charsets.US_ASCII))
        b.position(8)
        assertEquals(1, b.short.toInt()) // formatVersion
        assertEquals(1, b.get().toInt()) // kdfId: Argon2id v1.3
        assertEquals(65_536, b.int) // m in KiB
        assertEquals(3, b.int) // t
        assertEquals(1, b.get().toInt()) // p
        val salt = ByteArray(16).also { b.get(it) }
        assertArrayEquals(header.salt, salt)
        assertEquals(1, b.get().toInt()) // aeadId
        assertEquals(0, b.short.toInt()) // reserved
        assertEquals(BackupHeader.SIZE, b.position())
        // The raw bytes of the spec layout, byte for byte.
        assertArrayEquals(
            hex("4d4153524f4f4642") + hex("0001") + hex("01") + hex("00010000") + hex("00000003") + hex("01") + salt + hex("01") +
                hex("0000"),
            file.copyOfRange(0, BackupHeader.SIZE),
        )
        // And a decryptor with the default (expensive) KDF and the bounds check opens it.
        val plain = BackupTestDecryptor.decrypt(file, PASS, enforceBounds = true)
        assertEquals("manifest.json", plain.names.first())
    }

    @Test
    fun `REQ-SEC-066 the salt is 16 random bytes and fresh headers differ`() {
        val a = BackupHeader.fresh()
        val b = BackupHeader.fresh()
        assertEquals(16, a.salt.size)
        assertFalse(a.salt.contentEquals(b.salt))
    }

    @Test
    fun `REQ-SEC-066 two backups of the same data with the same passphrase differ in salt and ciphertext`() {
        val env = environment()
        val (one, _) = writeBackup(env, header = BackupHeader(1, 1, 64, 1, 1, ByteArray(16) { 1 }, 1, 0))
        val (two, _) = writeBackup(env, header = BackupHeader(1, 1, 64, 1, 1, ByteArray(16) { 2 }, 1, 0))
        assertNotEquals(one.copyOfRange(SALT_AT, SALT_AT + 16).toList(), two.copyOfRange(SALT_AT, SALT_AT + 16).toList()) // the salt
        assertNotEquals(one.copyOfRange(BackupHeader.SIZE, one.size).toList(), two.copyOfRange(BackupHeader.SIZE, two.size).toList())
        // Even with the very same header (so the same key) Tink's per-stream random nonce prefix makes the ciphertext differ.
        val header = fastHeader()
        val (a, _) = writeBackup(env, header = header)
        val (b, _) = writeBackup(env, header = header)
        assertNotEquals(a.toList(), b.toList())
        assertEquals(BackupTestDecryptor.decrypt(a, PASS).names, BackupTestDecryptor.decrypt(b, PASS).names)
    }

    @Test
    fun `REQ-SEC-066 any single flipped byte of the header makes decryption fail`() {
        val file = bigBackup()
        assertTrue(BackupTestDecryptor.decrypt(file, PASS, enforceBounds = true).names.isNotEmpty())
        for (i in 0 until BackupHeader.SIZE) {
            rejects(file.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() })
        }
    }

    @Test
    fun `REQ-SEC-066 a flipped byte in the body makes decryption fail`() {
        val file = bigBackup()
        val positions =
            listOf(
                BackupHeader.SIZE,
                BackupHeader.SIZE + 20,
                BackupHeader.SIZE + 45,
                file.size / 2,
                file.size - 40,
                file.size - 1,
            )
        for (i in positions) rejects(file.copyOf().also { it[i] = (it[i].toInt() xor 0x80).toByte() })
    }

    @Test
    fun `REQ-SEC-066 a file with the last 100 bytes cut off does not decrypt`() {
        val file = bigBackup()
        rejects(file.copyOfRange(0, file.size - 100))
    }

    @Test
    fun `REQ-SEC-066 two swapped 1 MiB segments do not decrypt`() {
        val file = bigBackup()
        val seg = 1 shl 20
        // [header 39][Tink header 40 + first segment (1 MiB - 40)][segment 1][segment 2]...: segments 1 and 2 are full 1 MiB blocks.
        val first = BackupHeader.SIZE + seg
        val swapped = file.copyOf()
        System.arraycopy(file, first + seg, swapped, first, seg)
        System.arraycopy(file, first, swapped, first + seg, seg)
        assertFalse(swapped.contentEquals(file))
        rejects(swapped)
    }

    @Test
    fun `REQ-SEC-066 a wrong passphrase does not decrypt and says the same as a damaged file`() {
        rejects(bigBackup(), passphrase = "correct horse batterz")
    }

    @Test
    fun `REQ-SEC-066 a header that is not MASROOFB or has an unknown version is refused before any key work`() {
        val file = bigBackup()
        assertNull(BackupHeader.parse(file.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(BackupHeader.parse(file.copyOfRange(0, 20)))
        rejects(file.copyOf().also { it[9] = 2 }) // formatVersion 2
        // A crafted header asking for 1 GiB of memory is refused by the bounds, never allocated.
        val crafted = file.copyOf()
        ByteBuffer.wrap(crafted, 11, 4).putInt(1024 * 1024)
        rejects(crafted)
    }

    @Test
    fun `REQ-SEC-067 the same passphrase typed in another Unicode composition form opens the file`() {
        val composed = "café été أهلا بك" // precomposed e-acute and alef-hamza
        val decomposed = "café été أهلا بك" // base letter + combining mark
        assertNotEquals(composed, decomposed)
        val (file, _) = writeBackup(environment(), passphrase = composed)
        assertEquals("manifest.json", BackupTestDecryptor.decrypt(file, decomposed).names.first())
    }

    @Test
    fun `REQ-SEC-067 an Arabic passphrase of 17 code points is long enough and opens its file`() {
        val arabic = "كلمة سر طويلة جدا"
        assertEquals(17, PassphraseKdf.codePoints(arabic))
        assertTrue(PassphraseKdf.isLongEnough(arabic))
        val (file, _) = writeBackup(environment(), passphrase = arabic)
        assertEquals("manifest.json", BackupTestDecryptor.decrypt(file, arabic).names.first())
    }

    @Test
    fun `REQ-SEC-067 length counts code points after NFC, not UTF-16 units or bytes`() {
        assertFalse(PassphraseKdf.isLongEnough("12345678901")) // 11
        assertTrue(PassphraseKdf.isLongEnough("123456789012")) // 12
        // 11 emoji are 22 UTF-16 units but 11 code points; a decomposed e + accent counts as one after NFC.
        assertFalse(PassphraseKdf.isLongEnough("😀".repeat(11)))
        assertTrue(PassphraseKdf.isLongEnough("😀".repeat(12)))
        assertFalse(PassphraseKdf.isLongEnough("é".repeat(11)))
    }
}

private const val PASS = "correct horse battery"

/** magic 8 + version 2 + kdf 1 + m 4 + t 4 + p 1. */
private const val SALT_AT = 20
