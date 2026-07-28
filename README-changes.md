# Kaze-SLauncher 改进补丁集

此目录包含对 Kaze-SLauncher (0Sakura721/Kaze-SLauncher) 的全面改进，已在 D:\reasonix\Kaze-SLauncher 中应用。

## 改进清单

### 🔴 安全加固
- .gitignore — 移除 keystore 例外规则，不再提交 `release-keystore.jks`
- build.yml — lint 严格化（移除 `continue-on-error`）；Release 失败不 fallback 到 Debug
- build.gradle.kts — 签名配置只接受 local.properties 或 Secrets

### 🔴 数据安全 + 🟡 架构重构
- PreferencesManager.kt — Kotlinx Serialization 替换手工 JSON；EncryptedSharedPreferences 加密 CurseForge API Key

### 🟡 架构改进
- ServerManager.kt — 单例模式简化（Holder 模式）；提取回调消除代码重复
- ProotServerManager.kt — 停止超时可配置（stopGraceSeconds/stopFallbackTimeoutMs）；Console 缓冲区扩容 3 倍

### 🟡 UI 改进
- MainActivity.kt — 硬编码中文→strings.xml；import 扁平化
- strings.xml — 新增 30+ 条字符串资源

### 🟢 工程化
- ISSUE_TEMPLATE — Bug 报告 + 功能建议模板
- 单元测试 — 序列化测试 5 用例 + 状态机测试 10 用例
- local.properties.example — 新增

## 部署
1. `git rm app/release-keystore.jks` 从仓库移除 keystore
2. 配置 GitHub Secrets: RELEASE_KEYSTORE_BASE64 / PASSWORD / ALIAS / PASSWORD
3. 本地复制 `local.properties.example` → `local.properties`
4. 运行 `./gradlew test` 验证
