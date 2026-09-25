package com.kaze.newage.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * 形状变化加载指示器的几何自检。
 *
 * 这套几何是「互插值」的前提：7 个形状必须被重采样成**同一数量的点**，
 * 且都归一化到以原点为中心、外接框塞进 [-1,1]。任何一条不成立，
 * morph 就会画出扭曲的轮廓 —— 而那种问题在截图里很难一眼看出来。
 *
 * 纯几何，不需要 Compose 运行时，所以是普通单元测试。
 */
class LoadingShapesTest {

    @Test
    fun `七个形状都存在且点数一致`() {
        val shapes = LOADING_SHAPES
        assertEquals("官方 7 形：SoftBurst/Cookie9/Pentagon/Pill/Sunny/Cookie4/Oval", 7, shapes.size)
        shapes.forEachIndexed { i, pts ->
            assertEquals(
                "第 $i 个形状（${LOADING_SHAPE_NAMES[i]}）重采样后的点数必须与其余形状一致，" +
                    "否则逐点插值会错位",
                SHAPE_POINTS,
                pts.size,
            )
        }
    }

    @Test
    fun `每个形状都归一化到以原点为中心的 2x2 框内`() {
        LOADING_SHAPES.forEachIndexed { i, pts ->
            val minX = pts.minOf { it.x }
            val maxX = pts.maxOf { it.x }
            val minY = pts.minOf { it.y }
            val maxY = pts.maxOf { it.y }

            // 外接框必须落在 [-1,1]：这是 normalize() 的归一化口径（缩放到外接圆半径 1 的方形内）
            assertTrue("${LOADING_SHAPE_NAMES[i]} 的 x 超出 [-1,1]：$minX..$maxX", minX >= -1.001f && maxX <= 1.001f)
            assertTrue("${LOADING_SHAPE_NAMES[i]} 的 y 超出 [-1,1]：$minY..$maxY", minY >= -1.001f && maxY <= 1.001f)

            // 居中的意思：外接框的中心在原点附近（morph 时两个形状才不会整体漂移）
            assertTrue(
                "${LOADING_SHAPE_NAMES[i]} 未居中：x 中心 ${(minX + maxX) / 2f}",
                abs((minX + maxX) / 2f) < 0.02f,
            )
            assertTrue(
                "${LOADING_SHAPE_NAMES[i]} 未居中：y 中心 ${(minY + maxY) / 2f}",
                abs((minY + maxY) / 2f) < 0.02f,
            )

            // 长边必须真的撑满 2.0；否则形状会显得比别的形状小一圈
            val span = max(maxX - minX, maxY - minY)
            assertTrue("${LOADING_SHAPE_NAMES[i]} 未撑满归一化框：span=$span", abs(span - 2f) < 0.02f)
        }
    }

    @Test
    fun `重采样是等弧长的，没有零长度的重复点`() {
        LOADING_SHAPES.forEachIndexed { i, pts ->
            val segs = pts.indices.map { idx ->
                val a = pts[idx]
                val b = pts[(idx + 1) % pts.size]
                hypot(b.x - a.x, b.y - a.y)
            }
            val avg = segs.average().toFloat()
            assertTrue("${LOADING_SHAPE_NAMES[i]} 的平均段长为 0", avg > 1e-4f)
            // 等弧长采样后，各段长应当彼此接近（允许形状拐角处的少量偏差）
            val worst = segs.maxOf { abs(it - avg) } / avg
            assertTrue("${LOADING_SHAPE_NAMES[i]} 的段长不均匀：最大偏差 ${worst * 100}%", worst < 0.6f)
        }
    }

    @Test
    fun `morphedShape 在整数处返回完整形状，中点返回插值`() {
        for (i in 0 until 7) {
            val exact = morphedShape(i.toFloat())
            val shape = LOADING_SHAPES[i % 7]
            exact.indices.forEach { k ->
                assertEquals("morphedShape($i) 应当等于第 $i 个形状（点 $k）", shape[k].x, exact[k].x, 1e-5f)
                assertEquals("morphedShape($i) 应当等于第 $i 个形状（点 $k）", shape[k].y, exact[k].y, 1e-5f)
            }
        }
        // 2.5 应当是第 2 与第 3 个形状的中点
        val mid = morphedShape(2.5f)
        val a = LOADING_SHAPES[2]
        val b = LOADING_SHAPES[3]
        mid.indices.forEach { k ->
            assertEquals("中点插值错误（点 $k）", (a[k].x + b[k].x) / 2f, mid[k].x, 1e-5f)
            assertEquals("中点插值错误（点 $k）", (a[k].y + b[k].y) / 2f, mid[k].y, 1e-5f)
        }
    }

    @Test
    fun `morphedShape 会环绕到第一个形状`() {
        // 弹簧过冲可能把进度推过 7；取整后必须回到 0，而不是越界
        val wrapped = morphedShape(7f)
        val first = LOADING_SHAPES[0]
        wrapped.indices.forEach { k ->
            assertEquals("morphedShape(7) 应当绕回第 0 个形状（点 $k）", first[k].x, wrapped[k].x, 1e-5f)
            assertEquals("morphedShape(7) 应当绕回第 0 个形状（点 $k）", first[k].y, wrapped[k].y, 1e-5f)
        }
    }

    @Test
    fun `变形过程的每一帧都不外溢`() {
        // 逐帧扫一遍：插值出来的中间轮廓同样必须留在 [-1,1] 里，
        // 否则指示器在变形过程中会被容器裁掉一角
        var t = 0f
        while (t < 7f) {
            val pts = morphedShape(t)
            assertEquals(SHAPE_POINTS, pts.size)
            val maxAbs = pts.maxOf { max(abs(it.x), abs(it.y)) }
            assertTrue("进度 $t 的轮廓外溢：$maxAbs", maxAbs <= 1.02f)
            // 也不应该塌缩成一个点（两点形状差太大时的插值仍应有面积）
            val minAbs = pts.minOf { max(abs(it.x), abs(it.y)) }
            assertTrue("进度 $t 的轮廓塌缩", minAbs > 0.05f)
            t += 0.05f
        }
    }

    @Test
    fun `官方七形的顺序不能改`() {
        // 这个顺序来自官方 LoadingIndicator 的 IndeterminateIndicatorPolygons：
        // SoftBurst → Cookie9 → Pentagon → Pill → Sunny → Cookie4 → Oval
        assertEquals(
            listOf("SoftBurst", "Cookie9", "Pentagon", "Pill", "Sunny", "Cookie4", "Oval"),
            LOADING_SHAPE_NAMES,
        )
    }

    @Test
    fun `形状具备面积，不是退化成线`() {
        LOADING_SHAPES.forEachIndexed { i, pts ->
            // 鞋带公式：有向面积的绝对值应当明显大于 0
            var area = 0f
            for (k in pts.indices) {
                val a = pts[k]
                val b = pts[(k + 1) % pts.size]
                area += a.x * b.y - b.x * a.y
            }
            area = abs(area) / 2f
            assertTrue("${LOADING_SHAPE_NAMES[i]} 的面积过小：$area", area > 0.5f)
            assertTrue("${LOADING_SHAPE_NAMES[i]} 的面积过大：$area", area < 4f)
        }
    }

    @Test
    fun `包围圆半径不超过 1`() {
        // 绘制时按半径缩放，任何点超出单位圆都会被指示器的容器尺寸裁掉
        LOADING_SHAPES.forEachIndexed { i, pts ->
            val r = pts.maxOf { hypot(it.x, it.y) }
            assertTrue("${LOADING_SHAPE_NAMES[i]} 有顶点超出单位圆：r=$r", r <= 1.42f)
            assertTrue("${LOADING_SHAPE_NAMES[i]} 的半径过小：r=$r", r > 0.5f)
        }
    }
}
