"""造一份真的 markdown：剑来的段落 + 刻意放进去的每一种格式。

真机上要量的就是"这些格式有没有画出来"：标题比正文大、粗体那几个字真的变粗、
引用变灰并带竖线、代码走等宽、列表带缩进、链接换色。所以每种都单独占一处，
并且知道它在正文里的位置，方便对着截图量。
"""
import io
import re
import sys

SRC = r"G:\小说\剑来.txt"
OUT = sys.argv[1] if len(sys.argv) > 1 else r"H:\book\scratch\jianlai.md"
CHAPTER = re.compile(r"^\s*第\s*[0-9零〇一二三四五六七八九十百千万]{1,14}\s*章.*$")


def main() -> None:
    text = open(SRC, "rb").read().decode("utf-8")
    lines = [ln.strip() for ln in text.split("\n")]
    heads = [i for i, ln in enumerate(lines) if CHAPTER.match(ln)]
    out = []
    out.append("# 剑来")
    out.append("")
    out.append("> 引用：天雨虽宽，不润无根之草。")
    out.append("")
    # 同一组词各来一遍（普通 / 粗体）：比"整片墨量"会被字形本身干扰，只有同字对照才量得出笔画
    out.append("第一段：普通 山水风云 与 **山水风云** 并排。")
    out.append("")
    out.append("第二段：普通 山水风云，*斜体 山水风云*，`code width 0123`，~~删除 山水风云~~。")
    out.append("")
    out.append("- 列表第一层")
    out.append("  - 列表第二层")
    out.append("1. 有序第一")
    out.append("2. 有序第二")
    out.append("")
    out.append("## 二级标题")
    out.append("")
    out.append("### 三级标题")
    out.append("")
    out.append("---")
    out.append("")
    out.append("| 名 | 值 |")
    out.append("|----|----|")
    out.append("| 剑 | 来 |")
    out.append("")
    out.append("```")
    out.append("fun main() {")
    out.append("    println(\"泥瓶巷\")")
    out.append("}")
    out.append("```")
    out.append("")
    out.append("链接见[纵横中文网](https://www.zongheng.com)。")
    out.append("")
    # 再放 30 章真正文，让分页和滚动有东西可跑
    for k, h in enumerate(heads[:30]):
        stop = heads[k + 1] if k + 1 < len(heads) else len(lines)
        title = lines[h].strip()
        out.append("")
        out.append("# " + re.sub(r"^第\s*", "第 ", title))
        out.append("")
        for ln in lines[h + 1:stop]:
            if ln and not set(ln) <= set("-—_"):
                out.append(ln)
    md = "\n".join(out) + "\n"
    io.open(OUT, "w", encoding="utf-8", newline="").write(md)
    io.open(r"H:\book\scratch\md-expected.txt", "w", encoding="utf-8", newline="").write(
        "bytes=%d\nchars=%d\nlines=%d\nheadings=%d\nbold=粗体词\nitalic=斜体词\ncode=等宽词\nstrike=删除线\n"
        % (len(md.encode()), len(md), md.count("\n"), len(re.findall(r"(?m)^#{1,3} ", md)))
    )
    print("ok")


if __name__ == "__main__":
    main()
