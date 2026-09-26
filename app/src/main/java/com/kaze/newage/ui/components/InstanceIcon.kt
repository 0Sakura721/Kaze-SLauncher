package com.kaze.newage.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kaze.newage.R
import com.kaze.newage.data.model.CoreType

/**
 * 各核心的**官方图标**。
 *
 * 来源与授权见 `THIRD_PARTY_NOTICES.md`：都是对应项目自己的 logo
 * （Purpur 取自项目官网，其余取自各项目 GitHub 组织头像），仅用于标识"这是哪个核心"。
 *
 * 原版 Vanilla 用的是**本项目自己画的像素草方块**，不含 Mojang 的贴图素材。
 *
 * 没有官方图标的核心（自定义导入）返回 null，回退到着色方块 + 矢量图标。
 */
@DrawableRes
private fun coreLogoRes(type: CoreType): Int? = when (type) {
    CoreType.VANILLA -> R.drawable.ic_core_vanilla
    CoreType.PAPER -> R.drawable.ic_core_paper
    CoreType.PURPUR -> R.drawable.ic_core_purpur
    CoreType.SPIGOT -> R.drawable.ic_core_spigot
    CoreType.FABRIC -> R.drawable.ic_core_fabric
    CoreType.FORGE -> R.drawable.ic_core_forge
    CoreType.NEOFORGE -> R.drawable.ic_core_neoforge
    CoreType.CUSTOM -> null
}

/**
 * 实例/核心图标。
 *
 * 有官方图标的直接用官方图标（App 图标式的圆角方块），其余按类型回退到
 * "着色圆角方块 + 图标" —— 对应 ZalithLauncher2 的 VersionIconImage
 * （GPL-3.0，无自定义图时按类型回退默认图标）。
 *
 * 资源都是 192x192 的成品方图，所以这里用 [ContentScale.Crop] 铺满、由 clip 出圆角，
 * 不再额外加背景（Paper/Forge/NeoForge 的 logo 自带底色，透明底的几张已在资源里
 * 垫好浅灰底，否则 Spigot 的深灰 logo 在深色卡片上看不见）。
 */
@Composable
fun InstanceIcon(
    type: CoreType,
    modifier: Modifier = Modifier,
    size: Int = 44,
) {
    val shape = RoundedCornerShape((size / 3.5f).dp)
    coreLogoRes(type)?.let { res ->
        Image(
            painter = painterResource(res),
            contentDescription = type.displayName,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size.dp)
                .clip(shape),
        )
        return
    }

    // 回退：没有官方图标的核心（自定义导入）
    val tint = Color(0xFF6E7B8F)
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(lerp(tint, Color.White, 0.16f), tint))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Archive,
            contentDescription = type.displayName,
            tint = Color.White.copy(alpha = 0.95f),
            modifier = Modifier.size((size * 0.5f).dp),
        )
    }
}
