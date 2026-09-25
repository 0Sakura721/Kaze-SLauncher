请用 Material 3 Expressive 的设计实现“设置”屏幕。Android 上运行 Minecraft Java 服务端的启动器。核心链路是：部署自包含 Linux 环境（proot + Ubuntu rootfs）→ 按 MC 版本自动安装 Java → 下载或导入服务端核心 → 首次启动自动处理 eula.txt → 实时控制台看日志并输入命令。数据模型是「多实例」：每个服务端实例有独立的目录、Java 版本、内存上限、控制台日志与运行状态。这是对既有应用的一次界面重构：功能、后端与底栏完全保留，只重做其余界面层。
目标为竖屏手机（412×892dp），同时支持浅色和深色模式，并跟随设备的系统设置切换。
实现目标是 Android（原生应用）。
下面的屏幕结构是传达意图的草图，不是最终规格。不要把它当静态图片照搬，而要做成这类应用通常应具备的功能齐全、真正可用的成品。

## 配色
使用动态配色：在 Android 12 及以上应用由用户壁纸生成的配色方案（dynamicLightColorScheme / dynamicDarkColorScheme），不支持的设备则使用下面的颜色作为备用。
备用主题为 Purple 系。请在Material 3 的浅色和深色配色方案中设置以下颜色，UI 的所有颜色都通过这些角色引用。
浅色配色：
- primary #6750A4 / onPrimary #FFFFFF / primaryContainer #EADDFF / onPrimaryContainer #21005D
- secondary #635A75 / secondaryContainer #E8DEF8 / onSecondaryContainer #1D192B / tertiaryContainer #FFD8E4 / onTertiaryContainer #31111D
- surface #FEF7FF / surfaceContainerLow #F7F2FA / surfaceContainer #F3EDF7 / surfaceContainerHigh #ECE6F0 / surfaceContainerHighest #E6E0E9
- onSurface #1D1B20 / onSurfaceVariant #49454F / outline #79747E / outlineVariant #CAC4D0
- inverseSurface #322F35 / inverseOnSurface #F5EFF7 / inversePrimary #D0BCFF
- error #B3261E / onError #FFFFFF / errorContainer #F9DEDC / onErrorContainer #410E0B
深色配色：
- primary #D2BCFC / onPrimary #32226F / primaryContainer #4C3889 / onPrimaryContainer #E9DDFF
- secondary #CDC1E1 / secondaryContainer #4B425D / onSecondaryContainer #E9DDFD / tertiaryContainer #6C3644 / onTertiaryContainer #FDDAE1
- surface #141317 / surfaceContainerLow #1C1B1F / surfaceContainer #201F23 / surfaceContainerHigh #2B292D / surfaceContainerHighest #363438
- onSurface #E4E1E7 / onSurfaceVariant #C9C4D1 / outline #938F9B / outlineVariant #494550
- inverseSurface #E4E1E7 / inverseOnSurface #313034 / inversePrimary #6750A4
- error #F2B8B5 / onError #601410 / errorContainer #8C1D18 / onErrorContainer #F9DEDC

## 形状、字体与动效
- 圆角沿用 M3 Expressive 的默认值（按钮为胶囊形，卡片 20dp，对话框 28dp）。
- 字体使用 Roboto Flex。标题、按钮文字和标签页使用 M3 Expressive 的 emphasized 字体样式（headlineMediumEmphasized 等更粗的字重）。
- 动效使用 MotionScheme.expressive()：屏幕过渡和状态变化带轻微回弹的弹簧效果。

## 屏幕结构
外观、运行、存储、关于与许可证。
“设置”屏幕从上到下依次如下（重叠的组件会特别说明）：
- 上部靠左放置粗体文本“设置”（28sp）。
- 上部放置浮起卡片。标题“外观”，正文“主题模式 · 颜色来源 · 深色样式”。
- 中部放置3项的列表，从上到下依次为 “主题模式”（辅助文本“跟随系统”），左侧显示 brightness_6 图标（背景 secondaryContainer）、“颜色来源”（辅助文本“壁纸动态取色”），左侧显示 colorize 图标（背景 secondaryContainer）、“深色样式”（辅助文本“普通黑”），左侧显示 dark_mode 图标（背景 secondaryContainer）。
- 中部放置浮起卡片。标题“运行与存储”，正文“实例目录 · 电池优化 · 更新通道”。
- 下部放置3项的列表，从上到下依次为 “实例目录”（辅助文本“默认位置”），左侧显示 folder 图标（背景 secondaryContainer）、“启动时自动检查更新”开关（初始状态为开）、标签为“更新通道”的填充下拉菜单（点击展开菜单选择一项，选项为，初始未选择）（左侧显示 alt_route 图标），辅助文本为“预览版”。
- 下部放置4个项目的导航栏（“主页”(home)、“服务端”(dns)、“控制台”(terminal)、“设置”(settings)，“设置”为选中状态）（部分覆盖在列表项组之上，绘制在前面）。

## 行为与屏幕跳转
- 文本“设置”：页面标题。
- “外观”卡片：分组卡：卡片里是若干设置行。
- “更新通道”下拉菜单：稳定版 / 预览版，点开是一个下拉菜单。
- 导航栏：点击“主页”项后以淡入的方式跳转到“主页”屏幕；点击“服务端”项后以淡入的方式跳转到“服务端”屏幕；点击“控制台”项后以淡入的方式跳转到“控制台”屏幕；点击“设置”项后以淡入的方式跳转到“设置”屏幕；常驻底栏（**保留现有设计，本次不改动**）：液态玻璃浮动胶囊，4 个目的地。列在这里只是为了确认它相对内容的位置。

## 各组件的样式
以下是所用组件的参考。数值均为 M3 Expressive 的标准值，能用标准组件实现的就交给标准组件，并可根据内容适当调整。
- 文本：指定的 sp 字号。标题用 onSurface，说明文字用 onSurfaceVariant，行高为字号的 1.3〜1.5 倍。点击时不加涟漪等反馈。
- 卡片：圆角 20dp。图片区域按每张卡片的描述放在顶部、左侧、右侧或作为整卡背景（背景时从文字一侧加渐变遮罩：浅色文字用黑色，深色文字用白色）。图片保持宽高比并居中裁剪以填满区域。填充用 surfaceContainerHighest，浮起用 surfaceContainerLow 加 Level 1 阴影，描边用 1dp 的 outlineVariant 边框。标题用 titleMedium，正文用 bodyMedium。内边距 20dp，标题与正文间距 4dp，图片与文字间距 12dp。
- 列表项：高 72dp，左侧图标 24dp（未指定时放在 40dp 的 primaryContainer 圆形上），主文本用 bodyLarge，辅助文本用 bodyMedium 的 onSurfaceVariant，背景为指定的颜色角色（未指定则为 surfaceContainerLow）。上下相连的列表以 3dp 间距排列，外侧圆角 28dp，相邻内侧圆角 8dp（M3 Expressive 的列表样式）。
- 开关：M3 标准尺寸（轨道 52×32dp）。开为 primary，关为 surfaceContainerHighest 加 outline 边框。标签在左，开关靠右。
- 下拉菜单：外观与文本输入框相同（高 56dp，描边或填充），末尾带 arrow_drop_down 图标。按 exposed dropdown menu 实现：点击后在下方展开菜单（surfaceContainer，圆角 4dp，项高 48dp），所选值显示在输入框中。
- 导航栏：高 80dp，背景为 surfaceContainer。背景延伸到屏幕底部的手势导航区域，并按系统内边距在底部留出空间。选中项用 secondaryContainer 的胶囊指示器（宽 64dp、高 32dp）表示，图标为填充样式，标签用 labelMedium。

## 整体原则
- 先根据屏幕目的判断这是什么类型的应用，并实现该类应用通常应有的功能（新建、列表、详情、编辑、删除、搜索、设置等，视情况而定），即使草图中没有画出。
- 把数据当作真实数据处理：用户创建的数据要持久化到设备（Room、DataStore 等），重启后仍保留。不要放入虚拟或示例数据，没有数据时显示空状态提示。校验输入，删除和失败要有适当的确认或提示。
- 草图没有写明的行为，根据屏幕目的和组件标签补全。未指定行为的按钮或项目要实现与其标签相符的操作（保存、发送、打开详情页等），不要什么都不做。
- 布局只需保持意图（顺序、分组、相对位置），尺寸和间距可根据内容调整。若在真机上会出问题，宁可能用也不要死守草图。
- 组件使用 Jetpack Compose material3（包含 Expressive API 的最新版） 的标准组件，库里已有的组件不要自行绘制。
- 颜色必须通过上面配色方案的角色名（primary、surfaceContainer 等）引用，不要写死颜色值。
- 屏幕边缘留 16dp，组件之间 8〜16dp，排版使用 M3 的字体样式（titleLarge、bodyMedium 等）。
- 写明“横向排成一行”的组件必须放进同一个 Row（横向容器）并在同一行显示，不要竖着堆叠或换行。行高以最高的组件为准，其余组件垂直居中。
- 写明“内部叠放”的组件要绘制在该容器（容器框或卡片）之上（以容器为背景的 Box）。这种叠放是有意为之，不要因布局原因拆开或调整顺序。前后关系按描述顺序，后写的在前面。
- 可点击的组件加涟漪和轻微缩放反馈。“返回”反向播放进入时的过渡动画，系统返回手势／返回键也要做同样的效果。
- 图标使用 Material Symbols Rounded。
- 不需要在模拟器或真机上验证。实现完成后生成已签名的 release APK 作为交付物。