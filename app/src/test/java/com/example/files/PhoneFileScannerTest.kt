package com.example.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhoneFileScannerTest {

    @Test
    fun `excludes Android folder, system roots, app private package paths, and trash root`() {
        assertTrue(PhoneFileScanner.isExcludedPath("/storage/emulated/0/Android/data/com.foo/cache"))
        assertTrue(PhoneFileScanner.isExcludedPath("/storage/emulated/0/Android/obb/com.game"))
        assertTrue(PhoneFileScanner.isExcludedPath("/storage/emulated/0/Android"))
        assertTrue(PhoneFileScanner.isExcludedPath("/system/framework/core.jar"))
        assertTrue(PhoneFileScanner.isExcludedPath("/data/data/com.aistudio.phoneagent.wakeel/files"))
        assertTrue(
            PhoneFileScanner.isExcludedPath(
                "/storage/emulated/0/Downloads/com.aistudio.phoneagent.wakeel/backup.bin",
                appPackageName = "com.aistudio.phoneagent.wakeel"
            )
        )
        assertTrue(
            PhoneFileScanner.isExcludedPath(
                "/tmp/custom_trash/item/a.tmp",
                trashRootPath = "/tmp/custom_trash"
            )
        )
        assertFalse(PhoneFileScanner.isExcludedPath("/storage/emulated/0/Download/EmptyDir"))
    }

    @Test
    fun `scans empty folders, temp, cache, duplicate, and large files while skipping Android dir`() {
        val tempRoot = Files.createTempDirectory("wakeel_scanner_test").toFile()
        try {
            val emptyDir = File(tempRoot, "EmptyFolderOne").apply { mkdirs() }
            val androidDir = File(tempRoot, "Android/data/some.app").apply { mkdirs() }
            File(androidDir, "should_not_touch.tmp").writeText("ignored")

            val cacheDir = File(tempRoot, "MyApp/.cache").apply { mkdirs() }
            val cacheFile = File(cacheDir, "thumb.dat").apply { writeText("cached-bytes") }

            val tempFile = File(tempRoot, "Downloads/draft.tmp").apply {
                parentFile?.mkdirs()
                writeText("temp-content")
            }

            val dup1 = File(tempRoot, "Documents/photo_copy1.jpg").apply {
                parentFile?.mkdirs()
                writeText("identical-binary-payload-12345")
                setLastModified(1_000_000L)
            }
            val dup2 = File(tempRoot, "Downloads/photo_copy2.jpg").apply {
                parentFile?.mkdirs()
                writeText("identical-binary-payload-12345")
                setLastModified(2_000_000L)
            }

            val largeFile = File(tempRoot, "Movies/big_clip.mp4").apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(2048) { 7 })
            }

            val scanner = PhoneFileScanner()
            val report = scanner.scan(
                roots = listOf(tempRoot),
                accessMode = StorageAccessMode.ALL_FILES,
                appPackageName = "com.aistudio.phoneagent.wakeel",
                largeFileThresholdBytes = 1024L
            )

            assertTrue(report.excludedSystemCount >= 1)
            assertEquals(1, report.emptyFoldersCount)
            assertEquals(emptyDir.absolutePath, report.itemsByCategory(FileScanCategory.EMPTY_FOLDER).first().path)

            assertTrue(report.itemsByCategory(FileScanCategory.CACHE).any { it.path == cacheFile.absolutePath })
            assertTrue(report.itemsByCategory(FileScanCategory.TEMPORARY).any { it.path == tempFile.absolutePath })

            val dups = report.itemsByCategory(FileScanCategory.DUPLICATE)
            assertEquals(1, dups.size)
            assertEquals(dup2.absolutePath, dups.first().path)
            assertFalse(dups.any { it.path == dup1.absolutePath })

            assertTrue(report.itemsByCategory(FileScanCategory.LARGE).any { it.path == largeFile.absolutePath })
            assertFalse(report.items.any { it.path.contains("/Android") })
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
