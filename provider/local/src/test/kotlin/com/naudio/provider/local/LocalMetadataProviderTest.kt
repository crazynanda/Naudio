package com.naudio.provider.local

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Deterministic MediaStore tests: a fake ContentProvider registered under the
 * MediaStore audio authority serves MatrixCursor rows. No device, no flakiness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalMetadataProviderTest {

    private class FakeMediaStoreProvider : ContentProvider() {
        var permissionDenied = false
        val rows = mutableListOf(
            arrayOf<Any?>(1L, "Song One", "Artist One", "Album One", 100_000L),
            arrayOf<Any?>(2L, "Song Two", "Artist Two", "Album Two", 200_000L),
            arrayOf<Any?>(3L, "Other Song", "Artist Three", "Album Three", 300_000L),
        )

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            if (permissionDenied) throw SecurityException("Permission denied: missing READ_MEDIA_AUDIO")
            val args = selectionArgs?.toList().orEmpty()
            val filtered = rows.filter { row ->
                when {
                    selection?.contains("_id", ignoreCase = true) == true -> row[0] == args.firstOrNull()?.toLongOrNull()
                    else -> {
                        val needle = args.firstOrNull()?.removeSurrounding("%")
                        needle == null || (row[1] as String).contains(needle, ignoreCase = true)
                    }
                }
            }
            // The provider pages client-side from the sorted cursor, so the
            // fake returns every matching row in sort order.
            return MatrixCursor(projection ?: PROJECTION).apply {
                filtered.forEach { addRow(it) }
            }
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

        companion object {
            val PROJECTION = arrayOf("_id", "title", "artist", "album", "duration")
            const val AUTHORITY = "media"
        }
    }

    private fun newProvider(permissionDenied: Boolean = false): LocalMetadataProvider {
        val controller = Robolectric.buildContentProvider(FakeMediaStoreProvider::class.java)
            .create(FakeMediaStoreProvider.AUTHORITY)
        val fake = controller.get() as FakeMediaStoreProvider
        fake.permissionDenied = permissionDenied
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return LocalMetadataProvider(context)
    }

    @Test
    fun `search maps MediaStore rows to tracks`() = runTest {
        val provider = newProvider()
        val page = provider.searchTracks("Song")
        assertEquals(3, page.items.size)
        assertEquals("1", page.items[0].id)
        assertEquals("local", page.items[0].providerId)
        assertEquals("Song One", page.items[0].title)
        assertEquals("Artist One", page.items[0].artist)
        assertEquals("Album One", page.items[0].album)
        assertEquals(100_000L, page.items[0].durationMs)
    }

    @Test
    fun `search filters by title substring`() = runTest {
        val provider = newProvider()
        val page = provider.searchTracks("Other")
        assertEquals(1, page.items.size)
        assertEquals("Other Song", page.items[0].title)
    }

    @Test
    fun `search with no matches returns empty page with null nextOffset`() = runTest {
        val provider = newProvider()
        val page = provider.searchTracks("no-such-track")
        assertTrue(page.items.isEmpty())
        assertNull(page.nextOffset)
    }

    @Test
    fun `search on a full page reports nextOffset`() = runTest {
        val provider = newProvider()
        val page = provider.searchTracks("Song", offset = 0, limit = 2)
        assertEquals(2, page.items.size)
        assertEquals(2, page.nextOffset)
    }

    @Test
    fun `blank query short-circuits to an empty page`() = runTest {
        val provider = newProvider()
        val page = provider.searchTracks("   ")
        assertTrue(page.items.isEmpty())
        assertNull(page.nextOffset)
    }

    @Test
    fun `lookup returns the matching track`() = runTest {
        val provider = newProvider()
        val track = provider.lookupTrack("2")
        assertEquals("2", track?.id)
        assertEquals("Song Two", track?.title)
    }

    @Test
    fun `lookup with unknown or malformed id returns null`() = runTest {
        val provider = newProvider()
        assertNull(provider.lookupTrack("999"))
        assertNull(provider.lookupTrack("not-a-number"))
    }

    @Test
    fun `security exception propagates when permission is denied`() = runTest {
        val provider = newProvider(permissionDenied = true)
        try {
            provider.searchTracks("Song")
            fail("expected SecurityException")
        } catch (expected: SecurityException) {
            assertTrue(expected.message!!.contains("READ_MEDIA_AUDIO"))
        }
    }
}
