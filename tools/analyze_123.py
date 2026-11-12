# -*- coding: utf-8 -*-
"""深度分析 123.epub 的损坏情况：手工解析中央目录，逐条目检查本地头，
尝试"部分解压"抢救数据，评估可恢复性。"""
import struct
import sys
import zlib

PATH = r"D:/Project/OtherWorks/epub/testbooks/123.epub"

data = open(PATH, "rb").read()
n = len(data)
print(f"文件大小: {n:,} bytes")

# ── 找 EOCD ──
eocd = data.rfind(b"PK\x05\x06")
assert eocd > 0, "EOCD not found"
(_disk, _cddisk, _ndisk, cd_count, cd_size, cd_offset, _clen) = struct.unpack(
    "<HHHHIIH", data[eocd + 4:eocd + 22])
# ZIP64?
z64 = data.rfind(b"PK\x06\x06")
if z64 > 0 and z64 > eocd - 100:
    print("存在 ZIP64 EOCD")
    cd_count = struct.unpack("<Q", data[z64 + 24:z64 + 32])[0]
    cd_size = struct.unpack("<Q", data[z64 + 40:z64 + 48])[0]
    cd_offset = struct.unpack("<Q", data[z64 + 48:z64 + 56])[0]
print(f"中央目录: {cd_count} 条, @0x{cd_offset:X}, size={cd_size:,}")

# ── 解析中央目录 ──
entries = []
p = cd_offset
while p < cd_offset + cd_size:
    if data[p:p + 4] != b"PK\x01\x02":
        print(f"[!] CD 签名错误 @0x{p:X}: {data[p:p+4]!r}")
        break
    (sig, ver, vneed, flags, method, mtime, mdate, crc, csize, usize,
     nlen, elen, clen, disk, iattr, eattr, loffset) = struct.unpack("<IHHHHHHIIIHHHHHII", data[p:p + 46])
    name = data[p + 46:p + 46 + nlen]
    entries.append((name, flags, method, crc, csize, usize, loffset))
    p += 46 + nlen + elen + clen
print(f"解析出 {len(entries)} 个条目")

# ── 逐条目体检 ──
ok = bad_inflate = bad_crc = bad_header = zero = 0
recoverable_partial = 0
bad_ranges = []
sample = []
for (name, flags, method, crc, csize, usize, lo) in entries:
    nm = name.decode("utf-8", "replace")
    if data[lo:lo + 4] != b"PK\x03\x04":
        bad_header += 1
        bad_ranges.append((lo, nm, "header"))
        continue
    # 本地头长度
    ln, le = struct.unpack("<HH", data[lo + 26:lo + 30])
    dstart = lo + 30 + ln + le
    raw = data[dstart:dstart + csize]
    if method == 0:
        out = raw
    else:
        d = zlib.decompressobj(-15)
        try:
            out = d.decompress(raw, usize + 1)
            out += d.flush()
        except Exception as e:
            out = d.unconsumed_tail and b"" or (out if "out" in dir() else b"")
            # 尝试拿部分输出
            d2 = zlib.decompressobj(-15)
            chunks = []
            try:
                for i in range(0, len(raw), 65536):
                    chunks.append(d2.decompress(raw[i:i + 65536]))
            except Exception:
                pass
            out = b"".join(chunks)
    if len(out) >= usize * 0.98 or (usize == 0):
        if zlib.crc32(out) & 0xFFFFFFFF == crc:
            ok += 1
        else:
            bad_crc += 1
            if len(out) > 0.5 * usize:
                recoverable_partial += 1
            sample.append((nm, f"CRC坏 但数据近似完整 {len(out)}/{usize}"))
    else:
        bad_inflate += 1
        if len(out) > 0.3 * usize:
            recoverable_partial += 1
        if len(sample) < 8:
            sample.append((nm, f"部分解压 {len(out)}/{usize} ({len(out)*100//max(1,usize)}%)"))
        bad_ranges.append((lo, nm, f"inflate"))

print(f"\n完好: {ok}  头损坏: {bad_header}  解压失败: {bad_inflate}  CRC错误: {bad_crc}")
print(f"可部分抢救(>=30%~50%数据): {recoverable_partial}")

if bad_ranges:
    bad_ranges.sort()
    lo0 = bad_ranges[0][0]
    hi0 = bad_ranges[-1][0]
    print(f"损坏条目本地头偏移范围: 0x{lo0:X} ~ 0x{hi0:X} ({(hi0-lo0)/1024/1024:.1f} MB 跨度)")
    # 检查损坏区段是否连续
    offs = sorted(r[0] for r in bad_ranges)
    gaps = [(offs[i], offs[i+1]) for i in range(len(offs)-1) if offs[i+1]-offs[i] > 3_000_000]
    print(f"损坏偏移的大空隙(>3MB): {[(hex(a), hex(b)) for a, b in gaps[:5]]}")

print("\n样例:")
for nm, msg in sample:
    print("  -", nm, "=>", msg)

# 损坏条目的名字分布
bad_names = sorted(r[1] for r in bad_ranges)
if bad_names:
    print(f"\n损坏文件名范围: {bad_names[0]} ~ {bad_names[-1]}")

# 检查文件尾部（zip 之后有没有多余数据）
print(f"\nEOCD 位置 0x{eocd:X}, 文件末尾 0x{n:X}, EOCD 之后还有 {n - eocd - 22} bytes")
