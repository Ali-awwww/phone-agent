package com.example.files

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class FileTrashManager(
    private val trashRootDir: File,
    private val appPackageName: String = ""
) {
    companion object {
        const val RETENTION_DAYS: Long = 7L
        const val RETENTION_MILLIS: Long = RETENTION_DAYS * 24L * 60L * 60L * 1000L
        private const val MANIFEST_FILE_NAME = "wakeel_trash_manifest.tsv"
    }

    private val manifestFile: File
        get() = File(trashRootDir, MANIFEST_FILE_NAME)

    private fun ensureTrashDir() {
        if (!trashRootDir.exists()) {
            trashRootDir.mkdirs()
        }
    }

    @Synchronized
    fun moveToTrash(
        item: ScannedFileItem,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<TrashItemRecord> {
        return runCatching {
            if (PhoneFileScanner.isExcludedPath(item.path, appPackageName, trashRootDir.absolutePath)) {
                throw SecurityException("مسار محمي لا يمكن حذفه: ${item.path}")
            }

            val source = File(item.path)
            if (!source.exists()) {
                throw IllegalStateException("العنصر غير موجود: ${item.path}")
            }

            ensureTrashDir()
            val id = UUID.randomUUID().toString()
            val recordTrashPath: String

            if (item.isDirectory) {
                if (!source.isDirectory) {
                    throw IllegalStateException("العنصر ليس مجلداً: ${item.path}")
                }
                // إعادة التحقق من الفراغ في لحظة الحذف نفسها
                val children = source.listFiles()
                    ?: throw IllegalStateException("تعذر قراءة محتوى المجلد لحظة الحذف: ${item.path}")
                if (children.isNotEmpty()) {
                    throw IllegalStateException("المجلد لم يعد فارغاً لحظة الحذف: ${item.path}")
                }
                if (!source.delete()) {
                    throw IllegalStateException("تعذر حذف المجلد الفارغ: ${item.path}")
                }
                recordTrashPath = ""
            } else {
                if (!source.isFile) {
                    throw IllegalStateException("العنصر ليس ملفاً: ${item.path}")
                }
                val itemBucket = File(trashRootDir, id).apply { mkdirs() }
                val destFile = File(itemBucket, source.name)
                copyFileVerified(source, destFile)
                if (!source.delete()) {
                    destFile.delete()
                    itemBucket.deleteRecursively()
                    throw IllegalStateException("تعذر إزالة الملف الأصلي بعد نسخه للسلة: ${item.path}")
                }
                recordTrashPath = destFile.absolutePath
            }

            val record = TrashItemRecord(
                id = id,
                originalPath = source.absolutePath,
                trashPath = recordTrashPath,
                name = item.name,
                isDirectory = item.isDirectory,
                sizeBytes = if (item.isDirectory) 0L else item.sizeBytes,
                category = item.category,
                trashedAtMillis = nowMillis,
                expiresAtMillis = nowMillis + RETENTION_MILLIS
            )

            val records = loadManifestLocked().toMutableList()
            records.add(record)
            saveManifestLocked(records)
            record
        }
    }

    @Synchronized
    fun listActiveItems(nowMillis: Long = System.currentTimeMillis()): List<TrashItemRecord> {
        purgeExpired(nowMillis)
        return loadManifestLocked().sortedByDescending { it.trashedAtMillis }
    }

    @Synchronized
    fun restoreItem(id: String): TrashRestoreOutcome {
        val records = loadManifestLocked().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) {
            return TrashRestoreOutcome(false, "العنصر غير موجود في سلة المهملات.")
        }
        val record = records[index]
        if (PhoneFileScanner.isExcludedPath(record.originalPath, appPackageName, trashRootDir.absolutePath)) {
            return TrashRestoreOutcome(false, "لا يمكن الاستعادة إلى مسار نظام محمي.")
        }

        val target = resolveSafeRestoreTarget(File(record.originalPath), record.isDirectory)
        return runCatching {
            if (record.isDirectory) {
                if (!target.exists() && !target.mkdirs()) {
                    throw IllegalStateException("تعذر إنشاء المجلد المستعاد.")
                }
            } else {
                val trashPayload = File(record.trashPath)
                if (!trashPayload.exists() || !trashPayload.isFile) {
                    throw IllegalStateException("بيانات الملف غير موجودة داخل سلة المهملات.")
                }
                target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                copyFileVerified(trashPayload, target)
                trashPayload.parentFile?.deleteRecursively()
            }
            records.removeAt(index)
            saveManifestLocked(records)
            TrashRestoreOutcome(true, "تمت استعادة «${record.name}» إلى: ${target.absolutePath}")
        }.getOrElse { err ->
            TrashRestoreOutcome(false, "تعذرت الاستعادة: ${err.localizedMessage ?: "خطأ غير معروف"}")
        }
    }

    @Synchronized
    fun purgeExpired(nowMillis: Long = System.currentTimeMillis()): TrashPurgeOutcome {
        val records = loadManifestLocked()
        if (records.isEmpty()) return TrashPurgeOutcome(0, 0L)

        val remaining = mutableListOf<TrashItemRecord>()
        var purgedCount = 0
        var freedBytes = 0L

        for (record in records) {
            if (nowMillis >= record.expiresAtMillis) {
                deleteTrashPayload(record)
                purgedCount++
                freedBytes += record.sizeBytes
            } else {
                remaining.add(record)
            }
        }

        if (purgedCount > 0) {
            saveManifestLocked(remaining)
        }
        return TrashPurgeOutcome(purgedCount = purgedCount, freedBytes = freedBytes)
    }

    @Synchronized
    fun permanentlyDeleteItem(id: String): Boolean {
        val records = loadManifestLocked().toMutableList()
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return false
        val record = records.removeAt(index)
        deleteTrashPayload(record)
        saveManifestLocked(records)
        return true
    }

    private fun deleteTrashPayload(record: TrashItemRecord) {
        if (record.trashPath.isNotBlank()) {
            val payload = File(record.trashPath)
            val bucket = payload.parentFile
            if (bucket != null && bucket.parentFile?.canonicalPath == runCatching { trashRootDir.canonicalPath }.getOrNull()) {
                bucket.deleteRecursively()
            } else {
                payload.delete()
            }
        }
    }

    private fun resolveSafeRestoreTarget(desired: File, isDirectory: Boolean): File {
        if (!desired.exists()) return desired
        val parent = desired.parentFile ?: return desired
        val baseName = desired.name
        val dotIndex = if (isDirectory) -1 else baseName.lastIndexOf('.')
        val stem = if (dotIndex > 0) baseName.substring(0, dotIndex) else baseName
        val ext = if (dotIndex > 0) baseName.substring(dotIndex) else ""
        var suffix = 1
        while (suffix < 1000) {
            val candidate = File(parent, "${stem}_restored_${suffix}$ext")
            if (!candidate.exists()) return candidate
            suffix++
        }
        return File(parent, "${stem}_restored_${System.currentTimeMillis()}$ext")
    }

    private fun copyFileVerified(source: File, dest: File) {
        val expectedLength = source.length()
        val buffer = ByteArray(64 * 1024)
        FileInputStream(source).use { input ->
            FileOutputStream(dest).use { output ->
                var read = input.read(buffer)
                while (read > 0) {
                    output.write(buffer, 0, read)
                    read = input.read(buffer)
                }
                output.flush()
            }
        }
        if (dest.length() != expectedLength) {
            dest.delete()
            throw IllegalStateException("تفاوت حجم الملف المنقول إلى السلة عن الحجم الأصلي.")
        }
    }

    private fun loadManifestLocked(): List<TrashItemRecord> {
        val file = manifestFile
        if (!file.exists() || !file.isFile) return emptyList()
        return runCatching {
            file.readLines(Charsets.UTF_8)
                .filter { it.isNotBlank() }
                .mapNotNull { parseLine(it) }
        }.getOrDefault(emptyList())
    }

    private fun saveManifestLocked(records: List<TrashItemRecord>) {
        ensureTrashDir()
        val content = records.joinToString("\n") { encodeLine(it) }
        manifestFile.writeText(content, Charsets.UTF_8)
    }

    private fun encodeLine(record: TrashItemRecord): String {
        return listOf(
            escapeField(record.id),
            escapeField(record.originalPath),
            escapeField(record.trashPath),
            escapeField(record.name),
            record.isDirectory.toString(),
            record.sizeBytes.toString(),
            record.category.name,
            record.trashedAtMillis.toString(),
            record.expiresAtMillis.toString()
        ).joinToString("\t")
    }

    private fun parseLine(line: String): TrashItemRecord? {
        val parts = line.split('\t')
        if (parts.size < 9) return null
        val category = FileScanCategory.entries.firstOrNull { it.name == parts[6] }
            ?: FileScanCategory.TEMPORARY
        return TrashItemRecord(
            id = unescapeField(parts[0]),
            originalPath = unescapeField(parts[1]),
            trashPath = unescapeField(parts[2]),
            name = unescapeField(parts[3]),
            isDirectory = parts[4].toBooleanStrictOrNull() ?: false,
            sizeBytes = parts[5].toLongOrNull() ?: 0L,
            category = category,
            trashedAtMillis = parts[7].toLongOrNull() ?: 0L,
            expiresAtMillis = parts[8].toLongOrNull() ?: 0L
        )
    }

    private fun escapeField(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun unescapeField(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> sb.append('\\')
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> {
                        sb.append(c)
                        sb.append(value[i + 1])
                    }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
