package com.iaandroid17.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class SafeArchiveReaderTest {
    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Test fun readsRegularEntries() {
        val items = SafeArchiveReader.readZip(archive("docs/a.txt" to "hello", "b.md" to "world"))
        assertEquals(2, items.size)
        assertEquals("docs/a.txt", items[0].path)
        assertEquals("hello", items[0].bytes.toString(Charsets.UTF_8))
    }

    @Test fun traversalPathsAreRejectedWithoutExtraction() {
        val items = SafeArchiveReader.readZip(archive("../escape.txt" to "bad", "ok.txt" to "fine"))
        assertEquals(1, items.size)
        assertEquals("ok.txt", items.single().path)
    }

    @Test fun emptyArchivePayloadFailsClearly() {
        try {
            SafeArchiveReader.readZip(byteArrayOf())
            fail("expected IOException")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("пуст"))
        }
    }

    @Test fun rejectsExcessiveEntryCount() {
        try {
            SafeArchiveReader.readZip(
                archive("a.txt" to "a", "b.txt" to "b"),
                SafeArchiveReader.Limits(maxEntries = 1)
            )
            fail("expected IOException")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("много файлов"))
        }
    }

    @Test fun rejectsAbsoluteAndAmbiguousPaths() {
        val items = SafeArchiveReader.readZip(
            archive("/root.txt" to "x", "C:/drive.txt" to "y", "a//b.txt" to "z", "valid.txt" to "ok")
        )
        assertEquals(listOf("valid.txt"), items.map { it.path })
    }

    @Test fun enforcesExpandedPerEntryAndAggregateLimits() {
        try {
            SafeArchiveReader.readZip(
                archive("a.txt" to "12345", "b.txt" to "67890"),
                SafeArchiveReader.Limits(maxEntryBytes = 8, maxTotalBytes = 9)
            )
            fail("expected IOException")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("лимит") || expected.message!!.contains("превышает"))
        }
    }
}
