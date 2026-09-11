# 发布签名与密钥轮换手册

> 本文档对应代码审查中的 **P0-1**：已发布的 APK 使用了仓库内公开的 `debug.keystore` 签名。

---

## 一、问题是什么（含实测证据）

Android 用**签名证书**作为应用身份。系统安装器只认签名：同包名、同签名、`versionCode` 更高的 APK 会被当作**合法升级**覆盖安装，不会给用户任何不同于普通更新的提示。

本项目当前的状态是：

1. `debug.keystore` 提交在仓库里，口令明文写在 `app/build.gradle.kts`（`android` / `android`）。
2. `release` 构建类型**没有配置任何 `signingConfig`**（其产物是 unsigned，根本无法安装）。
3. 因此发布用的 APK 是**用那个公开密钥签名的**。实测确认：

```
从仓库 debug.keystore 导出证书（口令 android）：
    Subject : CN=Android Debug, O=Android, C=US
    SHA256  : d8317c00531ae073bfd92b83ef53327bff56200225eb577796c2ce2940ca4bb0

在官方发布的 Kaze-SLauncher-v0.1.2-arm64-v8a.apk 字节流中检索该证书 DER：
    命中，byte offset 51016617        → 发布包确实由这个公开密钥签名

同时该 APK 的 AndroidManifest 中不含 debuggable 属性
    → 它是 release 构建，只是签错了密钥（不是把 debug 包改名发出）
```

**后果**：任何人都可以克隆仓库，构建一个同包名、同签名、`versionCode` 更高的 APK，发布出去并被用户设备当作正式更新安装。结合应用持有的 `MANAGE_EXTERNAL_STORAGE`（全盘读写）、`REQUEST_INSTALL_PACKAGES`、`INTERNET` 与前台服务权限，等同于完全接管用户设备。

而且应用的自更新链路（下载更新包 → 交系统安装器）只校验「文件 > 1MB 且以 `PK\x03\x04` 开头」，更新包还会经过多个第三方加速镜像下载 —— 这条组合把上述风险从"理论"变成了"可操作"。

> 目前下载量仅十余次，**现在轮换的代价最低**。拖得越久，需要卸载重装的用户越多。

---

## 二、修复步骤

### 步骤 1：生成正式发布密钥

```bash
keytool -genkeypair -v \
  -keystore kaze-release.jks \
  -alias kaze \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storetype PKCS12
```

- `validity 10950` ≈ 30 年。Google Play 要求密钥有效期至少到 2033 年；自发布也建议越长越好。
- **务必设置强口令**，不要再用 `android`。
- 生成后立刻把 `.jks` **和口令一起离线备份两处**（密码管理器 + 离线介质）。
  **密钥丢失 = 该应用永远无法再发布更新**，只能换包名重新开始。

### 步骤 2：确保密钥绝不入库

`.gitignore` 里有一行需要删掉的"后门"：

```gitignore
*.jks
*.keystore
!debug.keystore
!release-keystore.jks      # ← 删除这一行：它会把误放进来的发布密钥放行入库
```

建议改为：

```gitignore
*.jks
*.keystore
!debug.keystore
# 发布密钥一律不入库；不要为任何 release keystore 开白名单
```

> 如果历史提交里曾经出现过发布密钥，必须视为已泄露并重新生成（Git 历史无法真正删除）。

### 步骤 3：让 Gradle 从环境变量读取口令

已在本仓库的 `app/build.gradle.kts` 中配置完毕（`signingConfigs.create("release")`），
它按以下顺序取值，缺任何一项就跳过签名（只出未签名包，不会静默用 debug 密钥签）：

| 变量 | 含义 |
|---|---|
| `KAZE_KEYSTORE` | keystore 文件路径（绝对路径或相对仓库根目录） |
| `KAZE_STORE_PASS` | keystore 口令 |
| `KAZE_KEY_ALIAS` | 密钥别名 |
| `KAZE_KEY_PASS` | 密钥口令 |

本地构建时在 `local.properties`（**已被 gitignore**）里写：

```properties
kaze.keystore=/absolute/path/to/kaze-release.jks
kaze.storePassword=***
kaze.keyAlias=kaze
kaze.keyPassword=***
```

CI 里则用仓库 Secret 注入同名环境变量。

### 步骤 4：出包

```bash
./gradlew assembleRelease      # 或 assembleArm64Release / assembleUniversalRelease
```

产物：`app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`

### 步骤 5：发版前验证签名（关键，别跳过）

```bash
# Android SDK 自带
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/arm64/release/app-arm64-release.apk
```

确认输出里的证书 SHA-256 **不是** `d8317c00531ae073bfd92b83ef53327bff56200225eb577796c2ce2940ca4bb0`
（那是 Android 调试证书），也不是任何 `CN=Android Debug` 的证书。

建议直接在 CI 里加一条断言，签名不符就 fail —— 这样"发布包被 debug 密钥签名"这类事故不可能再悄悄发生。

### 步骤 6：发布与用户告知

- `versionCode` 必须**严格递增**（轮换密钥的同时升版本号）。
- **签名变更后无法覆盖安装**：老用户必须先卸载再安装新包（数据会丢，请提前在 Release 说明里写清楚，
  并提醒用户先导出实例备份 —— 备份 zip 不受卸载影响，因为它们默认在外部存储）。
- Release 说明中建议明确写出：*本版本起改用正式发布密钥签名，安装前请先卸载旧版*。

### 步骤 7：以后

- 日常开发继续用 `debug.keystore`（它在仓库里是正常的、也是 Android 的惯例）。
- **正式包永远不碰 debug 密钥**。
- `build.gradle.kts` 的 `release` 若取不到密钥，应构建出**未签名**产物而不是回退到 debug 签名 —— 让错误暴露在发版前。

---

## 三、配套的更新链路加固（同一批修复）

仅换密钥不够 —— 应用端的自更新校验也必须补上，否则镜像投毒仍可造成拒绝服务或诱导安装：

1. **下载后校验 SHA-256**：GitHub Releases API 已为每个 asset 返回 `digest`（形如
   `sha256:d3457a1f...`），实测该仓库响应确实包含，可直接比对。
   参见 `UpdateChecker` / `UpdateInstaller`。
2. **安装前校验三件事**：包名一致、`versionCode` 单调递增、签名与当前安装一致
   （`PackageManager.getPackageArchiveInfo` + `GET_SIGNING_CERTIFICATES`）。
   这样即使前一步被绕过，系统安装器之前还有一道应用层防线。
3. **镜像降级**：第三方加速镜像仅作为兜底，且必须通过哈希校验才允许使用。

---

## 四、检查清单（发版前逐项确认）

- [ ] Release APK 由正式的 release keystore 签名（`apksigner verify` 证书指纹已确认）
- [ ] `versionCode` 比上一版大
- [ ] keystore 未出现在 `git status` / 提交历史中
- [ ] 口令未硬编码在任何入库文件里
- [ ] 更新包的 SHA-256 校验生效（可以故意改错一个字节，确认下载会被拒绝并换源）
- [ ] keystore 与口令已离线备份
- [ ] Release 说明中包含"签名变更需先卸载"的提示（仅本次轮换需要）
