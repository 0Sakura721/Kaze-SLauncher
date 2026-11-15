package com.kaze.newage.testutil

import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 单测用的 jar 构造器。
 *
 * 真实模组 jar 不可能进仓库（几十 MB、版权），所以测试自己拼一个**结构最小但格式正确**的
 * jar：元数据文件 + 图标条目。被测代码关心的是"能不能从 zip 里正确读出字段"，
 * 而不是 jar 里真的有一份可玩的模组。
 */
object TestJars {

    /** 最小 PNG 魔数 + 填充：图标缓存只校验魔数（不真正解码），单测足够 */
    val PNG_BYTES: ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32) { it.toByte() }

    fun zip(file: File, entries: Map<String, ByteArray>): File {
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

    /** Fabric 模组：`fabric.mod.json` + `assets/<id>/icon.png` */
    fun fabricMod(
        file: File,
        id: String = "lithium",
        name: String = "Lithium",
        version: String = "0.14.3+mc1.21.1",
        mcRange: String = ">=1.21 <1.22",
        icon: Boolean = true,
        description: String = "no-op optimizer",
    ): File {
        val json = """
            {"schemaVersion":1,"id":"$id","name":"$name","version":"$version",
             "description":"$description","authors":["jellysquid3","2No2Name"],
             "icon":"assets/$id/icon.png","environment":"*",
             "depends":{"minecraft":"$mcRange","fabricloader":">=0.15"}}
        """.trimIndent()
        val entries = linkedMapOf<String, ByteArray>("fabric.mod.json" to json.toByteArray())
        if (icon) entries["assets/$id/icon.png"] = PNG_BYTES
        return zip(file, entries)
    }

    /** Forge 模组：`META-INF/mods.toml` + 根目录 logo */
    fun forgeMod(
        file: File,
        id: String = "jei",
        name: String = "Just Enough Items",
        logo: Boolean = true,
    ): File {
        val toml = """
            modLoader="javafml"
            loaderVersion="[47,)"
            license="MIT"

            [[mods]]
            modId="$id"
            version="15.2.0"
            displayName="$name"
            authors="mezz"
            logoFile="logo.png"
            description='''Adds item lookup'''

            [[dependencies.$id]]
            modId="minecraft"
            mandatory=true
            versionRange="[1.21,1.22)"
            ordering="NONE"
            side="BOTH"
        """.trimIndent()
        val entries = linkedMapOf<String, ByteArray>("META-INF/mods.toml" to toml.toByteArray())
        if (logo) entries["logo.png"] = PNG_BYTES
        return zip(file, entries)
    }

    /** 没有元数据、只有图标的 jar（手搓/老式模组） */
    fun bareJar(file: File, icon: Boolean = true): File {
        val entries = linkedMapOf<String, ByteArray>("some/class.class" to ByteArray(8))
        if (icon) entries["assets/mystery/icon.png"] = PNG_BYTES
        return zip(file, entries)
    }

    fun instance(
        dir: File,
        core: CoreType = CoreType.FABRIC,
        mc: String = "1.21.1",
        name: String = "测试服",
    ): ServerInstance = ServerInstance(
        name = name,
        coreType = core,
        mcVersion = mc,
        dir = dir,
    )
}
