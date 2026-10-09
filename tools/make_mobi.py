"""造一本真的 MOBI：PalmDB 容器 + PalmDOC LZ77 文本记录。

判据不是"我自己写的解压自己读得回来"，而是这份文件要被 **KindleUnpack**（Calibre 用的那套，
pip 包 mobi 里带着）读通：`mobi.extract()` 成功 = 容器和压缩都是真的按格式写的。
LZ77 的位布局照 KindleUnpack 的 PalmdocReader 对过：
    distance = (pair >> 3) & 0x7FF, length = (pair & 7) + 3, pair 的首字节在 0x80~0xBF
    0x01~0x08 = 照抄后面 c 个字节；0x09~0x7F = 字面；0xC0~0xFF = 空格 + 字面
"""
import os
import re
import struct
import sys

SRC = r"G:\小说\剑来.txt"
OUT = sys.argv[1] if len(sys.argv) > 1 else r"H:\book\scratch\jianlai.mobi"
CHAPTER = re.compile(r"^\s*第\s*[0-9零〇一二三四五六七八九十百千万]{1,14}\s*章.*$")
RECORD_SIZE = 4096


def chapters(limit=60):
    text = open(SRC, "rb").read().decode("utf-8")
    lines = [ln.strip() for ln in text.split("\n")]
    heads = [i for i, ln in enumerate(lines) if CHAPTER.match(ln)]
    out = []
    for k, h in enumerate(heads[:limit]):
        stop = heads[k + 1] if k + 1 < len(heads) else len(lines)
        body = [ln for ln in lines[h + 1:stop] if ln and not set(ln) <= set("-—_")]
        out.append((lines[h].strip(), body))
    return out


def compress(data: bytes) -> bytes:
    """贪心匹配：长度 3~10、距离 1~2047，匹配不到就写字面（高位字节用 0x01 转义）。"""
    out = bytearray()
    pos = 0
    while pos < len(data):
        best_len, best_dist = 0, 0
        for length in range(min(10, len(data) - pos), 2, -1):
            found = False
            for dist in range(1, min(2047, pos) + 1):
                if data[pos - dist:pos - dist + length] == data[pos:pos + length]:
                    best_len, best_dist = length, dist
                    found = True
                    break
            if found:
                break
        if best_len >= 3:
            pair = 0x8000 | (best_dist << 3) | (best_len - 3)
            out += bytes([(pair >> 8) & 0xFF, pair & 0xFF])
            pos += best_len
            continue
        b = data[pos]
        if 0x09 <= b <= 0x7F:
            out.append(b)
        else:
            out += bytes([0x01, b])
        pos += 1
    return bytes(out)


def build() -> tuple:
    chs = chapters()
    html = []
    for title, body in chs:
        html.append("<h1>%s</h1>" % title)
        html += ["<p>%s</p>" % p.replace("&", "&amp;").replace("<", "&lt;") for p in body]
    text = "".join(html).encode("utf-8")
    parts = [text[i:i + RECORD_SIZE] for i in range(0, len(text), RECORD_SIZE)]
    packed = [b"\x00\x02" + compress(p) for p in parts]  # 每条记录开头 2 字节又是压缩方式
    return chs, text, packed, len(html)


def write_mobi(path: str) -> dict:
    chs, text, packed, blocks = build()
    n_text = len(packed)
    # record 0 = PalmDOC 头(16) + MOBI 头(232) + EXTH
    mobi_len = 232
    exth_entries = [(100, b"bookreader-probe"), (131, struct.pack(">L", 1 + n_text))]
    exth = b"EXTH" + struct.pack(">LL", 12 + sum(8 + len(v) for _, v in exth_entries), len(exth_entries))
    for eid, val in exth_entries:
        exth += struct.pack(">LL", eid, 8 + len(val)) + val
    rec0 = bytearray(16 + mobi_len + len(exth))
    rec0[0x00:0x02] = struct.pack(">H", 2)                     # compression = PalmDOC LZ77
    rec0[0x04:0x08] = struct.pack(">L", len(text))             # 解压后的总长
    rec0[0x08:0x0A] = struct.pack(">H", n_text)                # 文本记录条数
    rec0[0x0A:0x0C] = struct.pack(">H", RECORD_SIZE)           # 每条记录解压后最大 4096
    rec0[0x0C:0x0E] = struct.pack(">H", 0)                     # encryption = 无
    rec0[0x10:0x14] = b"MOBI"
    rec0[0x14:0x18] = struct.pack(">L", mobi_len)
    rec0[0x18:0x1C] = struct.pack(">L", 2)                     # type = book
    rec0[0x1C:0x20] = struct.pack(">L", 65001)                 # codepage = UTF-8
    rec0[0x20:0x24] = struct.pack(">L", 0x1234)                # unique id
    rec0[0x24:0x28] = struct.pack(">L", 6)                     # mobi version
    rec0[0x50:0x54] = struct.pack(">L", 1 + n_text)            # firstNonText：文本记录到此为止
    rec0[0x80:0x84] = struct.pack(">L", 0x40)                  # exthFlags：有 EXTH
    rec0[16 + mobi_len:16 + mobi_len + len(exth)] = exth

    records = [bytes(rec0)] + packed
    offsets = []
    pos = 78 + 8 * len(records)
    for r in records:
        offsets.append(pos)
        pos += len(r)

    head = bytearray(78)
    head[0:32] = b"jianlai".ljust(32, b"\x00")
    head[60:64] = b"BOOK"
    head[64:68] = b"MOBI"
    head[76:78] = struct.pack(">H", len(records))
    with open(path, "wb") as f:
        f.write(bytes(head))
        for i, r in enumerate(records):
            f.write(struct.pack(">L", offsets[i]) + b"\x00\x00\x00\x00")
        for r in records:
            # 记录之间不留空隙：记录表算的就是紧凑偏移，多塞一个对齐字节会让每条都错位一个字节
            f.write(r)
    return {
        "chapters": len(chs),
        "paras": sum(len(b) for _, b in chs),
        "blocks": blocks,
        "text_bytes": len(text),
        "text_chars": len(text.decode("utf-8")),
        "records": n_text,
        "compressed_bytes": sum(len(p) for p in packed),
        "file_bytes": os.path.getsize(path),
    }


if __name__ == "__main__":
    info = write_mobi(OUT)
    import io
    io.open(r"H:\book\scratch\mobi-expected.txt", "w", encoding="utf-8", newline="\n").write(
        "\n".join("%s=%s" % kv for kv in info.items()) + "\n"
    )
    print("ok")
