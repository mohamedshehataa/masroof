package eg.masroof.data.backup

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.util.SecretBytes
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.text.Normalizer

/**
 * The `.masroof` file header (ADR-0009 D5), big-endian, 39 bytes: magic `MASROOFB`, formatVersion u16, kdfId u8, memoryKiB u32,
 * iterations u32, parallelism u8, salt 16 bytes, aeadId u8, reserved u16. The exact bytes are the Tink stream's associated data, so
 * any change to them fails decryption.
 */
class BackupHeader(
    val formatVersion: Int,
    val kdfId: Int,
    val memoryKiB: Long,
    val iterations: Long,
    val parallelism: Int,
    val salt: ByteArray,
    val aeadId: Int,
    val reserved: Int,
) {
    fun encode(): ByteArray =
        ByteBuffer
            .allocate(SIZE)
            .put(BackupFormat.MAGIC)
            .putShort(formatVersion.toShort())
            .put(kdfId.toByte())
            .putInt(memoryKiB.toInt())
            .putInt(iterations.toInt())
            .put(parallelism.toByte())
            .put(salt)
            .put(aeadId.toByte())
            .putShort(reserved.toShort())
            .array()

    /** The KDF settings are written as read: bounds and version are the reader's decision (STORY-184), not the parser's. */
    companion object {
        const val SIZE = 39

        /** A header with the defaults of ADR-0009 D5 and a fresh random salt. */
        fun fresh(random: SecureRandom = SecureRandom()): BackupHeader =
            BackupHeader(
                formatVersion = BackupFormat.FORMAT_VERSION,
                kdfId = BackupFormat.KDF_ARGON2ID,
                memoryKiB = BackupFormat.MEMORY_KIB,
                iterations = BackupFormat.ITERATIONS,
                parallelism = BackupFormat.PARALLELISM,
                salt = ByteArray(BackupFormat.SALT_BYTES).also { random.nextBytes(it) },
                aeadId = BackupFormat.AEAD_AES256_GCM_HKDF_1MB,
                reserved = 0,
            )

        /** Null when [bytes] is too short or does not start with the magic. */
        fun parse(bytes: ByteArray): BackupHeader? {
            if (bytes.size < SIZE) return null
            val b = ByteBuffer.wrap(bytes, 0, SIZE)
            val magic = ByteArray(BackupFormat.MAGIC.size).also { b.get(it) }
            if (!magic.contentEquals(BackupFormat.MAGIC)) return null
            return BackupHeader(
                formatVersion = b.short.toInt() and 0xFFFF,
                kdfId = b.get().toInt() and 0xFF,
                memoryKiB = b.int.toLong() and 0xFFFFFFFFL,
                iterations = b.int.toLong() and 0xFFFFFFFFL,
                parallelism = b.get().toInt() and 0xFF,
                salt = ByteArray(BackupFormat.SALT_BYTES).also { b.get(it) },
                aeadId = b.get().toInt() and 0xFF,
                reserved = b.short.toInt() and 0xFFFF,
            )
        }
    }
}

object BackupFormat {
    val MAGIC: ByteArray = "MASROOFB".toByteArray(StandardCharsets.US_ASCII)
    const val FORMAT_VERSION = 1
    const val KDF_ARGON2ID = 1
    const val AEAD_AES256_GCM_HKDF_1MB = 1
    const val MEMORY_KIB = 65_536L
    const val ITERATIONS = 3L
    const val PARALLELISM = 1
    const val SALT_BYTES = 16
    const val KEY_BYTES = 32
    const val SEGMENT_BYTES = 1 shl 20

    /** Independent of the Room version: bumped only when the files inside the ZIP change shape. Room is at 10 today. */
    const val DATA_SCHEMA_VERSION = 10

    const val MIN_PASSPHRASE_CODE_POINTS = 12
}

/** The passphrase rules and the Argon2id key derivation (REQ-SEC-066/067). The passphrase is never stored or logged. */
object PassphraseKdf {
    /** NFC: the same text typed in a different Unicode composition form is the same passphrase. */
    fun normalise(passphrase: String): String = Normalizer.normalize(passphrase, Normalizer.Form.NFC)

    fun codePoints(passphrase: String): Int = normalise(passphrase).let { it.codePointCount(0, it.length) }

    fun isLongEnough(passphrase: String): Boolean = codePoints(passphrase) >= BackupFormat.MIN_PASSPHRASE_CODE_POINTS

    /** The 32-byte key for [header]'s KDF settings. Takes about a second at the default 64 MiB; call it off the main thread. */
    fun deriveKey(
        passphrase: String,
        header: BackupHeader,
    ): ByteArray {
        val password = normalise(passphrase).toByteArray(StandardCharsets.UTF_8)
        try {
            return argon2id(
                password,
                header.salt,
                memoryKiB = header.memoryKiB.toInt(),
                iterations = header.iterations.toInt(),
                parallelism = header.parallelism,
                outLength = BackupFormat.KEY_BYTES,
            )
        } finally {
            password.fill(0)
        }
    }

    /** Argon2id v1.3 through Bouncy Castle's lightweight generator (pure Java). [secret] and [additional] exist for the RFC 9106 vector. */
    @Suppress("LongParameterList") // the RFC 9106 vector needs secret and additional data as well
    fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        outLength: Int,
        secret: ByteArray? = null,
        additional: ByteArray? = null,
    ): ByteArray {
        val builder =
            Argon2Parameters
                .Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKiB)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
        if (secret != null) builder.withSecret(secret)
        if (additional != null) builder.withAdditional(additional)
        val generator = Argon2BytesGenerator()
        generator.init(builder.build())
        return ByteArray(outLength).also { generator.generateBytes(password, it, 0, outLength) }
    }
}

/** Tink streaming AEAD AES256_GCM_HKDF_1MB, keyed with the raw Argon2id output; the cleartext header is the associated data. */
object BackupCipher {
    private fun aead(key: ByteArray): StreamingAead {
        StreamingAeadConfig.register()
        val parameters =
            AesGcmHkdfStreamingParameters
                .builder()
                .setKeySizeBytes(BackupFormat.KEY_BYTES)
                .setDerivedAesGcmKeySizeBytes(BackupFormat.KEY_BYTES)
                .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
                .setCiphertextSegmentSizeBytes(BackupFormat.SEGMENT_BYTES)
                .build()
        val streamingKey = AesGcmHkdfStreamingKey.create(parameters, SecretBytes.copyFrom(key, InsecureSecretKeyAccess.get()))
        val handle =
            KeysetHandle
                .newBuilder()
                .addEntry(KeysetHandle.importKey(streamingKey).withFixedId(KEY_ID).makePrimary())
                .build()
        return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    /** Closing the returned stream writes the final segment; a stream that was not closed is a cut file that cannot be decrypted. */
    fun encrypt(
        sink: OutputStream,
        key: ByteArray,
        headerBytes: ByteArray,
    ): OutputStream = aead(key).newEncryptingStream(sink, headerBytes)

    fun decrypt(
        source: InputStream,
        key: ByteArray,
        headerBytes: ByteArray,
    ): InputStream = aead(key).newDecryptingStream(source, headerBytes)

    private const val KEY_ID = 1
}
