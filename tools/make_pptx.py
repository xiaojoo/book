"""造一本真的 PPTX：python-pptx 写的，所以包结构、EMU 坐标、run 级样式都是 PowerPoint 那一套。

里面刻意放了"查看器必须保留的东西"：标题页、带粗体/斜体/红字的项目符号页、图片页、表格页、
一个摆在自定义位置的文本框、以及一页 4:3 的（混版式，验每页比例各算各的）。
"""
import io
import os
import sys

sys.path.insert(0, r"H:\book\scratch\pkg2")

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN
from pptx.util import Emu, Pt
from PIL import Image, ImageDraw

OUT = sys.argv[1] if len(sys.argv) > 1 else r"H:\book\scratch\jianlai.pptx"


def make_image(path, size=(480, 300), color=(28, 42, 74)):
    im = Image.new("RGB", size, color)
    d = ImageDraw.Draw(im)
    d.rectangle([20, 20, size[0] - 20, size[1] - 20], outline=(230, 231, 233), width=6)
    d.ellipse([size[0] // 2 - 60, size[1] // 2 - 60, size[0] // 2 + 60, size[1] // 2 + 60], fill=(227, 0, 0))
    im.save(path)
    return path


def main() -> None:
    prs = Presentation()
    prs.slide_width = Emu(12192000)   # 16:9，13.333in
    prs.slide_height = Emu(6858000)   # 7.5in

    blank = prs.slide_layouts[6]

    # 1 标题页
    s = prs.slides.add_slide(blank)
    tb = s.shapes.add_textbox(Emu(914400), Emu(2286000), Emu(10363200), Emu(1373125))
    p = tb.text_frame.paragraphs[0]
    p.text = "剑来"
    p.font.size = Pt(66)
    p.font.bold = True
    p.alignment = PP_ALIGN.CENTER
    sub = s.shapes.add_textbox(Emu(914400), Emu(3700780), Emu(10363200), Pt(40).__int__())
    sp = sub.text_frame.paragraphs[0]
    sp.text = "烽火戏诸侯 · 第一卷 惊蛰"
    sp.font.size = Pt(24)
    sp.font.color.rgb = RGBColor(0x8B, 0x93, 0xA1)
    sp.alignment = PP_ALIGN.CENTER

    # 2 带行内样式的项目符号页
    s = prs.slides.add_slide(blank)
    body = s.shapes.add_textbox(Emu(914400), Emu(1600200), Emu(10363200), Emu(4204975))
    tf = body.text_frame
    tf.word_wrap = True
    rows = [
        ("第二章 开门", Pt(32), True, RGBColor(0x22, 0x26, 0x2E)),
        ("二月二，龙抬头。", Pt(20), False, RGBColor(0x22, 0x26, 0x2E)),
        ("暮色里，小镇名叫泥瓶巷的僻静地方，有位孤苦伶仃的清瘦少年。", Pt(20), False, RGBColor(0x22, 0x26, 0x2E)),
        ("此时他正按照习俗，一手持蜡烛，一手持桃枝。", Pt(20), False, RGBColor(0xE3, 0x00, 0x00)),
    ]
    for i, (text, size, bold, color) in enumerate(rows):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        r = p.add_run()
        r.text = text
        r.font.size = size
        r.font.bold = bold
        r.font.color.rgb = color
        p.level = 0 if i == 0 else 1
    # 同一行里混一个斜体 run：查看器必须按 run 换字体，不能整段一个样
    p = tf.add_paragraph()
    a = p.add_run()
    a.text = "桃打墙，人间蛇虫无处藏。"
    a.font.size = Pt(20)
    b = p.add_run()
    b.text = "（老话）"
    b.font.size = Pt(20)
    b.font.italic = True
    b.font.color.rgb = RGBColor(0x6A, 0x70, 0x79)

    # 3 图片页
    img = make_image(os.path.join(r"H:\book\scratch", "ppt_img.png"))
    s = prs.slides.add_slide(blank)
    s.shapes.add_picture(img, Emu(1200150), Emu(1300150), Emu(4800000), Emu(3000000))
    cap = s.shapes.add_textbox(Emu(1200150), Emu(4500150), Emu(9000000), Pt(30).__int__())
    cp = cap.text_frame.paragraphs[0]
    cp.text = "图 1  泥瓶巷的窑火"
    cp.font.size = Pt(18)
    cp.font.color.rgb = RGBColor(0x6A, 0x70, 0x79)

    # 4 表格页
    s = prs.slides.add_slide(blank)
    tbl = s.shapes.add_table(3, 3, Emu(914400), Emu(1600200), Emu(10363200), Emu(2400000)).table
    data = [["人物", "身份", "出处"], ["陈平安", "窑匠", "泥瓶巷"], ["姚老头", "半路师傅", "老瓷山"]]
    for r, row in enumerate(data):
        for c, val in enumerate(row):
            cell = tbl.cell(r, c)
            cell.text = val
            for para in cell.text_frame.paragraphs:
                para.font.size = Pt(18)
                if r == 0:
                    para.font.bold = True

    # 5 混版式：再来一页 16:9 的纯文本页，末页数一下
    s = prs.slides.add_slide(blank)
    tb = s.shapes.add_textbox(Emu(600000), Emu(600000), Emu(10992000), Emu(5600000))
    tf = tb.text_frame
    tf.word_wrap = True
    for i in range(6):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        r = p.add_run()
        r.text = "第 %d 行：天微微亮，尚未鸡鸣，陈平安就已经起床。" % (i + 1)
        r.font.size = Pt(22)

    prs.save(OUT)

    # 再造一本"放映顺序 ≠ 文件名顺序"的：复制过页的 deck 就是这个样子。
    # 读文件名顺序的解析器会在这里翻车 —— 它画得出来，只是页序不对，最难发现的那种错。
    order = Presentation()
    order.slide_width, order.slide_height = prs.slide_width, prs.slide_height
    for label in ("第一页", "第二页", "第三页"):
        sl = order.slides.add_slide(order.slide_layouts[6])
        box = sl.shapes.add_textbox(Emu(600000), Emu(600000), Emu(6000000), Emu(900000))
        para = box.text_frame.paragraphs[0]
        para.text = label
        para.font.size = Pt(40)
    lst = order.slides._sldIdLst
    lst.append(lst[0])  # 把第一页挪到最后：放映顺序变成 第二页、第三页、第一页
    order.save(r"H:\book\scratch\pptx-order.pptx")

    with open(r"H:\book\scratch\pptx-expected.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write("slides=%d\n" % len(prs.slides._sldIdLst))
        f.write("slide_width_emu=%d\n" % prs.slide_width)
        f.write("slide_height_emu=%d\n" % prs.slide_height)
        f.write("shapes=%d\n" % sum(len(sl.shapes) for sl in prs.slides))
        f.write("runs=%d\n" % sum(
            1 for sl in prs.slides for sh in sl.shapes
            if sh.has_text_frame for para in sh.text_frame.paragraphs for _ in para.runs))
        f.write("paragraphs=%d\n" % sum(
            1 for sl in prs.slides for sh in sl.shapes
            if sh.has_text_frame for _ in sh.text_frame.paragraphs))
        f.write("tables=%d\n" % sum(1 for sl in prs.slides for sh in sl.shapes if sh.has_table))
        f.write("pictures=%d\n" % sum(1 for sl in prs.slides for sh in sl.shapes if sh.shape_type == 13))
        f.write("file_bytes=%d\n" % os.path.getsize(OUT))
    print("ok")


if __name__ == "__main__":
    main()
