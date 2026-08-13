package com.mcserver.launcher.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mcserver.launcher.core.backup.BackupManager
import com.mcserver.launcher.core.download.CoreCatalog
import com.mcserver.launcher.core.download.DownloadManager
import com.mcserver.launcher.core.engine.JreManager
import com.mcserver.launcher.core.engine.ServerEngine
import com.mcserver.launcher.core.instance.CoreType
import com.mcserver.launcher.core.instance.EulaManager
import com.mcserver.launcher.core.instance.InstanceStore
import com.mcserver.launcher.core.linux.JdkManager
import com.mcserver.launcher.core.linux.LinuxEnv
import com.mcserver.launcher.core.linux.LinuxStatus
import com.mcserver.launcher.core.service.ServerService
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.util.KLog
import kotlinx.coroutines.launch

/** 全局状态聚合：实例 / 引擎 / 下载 / JRE 的 UI 门面 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val instances = mutableStateListOf<ServerInstance>()
    val selectedId = mutableStateOf<String?>(null)

    // 引擎与下载状态直接透传（StateFlow 订阅）
    val engineState get() = ServerEngine.state
    val engineStats get() = ServerEngine.stats
    val downloadState get() = DownloadManager.state
    val jreStatus get() = JreManager.status
    val jreProgress get() = JreManager.progress
    val jreVersion get() = JreManager.versionText
    val linuxStatus get() = LinuxEnv.status
    val linuxProgress get() = LinuxEnv.progress
    val linuxDetail get() = LinuxEnv.detail
    val jdks get() = JdkManager.jdks

    /** 当前实例的 EULA 状态（供 UI 展示签署条） */
    val eulaExists = mutableStateOf(false)
    val eulaAgreed = mutableStateOf(false)

    // 下载页选择态
    val coreType = mutableStateOf(CoreType.PAPER)
    val versions = mutableStateOf<List<String>>(emptyList())
    val mcVersion = mutableStateOf("")
    val versionsLoading = mutableStateOf(false)
    val toast = mutableStateOf<String?>(null)

    init {
        refreshInstances()
        JreManager.refresh()
        LinuxEnv.refresh()
        JdkManager.refresh()
        refreshEula()
    }

    fun refreshEula() {
        val inst = currentInstance()
        eulaExists.value = inst?.let { EulaManager.exists(it.id) } ?: false
        eulaAgreed.value = inst?.let { EulaManager.isAgreed(it.id) } ?: false
    }

    /** 部署内置 Linux 环境（从 APK assets 解包，无需下载） */
    fun installLinuxEnv() {
        viewModelScope.launch {
            val r = LinuxEnv.setup(getApplication())
            showToast(
                if (r.isSuccess) {
                    JdkManager.refresh()
                    "Linux 环境就绪"
                } else "环境部署失败: ${r.exceptionOrNull()?.message}"
            )
        }
    }

    /** 在 Linux 环境内在线安装 JDK/JRE */
    fun installJdk(feature: Int, jdk: Boolean = false) {
        viewModelScope.launch {
            val r = JdkManager.install(feature, jdk) { line ->
                if (line.length < 120) showToast(line)
            }
            showToast(if (r.isSuccess) "JDK$feature 安装完成" else "安装失败: ${r.exceptionOrNull()?.message}")
        }
    }

    /** 在 Linux 环境内卸载 JDK/JRE */
    fun uninstallJdk(feature: Int) {
        viewModelScope.launch {
            val r = JdkManager.uninstall(feature)
            showToast(if (r.isSuccess) "JDK$feature 已卸载" else "卸载失败: ${r.exceptionOrNull()?.message}")
        }
    }

    /** 同意 EULA 并保存实例标记 */
    fun acceptEula() {
        val inst = currentInstance() ?: return
        if (EulaManager.accept(inst.id)) {
            val updated = inst.copy(agreeEula = true)
            InstanceStore.add(updated)
            refreshInstances()
            refreshEula()
            showToast("已同意 EULA，可以启动了")
        } else {
            showToast("写入 eula.txt 失败")
        }
    }

    fun refreshInstances() {
        instances.clear()
        instances.addAll(InstanceStore.list())
        if (instances.isEmpty()) {
            selectedId.value = null
        } else if (selectedId.value == null || instances.none { it.id == selectedId.value }) {
            selectedId.value = instances.first().id
        }
    }

    fun currentInstance(): ServerInstance? =
        instances.firstOrNull { it.id == selectedId.value }

    fun selectInstance(id: String) {
        selectedId.value = id
    }

    fun showToast(msg: String) {
        toast.value = msg
    }

    fun clearToast() {
        toast.value = null
    }

    // ── 启动 / 停止 ──

    fun toggleRun() {
        when (engineState.value) {
            is ServerState.Idle, is ServerState.Crashed -> startCurrent()
            else -> stopCurrent()
        }
    }

    private fun startCurrent() {
        val inst = currentInstance()
        if (inst == null) {
            showToast("请先创建服务器实例")
            return
        }
        // Java 环境：优先内置 Linux（proot + rootfs 内 apk 安装的 JDK），回退 Android 直跑 JRE
        val guestJava = com.mcserver.launcher.core.linux.JdkManager.pickJavaInGuest()
        val fallbackJava = JreManager.currentJavaPath()
        val javaPath: String = if (guestJava != null && LinuxEnv.isReady()) {
            guestJava
        } else if (fallbackJava != null) {
            fallbackJava
        } else {
            showToast("请先部署 Linux 环境并安装 JDK（设置页），或安装 JRE")
            return
        }
        val ok = ServerEngine.start(getApplication(), inst, javaPath)
        if (ok) {
            val intent = Intent(getApplication(), ServerService::class.java)
            getApplication<Application>().startForegroundService(intent)
        } else {
            showToast("启动失败，请查看控制台日志")
        }
        refreshEula()
    }

    fun stopCurrent() {
        ServerEngine.stop()
    }

    fun sendCommand(cmd: String) {
        if (!ServerEngine.sendCommand(cmd)) {
            showToast("服务端未运行")
        }
    }

    // ── 实例操作 ──

    fun saveInstance(inst: ServerInstance): Boolean {
        val ok = InstanceStore.add(inst)
        if (ok) {
            refreshInstances()
            selectedId.value = inst.id
        }
        return ok
    }

    fun deleteInstance(id: String) {
        if (engineState.value is ServerState.Running && ServerEngine.runningInstanceId() == id) {
            showToast("请先停止服务器再删除")
            return
        }
        InstanceStore.remove(id)
        com.mcserver.launcher.data.AppPaths.instanceDir(id).deleteRecursively()
        refreshInstances()
    }

    fun backupCurrent() {
        val inst = currentInstance() ?: return
        if (engineState.value is ServerState.Running) {
            showToast("请先停止服务器再备份")
            return
        }
        viewModelScope.launch {
            val r = BackupManager.backup(inst.id, inst.name)
            showToast(if (r.isSuccess) "备份完成" else "备份失败: ${r.exceptionOrNull()?.message}")
        }
    }

    // ── 下载页 ──

    fun loadVersions(type: CoreType) {
        coreType.value = type
        versionsLoading.value = true
        viewModelScope.launch {
            try {
                val list = CoreCatalog.listMcVersions(type)
                versions.value = list.take(40)
                mcVersion.value = list.firstOrNull() ?: ""
            } catch (e: Exception) {
                showToast("加载版本失败: ${e.message}")
            } finally {
                versionsLoading.value = false
            }
        }
    }

    fun downloadSelectedCore() {
        val type = coreType.value
        val ver = mcVersion.value
        if (ver.isBlank()) {
            showToast("请选择 MC 版本")
            return
        }
        viewModelScope.launch {
            val info = CoreCatalog.resolve(type, ver)
            if (info == null) {
                showToast("该核心需自备 jar（如 Spigot/Forge），请在实例目录手动放入")
                return@launch
            }
            val url = info.downloadUrl
            if (url.isNullOrBlank()) {
                showToast("无法获取下载地址")
                return@launch
            }
            val dest = java.io.File(com.mcserver.launcher.data.AppPaths.downloadsDir, info.fileName)
            val r = DownloadManager.download(url, dest)
            if (r.isSuccess) {
                showToast("下载完成：${info.fileName}")
            } else {
                showToast("下载失败: ${r.exceptionOrNull()?.message}")
            }
        }
    }

    // ── 内容中心安装 ──

    /** 下载并安装内容到当前实例（模组/插件/资源包/数据包/整合包） */
    fun installContent(item: com.mcserver.launcher.core.content.ContentItem) {
        viewModelScope.launch {
            val inst = currentInstance()
            if (inst == null) {
                showToast("请先创建服务器实例")
                return@launch
            }
            val pair = com.mcserver.launcher.core.content.ModrinthApi.resolveDownload(item)
            if (pair == null) {
                showToast("该项目暂无可用文件")
                return@launch
            }
            val (url, filename) = pair
            val dest = java.io.File(com.mcserver.launcher.data.AppPaths.downloadsDir, filename)
            showToast("开始下载：$filename")
            val r = DownloadManager.download(url, dest)
            if (r.isFailure) {
                showToast("下载失败: ${r.exceptionOrNull()?.message}")
                return@launch
            }
            val dir = com.mcserver.launcher.data.AppPaths.instanceDir(inst.id)
            try {
                when {
                    filename.endsWith(".mrpack") -> installPack(dest, dir)
                    item.projectType == "mod" -> installFile(dest, java.io.File(dir, "mods"), filename)
                    item.projectType == "plugin" -> installFile(dest, java.io.File(dir, "plugins"), filename)
                    item.projectType == "datapack" ->
                        installFile(dest, java.io.File(dir, "world/datapacks"), filename)
                    item.projectType == "resourcepack" ->
                        installFile(dest, java.io.File(dir, "resourcepacks"), filename)
                    item.projectType == "shader" ->
                        installFile(dest, java.io.File(dir, "shaderpacks"), filename)
                    else -> installFile(dest, java.io.File(dir, "mods"), filename)
                }
                showToast("安装完成：$filename")
            } catch (e: Exception) {
                showToast("安装失败: ${e.message}")
            }
        }
    }

    private fun installFile(src: java.io.File, targetDir: java.io.File, filename: String) {
        targetDir.mkdirs()
        src.copyTo(java.io.File(targetDir, filename), overwrite = true)
        src.delete()
    }

    /** 整合包：解压 overrides + 按 index 下载依赖文件 */
    private suspend fun installPack(pack: java.io.File, dir: java.io.File) {
        val unzipDir = java.io.File(pack.parentFile, "pack-tmp")
        if (unzipDir.exists()) unzipDir.deleteRecursively()
        unzipDir.mkdirs()
        unzip(pack, unzipDir)
        // overrides 直接覆盖到实例目录
        java.io.File(unzipDir, "overrides").copyRecursively(dir, overwrite = true)
        // 解析 modrinth.index.json 依赖并下载
        val indexFile = java.io.File(unzipDir, "modrinth.index.json")
        if (indexFile.exists()) {
            val index = org.json.JSONObject(indexFile.readText())
            val files = com.mcserver.launcher.core.content.ModrinthApi.resolvePackFiles(index)
            files.forEach { f ->
                try {
                    val target = java.io.File(dir, f.path)
                    target.parentFile?.mkdirs()
                    val r = DownloadManager.download(f.url, target)
                    if (r.isFailure) {
                        KLog.w("整合包依赖下载失败: ${f.path}")
                    }
                } catch (e: Exception) {
                    KLog.w("整合包依赖安装失败: ${f.path} ${e.message}")
                }
            }
        }
        unzipDir.deleteRecursively()
        pack.delete()
    }

    private fun unzip(zip: java.io.File, dest: java.io.File) {
        java.util.zip.ZipInputStream(java.io.FileInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = java.io.File(dest, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    java.io.FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** 下载完成后一键创建实例 */
    fun createInstanceFromDownload(fileName: String): Boolean {
        val inst = ServerInstance(
            name = "${coreType.value.label}-${mcVersion.value}",
            coreType = coreType.value,
            mcVersion = mcVersion.value,
            coreFileName = fileName,
        )
        val ok = saveInstance(inst)
        if (ok) {
            val src = java.io.File(com.mcserver.launcher.data.AppPaths.downloadsDir, fileName)
            val dst = com.mcserver.launcher.data.AppPaths.instanceDir(inst.id)
            if (src.exists()) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    src.copyTo(java.io.File(dst, fileName), overwrite = true)
                    KLog.i("核心已放入实例目录: ${inst.name}")
                }
            }
        }
        return ok
    }
}