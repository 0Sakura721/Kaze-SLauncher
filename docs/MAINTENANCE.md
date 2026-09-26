# 维护笔记

给"以后接手的人"（包括未来的自己）看的踩坑记录。**面向用户的变更**在
[CHANGELOG](CHANGELOG.md)；这里只记那些"只有真跑一次才会暴露、而且往往以'静默成功'
的样子出现"的东西。

---

## 一、最危险的一类失败：静默跳过

2026-09-26 做增量补丁 + 自动发版时，**同一种问题一天撞了三次**，值得单独列在最前面：

| 现象 | 真相 |
|---|---|
| 单测全绿 | 两条"吃真实产物"的用例**一直在跳过**：Gradle 的 `-D` 只作用于守护进程，没进测试 JVM |
| release workflow 全绿 | 补丁其实**一份都没生成**：取基线资产的命令输出为空，而空结果被当成"正常跳过" |
| `gh release view --json assets --jq …` 无输出 | gh 的报错走 **stderr**，在管道里只看到空 stdout，于是"没找到"看起来像"没有" |

**结论 / 规矩：**

1. **"跳过"必须显式告警。** workflow 里任何"条件不满足就跳过"的分支都要
   `echo "::warning::…"`，不能只是 `return 0`。绿不等于对。
2. **吃真实产物的测试，要能证明它真的跑了。** 目前的做法：把结果**写到文件**
   （`out.apk` / `release-body.plain.txt`），人为核对过存在；而且它会在读不到输入时
   `println("跳过：…")`。以后再加这类测试，请顺手确认一次"它确实执行了"。
3. **优先选能在本地预先验证的写法。** `curl + python3` 读 GitHub API 我在本地对着真实
   release 验过；`gh … --jq` 是"看起来更简洁"所以没验，结果就是它挂的。

---

## 二、GitHub Actions / 发版流水线

### ⚠️ 最严重的一次：CI 打出了**未签名**的 release APK（v0.3.2）

**症状**：用户从 0.3.0 更新到 0.3.2，安装时提示**「缺少开发者证书」**。

**真相**：`app/build.gradle.kts` 里是

```kotlin
signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
```

这个设计本身有正当理由（本地没有密钥时也能构建 debug），但在 CI 上它意味着：
**没有密钥 → `signingConfig = null` → AGP 照常打出一个未签名的 release APK**，
构建全程没有一句警告，一路绿到发布。

**影响范围**（都验过签名）：`v0.3.0` ✅ 已签名、`v0.3.1-fix` ✅ 已签名
（这两个都是本地构建的）、**`v0.3.2` ❌ 未签名**（第一个由 CI 构建的版本）。

**修复**：

1. `release.yml` 里加了「还原签名密钥」步骤（从 secrets 还原 `kaze-release.jks` 并导出
   `KAZE_*` 环境变量），以及一道**决定性的闸**——「校验 APK 已签名」，
   未签名**直接失败**，绝不把包发出去。
2. 需要配置的仓库 secrets（Settings → Secrets and variables → Actions）：

   | Secret | 内容 | 怎么生成 |
   |---|---|---|
   | `KAZE_KEYSTORE_B64` | release 密钥库的 base64 | `base64 -w0 kaze-release.jks` |
   | `KAZE_STORE_PASS` | 密钥库口令 | 与本地 `local.properties` 的 `kaze.storePassword` 相同 |
   | `KAZE_KEY_ALIAS` | 别名 | 本地是 `kaze` |
   | `KAZE_KEY_PASS` | 别名口令 | 与 `kaze.keyPassword` 相同 |

3. 事故包已替换：v0.3.2 的 APK 换成**本地用发布密钥签名**的版本，
   补丁也按新包重新生成（补丁的 `targetSha256` 必须对上**实际发布的那份**）。

**教训（比 bug 本身重要）**：

- **"构建成功"不等于"产物可用"。** 发布流程里必须有一步**验证产物本身**
  （签名 / sha256 / 能否解析），而不是只看 Gradle 退出码。
- **我当时的验证方式有漏洞**：我查了 `aapt2 dump badging` 的 `debuggable: 否`，
  但**没查签名**；而那个"决定性校验"是拿补丁拼包对 sha256 —— 它对签名**完全不敏感**
  （签名块在"尾部"里，拼得对不对与签没签无关），所以也漏过去了。
  现在验签是发版流程的强制步骤。
- **下载回来的文件要核对大小**：中途有一次只下了 29.50 MB（完整 30.80 MB），
  拿截断的文件验签会得出"未签名"的**假结论**。现在下载都对着 release API 的
  `size` 字段核对。

### 其它真跑时修掉的问题

`release.yml` 在 4 次真跑里修掉的问题（run #1 → #2 → #4 → #5 全绿）：

1. **不要写死 AGP 的产物路径。**
   `cp app/build/outputs/apk/arm64/release/app-arm64-release.apk` 在 runner 上
   `No such file or directory`，而构建任务明明执行了。改用
   `find app/build/outputs/apk -path '*arm64*' -path '*release*' -name '*.apk'`
   定位，**并先把实际产物路径打进日志**（下次出问题直接有线索）。

2. **资产名匹配别假设后缀。**
   `jq` 里写 `test("armeabi-v7a\\.apk$")` 有两个坑：
   - v7a 的资产名是 `…-armeabi-v7a-experimental.apk`，**后缀根本不是 `.apk`**；
   - `$` 在 bash 双引号里会被特殊展开（`$"` 是本地化翻译语法）。

   现在用 `grep -F -- "$ABI"` 匹配 ABI 关键字 —— `arm64-v8a` 与 `armeabi-v7a`
   两个 token 互不包含，最稳。

3. **shell glob 不匹配不会报错，会把字面量传下去。**
   `gh release upload "$TAG" out/*.zip` 在 `out/` 里没有 zip 时，传的是字符串
   `out/*.zip`，然后 gh 报 `no such file`。改成先 `ls … 2>/dev/null || true`
   收集真实存在的文件，再判空。

4. **release 创建成功说明 `contents: write` 是生效的**（`permissions:` 块能拿到写权限，
   即便仓库默认是只读）。

---

## 三、测试基建

1. **Gradle 的 `-D` 不会进测试 JVM。** 需要透传：

   ```kotlin
   testOptions {
       unitTests.all {
           listOf("kaze.patch.dir", "kaze.notes.file").forEach { key ->
               it.systemProperty(key, System.getProperty(key) ?: "")
           }
       }
   }
   ```

2. **`org.json` 在纯 JVM 单测里是 stub**（android.jar 的假实现，链式 `put` 返回
   null → NPE）。用它的测试要加 `@RunWith(RobolectricTestRunner::class)`。

3. **Robolectric + `ComposeTestRule.waitUntil` 不要用来等后台协程。**
   它依赖 Compose 的 idle 判定与虚拟时钟；CI 上因此稳定超时（本地却通过）。
   等 `Dispatchers.IO` 上的东西要基于**真实时间**轮询。详见
   `ConsoleFollowTest` 里的注释。

4. **测试方法名里不能有 `.`**（Kotlin 反引号名字 → JVM 方法名限制），
   比如 `需要 -Dkaze.patch.dir` 这种名字会编译失败。

---

## 四、APK 增量补丁（`kaze-apkraw-1`）

格式与两端约定见 `tools/make_apk_patch.py` 与 `core/update/ApkPatchApplier.kt`
的注释（**改一处必须改另一处**）。实现时被测试抓出来的坑：

1. **zip 的字段是小端**，`RandomAccessFile.readInt()` 是大端 ——
   用它比对中央目录签名会永远不相等，目录被解析成**空表**。
2. **本地记录长度要按「本地头」算**，不能拿中央目录的 extra 长度：
   两者可以不同（`java.util.zip` 生成的包就会差），照中央目录算会少拷/多拷字节。
3. **本地头 flag 的 bit 3** 表示大小/CRC 写在数据后面的 data descriptor 里，
   记录末尾还有 12 或 16 字节（带可选签名 `0x08074b50`）。AGP 打的 APK 不带，
   但别的打包器会带。

验证方式（别省）：拿**已发布的补丁** + **已发布的旧包**拼一遍，
断言与**已发布的新包** sha256 相同 —— 这才是"真的能用"。

---

## 五、本机（Windows / PowerShell）操作

0. **别直连 github.com 下载 release 资产 —— 用加速镜像。** 同一台机器、同一个 30 MB 的包：

   | 来源 | 实测速度 |
   |---|---|
   | 直连 github.com | **~40 KB/s**（一个包十几分钟） |
   | `https://github.boki.moe/` | **5~6 MB/s**（快约 100 倍，6 个文件 40 秒下完） |
   | moeyy / gitmirror / gh.llkk.cc / mirror.ghproxy | 当时全部失败或超时 |

   应用自身的更新器本来就带这套镜像（`UpdateChecker.MIRRORS`），本地脚本却一直直连 ——
   白等了很久，还因此中途放弃过几次验证。`D:\dsh\tools\get.ps1` 是镜像优先、直连兜底、
   并按 `Content-Length` 校验大小的下载脚本（截断的文件不算成功）。

   ⚠️ 连带一个坑：**写 .ps1 时不要放中文**。PowerShell 会把无 BOM 的 UTF-8 当 ANSI 读，
   中文变乱码 → 报"缺少 }"之类的语法错误，而真正原因是编码。本地工具脚本一律 ASCII。

1. **`String.Replace(old, new, 1)` 在 PowerShell 里不存在**（那是 .NET Core 3+ 的
   三参重载，PowerShell 上直接报"找不到重载"）→ 用 `edit` 工具或 Python 改文件。
   因为这个，我有三次"CHANGELOG 明明改了却没写进去"。
   ⚠️ 更坑的是：写在外层 `if` 里的 `.Replace` 抛异常后，脚本**继续往下走**，
   后面照样打印"✅ 已加" —— 得到一次**假成功**。改完必须回读确认。
2. **`git commit -m` 的消息里有 `\$` 会被 git 当路径解释** →
   `fatal: '\$' is outside repository`，而且可能只提交了一半。
   消息一律写进文件再 `git commit -F`。
3. **`[System.IO.File]::WriteAllLines` 会把文件改成 CRLF**，而本仓库约定是 **LF**
   —— 会造成整文件级别的伪 diff（SettingsScreen.kt 曾被这样刷出 3000 行"改动"）。
   改文件一律用 `edit`/`write` 工具，或用 `WriteAllText(..., UTF8Encoding(false))`
   并保持 `\n`。
4. **中文参数经 `adb shell` 传过去会被控制台编码搞坏**（grep 中文永远不匹配）。
   排查真机上的中文文本时用 ASCII 模式匹配，或把文件拉回来再搜。
5. **验签前记得设 `JAVA_HOME`**：`apksigner.bat` 没它跑不起来，而失败看起来就像
   "这个包没签名" —— 我因此对**四个明明已签名的包**得出过 `NOT SIGNED` 的假结论。
   凡是"断言某东西不存在"的检查，先确认检查本身真的跑起来了。


---

## 六、发版检查清单

打 tag 前：

- [ ] `app/build.gradle.kts` 的 `versionName` 与要打的 tag 一致
      （workflow 会校验，不一致直接失败）
- [ ] `CHANGELOG.md` 有 `## [<version>]` 段落，且 `[Unreleased]` 已清空
      （workflow 用它生成发布说明，缺段落会写占位提示而不是留空）
- [ ] 本次是否真的需要动 `versionCode`（每个 release 递增，别复用）

打 tag 后：

- [ ] Release workflow 是否**全绿**（不是"大部分绿"）
- [ ] 资产数量对不对：**2 个 APK + 每个 ABI 一份补丁 `.zip`/`.json`**
- [ ] 补丁大小是否远小于整包（当前约 5%，如果接近整包说明对比基线选错了）
- [ ] 抽一次真实校验：下载「补丁 + 上一版 APK」→ 拼装 → sha256 是否等于新版 APK
