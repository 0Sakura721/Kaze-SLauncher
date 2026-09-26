#!/usr/bin/env python3
"""从 CHANGELOG 里抽出某个版本的段落，作为 GitHub Release 的正文。

用法: python extract_release_notes.py <version-without-v> <out-file>

找不到该版本时写一个占位说明（而不是空文件）—— 空正文的 release 页面很难看，
而且会让"CHANGELOG 忘了写"这件事悄无声息地过去。
"""
import io
import os
import re
import sys

CHANGELOG = "CHANGELOG.md"


def extract(version: str) -> str:
    if not os.path.isfile(CHANGELOG):
        return "_（仓库里没有 CHANGELOG.md）_"
    text = io.open(CHANGELOG, encoding="utf-8", newline="").read()
    pattern = re.compile(
        r"^## \[" + re.escape(version) + r"\].*?(?=^## \[|\Z)",
        re.S | re.M,
    )
    m = pattern.search(text)
    if not m:
        return "_（CHANGELOG 中没有 [%s] 段落，发布前请补上）_" % version
    return m.group(0).strip()


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    version, out = sys.argv[1], sys.argv[2]
    body = extract(version)
    io.open(out, "w", encoding="utf-8", newline="\n").write(body + "\n")
    print("版本 %s：抽出 %d 字符" % (version, len(body)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
