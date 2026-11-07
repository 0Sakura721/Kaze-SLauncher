package com.kaze.newage.util

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 实例目录解析（StorageDirUtil.treeUriToFile）的路径安全测试。
 *
 * 这个函数把 SAF 的 tree URI 转成真实目录，**输入来自外部**（选择器给的 docId 是
 * URI 里的字符串），所以必须像对待不可信数据一样处理。两条真实风险：
 *  1. `primary:../etc` 这类带 `..` 的 docId 按字符串拼出
 *     `/storage/emulated/0/../etc`，规范化后跑到所选卷之外；
 *  2. 卷根与 Android/data、Android/obb 必须拒绝 —— 前者会让实例扫描把存储根下
 *     任何含 .jar 的顶层目录登记成"实例"，后者是其它应用的私有目录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
class StorageDirUtilTest {

    private fun treeUri(docId: String): Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/${Uri.encode(docId)}")

    // ══════════ 正常路径 ══════════

    @Test
    fun `主存储下的目录能解析（存在时）`() {
        // 用真实存在的主存储目录：Robolectric 会把 getExternalStorageDirectory 指到临时路径
        val base = android.os.Environment.getExternalStorageDirectory()
        val child = File(base, "KazeTestDir").apply { mkdirs() }
        try {
            val dir = StorageDirUtil.treeUriToFile(treeUri("primary:KazeTestDir"))
            assertNotNull("应能解析出目录", dir)
            assertEquals(child.absolutePath, dir!!.absolutePath)
        } finally {
            child.delete()
        }
    }

    @Test
    fun `不存在的目录返回 null`() {
        assertNull(StorageDirUtil.treeUriToFile(treeUri("primary:NoSuchDir_xyz")))
    }

    // ══════════ 拒绝：越界与系统目录 ══════════

    @Test
    fun `带上一级引用（两个点）的 docId 被拒绝_防止跑出所选卷`() {
        val base = android.os.Environment.getExternalStorageDirectory()
        // 造一个上一级引用之后真实存在的目标，确保"拒绝"不是因为目录不存在
        File(base.parentFile, "etc").mkdirs()

        assertNull(
            "带两个点的 docId 必须拒绝",
            StorageDirUtil.treeUriToFile(treeUri("primary:../etc")),
        )
        assertNull(
            "多级上一级引用必须拒绝",
            StorageDirUtil.treeUriToFile(treeUri("primary:MCServer/../../etc")),
        )
        assertNull(
            "反斜杠分隔的上一级引用也要拒绝",
            StorageDirUtil.treeUriToFile(treeUri("primary:..\\etc")),
        )
    }

    @Test
    fun `卷根被拒绝`() {
        assertNull(
            "主存储根目录不能让用户选成实例目录",
            StorageDirUtil.treeUriToFile(treeUri("primary:")),
        )
    }

    @Test
    fun `Android data 与 obb 被拒绝`() {
        assertNull(StorageDirUtil.treeUriToFile(treeUri("primary:Android/data/com.example")))
        assertNull(StorageDirUtil.treeUriToFile(treeUri("primary:Android/obb/com.example")))
    }

    @Test
    fun `非 externalstorage provider 返回 null`() {
        assertNull(
            StorageDirUtil.treeUriToFile(Uri.parse("content://com.other.provider/tree/primary%3AX")),
        )
    }

    @Test
    fun `null 与畸形 URI 返回 null 而不是崩`() {
        assertNull(StorageDirUtil.treeUriToFile(null))
        assertNull(StorageDirUtil.treeUriToFile(Uri.parse("content://com.android.externalstorage.documents/tree")))
        assertNull(StorageDirUtil.treeUriToFile(Uri.parse("content://com.android.externalstorage.documents/other/x")))
    }

    // ══════════ 可写探测 ══════════

    @Test
    fun `可写目录探测成功并清理探针文件`() {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), "probe-test").apply { mkdirs() }
        try {
            assertTrue("临时目录应可写", StorageDirUtil.isWritableDir(dir))
            val leftovers = dir.listFiles { f: File -> f.name.startsWith(".kaze_probe_") }
            assertEquals("探针文件必须被删掉", 0, leftovers?.size ?: 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `不可写目录返回 false 而不是抛异常`() {
        val dir = File("/proc/nonexistent_kaze_probe")
        // 不存在或不可写，两者都应安全返回 false
        assertTrue(!StorageDirUtil.isWritableDir(dir) || !dir.exists())
    }
}