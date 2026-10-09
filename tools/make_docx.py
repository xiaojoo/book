"""造一本真的 DOCX：剑来前 60 章，Word 的最小合法结构（[Content_Types] + _rels + document.xml）。

判据是"Word 那套包结构我们读得回来"：每个段是 <w:p><w:r><w:t>，章标题走 Heading1 样式。
"""
import os
import re
import sys
import zipfile

SRC = r"G:\小说\剑来.txt"
OUT = sys.argv[1] if len(sys.argv) > 1 else r"H:\book\scratch\jianlai.docx"
CHAPTER = re.compile(r"^\s*第\s*[0-9零〇一二三四五六七八九十百千万]{1,14}\s*章.*$")

CT = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/word/document.xml" '
    'ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
    '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
    "</Types>"
)
RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
    'Target="word/document.xml"/></Relationships>'
)
DOC_RELS = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" '
    'Target="styles.xml"/></Relationships>'
)
STYLES = (
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
    '<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>'
    "</w:styles>"
)


def esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main() -> None:
    limit = int(sys.argv[2]) if len(sys.argv) > 2 else 60
    text = open(SRC, "rb").read().decode("utf-8")
    lines = [ln.strip() for ln in text.split("\n")]
    heads = [i for i, ln in enumerate(lines) if CHAPTER.match(ln)]
    chs = []
    for k, h in enumerate(heads[:limit]):
        stop = heads[k + 1] if k + 1 < len(heads) else len(lines)
        body = [ln for ln in lines[h + 1:stop] if ln and not set(ln) <= set("-—_")]
        chs.append((lines[h].strip(), body))

    xml = ['<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>']
    for title, body in chs:
        xml.append('<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t xml:space="preserve">%s</w:t></w:r></w:p>' % esc(title))
        for p in body:
            xml.append("<w:p><w:r><w:t xml:space=\"preserve\">%s</w:t></w:r></w:p>" % esc(p))
    xml.append("</w:body></w:document>")
    doc = "".join(xml)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", CT)
        z.writestr("_rels/.rels", RELS)
        z.writestr("word/_rels/document.xml.rels", DOC_RELS)
        z.writestr("word/styles.xml", STYLES)
        z.writestr("word/document.xml", doc)
    blocks = sum(1 + len(b) for _, b in chs)
    with open(r"H:\book\scratch\docx-expected.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write("chapters=%d\nblocks=%d\nparas=%d\nw_p=%d\nfile_bytes=%d\n" % (
            len(chs), blocks, sum(len(b) for _, b in chs), doc.count("<w:p>"), os.path.getsize(OUT)))
    print("ok")


if __name__ == "__main__":
    main()
