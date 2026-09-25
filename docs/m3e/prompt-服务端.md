请用 Material 3 Expressive 的设计实现“服务端”屏幕。Android 上运行 Minecraft Java 服务端的启动器。核心链路：部署自包含 Linux 环境（proot + Ubuntu rootfs）→ 按 MC 版本自动安装 Java → 下载或导入服务端核心 → 首次启动自动处理 eula.txt → 实时控制台看日志并输入命令。数据模型是「多实例」：每个服务端实例有独立的目录、Java 版本、内存上限、控制台日志与运行状态。这是对既有应用的一次界面重构：后端、功能与常驻底栏全部保留，只重做其余界面层。
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
服务端实例的列表与管理：多开、筛选、启停、导入。
“服务端”屏幕从上到下依次如下（重叠的组件会特别说明）：
- 上部靠左，从左到右横向排成一行：“新建”的填充按钮（带 add 图标）、“导入 jar”的色调按钮（带 file_open 图标）（放在同一行并垂直居中，不要竖着堆叠或换行）。
- 上部居中放置由“全部 3”(选中)“官方 1”“性能 1”“模组 1”横向排列组成的标签片组。
- 上部放置“生存服”（辅助文本“Paper 1.21.4 · Java 21 · 4096 MB · 运行中”），左侧显示 dns 图标（背景 primaryContainer）。
- 中部放置“创造服”（辅助文本“Paper 1.20.4 · Java 17 · 2048 MB · 已停止”），左侧显示 dns 图标（背景 secondaryContainer）。
- 中部放置“模组服”（辅助文本“Fabric 1.21.1 · Java 21 · 6144 MB · EULA 未接受”），左侧显示 extension 图标（背景 tertiaryContainer）。
- 中部靠左放置由“启动全部”(填充)“停止全部”(色调)这 2 个按钮横向相连组成的按钮组。
- 下部放置4个项目的导航栏（“主页”(home)、“服务端”(dns)、“控制台”(terminal)、“设置”(settings)，“服务端”为选中状态）。

## 行为与屏幕跳转
- 导航栏：点击“主页”项后以淡入的方式跳转到“主页”屏幕；点击“服务端”项后以淡入的方式跳转到“服务端”屏幕；点击“控制台”项后以淡入的方式跳转到“控制台”屏幕；点击“设置”项后以淡入的方式跳转到“外观设置”屏幕；常驻底栏：**这一处保留现有设计，本次重构不动它**。列在草图里只为了确认它相对内容的位置与高度。
- “新建”按钮：点击后以从底部滑入的方式跳转到“新建服务端”屏幕；打开新建向导。
- “导入 jar”按钮：用系统文件选择器挑一个已有的 server.jar 导入。
- “全部 3”标签片：分段筛选的选中项。
- “官方 1”标签片：Vanilla / Paper。
- “性能 1”标签片：Purpur / Spigot。
- “模组 1”标签片：Fabric / Forge / NeoForge。
- “生存服”列表项：点击后以从右侧滑入的方式跳转到“实例详情”屏幕；实例行：点开实例详情；左侧是运行状态徽标；行尾是启停按钮与 ⋮ 菜单。
- “模组服”列表项：EULA 未接受的实例必须在行内直接点出来，不能藏进详情页。
- “启动全部”按钮：启动当前筛选出的全部实例。
- “停止全部”按钮：停止全部运行中的实例。

## 各组件的样式
以下是所用组件的参考。数值均为 M3 Expressive 的标准值，能用标准组件实现的就交给标准组件，并可根据内容适当调整。
- 导航栏：高 80dp，背景为 surfaceContainer。背景延伸到屏幕底部的手势导航区域，并按系统内边距在底部留出空间。选中项用 secondaryContainer 的胶囊指示器（宽 64dp、高 32dp）表示，图标为填充样式，标签用 labelMedium。
- 按钮：中号，高 56dp，完全圆角（胶囊形）。填充用 primary，色调用 secondaryContainer，描边用 1dp 的 outline 边框。横向相连的按钮组以 3dp 间距排列，只把相邻的内侧圆角缩小到 8dp，外侧保持圆角（M3 Expressive 的 Connected button group）。
- 标签片：高 32dp，圆角 8dp。选中状态用 secondaryContainer 填充并在前面显示勾选图标。横向标签片组间距 8dp，溢出时横向滚动。
- 列表项：高 72dp，左侧图标 24dp（未指定时放在 40dp 的 primaryContainer 圆形上），主文本用 bodyLarge，辅助文本用 bodyMedium 的 onSurfaceVariant，背景为指定的颜色角色（未指定则为 surfaceContainerLow）。上下相连的列表以 3dp 间距排列，外侧圆角 28dp，相邻内侧圆角 8dp（M3 Expressive 的列表样式）。

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