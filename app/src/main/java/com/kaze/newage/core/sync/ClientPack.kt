package com.kaze.newage.core.sync

import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.core.addons.ModMetadataReader
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.util.Downloader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 清单里的一个模组条目 */
@Serializable
data class PackMod(
    val fileName: String,
    val size: Long,
    val sha1: String,
    val modId: String = "",
    val name: String = "",
    val version: String = "",
)

/**
 * 客户端模组包清单（`manifest.json`）。
 *
 * [fingerprint] 是整个模组集合的内容指纹（文件名 + sha1 + MC 版本 + 加载器）：
 * 客户端只要比对它就知道"要不要更新"，不必逐个文件问服务端。
 */
@Serializable
data class PackManifest(
    val schema: Int = 1,
    val name: String,
    val mcVersion: String,
    val loader: String,
    val coreType: String,
    val mods: List<PackMod>,
    val totalBytes: Long,
    val generatedAt: Long,
    val fingerprint: String,
)

/**
 * 客户端整合包：把服务端的**启用中模组**打成玩家能直接导入的包。
 *
 * 解决的是"服务器加了个模组，玩家怎么拿到"这个原始问题：
 *  - 手机开服没有面板、没有 FTP，让玩家自己去 Modrinth 搜同名模组极易版本对不上
 *    （客户端版本与服务器不一致会被踢，提示还是 `Incompatible mod set`）；
 *  - 所以由服务端生成 `client-pack.zip`（jar + 清单 + 同步脚本）或 `.mrpack`
 *    （Modrinth App / Prism / HMCL 可直接导入）。
 *
 * **只打启用中的模组**：被 `.disabled` 的模组服务端根本不加载，
 * 发给客户端只会让客户端多装一个没用的模组（还可能因为重复依赖崩掉）。
 */
object ClientPack {

    private val json = Json { encodeDefaults = true }

    private val pretty = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    /** 清单缓存：算 sha1 要把每个 jar 读一遍，几百 MB 的目录不能每次请求都重算 */
    private data class CacheEntry(val signature: String, val manifest: PackManifest, val mods: List<File>)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** 参与同步的模组文件（启用中，按名字排序，顺序稳定便于比对） */
    fun modFiles(instance: ServerInstance): List<File> =
        AddonManager.installed(instance, AddonKind.MOD)
            .filter { AddonManager.isEnabled(it) && it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
            .sortedBy { it.name.lowercase(Locale.US) }

    /** 生效中模组目录的"签名"：任一文件名/大小/时间变化就会变 */
    private fun signature(files: List<File>, instance: ServerInstance): String = buildString {
        append(instance.mcVersion).append('|')
            .append(instance.coreType.name).append('|')
            .append(instance.name).append('|')
        files.forEach {
            append(it.name).append(':').append(it.length()).append(':').append(it.lastModified()).append(';')
        }
    }

    /**
     * 生成清单（带缓存）。
     *
     * 阻塞式：会为每个模组计算 SHA-1。调用方应在 IO 线程执行。
     */
    fun manifest(instance: ServerInstance): PackManifest {
        val files = modFiles(instance)
        val sig = signature(files, instance)
        cache[instance.id]?.takeIf { it.signature == sig }?.let { return it.manifest }

        val mods = files.map { f ->
            val meta = ModMetadataReader.read(f)
            PackMod(
                fileName = f.name,
                size = f.length(),
                sha1 = Downloader.sha1Of(f).orEmpty(),
                modId = meta.modId,
                name = meta.displayName,
                version = meta.version,
            )
        }
        val manifest = PackManifest(
            name = instance.name,
            mcVersion = instance.mcVersion,
            loader = AddonManager.loaderFor(instance, AddonKind.MOD),
            coreType = instance.coreType.displayName,
            mods = mods,
            totalBytes = mods.sumOf { it.size },
            generatedAt = System.currentTimeMillis(),
            fingerprint = fingerprintOf(mods, instance),
        )
        cache[instance.id] = CacheEntry(sig, manifest, files)
        return manifest
    }

    /** 清单 JSON（紧凑格式：给客户端脚本与工具解析） */
    fun manifestJson(instance: ServerInstance): String =
        json.encodeToString(PackManifest.serializer(), manifest(instance))

    /** 清单 JSON（缩进：给人看 / 排查用） */
    fun manifestJsonPretty(instance: ServerInstance): String =
        pretty.encodeToString(PackManifest.serializer(), manifest(instance))

    /**
     * `mods.txt`：`sha1<TAB>size<TAB>文件名` 每行一条。
     *
     * 单独给客户端脚本准备一份这个格式，而不是让脚本解析 JSON ——
     * 没有 jq 的手机/精简系统上解析 JSON 只能靠正则，文件名里一个引号就会崩。
     */
    fun modsTsv(instance: ServerInstance): String = buildString {
        manifest(instance).mods.forEach { m ->
            append(m.sha1).append('\t').append(m.size).append('\t').append(m.fileName).append('\n')
        }
    }

    /** 内容指纹：模组集合与版本信息的 sha1 前 16 位 */
    private fun fingerprintOf(mods: List<PackMod>, instance: ServerInstance): String {
        val raw = buildString {
            append(instance.mcVersion).append('|').append(instance.coreType.name).append('|')
            mods.forEach { append(it.fileName).append(':').append(it.sha1).append(';') }
        }
        return sha1Hex(raw.toByteArray())?.take(16) ?: Integer.toHexString(raw.hashCode())
    }

    /** 字节数组的 SHA-1（十六进制小写）；失败返回 null */
    internal fun sha1Hex(bytes: ByteArray): String? = runCatching {
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** 清掉某个实例的清单缓存（导入/删除模组后调用，避免发出过期清单） */
    fun invalidate(instanceId: String) {
        cache.remove(instanceId)
    }

    // ── 打包 ──

    /**
     * 生成客户端包（zip）：`mods/` + `manifest.json` + `mods.txt` + 同步脚本 + 说明。
     *
     * 采用 **NO_COMPRESSION**：jar 本身就是压缩包，再 deflate 一遍只是白烧 CPU
     * （几百 MB 的模组能多花十几秒），体积几乎不变。
     *
     * @param includeConfig 是否带上 `config/`（有些模组要求客户端配置一致，但配置可能含密钥，默认关）
     */
    fun writeZip(
        instance: ServerInstance,
        out: OutputStream,
        includeConfig: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ) {
        val manifest = manifest(instance)
        val files = cache[instance.id]?.mods ?: modFiles(instance)
        val total = (files.size + if (includeConfig) 1 else 0).coerceAtLeast(1)
        var done = 0
        ZipOutputStream(java.io.BufferedOutputStream(out)).use { zos ->
            zos.setLevel(Deflater.NO_COMPRESSION)

            fun putText(name: String, text: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            putText("manifest.json", pretty.encodeToString(PackManifest.serializer(), manifest))
            putText("mods.txt", modsTsv(instance))
            putText("README.txt", readme(instance))
            putText("client-sync/kaze-sync.sh", syncScriptSh())
            putText("client-sync/kaze-sync.ps1", syncScriptPs1())

            files.forEach { f ->
                onProgress(done.toFloat() / total, "打包 ${f.name}")
                zos.putNextEntry(ZipEntry("mods/${f.name}"))
                f.inputStream().use { it.copyTo(zos, 128 * 1024) }
                zos.closeEntry()
                done++
            }
            if (includeConfig) {
                val configDir = File(instance.dir, "config")
                if (configDir.isDirectory) {
                    onProgress(done.toFloat() / total, "打包 config/")
                    configDir.walkTopDown().filter { it.isFile }.take(400).forEach { f ->
                        val rel = f.relativeTo(instance.dir).invariantSeparatorsPath
                        zos.putNextEntry(ZipEntry(rel))
                        f.inputStream().use { it.copyTo(zos, 64 * 1024) }
                        zos.closeEntry()
                    }
                }
            }
        }
        onProgress(1f, "完成")
    }

    /**
     * 生成 `.mrpack`（Modrinth 整合包格式）。
     *
     * jar 放在 `overrides/mods/`：Modrinth App / Prism / HMCL 都会把 overrides 原样铺进实例目录，
     * 所以**不需要**在 index 里写下载地址 —— 这让"服务端自己分发模组"在离线/内网也能用。
     * `dependencies` 里写出 MC 版本与加载器，启动器才知道该建什么类型的实例。
     */
    fun writeMrpack(
        instance: ServerInstance,
        out: OutputStream,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ) {
        val manifest = manifest(instance)
        val files = cache[instance.id]?.mods ?: modFiles(instance)
        val loaderKey = when (instance.coreType) {
            CoreType.FORGE -> "forge"
            CoreType.NEOFORGE -> "neoforge"
            else -> "fabric-loader"
        }
        val loaderVersion = instance.coreBuild.ifBlank { "0.16.0" }
        val index = buildString {
            append("{\n")
            append("  \"formatVersion\": 1,\n")
            append("  \"game\": \"minecraft\",\n")
            append("  \"versionId\": \"").append(escapeJson(packVersion(manifest))).append("\",\n")
            append("  \"name\": \"").append(escapeJson(instance.name)).append("\",\n")
            append("  \"summary\": \"")
                .append(escapeJson("${instance.coreType.displayName} 客户端整合包"))
                .append("\",\n")
            append("  \"files\": [],\n")
            append("  \"dependencies\": {\"minecraft\": \"")
                .append(escapeJson(instance.mcVersion.ifBlank { "1.21.1" })).append('"')
            if (loaderVersion.isNotBlank()) {
                append(", \"").append(loaderKey).append("\": \"")
                    .append(escapeJson(loaderVersion)).append('"')
            }
            append("}\n}\n")
        }
        ZipOutputStream(java.io.BufferedOutputStream(out)).use { zos ->
            zos.setLevel(Deflater.NO_COMPRESSION)
            zos.putNextEntry(ZipEntry("modrinth.index.json"))
            zos.write(index.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            files.forEachIndexed { idx, f ->
                onProgress(idx.toFloat() / files.size.coerceAtLeast(1), "打包 ${f.name}")
                zos.putNextEntry(ZipEntry("overrides/mods/${f.name}"))
                f.inputStream().use { it.copyTo(zos, 128 * 1024) }
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("overrides/manifest.json"))
            zos.write(pretty.encodeToString(PackManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        onProgress(1f, "完成")
    }

    private fun packVersion(m: PackManifest): String {
        val suffix = if (m.fingerprint.isBlank()) "" else "-${m.fingerprint.take(6)}"
        return "1.0.0$suffix"
    }

    // ── 文本资源（HTTP 端点与 zip 共用同一份）──

    fun readme(instance: ServerInstance): String = """
        ${instance.name} — 客户端模组包
        ========================================

        包含 ${modFiles(instance).size} 个模组（只含服务端当前启用中的模组）。

        怎么用（任选一种）：
        1. 直接导入整合包
           把 client-pack.zip 拖进 Modrinth App / Prism Launcher / HMCL 的「导入整合包」。
           它会按清单里的 MC 版本与加载器建实例，并把模组放进 mods/。

        2. 自动同步（推荐给玩家）
           在服务端打开「客户端同步」后，把界面给出的地址发给玩家：

             sh   client-sync/kaze-sync.sh http://<服务端IP>:<端口> <令牌> ~/.minecraft/mods
             pwsh client-sync/kaze-sync.ps1 -Base http://<服务端IP>:<端口> -Token <令牌> -Dest .\mods

           脚本按 sha1 比对，只下载新增/变化的模组；服务端换版本后重跑一次即可。

        重要提示
        - 客户端模组必须与服务端完全一致，否则会被服务器踢下线。
        - 服务端停用（.disabled）的模组不会出现在本包里。
        - 如果提示缺少依赖模组，在服务端装上后重新生成即可。
    """.trimIndent()

    /** POSIX sh 同步脚本；`$1` 服务端地址，`$2` 令牌，`$3` 本地 mods 目录，`--prune` 删除多余模组 */
    fun syncScriptSh(): String = script(
        """
        #!/bin/sh
        # Kaze SLauncher 客户端模组同步
        # 用法：./kaze-sync.sh <服务端地址> [令牌] [本地mods目录] [--prune]
        #   --prune 会删除本地 mods 目录里清单之外的 .jar（谨慎：会删掉你手动加的模组）
        set -u
        BASE="${'§'}{1:-}"
        TOKEN="${'§'}{2:-}"
        DEST="${'§'}{3:-./mods}"
        PRUNE=0
        for a in "${'§'}@"; do [ "${'§'}a" = "--prune" ] && PRUNE=1; done

        if [ -z "${'§'}BASE" ]; then
          echo "用法: ${'§'}0 <服务端地址> [令牌] [本地mods目录] [--prune]"
          echo "示例: ${'§'}0 http://192.168.1.5:8123 abcdef ./mods"
          exit 1
        fi
        command -v curl >/dev/null 2>&1 || { echo "需要 curl"; exit 1; }
        if command -v sha1sum >/dev/null 2>&1; then SHA="sha1sum"; else SHA="shasum -a 1"; fi

        mkdir -p "${'§'}DEST" || exit 1
        Q=""
        [ -n "${'§'}TOKEN" ] && Q="?token=${'§'}TOKEN"
        TMP="${'§'}(mktemp -d 2>/dev/null || echo "${'§'}DEST/.kaze-tmp")"
        mkdir -p "${'§'}TMP"
        trap 'rm -rf "${'§'}TMP"' EXIT

        echo "→ 拉取清单 ${'§'}BASE/mods.txt"
        curl -fsSL --max-time 30 "${'§'}BASE/mods.txt${'§'}Q" -o "${'§'}TMP/mods.txt" || {
          echo "拉取清单失败（检查地址/令牌，或服务端是否开着客户端同步）"; exit 1; }

        ok=0; skip=0; fail=0
        TAB="${'§'}(printf '\t')"
        while IFS="${'§'}TAB" read -r sha size name; do
          [ -z "${'§'}name" ] && continue
          file="${'§'}DEST/${'§'}name"
          if [ -f "${'§'}file" ] && [ "${'§'}( ${'§'}SHA "${'§'}file" | cut -d' ' -f1)" = "${'§'}sha" ]; then
            skip=${'§'}((skip + 1)); continue
          fi
          echo "↓ ${'§'}name"
          if curl -fsSL --max-time 600 -G --data-urlencode "f=${'§'}name" --data-urlencode "token=${'§'}TOKEN" \
               "${'§'}BASE/mod" -o "${'§'}TMP/dl"; then
            got="${'§'}( ${'§'}SHA "${'§'}TMP/dl" 2>/dev/null | cut -d' ' -f1)"
            if [ "${'§'}got" = "${'§'}sha" ]; then
              mv -f "${'§'}TMP/dl" "${'§'}file" && ok=${'§'}((ok + 1)) || fail=${'§'}((fail + 1))
            else
              echo "  ! 校验失败（服务端文件可能刚被替换，重跑一次）"; fail=${'§'}((fail + 1))
            fi
          else
            echo "  ! 下载失败"; fail=${'§'}((fail + 1))
          fi
        done < "${'§'}TMP/mods.txt"

        if [ "${'§'}PRUNE" = "1" ]; then
          echo "→ 清理清单之外的模组"
          for file in "${'§'}DEST"/*.jar; do
            [ -f "${'§'}file" ] || continue
            name="$(basename "${'§'}file")"
            if ! grep -qF "${'§'}name" "${'§'}TMP/mods.txt"; then
              echo "  - 删除 ${'§'}name"; rm -f "${'§'}file"
            fi
          done
        fi

        echo "完成：下载 ${'§'}ok 个，已是最新 ${'§'}skip 个，失败 ${'§'}fail 个"
        [ "${'§'}fail" = "0" ] || exit 2
        """,
    )

    /** PowerShell 同步脚本（Windows 玩家） */
    fun syncScriptPs1(): String = script(
        """
        # Kaze SLauncher 客户端模组同步（Windows PowerShell）
        # 用法：.\kaze-sync.ps1 -Base http://192.168.1.5:8123 -Token abcdef -Dest .\mods [-Prune]
        param(
            [Parameter(Mandatory=${'§'}true)][string]${'§'}Base,
            [string]${'§'}Token = "",
            [string]${'§'}Dest = ".\mods",
            [switch]${'§'}Prune
        )
        ${'§'}ErrorActionPreference = "Stop"
        ${'§'}Base = ${'§'}Base.TrimEnd('/')
        ${'§'}q = if (${'§'}Token) { "?token=" + [uri]::EscapeDataString(${'§'}Token) } else { "" }
        New-Item -ItemType Directory -Force -Path ${'§'}Dest | Out-Null

        Write-Host "→ 拉取清单 ${'§'}Base/mods.txt"
        ${'§'}lines = (Invoke-WebRequest -UseBasicParsing -Uri "${'§'}Base/mods.txt${'§'}q" -TimeoutSec 30).Content -split "`n"
        ${'§'}ok = 0; ${'§'}skip = 0; ${'§'}fail = 0
        ${'§'}names = New-Object System.Collections.Generic.List[string]
        foreach (${'§'}line in ${'§'}lines) {
            ${'§'}line = ${'§'}line.TrimEnd("`r")
            if (-not ${'§'}line) { continue }
            ${'§'}parts = ${'§'}line -split "`t"
            if (${'§'}parts.Count -lt 3) { continue }
            ${'§'}sha = ${'§'}parts[0]; ${'§'}name = ${'§'}parts[2]
            ${'§'}names.Add(${'§'}name)
            ${'§'}file = Join-Path ${'§'}Dest ${'§'}name
            if ((Test-Path ${'§'}file) -and ((Get-FileHash -Algorithm SHA1 ${'§'}file).Hash.ToLower() -eq ${'§'}sha)) {
                ${'§'}skip++; continue
            }
            Write-Host "↓ ${'§'}name"
            try {
                ${'§'}query = [uri]::EscapeDataString(${'§'}name)
                if (${'§'}Token) { ${'§'}query = ${'§'}query + "&token=" + [uri]::EscapeDataString(${'§'}Token) }
                ${'§'}tmp = "${'§'}file.part"
                Invoke-WebRequest -UseBasicParsing -Uri "${'§'}Base/mod?f=${'§'}query" -OutFile ${'§'}tmp -TimeoutSec 600
                if ((Get-FileHash -Algorithm SHA1 ${'§'}tmp).Hash.ToLower() -eq ${'§'}sha) {
                    Move-Item -Force ${'§'}tmp ${'§'}file; ${'§'}ok++
                } else {
                    Write-Host "  ! 校验失败（服务端文件可能刚被替换，重跑一次）"
                    Remove-Item ${'§'}tmp -ErrorAction SilentlyContinue; ${'§'}fail++
                }
            } catch {
                Write-Host ("  ! 下载失败: " + ${'§'}_.Exception.Message); ${'§'}fail++
            }
        }

        if (${'§'}Prune) {
            Write-Host "→ 清理清单之外的模组"
            Get-ChildItem ${'§'}Dest -Filter *.jar -File | ForEach-Object {
                if (-not ${'§'}names.Contains(${'§'}_.Name)) {
                    Write-Host ("  - 删除 " + ${'§'}_.Name); Remove-Item -Force ${'§'}_.FullName
                }
            }
        }
        Write-Host "完成：下载 ${'§'}ok 个，已是最新 ${'§'}skip 个，失败 ${'§'}fail 个"
        if (${'§'}fail -gt 0) { exit 2 }
        """,
    )

    /**
     * 脚本模板：用 `§` 代替 `$` 书写，最后统一换回来。
     * 直接在 Kotlin 字符串里写 `$` 需要 `${'$'}` 包一层，脚本可读性会被彻底毁掉。
     */
    private fun script(body: String): String = body.trimIndent().replace('§', '$')

    private fun escapeJson(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
