package com.kaze.newage.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * 「强制停止」确认框（真机要求，原话两条）：
 *
 *  1. **"点强制关闭按钮，要提示确定强制关闭"** —— 强制停止走 SIGTERM → 5 秒 → SIGKILL，
 *     没存完的部分可能会丢，所以必须先问一句。文案要把代价与过程说清楚（先 SIGTERM、最多等 5 秒），
 *     而不是干巴巴一句"确定吗"，也不要把"可能丢"写成"一定丢"。
 *  2. **"当询问时，服务器真正自己关闭完成时，关闭显示的是否强制关闭的提示"** ——
 *     框还开着的时候服务器自己停稳了（状态离开 Stopping），这个提示就没意义了：
 *     用户会对着一台已经停好的服务器点"强制停止"。所以状态一变就自动收起。
 *
 * 实现上把"实例现在是否仍在 Stopping"交给调用方传进来（[stillStopping]），
 * 框自己监听它：一旦变 false 就调 [onDismiss]（把调用方的状态也复位，避免残留）。
 */
@Composable
fun ForceStopConfirmDialog(
    visible: Boolean,
    instanceName: String,
    stillStopping: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 服务器自己停完了 → 撤销这个提示
    LaunchedEffect(visible, stillStopping) {
        if (visible && !stillStopping) onDismiss()
    }
    if (!visible || !stillStopping) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("强制停止？") },
        text = {
            Text(
                "「$instanceName」可能正在保存世界。强制停止会先发 SIGTERM 让它收尾，" +
                    "最多等 5 秒，之后强制结束进程 —— 没存完的部分可能会丢失。\n\n" +
                    "如果它只是慢，建议点「继续等它存完」；服务器自己停好后这个提示会自动消失。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("强制停止") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("继续等它存完") }
        },
    )
}
