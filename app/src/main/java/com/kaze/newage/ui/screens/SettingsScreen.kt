package com.kaze.newage.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.CheckChip
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.FgColorMode
import com.kaze.newage.ui.theme.GlassMode
import com.kaze.newage.ui.theme.LocalDarkTheme
import com.kaze.newage.ui.theme.cardBorderColor
import com.kaze.newage.ui.theme.parseSeedColor
import com.kaze.newage.ui.theme.statusPalette
import com.kaze.newage.util.StorageDirUtil
import com.canhub.cropper.CropImage
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import java.io.File

/** 背景图裁剪源：选图后复制到 cacheDir 此固定文件，经 CropFileProvider 交给裁剪 Activity */
private const val BG_CROP_SRC = "bg_crop_src.tmp"

/** 取色风格（materialkolor PaletteStyle）：英文名 → 中文说明 */
private val paletteStyles: List<Pair<String, String>> = listOf(
    "TonalSpot" to "贴近种子色相，均衡百搭",
    "Fidelity" to "尽量还原种子原色",
    "Vibrant" to "高饱和鲜明，活力感强",
    "Expressive" to "冷暖对冲，更有张力",
    "Content" to "柔和沉稳，适合长阅读",
    "Neutral" to "几乎无彩色，灰度极简",
    "Monochrome" to "单一色相，极简统一",
    "Rainbow" to "多色相组合，多彩活泼",
    "FruitSalad" to "果味多彩，鲜明跳脱",
)

/**
 * 设置：三区差异化布局——
 * ① 外观：展示卡（主题磁贴 + 色板，整页最重）；
 * ② 存储位置：自定义实例目录（游戏可存到其他目录并直接运行；环境部署已自动化，无需手动管理）；
 * ③ 通用：手风琴分组列表（背景图 / Java / 关于，逐条展开）。
 */
@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val javaVersions by viewModel.envJavaVersions.collectAsState()
    val javaTask by viewModel.javaTask.collectAsState()
    val appContext = LocalContext.current.applicationContext
    val uiPrefs = viewModel.uiPrefs

    // 手风琴：同一时刻只展开一节（null = 全部收起）
    var openSection by remember { mutableStateOf<String?>(null) }

    // 裁剪：CanHub Android-Image-Cropper（uCrop 同源，Apache-2.0），Activity 模式
    val cropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val bmp = result.getBitmap(appContext) ?: return@rememberLauncherForActivityResult
            uiPrefs.saveBackgroundImage(bmp)
            uiPrefs.setBgEnabled(true)
        }
    }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                // 复制到 cacheDir 固定文件，经 CropFileProvider 交给裁剪 Activity
                val dst = File(appContext.cacheDir, BG_CROP_SRC)
                appContext.contentResolver.openInputStream(uri)?.use { ins ->
                    dst.outputStream().use { outs -> ins.copyTo(outs) }
                }
                val fileUri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.cropper.fileprovider",
                    dst,
                )
                cropLauncher.launch(CropImageContractOptions(fileUri, CropImageOptions()))
            } catch (_: Exception) { }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            // 底部空白承载常驻栏：滚动中内容充分透过底栏玻璃，滚到底时最后内容不被遮挡
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 页头：标题 + 版本徽章 ──
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("设置", style = MaterialTheme.typography.titleLarge)
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("v1.0.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }

        // ═══ ① 外观（内容直接铺背景，与其他页面一致）═══
        Text(
            "外观",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column {
            // 主题样式
            Text("主题样式", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppThemeMode.entries.forEach { mode ->
                    ThemeTile(
                        mode = mode,
                        selected = uiPrefs.themeMode.value == mode.id,
                        onClick = {
                            if (mode == AppThemeMode.GLASS) {
                                // 液态玻璃功能封锁：点击仅提示（3 秒），不切换主题
                                android.widget.Toast.makeText(
                                    appContext,
                                    "液态玻璃功能已封锁，敬请期待",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                uiPrefs.setThemeMode(mode.id)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

                // 主题模式（BiliPai AppThemeMode：跟随系统/浅色/深色）
                Text("主题模式", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
                // FlowRow：窄屏（真机 360dp 宽）下 chip 自动换行，不会被横向挤压截断
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CheckChip(selected = uiPrefs.themeModeValue.value == 0, label = "跟随系统", onClick = { uiPrefs.setThemeModeValue(0) })
                    CheckChip(selected = uiPrefs.themeModeValue.value == 1, label = "浅色模式", onClick = { uiPrefs.setThemeModeValue(1) })
                    CheckChip(selected = uiPrefs.themeModeValue.value == 2, label = "深色模式", onClick = { uiPrefs.setThemeModeValue(2) })
                }

                // 深色样式（BiliPai DarkThemeStyle：普通黑/AMOLED纯黑）
                Text("深色样式", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CheckChip(selected = uiPrefs.darkStyle.value == 0, label = "普通黑", onClick = { uiPrefs.setDarkStyle(0) })
                    CheckChip(selected = uiPrefs.darkStyle.value == 1, label = "AMOLED 纯黑", onClick = { uiPrefs.setDarkStyle(1) })
                }

                // MD3 颜色来源（BiliPai Md3ColorSource：跟随系统壁纸/自定义颜色）
                Text("颜色来源", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CheckChip(selected = uiPrefs.md3ColorSource.value == "wallpaper", label = "跟随系统壁纸", onClick = { uiPrefs.setMd3ColorSource("wallpaper") })
                    CheckChip(selected = uiPrefs.md3ColorSource.value == "custom", label = "自定义颜色", onClick = { uiPrefs.setMd3ColorSource("custom") })
                }
                if (uiPrefs.md3ColorSource.value == "custom") {
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = uiPrefs.md3CustomColor.value,
                            onValueChange = { v ->
                                if (v.length <= 7) uiPrefs.setMd3CustomColor(v)
                            },
                            label = { Text("种子色（hex）") },
                            placeholder = { Text("#007AFF") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(runCatching {
                                    androidx.compose.ui.graphics.Color(
                                        uiPrefs.md3CustomColor.value.removePrefix("#").toLong(16).toInt() or 0xFF000000.toInt()
                                    )
                                }.getOrDefault(MaterialTheme.colorScheme.primary))
                        )
                    }
                }

                // 取色风格（materialkolor PaletteStyle，BiliPai resolveColorStyleOptions）——抽屉式，与「通用」手风琴一致
                var paletteExpanded by remember { mutableStateOf(false) }
                AccordionRow(
                    title = "取色风格",
                    desc = if (paletteExpanded) "点击收起" else "当前：${uiPrefs.paletteStyle.value}",
                    expanded = paletteExpanded,
                    onClick = { paletteExpanded = !paletteExpanded },
                )
                AnimatedVisibility(visible = paletteExpanded) {
                    // 每种风格按当前种子色 + 深浅实时生成主/次/第三色小色板，可直接对比效果
                    val previewDark = LocalDarkTheme.current
                    val previewSeed = parseSeedColor(uiPrefs.md3CustomColor.value) ?: 0xFF007AFF.toInt()
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        paletteStyles.forEach { (name, desc) ->
                            val preview = remember(name, previewSeed, previewDark, uiPrefs.darkStyle.value) {
                                runCatching {
                                    dynamicColorScheme(
                                        seedColor = Color(previewSeed),
                                        isDark = previewDark,
                                        isAmoled = uiPrefs.darkStyle.value == 1,
                                        style = PaletteStyle.valueOf(name),
                                    )
                                }.getOrNull()
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { uiPrefs.setPaletteStyle(name) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (preview != null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        listOf(preview.primary, preview.secondary, preview.tertiary).forEach { c ->
                                            Box(
                                                Modifier
                                                    .size(14.dp)
                                                    .clip(CircleShape)
                                                    .background(c)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        desc,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (uiPrefs.paletteStyle.value == name) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "当前使用",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // 液态玻璃模式（BiliPai LiquidGlassMode：清晰/均衡/磨砂）
                if (uiPrefs.themeMode.value == "glass") {
                    Text("液态玻璃模式", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GlassMode.entries.forEach { gm ->
                            CheckChip(
                                selected = uiPrefs.glassMode.value == gm.id,
                                label = gm.label,
                                onClick = { uiPrefs.setGlassMode(gm.id) },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("原生模糊", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "液态玻璃的真实背景模糊（Android 12+）；个别设备渲染异常时可关闭，回退为半透明玻璃",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = uiPrefs.glassBlur.value,
                            onCheckedChange = { uiPrefs.setGlassBlur(it) },
                        )
                    }

                    // 玻璃强度：饱和度增强 + 透镜折射幅度
                    Text(
                        "玻璃强度：${((uiPrefs.glassIntensity.floatValue - 0.5f) / 1f * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    Slider(
                        value = uiPrefs.glassIntensity.floatValue,
                        onValueChange = { uiPrefs.setGlassIntensity(it) },
                        valueRange = 0.5f..1.5f,
                    )
                    Text(
                        "饱和度增强与透镜折射幅度按此缩放（0.5 更通透低调，1.5 更强玻璃感）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }

        // ═══ ② 存储位置（自定义实例目录：游戏存到其他目录并直接从该目录运行）═══
        Text(
            "存储位置",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column {
            val dirPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                val dir = StorageDirUtil.treeUriToFile(uri)
                if (dir == null) {
                    Toast.makeText(appContext, "所选目录暂不支持（请选择主存储或 SD 卡目录）", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                if (!StorageDirUtil.isWritableDir(dir)) {
                    Toast.makeText(appContext, "所选目录不可写，无法存放实例", Toast.LENGTH_LONG).show()
                    return@rememberLauncherForActivityResult
                }
                // 持久化 SAF 授权（防系统回收）+ 保存路径
                runCatching {
                    appContext.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                // 释放旧目录的持久授权
                uiPrefs.instanceDirUri.value.takeIf { it.isNotBlank() }?.let { old ->
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            Uri.parse(old),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                }
                uiPrefs.setInstanceDir(dir.absolutePath, uri.toString())
                viewModel.rescanInstances()
                Toast.makeText(appContext, "实例目录已切换，正在扫描所选目录…", Toast.LENGTH_LONG).show()
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (uiPrefs.instanceDirPath.value.isBlank()) "默认：应用外部目录 instances/"
                    else "当前：${StorageDirUtil.displayPath(uiPrefs.instanceDirPath.value)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    if (StorageDirUtil.hasAllFilesAccess(appContext)) {
                        dirPicker.launch(null)
                    } else {
                        // 先引导授予「所有文件访问」（Android 11+ 分区存储下 File API 读写任意目录的前提）
                        Toast.makeText(
                            appContext,
                            "请在系统设置中授予「所有文件访问」后，再次点击选择目录",
                            Toast.LENGTH_LONG,
                        ).show()
                        runCatching {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:com.kaze.newage"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            appContext.startActivity(intent)
                        }
                    }
                }) { Text("选择目录") }
            }
            if (uiPrefs.instanceDirPath.value.isNotBlank()) {
                TextButton(
                    onClick = {
                        uiPrefs.setInstanceDir("")
                        viewModel.rescanInstances()
                        Toast.makeText(appContext, "已恢复默认实例目录", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(top = 2.dp),
                ) { Text("恢复默认") }
            }
            Text(
                "游戏实例默认存到应用外部目录；可自定义到其他目录（如大分区/SD 卡），新实例存到所选目录，所选目录中已有的服务端也会被直接识别运行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ═══ ③ 通用（内容直接铺背景）═══
        Text(
            "通用",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column {
                // 背景图
                AccordionRow(
                    title = "背景图",
                    desc = if (uiPrefs.hasBackgroundImage) "已设置 · 点击调整模糊与遮罩" else "自定义壁纸，选图后裁剪，可模糊 + 遮罩",
                    expanded = openSection == "bg",
                    onClick = { openSection = if (openSection == "bg") null else "bg" },
                )
                AnimatedVisibility(visible = openSection == "bg") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { imageLauncher.launch(arrayOf("image/*")) }) {
                                Text("选择背景图")
                            }
                            if (uiPrefs.hasBackgroundImage) {
                                OutlinedButton(onClick = {
                                    uiPrefs.clearBackgroundImage()
                                    uiPrefs.setBgEnabled(false)
                                }) { Text("清除") }
                            }
                            // 已设置时允许重新裁剪
                            if (uiPrefs.hasBackgroundImage) {
                                OutlinedButton(onClick = {
                                    val f = File(uiPrefs.backgroundImagePath() ?: "")
                                    if (f.exists()) {
                                        try {
                                            val fileUri = FileProvider.getUriForFile(
                                                appContext,
                                                "${appContext.packageName}.cropper.fileprovider",
                                                f,
                                            )
                                            cropLauncher.launch(CropImageContractOptions(fileUri, CropImageOptions()))
                                        } catch (_: Exception) { }
                                    }
                                }) { Text("重设") }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("显示背景", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "选择图片后自动开启",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = uiPrefs.bgEnabled.value,
                                onCheckedChange = { uiPrefs.setBgEnabled(it) },
                            )
                        }
                        if (uiPrefs.hasBackgroundImage) {
                            Text("模糊强度", style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = uiPrefs.bgBlur.floatValue,
                                onValueChange = { uiPrefs.setBgBlur(it) },
                                valueRange = 0f..25f,
                            )
                            Text("遮罩浓度（越高越暗，0 为不遮）", style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = uiPrefs.bgOpacity.floatValue,
                                onValueChange = { uiPrefs.setBgOpacity(it) },
                                valueRange = 0f..90f,
                            )
                        } else {
                            Text(
                                "未选择背景图",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("图标与文字颜色", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "深色背景图选「白色」、浅色图选「黑色」可保证可读性",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FgColorMode.entries.forEach { mode ->
                                CheckChip(
                                    selected = uiPrefs.fgColorMode.value == mode.id,
                                    label = mode.label,
                                    onClick = { uiPrefs.setFgColorMode(mode.id) },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))

                // Java 运行时
                var javaDeleteConfirm by remember { mutableStateOf<Int?>(null) }
                AccordionRow(
                    title = "Java 运行时",
                    desc = "已装：" + if (javaVersions.isEmpty()) "无" else javaVersions.sorted().joinToString(" / ") { "Java $it" },
                    expanded = openSection == "java",
                    onClick = { openSection = if (openSection == "java") null else "java" },
                )
                javaDeleteConfirm?.let { v ->
                    AlertDialog(
                        onDismissRequest = { javaDeleteConfirm = null },
                        title = { Text("删除 Java $v？") },
                        text = { Text("将删除运行时文件（约 200MB）并清理下载残留。正在使用 Java $v 的实例将无法启动，直到重新安装。") },
                        confirmButton = {
                            TextButton(onClick = {
                                javaDeleteConfirm = null
                                viewModel.uninstallJava(v)
                            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { javaDeleteConfirm = null }) { Text("取消") }
                        },
                    )
                }
                AnimatedVisibility(visible = openSection == "java") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Java 不随应用内置，按需下载所需版本；不同 MC 版本对 Java 的要求不同（1.8–1.16.5→8 / 1.18–1.20.4→17 / ≥1.20.5→21 / ≥26.x→25），可在新建实例时手动指定。下载支持断点续传，断网会自动重试；中断后可点击继续。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        listOf(8, 17, 21, 25).forEach { v ->
                            val installed = javaVersions.contains(v)
                            val isTaskFor = javaTask.version == v
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text("Java $v", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        when {
                                            installed -> "已安装"
                                            javaTask.running && isTaskFor -> "任务进行中…"
                                            javaTask.error != null && isTaskFor -> javaTask.error.orEmpty().take(80)
                                            else -> "未安装"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            installed -> statusPalette().running
                                            javaTask.error != null && isTaskFor -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                when {
                                    installed -> OutlinedButton(
                                        onClick = { javaDeleteConfirm = v },
                                        enabled = !javaTask.running,
                                    ) { Text("删除") }
                                    javaTask.running && isTaskFor && !javaTask.cancelRequested -> OutlinedButton(
                                        onClick = { viewModel.cancelJavaTask() },
                                    ) { Text("取消") }
                                    javaTask.error != null && isTaskFor -> Button(
                                        onClick = { viewModel.installJava(v) },
                                        enabled = !javaTask.running,
                                    ) { Text("重试") }
                                    else -> Button(
                                        onClick = { viewModel.installJava(v) },
                                        enabled = !javaTask.running,
                                    ) {
                                        Text(
                                            if (javaTask.running && isTaskFor) "取消中…" else "下载安装"
                                        )
                                    }
                                }
                            }
                        }
                        if (javaTask.running) {
                            LinearProgressIndicator(
                                progress = { javaTask.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            javaTask.message.let {
                                if (it.isNotBlank()) Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))

                // 守护与日志（前台服务 / 电池白名单 / 日志导出）
                AccordionRow(
                    title = "守护与电池",
                    desc = "前台服务守护 · 电池优化白名单",
                    expanded = openSection == "guard",
                    onClick = { openSection = if (openSection == "guard") null else "guard" },
                )
                AnimatedVisibility(visible = openSection == "guard") {
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("前台服务守护", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "服务端运行时会拉起前台服务，应用退到后台也不被系统回收。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HorizontalDivider(Modifier.padding(vertical = 2.dp))

                        // 电池优化白名单
                        val pm = appContext.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                        var batteryOk by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(appContext.packageName)) }
                        val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                            batteryOk = pm.isIgnoringBatteryOptimizations(appContext.packageName)
                        }
                        Text("电池优化白名单", style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (batteryOk) "已允许后台运行，应用不被省电策略回收"
                            else "未加入白名单：应用在后台可能被系统回收",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (batteryOk) statusPalette().running
                            else MaterialTheme.colorScheme.error,
                        )
                        if (!batteryOk) {
                            Button(
                                onClick = {
                                    try {
                                        batteryLauncher.launch(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                android.net.Uri.parse("package:${appContext.packageName}"),
                                            )
                                        )
                                    } catch (_: Exception) { }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("请求忽略电池优化")
                            }
                            Text(
                                "vivo 等机型另有「后台耗电管理」：请在 系统设置 → 电池 → 后台耗电管理 中允许 Kaze SLauncher。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))

                // 关于与许可证
                AccordionRow(
                    title = "关于与许可证",
                    desc = "v1.0.0 · GNU GPL-3.0",
                    expanded = openSection == "about",
                    onClick = { openSection = if (openSection == "about") null else "about" },
                )
                AnimatedVisibility(visible = openSection == "about") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        Text(
                            "Kaze SLauncher\n" +
                                "在 Android 上运行 Minecraft Java 服务端的启动器。\n\n" +
                                "本软件以 GNU GPL-3.0 发布，源码随发行提供。\n" +
                                "架构与 UI 体系参考 Fold Craft Launcher（FCL）与 ZalithLauncher2（均 GPL-3.0）；" +
                                "运行环境为 proot + Ubuntu 24.04 rootfs。完整第三方组件清单见 THIRD_PARTY_NOTICES.md。\n\n" +
                                "下载或运行 Minecraft 服务端即表示你同意 Minecraft EULA（aka.ms/MinecraftEULA）。\n" +
                                "Minecraft 是 Mojang Studios 的商标，本项目与其无关。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
        }
    }
}

/** 手风琴行：标题 + 摘要 + 旋转箭头 */
@Composable
private fun AccordionRow(title: String, desc: String, expanded: Boolean, onClick: () -> Unit) {
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "accordionArrow")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            Icons.Filled.KeyboardArrowDown,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(arrow),
        )
    }
}

/** 主题选择磁贴：迷你预览 + 名称 */
@Composable
private fun ThemeTile(
    mode: AppThemeMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .border(
                BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else cardBorderColor(),
                ),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ThemePreview(mode, Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)))
        Text(
            mode.label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 主题迷你预览：M3=白卡片蓝点 / GLASS=柔光玻璃 */
@Composable
private fun ThemePreview(mode: AppThemeMode, modifier: Modifier = Modifier) {
    when (mode) {
        AppThemeMode.M3 -> Box(modifier.background(Color(0xFFF5F7FA))) {
            Box(
                Modifier
                    .padding(8.dp)
                    .fillMaxWidth()
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, bottom = 8.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3563E9))
            )
        }
        AppThemeMode.GLASS -> Box(
            modifier.background(Brush.verticalGradient(listOf(Color(0xFFF2F6FC), Color(0xFFCCDFF4))))
        ) {
            // 柔光斑
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 6.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFF7FADFF).copy(alpha = 0.95f), Color.Transparent)))
            )
            // 玻璃面板 + 顶部镜面高光
            Column(Modifier.padding(8.dp).fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.95f), Color.Transparent)))
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
                        .background(Color.White.copy(alpha = 0.45f))
                )
            }
        }
    }
}
