package com.example.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileTrashManagerTest {

    @Test
    fun `moves file to 7-day trash, restores safely, and auto-purges after 7 days`() {
        val sandbox = Files.createTempDirectory("wakeel_trash_test").toFile()
        try {
            val userDir = File(sandbox, "UserFiles").apply { mkdirs() }
            val trashDir = File(sandbox, "wakeel_trash")
            val manager = FileTrashManager(trashDir, appPackageName = "com.aistudio.phoneagent.wakeel")

            val targetFile = File(userDir, "old_report.tmp").apply { writeText("hello-trash") }
            val item = ScannedFileItem(
                path = targetFile.absolutePath,
                name = targetFile.name,
                sizeBytes = targetFile.length(),
                category = FileScanCategory.TEMPORARY,
                isDirectory = false,
                lastModifiedMillis = 10_000L
            )

            val t0 = 1_700_000_000_000L
            val moved = manager.moveToTrash(item, nowMillis = t0)
            assertTrue(moved.isSuccess)
            assertFalse(targetFile.exists())

            val record = moved.getOrThrow()
            assertEquals(t0 + FileTrashManager.RETENTION_MILLIS, record.expiresAtMillis)
            assertEquals(1, manager.listActiveItems(nowMillis = t0 + 1000L).size)

            val restoreOutcome = manager.restoreItem(record.id)
            assertTrue(restoreOutcome.restored)
            assertTrue(targetFile.exists())
            assertEquals("hello-trash", targetFile.readText())
            assertTrue(manager.listActiveItems(nowMillis = t0 + 2000L).isEmpty())

            // Move again and test automatic 7-day expiry purge
            val movedAgain = manager.moveToTrash(item, nowMillis = t0).getOrThrow()
            val beforeExpiry = manager.purgeExpired(nowMillis = t0 + FileTrashManager.RETENTION_MILLIS - 1L)
            assertEquals(0, beforeExpiry.purgedCount)
            assertEquals(1, manager.listActiveItems(nowMillis = t0 + 1000L).size)

            val afterExpiry = manager.purgeExpired(nowMillis = t0 + FileTrashManager.RETENTION_MILLIS)
            assertEquals(1, afterExpiry.purgedCount)
            assertFalse(File(movedAgain.trashPath).exists())
            assertTrue(manager.listActiveItems(nowMillis = t0 + FileTrashManager.RETENTION_MILLIS + 1L).isEmpty())
        } finally {
            sandbox.deleteRecursively()
        }
    }

    @Test
    fun `re-verifies directory emptiness at deletion moment and rejects non-empty folder`() {
        val sandbox = Files.createTempDirectory("wakeel_empty_recheck").toFile()
        try {
            val userDir = File(sandbox, "UserFiles").apply { mkdirs() }
            val trashDir = File(sandbox, "wakeel_trash")
            val manager = FileTrashManager(trashDir, appPackageName = "com.aistudio.phoneagent.wakeel")

            val folder = File(userDir, "MaybeEmpty").apply { mkdirs() }
            val item = ScannedFileItem(
                path = folder.absolutePath,
                name = folder.name,
                sizeBytes = 0L,
                category = FileScanCategory.EMPTY_FOLDER,
                isDirectory = true,
                lastModifiedMillis = 10_000L
            )

            // Simulate a file appearing inside the folder right before approved deletion runs
            File(folder, "new_file.txt").writeText("appeared")
            val result = manager.moveToTrash(item)
            assertTrue(result.isFailure)
            assertTrue(folder.exists())
            assertTrue(result.exceptionOrNull()?.message?.contains("لم يعد فارغاً") == true)
        } finally {
            sandbox.deleteRecursively()
        }
    }
}
