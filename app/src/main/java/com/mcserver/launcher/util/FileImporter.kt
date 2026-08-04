package com.mcserver.launcher.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 外部资源导入:通过 SAF 选择本地文件/目录,复制到 App 目录。
 * 本地优先设计——优先使用用户已有资源,不消耗流量。
 */
object FileImporter {

    /**
     * 从 SAF 目录 Uri 递归复制到目标目录。
     * @return 复制的文件数
     */
    suspend fun copyTree(context: Context, treeUri: Uri, destDir: File): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val root = DocumentFile.fromTreeUri(context, treeUri)
                    ?: return@withContext Result.failure(RuntimeException("无法读取所选目录"))
                destDir.mkdirs()
                var count = 0
                fun copyDir(doc: DocumentFile, target: File) {
                    target.mkdirs()
                    doc.listFiles().forEach { child ->
                        if (child.isDirectory) {
                            copyDir(child, File(target, child.name ?: "dir"))
                        } else if (child.isFile) {
                            val name = child.name ?: return@forEach
                            context.contentResolver.openInputStream(child.uri)?.use { input ->
                                FileOutputStream(File(target, name)).use { out -> input.copyTo(out) }
                            }
                            count++
                        }
                    }
                }
                copyDir(root, destDir)
                Result.success(count)
            } catch (e: Exception) {
                Logger.w("copyTree failed", e)
                Result.failure(e)
            }
        }

    /**
     * 从 SAF 文件 Uri 复制单个文件到目标目录。
     * @return 目标文件
     */
    suspend fun copyFile(context: Context, uri: Uri, destDir: File): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                destDir.mkdirs()
                // 优先从 URI 解析文件名
                var name = "imported.bin"
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0 && !cursor.isNull(idx)) name = cursor.getString(idx)
                        }
                    }
                } catch (_: Exception) {}
                val target = File(destDir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                } ?: return@withContext Result.failure(RuntimeException("无法读取所选文件"))
                Result.success(target)
            } catch (e: Exception) {
                Logger.w("copyFile failed", e)
                Result.failure(e)
            }
        }
}
