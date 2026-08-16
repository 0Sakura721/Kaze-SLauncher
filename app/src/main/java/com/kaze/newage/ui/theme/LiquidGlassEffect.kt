package com.kaze.newage.ui.theme

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/**
 * 液态玻璃效果库（对齐「现在的 BiliPai」= Miuix + Kyant0/AndroidLiquidGlass，均为 Apache-2.0）：
 *  - glassSaturation：vibrancy 等效（BiliPai vibrancy() = 饱和度 1.5）
 *  - liquidGlassLensSafe：Kyant0 圆角矩形折射透镜（BiliPai lens(refractionHeight=24dp, amount=24dp) 同款，
 *    单次 content.eval、无三角函数——vivo Adreno 735 实测可用；软件渲染自动跳过）
 *  - liquidGlassEdgeRefractionSafe：BiliPai 旧 SDF 边缘折射（保留备用）
 */

/**
 * 饱和度增强（等效 BiliPai vibrancy()：brightness=0, contrast=1, saturation=1.5）。
 * 不传 factor 时按「玻璃强度」设置自动缩放（默认 1.5 × LocalGlassIntensity）。
 */
fun Modifier.glassSaturation(factor: Float? = null): Modifier = composed {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@composed this
    val intensity = LocalGlassIntensity.current
    val f = (factor ?: 1.5f * intensity).coerceIn(0.1f, 3f)
    val effect = remember(f) {
        RenderEffect.createColorFilterEffect(
            android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix().apply { setSaturation(f) }
            )
        ).asComposeRenderEffect()
    }
    this.graphicsLayer { renderEffect = effect }
}

/**
 * 底栏玻璃背景：**软件模糊**（vivo Android 16 实测 RenderEffect blur 不渲染——
 * Haze 与自研 graphicsLayer renderEffect 均无效，100dp 极端值文字仍清晰）。
 * 原理：draw 阶段把录制的内容层降采样 record 进小 GraphicsLayer（指令级，廉价）；
 * 协程每帧 toImageBitmap 栅格化小图并缓存；绘制时线性放大（降采样放大 = 模糊，
 * 与 GPU 模糊支持无关，任何设备都生效）。
 *
 * @param contentLayer 录制了全屏内容的 GraphicsLayer
 * @param sampleScale 降采样比例（越小越糊；0.25 柔和，0.12 强糊）
 */
@Composable
fun Modifier.glassBackdropBlur(
    contentLayer: GraphicsLayer,
    sampleScale: Float = 0.25f,
): Modifier = composed {
    val smallLayer = rememberGraphicsLayer()
    var cached by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    // 底栏节点在窗口中的实际坐标（px）：模糊源必须取底栏**正下方**的内容区域
    var offsetInWin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val s = sampleScale.coerceIn(0.08f, 0.6f)
    // 性能关键：只栅格化**底栏那一小条区域**（约节点尺寸×s²≈15KB），
    // 而不是全屏 14MB；每 2 帧栅格化一次（30fps 模糊更新，滚动时无感）
    LaunchedEffect(contentLayer, s) {
        var frame = 0
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            frame++
            if (frame and 1 == 0) cached = smallLayer.toImageBitmap()
        }
    }
    this
        .onGloballyPositioned { offsetInWin = it.positionInWindow() }
        .drawWithContent {
            val barW = (size.width * s).toInt().coerceAtLeast(1)
            val barH = (size.height * s).toInt().coerceAtLeast(1)
            // 显式 record(size)（Miuix LayerRecorder 同款）：视口=底栏区域缩小图；
            // 平移使视口对准底栏窗口位置，再缩比降采样（录制即盒式模糊）
            smallLayer.record(androidx.compose.ui.unit.IntSize(barW, barH)) {
                withTransform({
                    translate(-offsetInWin.x, -offsetInWin.y)
                    scale(s, s, pivot = androidx.compose.ui.geometry.Offset.Zero)
                }) {
                    drawLayer(contentLayer)
                }
            }
            val bmp = cached ?: return@drawWithContent
            // 线性放大绘制（降采样放大 = 模糊），src 即整张小图
            drawImage(
                image = bmp,
                srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                srcSize = androidx.compose.ui.unit.IntSize(bmp.width, bmp.height),
                dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
            )
        }
}

/**
 * 圆角矩形折射透镜（照搬 Kyant0/AndroidLiquidGlass ROUNDED_RECT_REFRACTION_SHADER，
 * 即 BiliPai 现役 lens() 效果，Apache-2.0）。
 * 在面板边缘 [refractionHeight] 条带内按 SDF 梯度做 circleMap 位移采样（玻璃边缘的透镜弯曲）。
 * 真实 GPU 启用，软件渲染（模拟器）自动跳过；vivo Adreno 735/API36 实测渲染正常。
 *
 * @param refractionHeight 折射条带宽度（px）；BiliPai 底栏用 24dp
 * @param refractionAmount 最大位移量（px）；BiliPai 底栏用 24dp
 * @param depthEffect 附加向心梯度（景深感）
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun Modifier.liquidGlassLensSafe(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
): Modifier = composed {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@composed this
    val shader = remember { RuntimeShader(ROUNDED_RECT_REFRACTION_SHADER) }
    val shaderEffect = remember(shader) {
        RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
    var gpuOk by remember { mutableStateOf<Boolean?>(null) }
    this.graphicsLayer {
        val ok = gpuOk ?: probeGpuRenderer().also {
            gpuOk = it
            // vivo 屏蔽 Log.d，用 println 走 System.out 便于真机诊断
            println("KazeGlass: lens enabled=$it")
        }
        if (!ok) return@graphicsLayer
        shader.setFloatUniform("size", size.width.toFloat(), size.height.toFloat())
        // 全圆角胶囊：cornerRadii = min(半宽, 半高)
        val radius = minOf(size.width / 2f, size.height / 2f)
        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
        shader.setFloatUniform("refractionHeight", refractionHeight)
        shader.setFloatUniform("refractionAmount", -refractionAmount)
        shader.setFloatUniform("depthEffect", if (depthEffect) 1f else 0f)
        renderEffect = shaderEffect
    }
}

/**
 * 边缘折射（照搬 BiliPai 旧 LiquidGlassShader，GPL-3.0）：SDF 圆角矩形边缘采样偏移。
 * 保留备用（曾用于底栏，现已被 Kyant0 透镜取代）。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun Modifier.liquidGlassEdgeRefractionSafe(
    refractIntensity: Float = 0.32f,
    thickness: Float = 12f,
    refractIndex: Float = 1.52f,
    cornerRadius: Float = 24f,
): Modifier = composed {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@composed this
    val shader = remember { RuntimeShader(LIQUID_GLASS_EDGE_SHADER) }
    val shaderEffect = remember(shader) {
        RenderEffect.createRuntimeShaderEffect(shader, "img").asComposeRenderEffect()
    }
    var gpuOk by remember { mutableStateOf<Boolean?>(null) }
    this.graphicsLayer {
        val ok = gpuOk ?: probeGpuRenderer().also { gpuOk = it }
        if (!ok) return@graphicsLayer
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("center", size.width / 2f, size.height / 2f)
        shader.setFloatUniform("size", size.width / 2f, size.height / 2f)
        shader.setFloatUniform("radius", cornerRadius, cornerRadius, cornerRadius, cornerRadius)
        shader.setFloatUniform("thickness", thickness)
        shader.setFloatUniform("refract_index", refractIndex)
        shader.setFloatUniform("refract_intensity", refractIntensity)
        shader.setFloatUniform("foreground_color_premultiplied", 0f, 0f, 0f, 0f)
        renderEffect = shaderEffect
    }
}

/** 探测 GL 渲染器：软件渲染器（模拟器 SwiftShader/llvmpipe/ANGLE 等）返回 false */
private fun probeGpuRenderer(): Boolean {
    val renderer = runCatching {
        android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
    }.getOrNull() ?: return true
    Log.d("KazeGlass", "GL renderer: $renderer")
    val s = renderer.lowercase()
    return !(s.contains("swiftshader") ||
        s.contains("emulator") ||
        s.contains("llvmpipe") ||
        s.contains("google"))
}

/** Kyant0/AndroidLiquidGlass 圆角矩形折射透镜（Apache-2.0，BiliPai 现役 lens 同款） */
private const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = clamp(coord + d * grad, float2(0.0), size);
    return content.eval(refractedCoord);
}
"""

/** BiliPai 旧 LiquidGlassShader（GPL-3.0）：SDF 边缘采样偏移，保留备用 */
private const val LIQUID_GLASS_EDGE_SHADER = """
uniform shader img;

uniform float2 resolution;
uniform float2 center;
uniform float2 size;
uniform float4 radius;
uniform float thickness;
uniform float refract_index;
uniform float refract_intensity;
uniform float4 foreground_color_premultiplied;

half sdfRect(half2 p, half4 r) {
  r.xy = (p.x > 0.0) ? r.xy : r.zw;
  r.x  = (p.y > 0.0) ? r.x  : r.y;
  half2 q = abs(p) - size + r.x;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r.x;
}

half4 srcOver(half4 src, half4 dst) {
    half3 outRGB = (src.rgb + dst.rgb * (1.0 - src.a));
    float outA = src.a + (1.0 - src.a) * dst.a;
    return half4(outRGB, outA);
}

half4 main(in float2 fragCoord) {
  half2 p = fragCoord - center;
  half sd = sdfRect(p, radius);
  half2 uv = fragCoord;

  if (sd < 0.0 && thickness > 0.0 && refract_intensity > 0.0) {
    half edge = clamp((thickness + sd) / thickness, 0.0, 1.0);
    half strength = edge * edge * refract_intensity;
    half2 direction = normalize(p / max(size, half2(1.0, 1.0)));
    uv += direction * strength * max(refract_index - 1.0, 0.0);
  }

  return srcOver(half4(foreground_color_premultiplied), img.eval(uv));
}
"""
