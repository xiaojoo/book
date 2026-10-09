"""从真的 剑来.txt 造一本真的 EPUB：一章一个 xhtml，spine 按原顺序。
判据是"我们的解析器造得出来的东西，也必须读得回来"，所以标题/正文都按网上书的写法排：
<h1 class="chapter">第N章 标题</h1> + 一串 <p>，段间不空行。
"""
import io
import os
import re
import sys
import zipfile

SRC = r"G:\小说\剑来.txt"
OUT = sys.argv[1] if len(sys.argv) > 1 else r"H:\book\scratch\jianlai.epub"
CHAPTER = re.compile(r"^\s*第\s*[0-9一二三四五六七八九十百千万零〇]+\s*章.*$")


def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main() -> None:
    text = open(SRC, "rb").read().decode("utf-8")
    lines = text.splitlines()
    heads = [i for i, ln in enumerate(lines) if CHAPTER.match(ln)]
    print("源 bytes=%d chars=%d 行=%d 章标题=%d" % (len(text.encode()), len(text), len(lines), len(heads)))
    if len(heads) < 100:
        raise SystemExit("章节识别太少，别拿这个当夹具")

    chapters = []
    for k, h in enumerate(heads):
        stop = heads[k + 1] if k + 1 < len(heads) else len(lines)
        title = lines[h].strip()
        body = [ln.strip() for ln in lines[h + 1:stop] if ln.strip()]
        chapters.append((title, body))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        # mimetype 必须是第一项且不压缩，否则严格的阅读器会拒
        z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip", zipfile.ZIP_STORED)
        z.writestr(
            "META-INF/container.xml",
            '<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
            '<rootfiles><rootfile full-path="OEBPS/content.opf" '
            'media-type="application/oebps-package+xml"/></rootfiles></container>',
        )
        items, refs = [], []
        for i, (title, body) in enumerate(chapters, start=1):
            html = (
                '<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml">'
                f"<head><title>{esc(title)}</title></head><body>"
            )
            html += f'<h1 class="chapter">{esc(title)}</h1>'
            for para in body:
                html += f"<p>{esc(para)}</p>"
            html += "</body></html>"
            z.writestr(f"OEBPS/Text/c{i}.xhtml", html)
            items.append(f'<item id="c{i}" href="Text/c{i}.xhtml" media-type="application/xhtml+xml"/>')
            refs.append(f'<itemref idref="c{i}"/>')
        opf = (
            '<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bid" version="3.0">'
            '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
            "<dc:title>剑来</dc:title><dc:creator>烽火戏诸侯</dc:creator>"
            '<dc:identifier id="bid">urn:uuid:1</dc:identifier><dc:language>zh</dc:language></metadata>'
            "<manifest>" + "".join(items) + "</manifest><spine>" + "".join(refs) + "</spine></package>"
        )
        if opf.count("<item ") != len(chapters):
            raise SystemExit("manifest 条目数和章数不一致，夹具别拿出去测")
        z.writestr("OEBPS/content.opf", opf)
    print("写出 %s：%d 章，%d 段，%.1f MB" % (
        OUT, len(chapters), sum(len(b) for _, b in chapters), os.path.getsize(OUT) / 1048576.0))
    with io.open(r"H:\book\scratch\jianlai-expected.txt", "w", encoding="utf-8") as f:
        f.write("chapters=%d\n" % len(chapters))
        f.write("paras=%d\n" % sum(len(b) for _, b in chapters))
        f.write("chars=%d\n" % sum(len(p) + 1 for _, b in chapters for p in b))
        f.write("first_title=%s\nlast_title=%s\n" % (chapters[0][0], chapters[-1][0]))


if __name__ == "__main__":
    main()
