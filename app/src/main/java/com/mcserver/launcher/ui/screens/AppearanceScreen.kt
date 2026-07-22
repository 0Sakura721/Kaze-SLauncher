package com.mcserver.launcher.ui.screens

import android.net.Uri
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.data.PreferencesManager
import com.mcserver.launcher.ui.components.isImageFile
import com.mcserver.launcher.ui.components.isVideoFile
import com.mcserver.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import java.io.File

/**
 * 外观自定义页面 — 主题 + 背景 + 强调色 + 布局密度。
 *
 * 设计：基底保持纯黑白灰，个性化在底层叠加，不破坏文字可读性。
 */
@Composable
fun AppearanceScreen(
    prefsManager: PreferencesManager,
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val bgType by prefsManager.backgroundType.collectAsState(initial = "none")
    val bgPath by prefsManager.backgroundPath.collectAsState(initial = "")
    val bgBlur by prefsManager.backgroundBlur.collectAsState(initial = 0f)
    val bgDarkMask by prefsManager.backgroundDarkMask.collectAsState(initial = 0.3f)
    val accentColor by prefsManager.accentColor.collectAsState(initial = "default")
    val cornerRadius by prefsManager.cornerRadius.collectAsState(initial = 12)
    val layoutDensity by prefsManager.layoutDensity.collectAsState(initial = "normal")
    val fontSize by prefsManager.fontSize.collectAsState(initial = "medium")

    // 图片选择器
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val path = it.toString()
            scope.launch {
                prefsManager.setBackgroundPath(path)
                prefsManager.setBackgroundType("image")
            }
        }
    }

    // 视频选择器
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val path = it.toString()
            scope.launch {
                prefsManager.setBackgroundPath(path)
                prefsManager.setBackgroundType("video")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        // ── 标题 ──
        Text(
            "外观",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "自定义主题、背景和布局",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // ── 1. 主题模式 ──
        SectionHeader("主题模式")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(ThemeMode.LIGHT, "浅色", "白底黑字"),
                Triple(ThemeMode.DARK, "深色", "深灰底白字"),
                Triple(ThemeMode.AMOLED, "AMOLED", "纯黑省电")
            ).forEach { (mode, label, desc) ->
                val selected = currentTheme == mode
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onThemeChange(mode) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = if (selected) 4.dp else 0.dp
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                        Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── 2. 背景 ──
        SectionHeader("背景")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple("none", "无", Icons.Filled.Block),
                Triple("image", "图片", Icons.Filled.Image),
                Triple("video", "视频", Icons.Filled.VideoFile)
            ).forEach { (type, label, icon) ->
                val selected = bgType == type
                FilterChip(
                    selected = selected,
                    onClick = {
                        scope.launch { prefsManager.setBackgroundType(type) }
                        when (type) {
                            "image" -> imagePicker.launch("image/*")
                            "video" -> videoPicker.launch("video/*")
                            else -> scope.launch { prefsManager.setBackgroundPath("") }
                        }
                    },
                    label = { Text(label) },
                    leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) }
                )
            }
        }
        if (bgPath.isNotEmpty() && bgType != "none") {
            Spacer(Modifier.height(8.dp))
            Text(
                "已选择: ${if (bgPath.startsWith("content://")) "来自相册" else File(bgPath).name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        // 模糊强度
        Text("模糊强度: ${String.format(Locale.US, "%.0f", bgBlur * 10)}%")
        Slider(
            value = bgBlur,
            onValueChange = { scope.launch { prefsManager.setBackgroundBlur(it) } },
            valueRange = 0f..2f,
            modifier = Modifier.fillMaxWidth()
        )

        // 暗化遮罩
        Text("暗化遮罩: ${String.format(Locale.US, "%.0f", bgDarkMask * 100)}%")
        Slider(
            value = bgDarkMask,
            onValueChange = { scope.launch { prefsManager.setBackgroundDarkMask(it) } },
            valueRange = 0f..0.8f,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        // ── 3. 强调色 ──
        SectionHeader("强调色")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val colors = listOf(
                "default" to "默认",
                "red" to "红",
                "blue" to "蓝",
                "green" to "绿",
                "purple" to "紫",
                "orange" to "橙"
            )
            colors.forEach { (key, label) ->
                FilterChip(
                    selected = accentColor == key,
                    onClick = { scope.launch { prefsManager.setAccentColor(key) } },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── 4. 布局密度 ──
        SectionHeader("布局密度")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("compact" to "紧凑", "normal" to "标准", "comfortable" to "舒适").forEach { (key, label) ->
                FilterChip(
                    selected = layoutDensity == key,
                    onClick = { scope.launch { prefsManager.setLayoutDensity(key) } },
                    label = { Text(label) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── 5. 字体大小 ──
        SectionHeader("字体大小")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("small" to "小", "medium" to "中", "large" to "大").forEach { (key, label) ->
                FilterChip(
                    selected = fontSize == key,
                    onClick = { scope.launch { prefsManager.setFontSize(key) } },
                    label = { Text(label) }
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── 6. 圆角大小 ──
        SectionHeader("圆角")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "无", 8 to "小", 12 to "中", 20 to "大").forEach { (r, label) ->
                FilterChip(
                    selected = cornerRadius == r,
                    onClick = { scope.launch { prefsManager.setCornerRadius(r) } },
                    label = { Text(label) }
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
    )
}