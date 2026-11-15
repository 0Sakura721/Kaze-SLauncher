# -*- coding: utf-8 -*-
"""extract_release_notes.py：找不到版本段落时改为**失败**，不是写占位说明发版。

原设计「写占位说明而不是空文件」避免了空正文，但**不阻止发版** —— v0.9.1 就这么漏了。
改为：找不到段落就退出码非 0，让 release.yml 的「生成发布说明」这一步直接失败，
CI 会中止，不发布一个没有说明的 release。

保留占位说明的写入（失败时也写出文件），便于人工排查。
"""
import io
import os
import re
import sys

CHANGELOG = "CHANGELOG.md"


def extract(version: str):
    """返回 (正文, 是否找到)。"""
    if not os.path.isfile(CHANGELOG):
        return "_（仓库里没有 CHANGELOG.md）_", False
    text = io.open(CHANGELOG, encoding="utf-8", newline="").read()
    pattern = re.compile(
        r"^## \[" + re.escape(version) + r"\].*?(?=^## \[|\Z)",
        re.S | re.M,
    )
    m = pattern.search(text)
    if not m:
        return "_（CHANGELOG 中没有 [%s] 段落，发布前请补上）_" % version, False
    return m.group(0).strip(), True


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    version, out = sys.argv[1], sys.argv[2]
    body, found = extract(version)
    io.open(out, "w", encoding="utf-8", newline="\n").write(body + "\n")
    if not found:
        # 写出占位说明便于排查，但**不返回 0** —— 让 release 的这一步失败
        print("  ✗ CHANGELOG 中没有 [%s] 段落，不发布没有说明的 release" % version, file=sys.stderr)
        print("  ✗ 先在 CHANGELOG.md 补上 ## [%s] - YYYY-MM-DD 段落再打 tag" % version, file=sys.stderr)
        return 1
    print("版本 %s：抽出 %d 字符" % (version, len(body)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
