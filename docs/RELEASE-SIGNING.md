# 发布签名与密钥管理

> 本文描述当前发布流程。历史上 v0.1.2 及更早的发布包曾使用公开的 Android debug 密钥；该问题在后续版本中修正。旧事故记录不应被理解为当前 Release APK 的签名状态。

## 1. 当前规则

- Release APK 必须由项目正式发布密钥签名。
- 缺少发布密钥时，Gradle **不会回退使用 debug 密钥**；本地可能生成未签名 APK，不能将其分发给用户。
- CI 的 APK 构建会在签名凭据缺失时中止；正式 Release 流程也会检查签名，避免把未签名包发布出去。
- 发布密钥、密钥口令和私钥材料不得提交到 Git 仓库或写入源码。
- 应用更新要求包名、版本号与签名保持兼容；更换签名密钥会影响老用户覆盖安装，必须提前规划与公告。

## 2. 本地配置

将发布密钥文件放在仓库外，并在被 Git 忽略的 `local.properties` 中配置：

```properties
kaze.keystore=/absolute/path/to/kaze-release.jks
kaze.storePassword=替换为实际口令
kaze.keyAlias=kaze
kaze.keyPassword=替换为实际口令
```

将路径替换为当前设备上的真实位置，口令不要照抄示例。也可通过 Gradle 所读取的环境变量配置：

| 环境变量 | 含义 |
|---|---|
| `KAZE_KEYSTORE` | keystore 文件路径 |
| `KAZE_STORE_PASS` | keystore 口令 |
| `KAZE_KEY_ALIAS` | 密钥别名 |
| `KAZE_KEY_PASS` | 密钥口令 |

请先确认 `local.properties` 已被 `.gitignore` 排除，并检查 `git status`，不要误提交密钥或含密钥口令的文件。

## 3. GitHub Actions 配置

Release 流程使用以下仓库 Secrets：

| Secret | 内容 |
|---|---|
| `KAZE_KEYSTORE_B64` | 发布 keystore 文件的 Base64 编码 |
| `KAZE_STORE_PASS` | keystore 口令 |
| `KAZE_KEY_ALIAS` | 密钥别名 |
| `KAZE_KEY_PASS` | 密钥口令 |

在 Linux 环境中，可用 `base64 -w0 kaze-release.jks` 生成第一项的值；其他平台请使用对应的 Base64 工具。只把编码结果存入 GitHub Secret，不要把原始 keystore 上传到仓库。

若 Secrets 未配置或不完整，必须先修复配置再发布。CI 的日志会提示签名配置问题；不要把“Gradle 构建结束”当作 APK 已可分发的证明。

## 4. 构建正式 APK

当前发布架构为 arm64 与 armhf，不发布 universal 包：

```bash
./gradlew :app:assembleArm64Release :app:assembleArmhfRelease
```

常见产物路径：

- `app/build/outputs/apk/arm64/release/app-arm64-release.apk`
- `app/build/outputs/apk/armhf/release/app-armhf-release.apk`

实际文件名以当前构建输出为准。没有正式签名的 APK 不应上传至 Releases，也不应作为升级包分发。

## 5. 发布前核验签名

使用 Android SDK Build Tools 中的 `apksigner`：

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/arm64/release/app-arm64-release.apk
```

Windows 环境可使用对应的 `apksigner.bat`。发布前至少核验：

1. 命令以成功状态退出，且 APK 能通过签名验证。
2. 输出的证书 SHA-256 指纹与上一份已确认的正式发布包一致；若不一致，停止发布并确认是否计划轮换密钥。
3. `versionCode` 严格大于上一版。
4. 两种架构的产物都已验证；不要只检查 arm64 包。
5. 实际上传到 Releases 的文件与经过验证的文件是同一份。下载后可再次核对 GitHub asset 的 SHA-256。

请将最后一次已知正确的证书指纹记录在维护者可访问的安全位置，不要只依赖本地构建缓存或文件名来判断签名。

## 6. 密钥保管与轮换

- 强口令保存在密码管理器中；keystore 与口令应分别备份到至少两个安全位置。
- 发布密钥丢失可能导致无法对现有安装执行常规升级。不要只在一台开发机上保留唯一副本。
- 如果怀疑密钥或口令泄漏，应先评估已发布版本和用户升级路径，再决定轮换方案。**签名变更通常会阻止旧安装直接覆盖升级**，不能只换密钥后直接发版。
- 日常开发使用 Debug 构建即可；正式 Release 永远不应使用公开的 debug 密钥。
- 发版前应检查 Git 历史与工作区，确保正式 keystore 和口令没有被提交。仅删除当前文件不能清除历史提交中的泄漏。

## 7. 增量更新核验

增量补丁只能在验证通过后交给更新器使用。发布前应抽样执行以下闭环：

1. 下载上一版正式 APK、新版正式 APK 和对应补丁。
2. 用项目当前的补丁工具从旧 APK 重建新 APK。
3. 比较重建产物与正式新版 APK 的 SHA-256，必须完全一致。
4. 再验证重建产物的签名，并确认更新器在补丁损坏时会退回完整包。

补丁生成/应用格式的实现分别位于 `tools/make_apk_patch.py` 和 `app/src/main/java/com/kaze/newage/core/update/ApkPatchApplier.kt`；改动格式时必须同步检查两端并更新测试。

## 8. 发版检查清单

- [ ] `versionName` 与准备发布的 tag 一致。
- [ ] `versionCode` 大于上一版，未重复使用。
- [ ] 本次版本的 `CHANGELOG.md` 条目完整，当前顶部的 `[Unreleased]` 已按发版流程处理。
- [ ] Release APK 已由正式密钥签名，并核对证书指纹。
- [ ] arm64 与 armhf 两种产物都经过检查。
- [ ] 上传资产与验证过的本地文件一致，SHA-256 已核验。
- [ ] 增量补丁可从旧包重建出与新版完全一致的 APK。
- [ ] 发布密钥与口令没有进入 Git，且已完成离线备份。
- [ ] 如果签名或安装方式有变化，Release 说明已写明用户需要做的操作。

## 附：历史事故说明

仓库历史记录显示，v0.1.2 及更早的正式发布资产曾与公开的 debug 密钥有关，并且当时的包还存在 debuggable 配置问题。此后项目引入了正式 Release 签名配置与 CI 检查。旧版本的证书指纹和事故细节仅用于追溯；**判断当前 APK 时，应以当前实际下载的资产、`apksigner` 输出和已确认的发布证书为准**。
