package com.asterlink.app

import java.io.File
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], manifest = Config.NONE)
class StateKeyVaultTest {
    @get:Rule val temporary = TemporaryFolder()
    private val namespace = "state_" + "a".repeat(32)
    private val value = ByteArray(32) { it.toByte() }

    private class DeviceKeys : StateKeyVault.Keys {
        val records = mutableMapOf<String, SecretKey>()
        var rejectColdRead = false
        var creates = 0
        override fun create(alias: String): SecretKey {
            check(!records.containsKey(alias))
            creates++
            return KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { records[alias] = it }
        }
        override fun read(alias: String): SecretKey {
            if (rejectColdRead) throw UnrecoverableKeyException("test cold read rejected")
            return records[alias] ?: throw UnrecoverableKeyException("test key missing")
        }
    }

    @Test fun coldReadUsesPersistedCiphertextAndFreshKeyLookup() {
        val root = temporary.newFolder()
        val keys = DeviceKeys()
        StateKeyVault(root, keys).writeFresh(namespace, value)
        repeat(3) {
            val nextProcess = StateKeyVault(root, keys)
            assertArrayEquals(value, nextProcess.read(namespace, false))
            assertArrayEquals(value, nextProcess.read(namespace, true))
        }
        val primary = File(root, "$namespace/primary.bin").readBytes()
        val copy = File(root, "$namespace/copy.bin").readBytes()
        assertFalse(primary.contentEquals(copy))
        assertFalse(primary.toList().windowed(32).any { it == value.toList() })
        assertEquals(1, keys.creates)
    }

    @Test fun successfulEncryptionDoesNotHideColdUnwrapFailure() {
        val root = temporary.newFolder()
        val keys = DeviceKeys().apply { rejectColdRead = true }
        assertThrows(UnrecoverableKeyException::class.java) {
            StateKeyVault(root, keys).writeFresh(namespace, value)
        }
        assertEquals(1, keys.creates)
        // The caller must not publish a pointer just because encryption worked.
        assertTrue(File(root, "$namespace/primary.bin").isFile)
    }

    @Test fun missingSystemKeyNeverGeneratesReplacementOrDeletesCiphertext() {
        val root = temporary.newFolder()
        val keys = DeviceKeys()
        StateKeyVault(root, keys).writeFresh(namespace, value)
        val file = File(root, "$namespace/primary.bin")
        val before = file.readBytes()
        keys.records.clear()
        assertThrows(UnrecoverableKeyException::class.java) { StateKeyVault(root, keys).read(namespace, false) }
        assertEquals(1, keys.creates)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun corruptPrimaryStillAllowsIndependentCopy() {
        val root = temporary.newFolder()
        val keys = DeviceKeys()
        val vault = StateKeyVault(root, keys)
        vault.writeFresh(namespace, value)
        val file = File(root, "$namespace/primary.bin")
        val bytes = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        assertThrows(AEADBadTagException::class.java) { vault.read(namespace, false) }
        assertArrayEquals(value, vault.read(namespace, true))
        assertThrows(IllegalStateException::class.java) { vault.writeFresh(namespace, ByteArray(32)) }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun absentRecordsAndInvalidPathsDoNotCreateKeys() {
        val root = temporary.newFolder()
        val keys = DeviceKeys()
        val vault = StateKeyVault(root, keys)
        assertNull(vault.read(namespace, false))
        for (path in listOf("../elsewhere", "", "state_a", "$namespace/other")) {
            assertThrows(IllegalArgumentException::class.java) { vault.read(path, false) }
        }
        assertEquals(0, keys.creates)
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
