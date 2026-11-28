package com.example.files

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

class PhoneFileScanner {

    companion object {
        const val DEFAULT_LARGE_FILE_THRESHOLD_BYTES: Long = 50L * 1024L * 1024L

        private val SYSTEM_ROOT_PREFIXES = listOf(
            "/system",
            "/proc",
            "/sys",
            "/dev",
            "/vendor",
            "/oem",
            "/apex",
            "/product",
            "/odm",
            "/lost+found",
            "/data/system",
            "/data/data",
            "/data/user",
            "/data/app",
            "/data/misc",
            "/mnt/runtime",
            "/mnt/vendor"
        )

        private val PROTECTED_SEGMENT_NAMES = setOf(
            "android",
            ".android_secure",
            "lost.dir",
            "wakeel_trash"
        )

        private val CACHE_DIR_NAMES = setOf(
            "cache",
            ".cache",
            "thumbnails",
            ".thumbnails",
            "thumbcache",
            "image_manager_disk_cache",
            "code_cache"
        )

        private val TEMP_EXTENSIONS = setOf(
            "tmp",
            "temp",
            "bak",
            "part",
            "crdownload",
            "download",
            "log",
            "old",
            "chk"
        )

        fun isExcludedPath(
            path: String,
            appPackageName: String = "",
            trashRootPath: String? = null
        ): Boolean {
            if (path.isBlank()) return true
            val normalized = path.replace('\\', '/').trimEnd('/')
            val lower = normalized.lowercase()

            if (!trashRootPath.isNullOrBlank()) {
                val normalizedTrash = trashRootPath.replace('\\', '/').trimEnd('/').lowercase()
                if (lower == normalizedTrash || lower.startsWith("$normalizedTrash/")) {
                    return true
                }
            }

            if (SYSTEM_ROOT_PREFIXES.any { prefix -> lower == prefix || lower.startsWith("$prefix/") }) {
                return true
            }

            val segments = normalized.split('/').filter { it.isNotEmpty() }
            for (seg in segments) {
                val segLower = seg.lowercase()
                if (segLower in PROTECTED_SEGMENT_NAMES) {
                    return true
                }
                if (appPackageName.isNotBlank() && segLower == appPackageName.lowercase()) {
                    return true
                }
            }
            return false
        }

        fun isCacheDirectoryName(name: String): Boolean {
            val lower = name.lowercase()
            return lower in CACHE_DIR_NAMES || lower.startsWith(".thumbdata")
        }

        fun isTemporaryFileName(name: String): Boolean {
            val lower = name.lowercase()
            if (lower.startsWith("~$") || lower.startsWith(".~")) return true
            val dotIdx = lower.lastIndexOf('.')
            if (dotIdx <= 0 || dotIdx == lower.length - 1) return false
            val ext = lower.substring(dotIdx + 1)
            return ext in TEMP_EXTENSIONS
        }
    }

    fun scan(
        roots: List<File>,
        accessMode: StorageAccessMode,
        appPackageName: String,
        trashRootPath: String? = null,
        largeFileThresholdBytes: Long = DEFAULT_LARGE_FILE_THRESHOLD_BYTES,
        nowMillis: Long = System.currentTimeMillis()
    ): FileScanReport {
        val existingRoots = roots.filter { it.exists() && it.isDirectory }
        val rootCanonicalPaths = existingRoots.mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.toSet()
        val visitedDirs = HashSet<String>()

        val discoveredItems = mutableListOf<ScannedFileItem>()
        val claimedPaths = HashSet<String>()
        val regularFilesForSizeCheck = mutableListOf<File>()
        var excludedCount = 0

        data class DirEntry(val dir: File, val insideCacheDir: Boolean)
        val queue = ArrayDeque<DirEntry>()
        existingRoots.forEach { root ->
            val rootPath = root.absolutePath
            if (isExcludedPath(rootPath, appPackageName, trashRootPath)) {
                excludedCount++
            } else {
                queue.addLast(DirEntry(root, insideCacheDir = false))
            }
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val dir = current.dir
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: dir.absolutePath
            if (!visitedDirs.add(canonical)) continue

            if (isExcludedPath(dir.absolutePath, appPackageName, trashRootPath)) {
                excludedCount++
                continue
            }

            val children = dir.listFiles() ?: continue
            val isRoot = canonical in rootCanonicalPaths

            if (children.isEmpty()) {
                if (!isRoot && claimedPaths.add(dir.absolutePath)) {
                    discoveredItems.add(
                        ScannedFileItem(
                            path = dir.absolutePath,
                            name = dir.name,
                            sizeBytes = 0L,
                            category = FileScanCategory.EMPTY_FOLDER,
                            isDirectory = true,
                            lastModifiedMillis = dir.lastModified()
                        )
                    )
                }
                continue
            }

            for (child in children) {
                val childPath = child.absolutePath
                if (isExcludedPath(childPath, appPackageName, trashRootPath)) {
                    excludedCount++
                    continue
                }

                if (child.isDirectory) {
                    val isCacheDir = current.insideCacheDir || isCacheDirectoryName(child.name)
                    queue.addLast(DirEntry(child, insideCacheDir = isCacheDir))
                } else if (child.isFile) {
                    val size = child.length()
                    when {
                        current.insideCacheDir || child.name.lowercase().startsWith(".thumbdata") -> {
                            if (claimedPaths.add(childPath)) {
                                discoveredItems.add(
                                    ScannedFileItem(
                                        path = childPath,
                                        name = child.name,
                                        sizeBytes = size,
                                        category = FileScanCategory.CACHE,
                                        isDirectory = false,
                                        lastModifiedMillis = child.lastModified()
                                    )
                                )
                            }
                        }
                        isTemporaryFileName(child.name) -> {
                            if (claimedPaths.add(childPath)) {
                                discoveredItems.add(
                                    ScannedFileItem(
                                        path = childPath,
                                        name = child.name,
                                        sizeBytes = size,
                                        category = FileScanCategory.TEMPORARY,
                                        isDirectory = false,
                                        lastModifiedMillis = child.lastModified()
                                    )
                                )
                            }
                        }
                        else -> {
                            regularFilesForSizeCheck.add(child)
                        }
                    }
                }
            }
        }

        // كشف الملفات المكررة (تجميع حسب الحجم أولاً ثم SHA-256)
        val sizeGroups = regularFilesForSizeCheck
            .filter { it.length() > 0L }
            .groupBy { it.length() }
            .filterValues { it.size >= 2 }

        for ((size, sameSizeFiles) in sizeGroups) {
            val hashGroups = sameSizeFiles
                .mapNotNull { file ->
                    val hash = computeSha256(file) ?: return@mapNotNull null
                    hash to file
                }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size >= 2 }

            for ((hash, dupFiles) in hashGroups) {
                val sortedByOldest = dupFiles.sortedWith(
                    compareBy<File> { it.lastModified() }.thenBy { it.absolutePath }
                )
                // الإبقاء على النسخة الأقدم آمنة واقتراح النسخ المكررة الزائدة فقط
                sortedByOldest.drop(1).forEach { dupFile ->
                    if (claimedPaths.add(dupFile.absolutePath)) {
                        discoveredItems.add(
                            ScannedFileItem(
                                path = dupFile.absolutePath,
                                name = dupFile.name,
                                sizeBytes = size,
                                category = FileScanCategory.DUPLICATE,
                                isDirectory = false,
                                lastModifiedMillis = dupFile.lastModified(),
                                duplicateGroupKey = hash.take(12)
                            )
                        )
                    }
                }
            }
        }

        // كشف الملفات الكبيرة غير المصنفة مسبقاً كمكررة أو مؤقتة أو كاش
        for (file in regularFilesForSizeCheck) {
            val size = file.length()
            if (size >= largeFileThresholdBytes && claimedPaths.add(file.absolutePath)) {
                discoveredItems.add(
                    ScannedFileItem(
                        path = file.absolutePath,
                        name = file.name,
                        sizeBytes = size,
                        category = FileScanCategory.LARGE,
                        isDirectory = false,
                        lastModifiedMillis = file.lastModified()
                    )
                )
            }
        }

        val sortedItems = discoveredItems.sortedWith(
            compareBy<ScannedFileItem> { it.category.ordinal }
                .thenByDescending { it.sizeBytes }
                .thenBy { it.path }
        )

        return FileScanReport(
            accessMode = accessMode,
            scannedRoots = existingRoots.map { it.absolutePath },
            items = sortedItems,
            excludedSystemCount = excludedCount,
            scannedAtMillis = nowMillis
        )
    }

    private fun computeSha256(file: File): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            FileInputStream(file).use { input ->
                var read = input.read(buffer)
                while (read > 0) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }
}
