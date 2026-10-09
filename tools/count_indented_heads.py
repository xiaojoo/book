"""数一下：原 txt 里有多少章标题是"前面有空白"的（引擎的章标题正则从行首认，这类会被漏掉）。"""
import io
import re

SRC = r"G:\小说\剑来.txt"
CN = re.compile(r"^第\s*[0-9零〇一二三四五六七八九十百千万两亿]{1,14}\s*[节章回卷篇部折幕](?:[ 　、，:：]?[^\s].{0,58})?$")
SENT = re.compile(r"[。！？；!?;]")


def matches(line: str) -> bool:
    if not (2 <= len(line) <= 80):
        return False
    if SENT.search(line):
        return False
    return bool(CN.match(line))


def main() -> None:
    text = open(SRC, "rb").read().decode("utf-8")
    raw = text.split("\n")
    raw_hits = [ln for ln in raw if matches(ln.rstrip("\r"))]
    strip_hits = [ln for ln in raw if matches(ln.strip())]
    lost = [ln for ln in raw if matches(ln.strip()) and not matches(ln.rstrip("\r"))]
    with io.open(r"H:\book\scratch\heads.txt", "w", encoding="utf-8", newline="\n") as o:
        o.write("行总数=%d 顶格认出的章标题=%d 去掉行首空白后认出=%d 差=%d\n" % (len(raw), len(raw_hits), len(strip_hits), len(lost)))
        for ln in lost[:6]:
            o.write("  漏掉的样本 %r\n" % ln[:46])
    print("done")


if __name__ == "__main__":
    main()
