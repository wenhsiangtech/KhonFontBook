"""Bounded metadata inspection, using FontTools; no conversion or subsetting."""
import json
import os
import struct
import sys
import math


def reject(code, message):
    print(json.dumps({"error": code, "message": message}, ensure_ascii=True))
    raise SystemExit(2)


try:
    import resource
    resource.setrlimit(resource.RLIMIT_AS, (256 * 1024 * 1024,) * 2)
    resource.setrlimit(resource.RLIMIT_CPU, (12, 12))
except ImportError:
    reject("UNSUPPORTED_RUNTIME", "解析环境需要 Linux 资源限制支持")

try:
    import fontTools
    import brotli
    from fontTools.ttLib import TTFont
    if fontTools.__version__ != "4.67.0" or brotli.__version__ != "1.2.0":
        reject("RUNTIME_VERSION", "需要 FontTools 4.67.0 和 Brotli 1.2.0")
    if len(sys.argv) == 1:
        print(json.dumps({"python": sys.version.split()[0], "fontTools": fontTools.__version__,
                          "brotli": brotli.__version__}))
        raise SystemExit(0)
    path = sys.argv[1]
    size = os.path.getsize(path)
    if size > 20 * 1024 * 1024:
        reject("FILE_TOO_LARGE", "文件不得超过 20 MiB")
    with open(path, "rb") as stream:
        header = stream.read(48)
    if header[:4] == b"wOF2":
        if len(header) != 48:
            reject("INVALID_FONT", "WOFF2 文件头不完整")
        flavor, length, tables, reserved, expanded = struct.unpack(">IIHHI", header[4:20])
        if flavor == 0x74746366:
            reject("UNSUPPORTED_FONT", "不支持字体集合")
        if length != size or reserved or not 1 <= tables <= 256:
            reject("INVALID_FONT", "WOFF2 文件头或长度不合法")
        if expanded > 64 * 1024 * 1024:
            reject("EXPANDED_TOO_LARGE", "解压字体不得超过 64 MiB")
    elif header[:4] == b"\x00\x01\x00\x00":
        if len(header) < 12:
            reject("INVALID_FONT", "TTF 文件头不完整")
        tables = struct.unpack(">H", header[4:6])[0]
        if not 1 <= tables <= 256 or 12 + tables * 16 > size:
            reject("INVALID_FONT", "TTF 表目录不合法")
        with open(path, "rb") as stream:
            stream.seek(12)
            for _ in range(tables):
                tag, checksum, offset, length = struct.unpack(">4sIII", stream.read(16))
                if offset < 12 + tables * 16 or offset + length > size:
                    reject("INVALID_FONT", "TTF 表超出文件边界")
    else:
        reject("INVALID_FONT", "仅支持单字体 WOFF2 或 TrueType TTF 文件")
    with TTFont(path, lazy=False, checkChecksums=2) as font:
        if font.flavor not in (None, "woff2") or (font.flavor is None and font.sfntVersion != "\x00\x01\x00\x00"):
            reject("INVALID_FONT", "仅支持 WOFF2 或 TrueType TTF")
        if any(t in font for t in ("fvar", "gvar", "CFF2")):
            reject("UNSUPPORTED_FONT", "首版不支持可变字体，请选择静态字形")
        if any(t in font for t in ("COLR", "CPAL", "CBDT", "CBLC", "sbix", "SVG ")):
            reject("UNSUPPORTED_FONT", "首版不支持颜色字体")
        required = ("head", "hhea", "maxp", "hmtx", "OS/2", "name", "cmap", "post")
        if any(t not in font for t in required):
            reject("INVALID_FONT", "字体缺少必要的 OpenType 表")
        if "CFF " not in font and not ("glyf" in font and "loca" in font):
            reject("INVALID_FONT", "字体缺少字形轮廓")
        # Decode metadata tables rather than trusting names/header alone. Iterating
        # every table also reconstructs WOFF2 glyf/loca outlines, which is costly
        # for large CJK fonts and unnecessary for metadata inspection. This is
        # not a full outline/layout sanitizer.
        for tag in required:
            font[tag]
        weight = font["OS/2"].usWeightClass
        italic = bool(font["OS/2"].fsSelection & 1)
        oblique = font["OS/2"].version >= 4 and bool(font["OS/2"].fsSelection & (1 << 9))
        angle = -float(font["post"].italicAngle)
        if not 1 <= weight <= 1000 or not math.isfinite(angle) or not -90 < angle < 90:
            reject("UNSUPPORTED_FONT", "字重或倾斜角度不受支持")
        if italic != bool(font["head"].macStyle & 2) or (not oblique and italic != (angle != 0)):
            reject("INVALID_FONT", "斜体元数据相互矛盾")
        style = ("oblique " + format(angle, ".6f").rstrip("0").rstrip(".") + "deg") if oblique else ("italic" if italic else "normal")
        family = font["name"].getDebugName(16) or font["name"].getDebugName(1)
        if not family or len(family) > 160:
            reject("INVALID_FONT", "字体族名称缺失或过长")
        glyphs = set(font.getGlyphOrder())
        cmap = font.getBestCmap() or {}
        if any(name not in glyphs for name in cmap.values()):
            reject("INVALID_FONT", "字符映射引用了不存在的字形")
        points = sorted(c for c, name in cmap.items() if name != ".notdef"
                        and 0 <= c <= 0x10FFFF and not 0xD800 <= c <= 0xDFFF)
        if not points or not 1 <= font["maxp"].numGlyphs <= 65535:
            reject("INVALID_FONT", "字体没有可用字符或字形")
        ranges = []
        for c in points:
            if ranges and ranges[-1]["end"] + 1 == c:
                ranges[-1]["end"] = c
            else:
                ranges.append({"start": c, "end": c})
        print(json.dumps({"family": family, "subfamily": font["name"].getDebugName(2) or "",
                          "weight": weight, "style": style,
                          "characterCount": len(points), "numGlyphs": font["maxp"].numGlyphs,
                          "embeddingRights": font["OS/2"].fsType,
                          "parserVersion": fontTools.__version__, "coverageRanges": ranges},
                         ensure_ascii=True))
except SystemExit:
    raise
except MemoryError:
    reject("EXPANDED_TOO_LARGE", "字体解析超过内存限制")
except Exception:
    reject("INVALID_FONT", "字体损坏或无法安全读取；请检查文件和解析环境")
