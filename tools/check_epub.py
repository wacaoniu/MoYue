# -*- coding: utf-8 -*-
"""EPUB 体检脚本：检查压缩包结构、逐条目解压测试、定位损坏条目。
用法: python tools/check_epub.py 某本书.epub
"""
import sys
import zipfile


def main(path):
    print("文件:", path)
    try:
        z = zipfile.ZipFile(path)
    except Exception as e:
        print("[X] 无法作为 ZIP 打开:", e)
        return

    names = z.namelist()
    print(f"条目总数: {len(names)}")

    opf = [n for n in names if n.lower().endswith('.opf')]
    print("OPF 文件:", opf if opf else "[!] 没有找到 .opf")
    container = [n for n in names if n.lower() == 'meta-inf/container.xml']
    print("container.xml:", container if container else "[!] 缺少 META-INF/container.xml")

    bad = []
    for n in names:
        try:
            z.read(n)
        except Exception as e:
            bad.append((n, str(e)))
    if bad:
        print(f"\n[X] 有 {len(bad)} 个损坏条目（其它阅读器可能跳过它们）:")
        for n, e in bad[:20]:
            print("   -", n, "->", e)
    else:
        print("\n[OK] 所有条目解压测试通过，数据无损坏")

    imgs = [n for n in names if n.lower().endswith(('.jpg', '.jpeg', '.png', '.webp', '.gif'))]
    print(f"图片条目: {len(imgs)}")


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1])
