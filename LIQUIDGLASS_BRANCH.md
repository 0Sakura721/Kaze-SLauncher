# 这条分支：`liquidglassver`

**用途：保留「液态玻璃完整接线」的版本。**

## 为什么有这条分支

`main` 上的液态玻璃已经**从构建中彻底移除**（见 `CHANGELOG.md` 的 `Removed（构建内）`）：

- 设置页删掉了整个「液态玻璃」分区与两个对话框，顶部分类条 7 → 6 个分区
- 「主题样式」不再出现 GLASS 选项
- `AppRoot` 底栏的玻璃链改成编译期常量 `val isGlass = false`，
  这样 R8 才能证明整条链不可达并把它从 APK 里剥离
  （写成 `LocalAppTheme.current == GLASS` 时 R8 无法证明恒假，玻璃类仍会打进包）
- 代码本身**一行没删**，仍留在 `main`：`ui/theme/LiquidGlassEffect.kt`、
  `ui/theme/blur/`、`ui/theme/shader/`、`GlassMode`/`glassParams`/`LocalGlassMode` 令牌、
  `SettingsPrefs` 的玻璃项、`AppThemeMode.GLASS` 枚举与玻璃配色分支 —— 只是不再被引用

也就是说，`main` 是"代码在、但接不上"，这条分支是"**代码在、且真的能用**"。

## 这条分支是什么

分支起点是 `effed83`（`main` 上移除液态玻璃那个提交的**父提交**），
所以它**同时**包含：

- 完整接线的液态玻璃（玻璃主题、玻璃模式/强度/原生模糊设置、底栏玻璃链、折射透镜）
- 之后 `main` 上所有功能：应用内诊断日志、`/sdcard/KazeS` 存档目录、
  闪退现场保留（崩溃处理器 + 启动补捞）、KazeS 目录迁移、以及更早的全部修复

## 怎么用

```bash
# 看/跑这个版本
git checkout liquidglassver

# 把某个修复从 main 拿过来（推荐 cherry-pick，别直接 merge，否则玻璃又会被移除）
git cherry-pick <main 上的提交>

# 想恢复 main 的构建里也带上液态玻璃：把 AppRoot 里那两行常量改回读取，
# 并把设置页的主题选项与玻璃分区加回来（注释里写了具体位置）
```

## 注意

从 `main` **merge** 这条分支会把液态玻璃重新接回构建 —— 那是刻意的设计，
不是能顺手合掉的分支。要用就 checkout 出来用。
