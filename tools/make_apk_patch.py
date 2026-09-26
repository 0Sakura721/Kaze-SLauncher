#!/usr/bin/env python3
"""生成 Kaze SLauncher 的 APK 增量补丁（CI 侧）。

思路（参考 Operit 的 apkraw 格式，实现独立）：
APK 是个 zip，其中绝大部分条目在两次构建之间**逐字节不变**（原生库、资源…）。
所以补丁只需携带"变化条目的原始本地记录" + "文件尾部（中央目录 + EOCD + APK 签名块）"，
客户端再把它**本地已安装的 APK** 里没变的条目原样拷过来，拼出与官方包**逐字节相同**的新 APK。
好处：不需要 native 差分库（bsdiff/hdiffpatch），输出与官方包一致所以签名可验、哈希可对。

用法：
    python make_apk_patch.py <base.apk> <target.apk> <out_dir> [--version X.Y.Z]

输出：
    <out_dir>/patch_<base8>_<target8>.zip
    <out_dir>/patch_<base8>_<target8>.json      （作为 release 资产发布，客户端据此选路）

格式："kaze-apkraw-1"，与 Kotlin 端 ApkPatchApplier 严格对应，改一处必须改另一处。
"""

import hashlib
import io
import json
import os
import struct
import sys
import zipfile

FORMAT = "kaze-apkraw-1"


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def local_record_len(f, local_offset, csize):
    """本地记录总长 = 30 + 文件名长 + extra 长 + 压缩数据长。

    **必须读本地头**，不能拿中央目录的长度算：两者可以不同（不少打包器会差），
    照中央目录算会少拷/多拷字节，客户端拼出来的包哈希对不上。
    """
    f.seek(local_offset + 6)
    fl = f.read(2)
    flags = fl[0] | (fl[1] << 8)
    f.seek(local_offset + 26)
    b = f.read(4)
    nlen = b[0] | (b[1] << 8)
    elen = b[2] | (b[3] << 8)
    rec = 30 + nlen + elen + csize
    if flags & 0x08:
        # 数据后面还跟着 data descriptor（12 或 16 字节，带可选签名）
        f.seek(local_offset + rec)
        d = f.read(4)
        sig = d[0] | (d[1] << 8) | (d[2] << 16) | (d[3] << 24)
        rec += 16 if sig == 0x08074B50 else 12
    return rec


def read_central_directory(path):
    """返回 {name: (local_offset, compressed_size, record_len)}"""
    out = {}
    with open(path, "rb") as f:
        with zipfile.ZipFile(path) as z:
            for i in z.infolist():
                rec = local_record_len(f, i.header_offset, i.compress_size)
                out[i.filename] = (i.header_offset, i.compress_size, rec)
    return out


def local_record(path, offset, record_len):
    with open(path, "rb") as f:
        f.seek(offset)
        return f.read(record_len)


def tail_start(path, cd):
    """尾部起点 = 最后一个本地记录结束处（其后是中央目录 + EOCD + 签名块）"""
    return max(off + rec for (off, _csize, rec) in cd.values())


def build(base_apk, target_apk, out_dir, to_version="", base_version=""):
    os.makedirs(out_dir, exist_ok=True)
    base_cd = read_central_directory(base_apk)
    target_cd = read_central_directory(target_apk)

    base_sha = sha256_of(base_apk)
    target_sha = sha256_of(target_apk)

    # 按**本地记录在文件里的先后**输出（必须与目标包一致，否则拼出来不是逐字节相同）
    order = sorted(target_cd.items(), key=lambda kv: kv[1][0])

    tail_off = tail_start(target_apk, target_cd)
    with open(target_apk, "rb") as f:
        f.seek(tail_off)
        tail = f.read()

    base_sha_of_records = {}
    for name, (off, _csize, rec) in base_cd.items():
        base_sha_of_records[name] = hashlib.sha256(local_record(base_apk, off, rec)).hexdigest()

    entries = []
    adds = []          # (record_path_in_patch, bytes)
    copied = changed = 0

    for idx, (name, (off, _csize, rec)) in enumerate(order):
        raw = local_record(target_apk, off, rec)
        same = (name in base_sha_of_records
                and base_sha_of_records[name] == hashlib.sha256(raw).hexdigest())
        if same:
            entries.append({"name": name, "mode": "copy"})
            copied += 1
        else:
            rp = "r/%04d.bin" % idx
            adds.append((rp, raw))
            entries.append({"name": name, "mode": "add", "record": rp})
            changed += 1

    base8, target8 = base_sha[:8], target_sha[:8]
    stem = "patch_%s_%s" % (base8, target8)
    zip_path = os.path.join(out_dir, stem + ".zip")
    json_path = os.path.join(out_dir, stem + ".json")

    meta = {
        "format": FORMAT,
        "baseSha256": base_sha,
        "targetSha256": target_sha,
        "baseVersion": base_version,
        "toVersion": to_version,
        "tail": "tail.bin",
        "entries": entries,
    }

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr("meta.json", json.dumps(meta, ensure_ascii=False, separators=(",", ":")))
        z.writestr("tail.bin", tail)
        for rp, raw in adds:
            # 原始本地记录**不再压缩**（它本身已是 deflate 数据，再压没收益还费时）
            z.writestr(zipfile.ZipInfo(rp, date_time=(1980, 1, 1, 0, 0, 0)), raw, zipfile.ZIP_STORED)

    # release 资产用的元数据（客户端先取它判断"我这份包能不能打补丁"）
    asset_meta = dict(meta)
    asset_meta["patchFile"] = os.path.basename(zip_path)
    asset_meta["patchSha256"] = sha256_of(zip_path)
    asset_meta["patchSize"] = os.path.getsize(zip_path)
    asset_meta["fullSize"] = os.path.getsize(target_apk)
    with io.open(json_path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(asset_meta, f, ensure_ascii=False, indent=2)

    patch_size = os.path.getsize(zip_path)
    full_size = os.path.getsize(target_apk)
    print("base   %s  (%.2f MB)" % (base_sha[:16], os.path.getsize(base_apk) / 1048576))
    print("target %s  (%.2f MB)" % (target_sha[:16], full_size / 1048576))
    print("entries: copy %d / add %d (total %d)" % (copied, changed, len(entries)))
    print("patch  %s  %.2f MB  = %.1f%% of full" % (os.path.basename(zip_path),
                                                   patch_size / 1048576,
                                                   patch_size / full_size * 100))
    print("meta   %s" % os.path.basename(json_path))
    return zip_path, json_path


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict()
    for a in sys.argv[1:]:
        if a.startswith("--") and "=" in a:
            k, v = a[2:].split("=", 1)
            opts[k] = v
    if len(args) < 3:
        print(__doc__)
        return 2
    build(args[0], args[1], args[2],
          to_version=opts.get("version", ""),
          base_version=opts.get("base-version", ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
