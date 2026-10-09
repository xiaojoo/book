"""造一本真的 PDF：剑来前 20 章，A4 竖版 + 一页横版（横版是用来验"每页比例各算各的"）。

PDF 走的是"整页渲染、保留原件版面"那条路，所以这份夹具要有的就是版面：页边距、字号、
行距、页眉页脚、标题居中 —— 全在 PDF 里定死，不由阅读器决定。
"""
import re
import sys

import fitz

SRC = r"G:\小说\剑来.txt"
OUT = sys.argv[1] if len(sys.argv) > 1 else r"H:\book\scratch\jianlai.pdf"
CHAPTER = re.compile(r"^\s*第\s*[0-9零〇一二三四五六七八九十百千万]{1,14}\s*章.*$")


def chapters(limit=20):
    text = open(SRC, "rb").read().decode("utf-8")
    lines = [ln.strip() for ln in text.split("\n")]
    heads = [i for i, ln in enumerate(lines) if CHAPTER.match(ln)]
    out = []
    for h in heads[:limit]:
        k = heads.index(h)
        stop = heads[k + 1] if k + 1 < len(heads) else len(lines)
        out.append((lines[h], [ln for ln in lines[h + 1:stop] if ln and not set(ln) <= set("-—_")]))
    return out


def main() -> None:
    books = chapters()
    doc = fitz.open()
    page_no = 0
    for ci, (title, paras) in enumerate(books):
        landscape = ci == 3  # 第四章排成横版，用来验混排比例
        w, h = (842, 595) if landscape else (595, 842)
        page = doc.new_page(width=w, height=h)
        margin = 56
        y = margin + 20
        page.insert_text((margin, y), title, fontname="china-s", fontsize=20)
        y += 40
        for p in paras:
            # 一行只放排得下的字：PDF 不会自动换行，得自己切
            room = w - 2 * margin
            per = max(8, int(room / 12.6))
            for s in (p[i:i + per] for i in range(0, len(p), per)):
                if y > h - margin - 30:
                    page.insert_text((w / 2 - 20, h - 26), "- %d -" % (page_no + 1), fontname="china-s", fontsize=9)
                    page_no += 1
                    page = doc.new_page(width=w, height=h)
                    y = margin + 20
                page.insert_text((margin, y), s, fontname="china-s", fontsize=12.6)
                y += 21
        page.insert_text((w / 2 - 20, h - 26), "- %d -" % (page_no + 1), fontname="china-s", fontsize=9)
        page_no += 1
    doc.save(OUT)
    print("pages=%d size=%s" % (doc.page_count, doc[0].rect))
    doc.close()
    import os
    print("bytes=%d" % os.path.getsize(OUT))


if __name__ == "__main__":
    main()
