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
import com.mcserver.launcher.core.instance.InstanceStore
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

    // 下载页选择态
    val coreType = mutableStateOf(CoreType.PAPER)
    val versions = mutableStateOf<List<String>>(emptyList())
    val mcVersion = mutableStateOf("")
    val versionsLoading = mutableStateOf(false)
    val toast = mutableStateOf<String?>(null)

    init {
        refreshInstances()
        JreManager.refresh()
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
        val java = JreManager.currentJavaPath()
        if (java == null) {
            showToast("尚未安装 JRE，请到设置页下载或导入")
            return
        }
        val ok = ServerEngine.start(getApplication(), inst, java)
        if (ok) {
            val intent = Intent(getApplication(), ServerService::class.java)
            getApplication<Application>().startForegroundService(intent)
        } else {
            showToast("启动失败，请查看控制台日志")
        }
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