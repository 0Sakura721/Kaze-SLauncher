#!/usr/bin/env python3
"""发版前的本地护栏：强制「先写 CHANGELOG，再打 tag」。

## 为什么需要它
发版流程是「打 tag → GitHub Actions 自动构建」，而 release 正文**取自 CHANGELOG**。
只改 build.gradle.kts、不写 CHANGELOG 就打 tag，CI 会照跑、release 也会发出去，
但页面正文是空的 —— 用户在 Releases 里看不到任何说明，而这件事**悄无声息地过去了**。

v0.9.1 就是这么漏的：只改了 versionCode/versionName，CHANGELOG 没写。

## 用法（打 tag 之前跑一次）
    python tools/pre-release-check.py
    python tools/pre-release-check.py --tag v0.9.2   # 指定版本

它检查：
  1. CHANGELOG.md 存在；
  2. 有该版本的段落（`## [X.Y.Z] - YYYY-MM-DD`）；
  3. 段落**不是空壳**（只有标题没有正文）；
  4. build.gradle.kts 的 versionName 与该版本一致（防止漏改版本号）。

任何一项不过就退出码非 0，不允许打 tag。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHANGELOG = os.path.join(ROOT, "CHANGELOG.md")
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")


def fail(msg: str) -> int:
    print("  ✗ " + msg)
    return 1


def check(version: str | None) -> int:
    if not os.path.isfile(CHANGELOG):
        return fail("没有 CHANGELOG.md")
    text = io.open(CHANGELOG, encoding="utf-8", newline="").read()

    # 从 build.gradle.kts 推断当前版本
    gradle_ver = None
    if os.path.isfile(GRADLE):
        g = io.open(GRADLE, encoding="utf-8", newline="").read()
        m = re.search(r'versionName\s*=\s*"([^"]+)"', g)
        if m:
            gradle_ver = m.group(1)
        m2 = re.search(r"(?m)^\s*versionCode\s*=\s*(\d+)", g)
        gradle_code = m2.group(1) if m2 else "?"
    else:
        gradle_code = "?"

    if version is None:
        version = gradle_ver
    if version is None:
        return fail("没指定 --tag，且 build.gradle.kts 里也读不到 versionName")

    print("  版本 %s（build.gradle.kts: versionCode=%s）" % (version, gradle_code))

    # ① 有该版本段
    pat = re.compile(r"^## \[" + re.escape(version) + r"\].*?(?=^## \[|\Z)", re.S | re.M)
    m = pat.search(text)
    if not m:
        return fail("CHANGELOG.md 里没有 ## [%s] 段落 —— 先补上再打 tag" % version)

    # ② 段落不是空壳
    body = [l for l in m.group(0).split("\n")[1:] if l.strip()]
    if len(body) < 2:
        return fail("CHANGELOG 的 [%s] 段落只有 %d 行内容，疑似空壳" % (version, len(body)))

    # ③ versionName 与 tag 一致
    if gradle_ver and version != gradle_ver:
        return fail("build.gradle.kts 的 versionName(%s) 与 tag(%s) 不一致" % (gradle_ver, version))

    print("  ✓ CHANGELOG 的 [%s] 段落有 %d 行内容，versionName 一致" % (version, len(body)))
    return 0


def main() -> int:
    version = None
    argv = sys.argv[1:]
    if "--tag" in argv:
        i = argv.index("--tag")
        if i + 1 >= len(argv):
            print("  --tag 后面要跟版本号，如 --tag v0.9.2")
            return 2
        version = argv[i + 1]
    elif "--help" in argv or "-h" in argv:
        print(__doc__)
        return 0
    elif argv:
        version = argv[0]
    # 去掉前缀 v
    if version:
        version = version.lstrip("vV")
    return check(version)


if __name__ == "__main__":
    sys.exit(main())
