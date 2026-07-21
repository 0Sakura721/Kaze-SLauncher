package com.mcserver.launcher.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 简易 Compose 错误边界。
 * 子树内任何未捕获异常会被捕获并显示降级界面，
 * 避免整个应用崩溃。点击"重试"会重建子内容。
 */
@Composable
fun ErrorBoundary(
    onError: (Throwable) -> Unit = {},
    content: @Composable () -> Unit
) {
    var throwable by remember { mutableStateOf<Throwable?>(null) }

    CompositionLocalProvider(
        LocalErrorBoundary provides { t ->
            throwable = t
            onError(t)
        }
    ) {
        if (throwable == null) {
            content()
        } else {
            ErrorFallback(
                error = throwable!!,
                onRetry = { throwable = null }
            )
        }
    }
}

/** 本地 CompositionLocal，用于在 Composable 子树内主动上报错误 */
val LocalErrorBoundary = staticCompositionLocalOf<(Throwable) -> Unit> { { _ -> } }

/**
 * 主动上报错误，触发 ErrorBoundary 显示降级界面。
 */
@Composable
fun rememberErrorReporter(): (Throwable) -> Unit {
    val report = LocalErrorBoundary.current
    return remember(report) { { t -> report(t) } }
}

@Composable
private fun ErrorFallback(error: Throwable, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.BugReport,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "界面出现错误",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                error.message ?: error.javaClass.simpleName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("重试")
            }
            // 调试模式显示堆栈（DEBUG 构建变体）
            if (com.mcserver.launcher.BuildConfig.DEBUG) {
                Spacer(Modifier.height(16.dp))
                val stack = remember(error) { stackTraceToString(error) }
                val scrollState = rememberScrollState()
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(scrollState)
                    ) {
                        Text(
                            text = stack,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun stackTraceToString(t: Throwable): String {
    val sw = StringWriter()
    t.printStackTrace(PrintWriter(sw))
    return sw.toString()
}
