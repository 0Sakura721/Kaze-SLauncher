package com.kaze.newage.testutil

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 测试用的最小 NBT / 世界存档构造器。
 *
 * `level.dat` 是 gzip 压缩的 NBT；这里只写被测代码会读的那几个字段
 * （LevelName / Version.Name / LastPlayed），足以验证"能不能从存档里读出世界名"。
 */
object TestNbt {

    fun levelDat(
        levelName: String,
        version: String = "1.21.1",
        lastPlayed: Long = 1_700_000_000_000L,
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            val out = DataOutputStream(gz)
            out.writeByte(10)          // root compound
            out.writeUTF("")
            // Data: compound
            out.writeByte(10)
            out.writeUTF("Data")
            out.writeByte(8)           // string
            out.writeUTF("LevelName")
            out.writeUTF(levelName)
            // Version: compound { Name }
            out.writeByte(10)
            out.writeUTF("Version")
            out.writeByte(8)
            out.writeUTF("Name")
            out.writeUTF(version)
            out.writeByte(0)           // end Version
            out.writeByte(4)           // long
            out.writeUTF("LastPlayed")
            out.writeLong(lastPlayed)
            out.writeByte(0)           // end Data
            out.writeByte(0)           // end root
            out.flush()
        }
        return bos.toByteArray()
    }

    /** 把若干条目打成 zip（路径用 `/` 分隔） */
    fun zip(file: java.io.File, entries: Map<String, ByteArray>): java.io.File {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return file
    }
}
