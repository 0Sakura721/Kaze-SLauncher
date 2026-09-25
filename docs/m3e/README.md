# KAZE SLauncher 界面重构 · m3e-canvas 设计稿

这一目录是本次界面重构的**设计源**。它不是手写的设计文档，而是用
[m3e-canvas](https://github.com/lnkiai/m3e-canvas)（MIT，Material 3 Expressive 草图工具）
**真正跑出来的产物**：`docs/m3e/kaze-redesign.prompt.md` 里的每一句话都由 m3e-canvas 自己的
prompt 引擎（`lib/prompt.ts` 的 `buildPrompt`）根据画布文档生成，不是我们代笔的。

## 怎么来的

m3e-canvas 是纯前端工具（Next.js 静态导出），数据存在 localStorage，没有后端。
本机无法访问 `maven.google.com` / `raw.githubusercontent.com` 等站点，但 `api.github.com`
与 npm registry 可用，因此流程是：

1. `git clone --depth 1 https://github.com/lnkiai/m3e-canvas` 到临时目录；
2. 用 `gen.mts`（本目录 `tools/gen.mts` 存档）**在代码里构造 `Doc` 文档对象**，
   再直接调用该项目的 `buildPrompt()` / `isProject()`；
3. 校验几何（不越界、不重叠——m3e-canvas 把"两组重叠"读作"叠放"，普通堆叠必须不重叠）；
4. 同时导出画布文档、分享链接与逐屏 prompt。

这样做而不是手工拖拽，是因为拖拽结果无法版本化、无法复核；而这个脚本
**每次跑出来的 prompt 都与在浏览器里拖出同样画布后点「Prompt」得到的一致**。

## 文件

| 文件 | 说明 |
|---|---|
| `kaze-redesign.prompt.md` | **整包 prompt**：配色、形状/字体/动效、9 个屏幕的结构、行为跳转、组件样式、整体原则 |
| `prompt-<屏幕>.md` | 逐屏 prompt（m3e-canvas 的"只导出这一屏"）。实现时按屏喂给编码 agent，比整包更准 |
| `kaze-redesign.m3e.json` | 画布文档。在 m3e-canvas 里用「打开项目」导入即可继续编辑 |
| `kaze-redesign.share.txt` | 分享链接（`#docz=`，deflate-raw + base64url）。用浏览器打开即还原整份画布 |
| `kaze-redesign.canvas-notes.json` | 画布上的旁注（状态栏提示），不进 prompt |
| `screenshots/` | 重构后的真实渲染图（Roborazzi 纯 JVM 截图，非设计稿）。见下 |
| `tools/gen.mts` | 生成上面一切的脚本 |

## 截图

`screenshots/` 是**实现之后**从真实 Compose 渲染出来的 PNG，用来核对设计是否真的落地了：

```
./gradlew :app:recordRoborazziArm64Debug     # 重新生成到 app/build/screenshots/
```

| 图 | 看什么 |
|---|---|
| `m3e_loading_shapes.png` | 官方 7 个加载形状（SoftBurst / Cookie9 / Pentagon / Pill / Sunny / Cookie4 / Oval）逐一渲染 |
| `m3e_loading_variants.png` | 指示器 / 细环 / 静止造型三种用法，以及中间帧的顶点数 |
| `m3e_components_light.png` · `m3e_components_dark.png` | 组件层：卡片三变体、状态胶囊、相连列表、分段选择、波浪进度条（浅色 + 深色各一遍） |
| `screen_home.png` · `screen_home_with_instance.png` | 主页空态 / 有实例（后者才看得到主行动按钮组与次要动作行） |
| `screen_server_with_instances.png` | 服务端列表的相连列表、分类着色、选中环 |
| `screen_console.png` · `screen_console_dark.png` | 终端画布 + 融合的发送按钮（刻意保留的「终端」观感） |
| `screen_settings.png` · `screen_settings_dark.png` | 分组卡 + 设置行 |
| `screen_new_server_step1.png` | 新建向导第 1 步 |
| `status_orbs_light.png` · `components_dark.png` | 旧的状态球组件级截图（已换成形状变化指示器，仅作对照保留） |

深色版逐屏都有（`*_dark.png`）：设计稿明确要求浅色与深色两套都做。

## 画布

浏览器打开 `kaze-redesign.share.txt` 里那一条链接，就能看到 9 个并排的手机画布，
包含 68 个组件组 / 77 个组件、真实的 Material 3 Expressive 组件库、屏幕间的跳转箭头，
按 `P` 可以点着走一遍流程。

## 设计要点（agent 已定的四轴）

| 轴 | 取值 | 理由 |
|---|---|---|
| 颜色 | `purple` 预设 + **动态取色** | `#6750A4` 是 Google 官方 baseline seed，动态取色在 Android 12+ 仍跟用户壁纸走，与我们原有的「壁纸动态取色 / 自定义主色」设置兼容 |
| 形状 | `rounded` | 按钮胶囊、卡片 20dp、对话框 28dp；不再给每个组件单独定圆角 |
| 字体 | `Roboto Flex` + **emphasized** | 标题/按钮/标签用 M3 Expressive 的强调字阶（titleMedium 及以下由 Medium 提到 Bold） |
| 动效 | `expressive` | `MotionScheme.expressive()`：屏幕过渡与状态变化带轻微回弹 |

主题同时覆盖**浅色与深色**（画布里 9 屏都是浅色，prompt 里明确要求两套都做）。

## 一处刻意不动

**常驻底栏保留现有设计。** 画布里为每个屏幕都画了一个 4 项导航栏，只为确认它相对内容的
位置与高度；prompt 的「行为与屏幕跳转」一节里也写明了这一点。底栏的液态玻璃实现
（`AppRoot.kt` 的 `LiquidGlassNavBar`）本次一个字都不改。
