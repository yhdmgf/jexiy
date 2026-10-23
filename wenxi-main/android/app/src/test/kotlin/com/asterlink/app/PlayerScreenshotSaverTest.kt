package com.asterlink.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PlayerScreenshotSaverTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAABAAAAAJCAIAAAC0SDtlAAAAFElEQVR4nGNwaDhAEmIY1TAoNAAA0JDYAfmWJooAAAAASUVORK5CYII=")
    private class ImageProvider(private val file: File, private val fail: Boolean = false) : ContentProvider() {
        var inserted: ContentValues? = null
        var finished = false
        var removed = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            inserted = ContentValues(values)
            return Uri.parse("content://media/external/images/media/101")
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (fail) throw java.io.IOException("fixture full disk")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            finished = values?.getAsInteger(MediaStore.Images.Media.IS_PENDING) == 0
            return 1
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { removed = true; return 1 }
    }
    private fun provider(file: File, fail: Boolean = false): ImageProvider {
        val app = RuntimeEnvironment.getApplication()
        return ImageProvider(file, fail).also {
            it.attachInfo(app, ProviderInfo().apply { authority = "media" })
            ShadowContentResolver.registerProviderInternal("media", it)
        }
    }
    @Test fun savesPngToPicturesAndPublishesOnlyAfterWriting() {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "screenshot-test.png")
        val provider = provider(file)
        try {
            val saved = PlayerScreenshotSaver(app).save("视频截图.png", png)
            assertEquals("content://media/external/images/media/101", saved)
            assertArrayEquals(png, file.readBytes())
            assertEquals("Pictures/文析助手", provider.inserted!!.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
            assertEquals(1, provider.inserted!!.getAsInteger(MediaStore.Images.Media.IS_PENDING))
            assertTrue(provider.finished)
            assertFalse(provider.removed)
        } finally { file.delete() }
    }
    @Test fun writeFailureRemovesIncompleteMediaEntry() {
        val app = RuntimeEnvironment.getApplication()
        val provider = provider(File(app.cacheDir, "screenshot-fail.png"), true)
        assertThrows(Exception::class.java) { PlayerScreenshotSaver(app).save("截图.png", png) }
        assertTrue(provider.removed)
        assertFalse(provider.finished)
    }
    @Test fun invalidBytesAndPathNamesCannotCreateImages() {
        val app = RuntimeEnvironment.getApplication()
        val provider = provider(File(app.cacheDir, "screenshot-invalid.png"))
        assertThrows(IllegalArgumentException::class.java) { PlayerScreenshotSaver(app).save("../截图.png", png) }
        assertThrows(IllegalArgumentException::class.java) { PlayerScreenshotSaver(app).save("截图.png", ByteArray(20)) }
        assertNull(provider.inserted)
    }
    @Test @Config(sdk = [28]) fun oldAndroidFallsBackToSystemDocumentPicker() {
        assertNull(PlayerScreenshotSaver(RuntimeEnvironment.getApplication()).save("截图.png", png))
    }
}
