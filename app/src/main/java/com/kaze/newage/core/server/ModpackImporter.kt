package com.kaze.newage.core.server

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 整合包 zip 导入（issue #15 的「服务器整合包 zip 直接导入」）。
 *
 * ## 与「导入 server.jar」的区别
 * 单个 jar 导入只需要复制一个文件。整合包 zip 是一个**目录树**：mods / config / libraries /
 * 启动脚本，甚至已经装好的 Forge。所以这里要做三件事：
 *  1. **安全解压**（外部 zip 是不可信输入，见 [extract]）；
 *  2. **整理目录形态**：整合包普遍多一层壳（`MyPack-1.20.1/`）或 CurseForge 的 `overrides/`，
 *     不整理的话 `server.jar` 就不在实例根目录，启动器找不到它；
 *  3. **识别怎么启动**：目录里有 jar（直接 `-jar`）？有 `unix_args.txt`（Forge 系 `@argfile`）？
 *     还是只有 mods（缺服务端核心，需要用户补）？
 *
 * ## 为什么不猜 MC 版本
 * 猜不出来时**留空**并给一条 warning，而不是编一个 —— 版本错了会让 Java 选错、
 * 插件过滤与备份命名也全错。v0.6.0 起用户能在配置页/实例详情手动指定 Java，
 * 所以「留空 + 提示」是一条走得通的路。
 */
object ModpackImporter {

    /** 启动方式 */
    enum class Launch {
        /** 直接 `java -jar <jar>`（原版 / Paper / Fabric 启动器 jar） */
        JAR,

        /** Forge/NeoForge：`@user_jvm_args.txt @libraries/.../unix_args.txt` */
        FORGE_ARGS,

        /** 只有 mods 等文件，没有可用的服务端核心 */
        MODS_ONLY,
    }

    /** 识别结果 */
    data class Plan(
        val launch: Launch,
        /** [com.kaze.newage.data.model.CoreType] 的名字；认不出是 CUSTOM */
        val coreTypeName: String,
        val mcVersion: String,
        val javaMajor: Int,
        /** 应作为启动入口的 jar（[Launch.JAR] 时有值） */
        val launchJar: File?,
        /** Forge 的 `unix_args.txt`（[Launch.FORGE_ARGS] 时有值） */
        val argsFile: File?,
        /**
         * `unix_args.txt` 里列了、但文件不存在的依赖库。
         *
         * 非空表示这个整合包**不完整**（下到一半、或少了 libraries）。
         * 这时**绝不能**写 `.forge-installed` 标记 —— 标记一旦写错，每次启动都会跳过安装，
         * 永远修不回来（[com.kaze.newage.data.model.ServerInstance.forgeInstalled] 的注释里有实锤）。
         */
        val missingForgeLibs: List<String>,
        /** 给用户看的提醒（不阻断导入） */
        val warnings: List<String>,
        /** 为什么这么判（写进控制台，便于用户核对） */
        val evidence: String,
        /**
         * 识别出的整合包格式（Modrinth .mrpack / CurseForge .zip / 通用懒人包）。
         *
         * 三者决定了「还要不要联网下载」：通用懒人包的文件已经实体在包里，
         * Modrinth 只有引用清单（必须按 URL 下载），CurseForge 只有平台 ID
         * （免密钥拿不到下载地址，见 [ModpackManifest] 的说明）。
         */
        val format: ModpackManifest.Format = ModpackManifest.Format.Plain,
        val loader: ModpackManifest.Loader = ModpackManifest.Loader.UNKNOWN,
        val loaderVersion: String = "",
    )

    // ── 限额：外部 zip 是不可信输入 ──

    /** 条目数上限：正常整合包几千个文件，20 万已是异常 */
    private const val MAX_ENTRIES = 200_000

    /** 解压后总字节上限（防 zip 炸弹）：整合包含 libraries 通常 < 2GB */
    private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024

    /** 单条目字节上限：单个 mod 大不过几十 MB */
    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024

    /** 解压结果 */
    data class ExtractResult(
        val entryCount: Int,
        val totalBytes: Long,
        /** 被跳过的条目（路径非法或超限） */
        val skipped: List<String>,
    )

    /**
     * 解压到 [dest]。调用方负责保证 [dest] 已存在且为空（导入流程用新建的实例目录）。
     *
     * 安全措施（外部 zip 是不可信输入）：
     *  - **Zip Slip**：每个条目的 canonicalPath 必须落在 [dest] 之内，否则跳过该条目；
     *  - **条目数 / 总量 / 单条目**三项限额，超限即中止并抛错（zip 炸弹）；
     *  - 只写普通文件与目录，不还原符号链接（zip 里的符号链接在某些平台会指向任意路径）。
     *
     * 单个条目被跳过不中断整体（记进 [ExtractResult.skipped]），因为整合包里偶尔会有
     * 结构奇怪的文件，为它整包导入失败不值得；但**限额触发**必须抛错 —— 那意味着包本身有问题。
     */
    fun extract(
        input: InputStream,
        dest: File,
        onProgress: (done: Long) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): ExtractResult = extractWithLimits(
        input, dest, MAX_ENTRIES, MAX_TOTAL_BYTES, MAX_ENTRY_BYTES, onProgress, shouldCancel,
    )

    /**
     * 与 [extract] 相同，但限额可注入。
     *
     * 单独开一个 internal 入口是为了**能测**：真的去造 20 万个条目或 4GB 数据来验限额，
     * 测试要跑几分钟且吃内存；把限额当参数传进来，用 3 个条目就能把"超限即中止"这条路径
     * 真正跑到（而不是只写一个永远为真的断言）。
     */
    internal fun extractWithLimits(
        input: InputStream,
        dest: File,
        maxEntries: Int,
        maxTotalBytes: Long,
        maxEntryBytes: Long,
        onProgress: (done: Long) -> Unit = {},
        shouldCancel: () -> Boolean = { false },
    ): ExtractResult {
        val destCanonical = dest.canonicalPath + File.separator
        val skipped = mutableListOf<String>()
        var entries = 0
        var total = 0L
        val buf = ByteArray(256 * 1024)

        ZipInputStream(input).use { zin ->
            while (true) {
                if (shouldCancel()) throw InterruptedException("导入已取消")
                val entry = zin.nextEntry ?: break
                entries++
                if (entries > maxEntries) {
                    throw IllegalStateException(
                        "压缩包条目过多（超过 $maxEntries 个），可能是异常文件而非整合包",
                    )
                }
                val name = entry.name
                // 目录串与绝对路径都不可信；统一先规范化再判前缀
                val target = File(dest, name)
                val ok = runCatching { target.canonicalPath.startsWith(destCanonical) }
                    .getOrDefault(false)
                if (!ok) {
                    skipped += name
                    zin.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    zin.closeEntry()
                    continue
                }
                val declared = entry.size
                if (declared > maxEntryBytes) {
                    skipped += "$name（单文件 ${declared / 1024 / 1024} MB 超限）"
                    zin.closeEntry()
                    continue
                }
                target.parentFile?.mkdirs()
                // 单文件限额**不能只看 entry.size**：流式读取（SAF 打开 zip 就是流式，
                // ZipInputStream 也是）时本地文件头里的 size 常常是 0 或 -1，真正的长度
                // 在数据描述符里。只信它的话单文件限额就形同虚设 ——
                // 测试「单个超大文件被跳过而不是整包失败」抓到的正是这一点。
                // 所以：declared 只做"能提前拒绝就拒绝"，真限额在读取循环里边读边算。
                var entryBytes = 0L
                var overflow = false
                try {
                    target.outputStream().use { out ->
                        while (true) {
                            val n = zin.read(buf)
                            if (n <= 0) break
                            entryBytes += n
                            if (entryBytes > maxEntryBytes) {
                                // 超限即停：已写出的部分稍后删除，剩余字节交给 closeEntry 丢弃
                                overflow = true
                                break
                            }
                            total += n
                            if (total > maxTotalBytes) {
                                throw IllegalStateException(
                                    "解压总量超过 ${maxTotalBytes / 1024 / 1024 / 1024} GB，压缩包可能损坏或为恶意炸弹",
                                )
                            }
                            out.write(buf, 0, n)
                            onProgress(total)
                        }
                    }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: IllegalStateException) {
                    throw e   // 限额错误要往上抛，不当作"跳过这个文件"
                } catch (e: Exception) {
                    // 单个文件写不出来（名字过长、磁盘满前的个别错误）→ 记下来继续
                    skipped += "$name（写入失败：${e.message}）"
                    runCatching { target.delete() }
                }
                if (overflow) {
                    skipped += "$name（单文件超限：超过 ${maxEntryBytes / 1024} KB）"
                    runCatching { target.delete() }
                }
                zin.closeEntry()
            }
        }
        return ExtractResult(entries, total, skipped)
    }

    /**
     * 把「整包多一层目录」与「CurseForge 的 overrides/」摊平到实例根目录。
     *
     * 两种真实形态：
     *  1. `MyPack-1.20.1/{mods,config,server.jar,…}` —— 解压后根目录只有一个子目录；
     *  2. `{manifest.json, overrides/{mods,config,…}}` —— CurseForge 服务端包标准布局，
     *     真正的游戏文件在 `overrides/` 里。
     *
     * 摊平后 `server.jar` 才会落在实例根目录（[com.kaze.newage.data.model.ServerInstance.jarFile]
     * 只找根目录下的 jar）。
     *
     * @return 是否做过摊平（调用方据此写日志）
     */
    fun flatten(root: File): Boolean {
        var changed = false

        // ① 唯一的子目录当壳（且根目录没有散落文件）
        val topLevel = root.listFiles()?.toList().orEmpty()
        if (topLevel.size == 1 && topLevel[0].isDirectory) {
            val inner = topLevel[0]
            val moved = moveChildren(inner, root)
            if (moved) {
                runCatching { inner.delete() }   // 空目录才删得掉
                changed = true
            }
        }

        // ② overrides/ 与 server-overrides/ 摊平
        //    CurseForge 用 overrides；Modrinth 的服务端包还会额外带 server-overrides/
        for (name in listOf("overrides", "server-overrides")) {
            val d = File(root, name)
            if (!d.isDirectory) continue
            if (moveChildren(d, root)) {
                runCatching { d.delete() }
                changed = true
            }
        }
        return changed
    }

    // ── 联网安装所需的两个纯函数（都可单测，不碰网络） ──

    /**
     * 加载器安装器（Forge/NeoForge）的下载地址与建议文件名。
     *
     * 用的是 [com.kaze.newage.core.download.CoreSources] 里**同一套 maven 路径**
     * （应用本来就从这两个 maven 下 Forge/NeoForge 安装器），只是这里按清单给的
     * **确切版本**构造，而不是取最新 —— 整合包的 mod 与加载器版本是绑定的。
     *
     * Fabric 不走这里：它没有 installer，用 `CoreSources.getFabricDownload()` 直接取
     * 现成的服务端启动器 jar。Quilt 本项目暂不支持（返回 null，由调用方给明确说明）。
     */
    fun loaderInstallerUrl(
        loader: ModpackManifest.Loader,
        mcVersion: String,
        loaderVersion: String,
    ): Pair<String, String>? {
        if (loaderVersion.isBlank()) return null
        return when (loader) {
            ModpackManifest.Loader.FORGE -> {
                if (mcVersion.isBlank()) return null
                val match = "$mcVersion-$loaderVersion"
                // `to` 必须与左值在同一表达式里：换行写会让 Kotlin 把两行当两条语句
                "https://maven.minecraftforge.net/net/minecraftforge/forge/$match/forge-$match-installer.jar" to
                    "forge-$match-installer.jar"
            }
            ModpackManifest.Loader.NEOFORGE -> {
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/$loaderVersion/neoforge-$loaderVersion-installer.jar" to
                    "neoforge-$loaderVersion-installer.jar"
            }
            else -> null
        }
    }

    /**
     * 已有文件能不能直接复用（= 不必重新下载）。
     *
     * **有哈希时必须以哈希为准。** 只比大小会放过"长度恰好相同的损坏文件"
     * （下载中断后残留、写盘丢块都可能造成），而且它会被**永久**复用 ——
     * 之后每次导入都判定"已经下好了"，模组一直跑不起来却查不出原因。
     * 清单没给哈希时才只能比大小（那种文件本来就没法验证内容）。
     *
     * [hashOk] 由调用方注入（sha512 优先、退 sha1），这样这条判定可以单测。
     */
    fun canReuseExisting(
        existing: File?,
        remote: ModpackManifest.RemoteFile,
        hashOk: (File) -> Boolean,
    ): Boolean {
        if (existing == null || !existing.isFile) return false
        if (remote.sha512 != null || remote.sha1 != null) return hashOk(existing)
        // 没有哈希可比：只能认大小（size 为 0 说明清单没给，那就接受任何非空文件）
        return remote.size <= 0 || existing.length() == remote.size
    }

    /**
     * 把清单里的相对路径解析成实例目录内的目标文件；越界或非法返回 null。
     *
     * **远端清单是不可信数据**：`path` 完全由整合包作者（或伪造的包）决定，
     * `../../foo` 这类值直接拼进 File 就会写到实例目录之外。这里做与解压同一套
     * canonical 前缀校验。
     *
     * 关于"看起来像绝对路径"的输入（`/mods/x.jar`、`C:/x.dll`）：**不拒绝，而是规范化**
     * —— 前导 `/` 去掉、`\` 换成 `/`，然后当作实例目录内的相对路径。理由有两个：
     *  1. 很多整合包（尤其从其他启动器导出的）就是这么写路径的，拒绝会让它们装不上；
     *  2. 真正的安全属性是"**最终落在实例目录内**"，规范化之后这一点仍然成立 ——
     *     `/etc/passwd` 会变成实例目录里的 `etc/passwd`，不是系统的 `/etc/passwd`。
     * 所以真正必须拒绝的只有 `..` 段（它会跳出目录）。
     */
    fun resolveRemoteDest(root: File, path: String): File? {
        val clean = path.replace('\\', '/').trimStart('/')
        if (clean.isEmpty()) return null
        // 逐段检查：不允许任何 `..` 段（哪怕最终 canonical 恰好落在目录内）
        if (clean.split('/').any { it == ".." }) return null
        val dest = File(root, clean)
        val ok = runCatching {
            dest.canonicalPath.startsWith(root.canonicalPath + File.separator)
        }.getOrDefault(false)
        return dest.takeIf { ok }
    }

    /** 把 [from] 下的子项移到 [to]（同名已存在时**保留 [to] 的**，不覆盖用户已放的文件） */
    private fun moveChildren(from: File, to: File): Boolean {
        val children = from.listFiles()?.toList().orEmpty()
        if (children.isEmpty()) return false
        var movedAny = false
        for (child in children) {
            val dst = File(to, child.name)
            if (dst.exists()) continue
            if (child.renameTo(dst)) movedAny = true
        }
        return movedAny
    }

    // ── 识别 ──

    /** Forge/NeoForge 的库路径前缀 → 核心类型 */
    private val FORGE_PREFIX = "libraries/net/minecraftforge/"
    private val NEOFORGE_PREFIX = "libraries/net/neoforged/"

    /** 从 Forge 库目录名里抽 MC 版本：`libraries/net/minecraftforge/forge/1.20.1-47.2.0/` */
    private val FORGE_DIR_MC = Regex("""^(\d+\.\d+(?:\.\d+)?)-""")

    fun inspect(root: File): Plan = withManifest(inspectBody(root), ModpackManifest.detect(root))

    /**
     * 把清单信息填进识别结果。
     *
     * 单独一层是为了让 [inspectBody] 专注"目录里有什么"，清单解析交给
     * [ModpackManifest]（两者都能独立测）。清单给出的信息**优先于**目录推断：
     * 目录里可能只有一个 mods/（认不出核心），但清单明确写着 forge 47.2.0。
     */
    private fun withManifest(plan: Plan, format: ModpackManifest.Format): Plan {
        val (loader, loaderVersion, mcFromManifest) = when (format) {
            is ModpackManifest.Format.Mrpack -> Triple(
                format.manifest.loader, format.manifest.loaderVersion, format.manifest.mcVersion,
            )
            is ModpackManifest.Format.Curse -> Triple(
                format.manifest.loader, format.manifest.loaderVersion, format.manifest.mcVersion,
            )
            ModpackManifest.Format.Plain -> Triple(
                ModpackManifest.Loader.UNKNOWN, "", "",
            )
        }
        if (loader == ModpackManifest.Loader.UNKNOWN && mcFromManifest.isEmpty()) {
            return plan.copy(format = format)
        }
        // 清单给了 MC 版本就以它为准（比从 mods 文件名猜可靠得多）
        val mc = mcFromManifest.ifEmpty { plan.mcVersion }
        val coreFromLoader = when (loader) {
            ModpackManifest.Loader.FORGE -> "FORGE"
            ModpackManifest.Loader.NEOFORGE -> "NEOFORGE"
            ModpackManifest.Loader.FABRIC -> "FABRIC"
            ModpackManifest.Loader.QUILT -> "CUSTOM"   // CoreType 里没有 Quilt
            else -> plan.coreTypeName
        }
        // 清单明确写了加载器时，以它的 Java 推断为准（mc 变量已优先取清单版本）
        val java = if (mc.isNotEmpty() && mc != plan.mcVersion) {
            com.kaze.newage.data.model.JavaVersionInference.infer(mc)
        } else {
            plan.javaMajor
        }
        val warnings = if (plan.launch == Launch.MODS_ONLY &&
            loader != ModpackManifest.Loader.UNKNOWN && loader != ModpackManifest.Loader.VANILLA
        ) {
            // 清单说了要装哪个加载器 → 不再是"缺核心"，交给导入流程自动装
            plan.warnings.filterNot { it.contains("没有服务端核心") }
        } else {
            plan.warnings
        }
        return plan.copy(
            coreTypeName = if (coreFromLoader != "CUSTOM") coreFromLoader else plan.coreTypeName,
            mcVersion = mc,
            javaMajor = java,
            warnings = warnings,
            format = format,
            loader = loader,
            loaderVersion = loaderVersion,
            evidence = plan.evidence +
                if (loader != ModpackManifest.Loader.UNKNOWN) {
                    (if (plan.evidence.isEmpty()) "" else "; ") + "清单声明加载器 ${loader.name}"
                } else {
                    ""
                },
        )
    }

    private fun inspectBody(root: File): Plan {
        val warnings = mutableListOf<String>()
        val evidence = StringBuilder()

        val hasMods = File(root, "mods").isDirectory
        if (hasMods) evidence.append("含 mods/; ")

        // ① Forge / NeoForge：看 libraries 下有没有 unix_args.txt（现代安装形态）
        val argsFile = findArgsFile(root)
        if (argsFile != null) {
            val rel = argsFile.relativeTo(root).path.replace('\\', '/')
            val core = when {
                rel.startsWith(FORGE_PREFIX) -> "FORGE"
                rel.startsWith(NEOFORGE_PREFIX) -> "NEOFORGE"
                else -> "FORGE"
            }
            val mcFromDir = FORGE_DIR_MC.find(
                rel.removePrefix(FORGE_PREFIX).removePrefix(NEOFORGE_PREFIX),
            )?.groupValues?.get(1).orEmpty()
            if (mcFromDir.isNotEmpty()) evidence.append("forges 目录含 MC $mcFromDir; ")

            val missing = missingClasspathEntries(root, argsFile)
            if (missing.isNotEmpty()) {
                warnings += "依赖库不完整（缺 ${missing.size} 个文件），启动前会自动补齐"
            } else {
                evidence.append("依赖库齐全; ")
            }

            val mc = mcFromDir.ifEmpty {
                // 有些包的目录名不带版本，退一步读 args 里的版本串
                MC_IN_ARGS.find(readText(argsFile, 512 * 1024))?.value.orEmpty()
            }
            return Plan(
                launch = Launch.FORGE_ARGS,
                coreTypeName = core,
                mcVersion = mc,
                javaMajor = if (mc.isNotEmpty()) {
                    com.kaze.newage.data.model.JavaVersionInference.infer(mc)
                } else {
                    17
                }.also { if (mc.isEmpty()) warnings += "认不出 MC 版本，Java 先按 17（可在实例详情里改）" },
                launchJar = null,
                argsFile = argsFile,
                missingForgeLibs = missing,
                warnings = warnings,
                evidence = evidence.toString().trimEnd(' ', ';'),
            )
        }

        // ② 有可直接 -jar 的启动器 jar（Fabric 的 fabric-server-launch.jar / 原版 server.jar）
        val launchJar = pickLaunchJar(root)
        if (launchJar != null) {
            val info = JarInspector.quickCheck(launchJar)
            evidence.append("启动 jar=${launchJar.name}; ")
            if (info.evidence.isNotEmpty()) evidence.append(info.evidence).append("; ")
            val core = when {
                launchJar.name.startsWith("fabric-server-launch", ignoreCase = true) -> "FABRIC"
                info.coreTypeName != "CUSTOM" -> info.coreTypeName
                hasMods -> "FABRIC"      // 有 mods 又没有 Forge 库 → 多半是 Fabric 系；仍可被用户改
                else -> "CUSTOM"
            }
            val mc = info.mcVersion
            val mcFromMods = if (mc.isEmpty() && hasMods) mcFromModsDir(root) else ""
            val effectiveMc = mc.ifEmpty { mcFromMods }
            if (effectiveMc.isEmpty()) {
                warnings += "认不出 MC 版本（可稍后在实例详情里指定 Java）"
            } else if (mc.isEmpty()) {
                evidence.append("MC 版本取自 mods 文件名 $effectiveMc; ")
            }
            val java = if (effectiveMc.isNotEmpty()) {
                com.kaze.newage.data.model.JavaVersionInference.infer(effectiveMc)
            } else {
                info.javaMajor
            }
            return Plan(
                launch = Launch.JAR,
                coreTypeName = core,
                mcVersion = effectiveMc,
                javaMajor = java,
                launchJar = launchJar,
                argsFile = null,
                missingForgeLibs = emptyList(),
                warnings = warnings,
                evidence = evidence.toString().trimEnd(' ', ';'),
            )
        }

        // ③ 没有可用核心：mods 有了、但缺服务端 jar
        if (hasMods) {
            warnings += "这是整合包的「附加内容」（mods/config 等），但**没有服务端核心 jar**。" +
                "请再用「新建 → 选核心」装一次同版本的 Forge/Fabric/Paper，" +
                "或用「导入 jar」把 server.jar 放进来。"
        }
        return Plan(
            launch = Launch.MODS_ONLY,
            coreTypeName = "CUSTOM",
            mcVersion = "",
            javaMajor = 17,
            launchJar = null,
            argsFile = null,
            missingForgeLibs = emptyList(),
            warnings = warnings,
            evidence = "无可识别的启动入口",
        )
    }

    /** 找 `libraries/<核心>/<版本>/unix_args.txt`（深度受限：正常是 6~8 层） */
    private fun findArgsFile(root: File): File? {
        val libs = File(root, "libraries")
        if (!libs.isDirectory) return null
        return libs.walkTopDown().maxDepth(8)
            .firstOrNull { it.isFile && it.name == "unix_args.txt" }
    }

    /**
     * 根目录下可作为启动入口的 jar。
     *
     * 排除：`*-installer.jar`（Forge 安装器，不能直接跑）、`libraries/` 里的库、
     * 明显是客户端或模组的 jar。优先取名字最像服务端的那个。
     */
    private fun pickLaunchJar(root: File): File? {
        val jars = root.listFiles()?.filter {
            it.isFile && it.name.endsWith(".jar", ignoreCase = true) &&
                !it.name.contains("installer", ignoreCase = true)
        }.orEmpty()
        if (jars.isEmpty()) return null
        // 优先级：server.jar > fabric-server-launch > forge-*（非 installer） > 其它
        jars.firstOrNull { it.name.equals("server.jar", ignoreCase = true) }?.let { return it }
        jars.firstOrNull { it.name.startsWith("fabric-server-launch", ignoreCase = true) }?.let { return it }
        jars.firstOrNull {
            it.name.startsWith("forge-", ignoreCase = true) || it.name.startsWith("neoforge-", true)
        }?.let { return it }
        return jars.firstOrNull()
    }

    /**
     * `unix_args.txt` 里列出但不存在的 classpath 条目。
     *
     * 形如 `-cp` + 一长串 `a.jar:b.jar:...`（Linux 用 `:`），或 `-classpath`。
     * 只看 `libraries/` 开头的条目 —— 其它条目（模块路径等）不作为完整性依据。
     */
    internal fun missingClasspathEntries(root: File, argsFile: File): List<String> {
        val text = readText(argsFile, 4 * 1024 * 1024)
        if (text.isEmpty()) return emptyList()
        val candidates = text.split(Regex("""[\s,]+"""))
            .flatMap { it.split(':') }
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.startsWith("libraries/") && it.endsWith(".jar") }
            .distinct()
        return candidates.filter { !File(root, it).isFile }.take(50)
    }

    private fun readText(f: File, limit: Int): String = runCatching {
        if (!f.isFile || f.length() > limit) return ""
        f.readText()
    }.getOrDefault("")

    /** args 文件或 pack 元数据里出现的第一个 `1.x(.y)` 版本串 */
    private val MC_IN_ARGS = Regex("""(\d+\.\d+(?:\.\d+)?)""")

    /**
     * 兜底：从 `mods/` 里最常见的「+1.20.1」后缀推 MC 版本。
     *
     * 只在 jar 与 Forge 目录都给不出时才用，且要求**多数 mod 文件名指向同一个版本** ——
     * 只有一致才敢用，否则不同 mod 的版本号会互相打架（fabric-api 的版本号与 MC 版本无关）。
     */
    internal fun mcFromModsDir(root: File): String {
        val mods = File(root, "mods").listFiles()?.filter { it.name.endsWith(".jar", true) }.orEmpty()
        if (mods.isEmpty()) return ""
        val votes = HashMap<String, Int>()
        for (m in mods) {
            // 只认 "+1.20.1" / "_1.20.1" 这类紧跟在分隔符后的版本，避免匹配到 mod 自己的版本号
            val hit = Regex("""[+_](\d+\.\d+(?:\.\d+)?)""").find(m.name) ?: continue
            val v = hit.groupValues[1]
            votes[v] = (votes[v] ?: 0) + 1
        }
        if (votes.isEmpty()) return ""
        val best = votes.maxByOrNull { it.value } ?: return ""
        // 至少 1/3 的 mod 指向同一版本才采用，否则视为不可靠
        return if (best.value * 3 >= mods.size) best.key else ""
    }

    /**
     * 判据汇总：这个目录适不适合当实例根？
     *
     * 用于在导入前给用户一个"这看起来不像整合包"的提示（不阻断 —— 结构千奇百怪，
     * 只要用户愿意就让他导，启动失败能自己删）。
     */
    fun looksLikeModpack(root: File): Boolean =
        File(root, "mods").isDirectory ||
            File(root, "libraries").isDirectory ||
            File(root, "config").isDirectory ||
            File(root, "server.jar").isFile
}