package com.asterlink.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.asterlink.app.security.AesGcmBox
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** No cached wrapping key: every read reopens both the file and AndroidKeyStore. */
internal class StateKeyVault(
    private val root: File,
    private val keys: Keys = AndroidKeys(),
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "state_keys_v2"))

    internal interface Keys {
        fun create(alias: String): SecretKey
        fun read(alias: String): SecretKey
    }

    private class AndroidKeys : Keys {
        private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        override fun read(alias: String): SecretKey =
            store().getKey(alias, null) as? SecretKey
                ?: throw UnrecoverableKeyException("State wrapping key missing")

        override fun create(alias: String): SecretKey {
            check(!store().containsAlias(alias)) { "State namespace already exists" }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build())
                generateKey()
            }
        }
    }

    fun read(namespace: String, copy: Boolean): ByteArray? = synchronized(lock) {
        validate(namespace)
        val file = record(namespace, copy)
        val packed = try {
            file.openRead().use { input ->
                val bytes = ByteArray(PACKED_SIZE + 1)
                var length = 0
                while (length < bytes.size) {
                    val count = input.read(bytes, length, bytes.size - length)
                    if (count < 0) break
                    length += count
                }
                require(length == PACKED_SIZE) { "Invalid state key record" }
                bytes.copyOf(length)
            }
        } catch (error: FileNotFoundException) {
            // Only absence is a missing record. Permission/IO failures must not
            // be mistaken for a fresh installation and generate a new key.
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw error
            return@synchronized null
        }
        AesGcmBox.open(keys.read(alias(namespace)), packed).also {
            check(it.size == 32) { "Invalid state key length" }
        }
    }

    /** Staging only: Dart publishes the namespace after this cold read succeeds. */
    fun writeFresh(namespace: String, value: ByteArray) = synchronized(lock) {
        validate(namespace)
        require(value.size == 32)
        val directory = File(root, namespace)
        check(!directory.exists()) { "State namespace already exists" }
        check(directory.mkdirs()) { "Cannot create state key directory" }
        val wrappingKey = keys.create(alias(namespace))
        for (copy in listOf(false, true)) {
            val file = record(namespace, copy)
            val packed = AesGcmBox.seal(wrappingKey, value)
            val stream = file.startWrite()
            try {
                stream.write(packed)
                // Detect sync errors rather than relying on AtomicFile's logging.
                stream.fd.sync()
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
        for (copy in listOf(false, true)) {
            // A new facade must load the key again, not reuse generateKey().
            val reopened = StateKeyVault(root, keys).read(namespace, copy)
            try {
                check(reopened != null && MessageDigest.isEqual(value, reopened)) {
                    "State key cold read failed"
                }
            } finally {
                reopened?.fill(0)
            }
        }
    }

    private fun record(namespace: String, copy: Boolean) =
        AtomicFile(File(File(root, namespace), if (copy) "copy.bin" else "primary.bin"))

    private fun alias(namespace: String) = "com.asterlink.app.state.aes.v2.$namespace"

    companion object {
        private val lock = Any()
        private val namespacePattern = Regex("^state_[a-f0-9]{32}$")
        private const val PACKED_SIZE = 12 + 32 + 16
        private fun validate(namespace: String) = require(namespacePattern.matches(namespace))

        // Exception class names only; never persist key bytes, aliases or values.
        fun diagnostic(error: Throwable): String = generateSequence(error) { it.cause }
            .take(6).joinToString(" > ") { it.javaClass.simpleName }
    }
}
