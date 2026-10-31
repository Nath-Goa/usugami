"""Builds the made-up Word and PowerPoint files the device tests convert.

    python tools/make_office_fixtures.py app/src/androidTest/assets

Writes report.docx (a few pages: title, headings, styled text, lists, a picture, a table with a
header row, a page break, a Hindi paragraph, a header and a footer with page numbers, and a
landscape section) and deck.pptx (a title slide, bullets, a picture, a table, shapes, a chart and a
hidden slide). Everything in them, the picture included, is made up here. Needs python-docx,
python-pptx and Pillow (tools/requirements.txt).
"""
import datetime
import io
import sys
from pathlib import Path

from PIL import Image, ImageDraw
from docx import Document
from docx.enum.section import WD_ORIENT
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK, WD_COLOR_INDEX
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor
from pptx import Presentation
from pptx.chart.data import CategoryChartData
from pptx.dml.color import RGBColor as SlideColor
from pptx.enum.chart import XL_CHART_TYPE
from pptx.enum.shapes import MSO_CONNECTOR, MSO_SHAPE
from pptx.enum.text import PP_ALIGN
from pptx.util import Inches as SlideInches, Pt as SlidePt

FIXED_DATE = datetime.datetime(2026, 10, 1, 12, 0, 0)


def garden_picture() -> bytes:
    """A small drawing of hills, a sun and garden beds, as a JPEG."""
    w, h = 960, 640
    img = Image.new("RGB", (w, h))
    draw = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        draw.line([(0, y), (w, y)], fill=(int(120 + 100 * t), int(170 + 60 * t), 235))
    draw.ellipse([700, 70, 820, 190], fill=(255, 205, 70))
    draw.ellipse([-200, 330, 600, 900], fill=(96, 160, 84))
    draw.ellipse([350, 380, 1250, 1000], fill=(70, 135, 70))
    for i in range(6):
        x = 90 + i * 140
        draw.rectangle([x, 470, x + 100, 600], fill=(120, 82, 50))
        for k in range(4):
            draw.ellipse([x + 8 + k * 22, 450, x + 30 + k * 22, 478], fill=(40 + 20 * k, 150, 60))
    out = io.BytesIO()
    img.save(out, "JPEG", quality=78)
    return out.getvalue()


def add_field(paragraph, instruction: str, cached: str):
    """A Word field such as PAGE, written the way Word writes it (begin, instruction, result, end)."""
    def char(kind):
        run = paragraph.add_run()
        fld = OxmlElement("w:fldChar")
        fld.set(qn("w:fldCharType"), kind)
        run._r.append(fld)
    char("begin")
    run = paragraph.add_run()
    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = f" {instruction} "
    run._r.append(instr)
    char("separate")
    paragraph.add_run(cached)
    char("end")


def add_link(paragraph, text: str, url: str):
    part = paragraph.part
    rel = part.relate_to(url, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", is_external=True)
    link = OxmlElement("w:hyperlink")
    link.set(qn("r:id"), rel)
    run = OxmlElement("w:r")
    props = OxmlElement("w:rPr")
    color = OxmlElement("w:color")
    color.set(qn("w:val"), "0563C1")
    underline = OxmlElement("w:u")
    underline.set(qn("w:val"), "single")
    props.append(color)
    props.append(underline)
    run.append(props)
    t = OxmlElement("w:t")
    t.text = text
    run.append(t)
    link.append(run)
    paragraph._p.append(link)


def repeat_header(row):
    props = row._tr.get_or_add_trPr()
    header = OxmlElement("w:tblHeader")
    props.append(header)


SENTENCES = [
    "Volunteers met every Saturday morning from March to October.",
    "The new rain barrels collected enough water for most of the summer.",
    "Children from the school next door planted a row of sunflowers by the gate.",
    "We shared seeds with two other gardens in the district.",
    "The compost bays were rebuilt with wood from an old fence.",
    "Most weeks, someone brought tea and the radio for the long afternoons.",
    "A late frost in April cost us the first beans, but the second sowing did well.",
]


def report(path: Path, picture: bytes):
    doc = Document()
    doc.core_properties.created = FIXED_DATE
    doc.core_properties.modified = FIXED_DATE
    doc.core_properties.author = "Usugami tests"
    section = doc.sections[0]
    section.page_width = Inches(8.27)
    section.page_height = Inches(11.69)
    section.left_margin = section.right_margin = Inches(1)
    section.top_margin = section.bottom_margin = Inches(1)

    section.header.paragraphs[0].text = "Riverside Community Garden – Annual report"
    section.header.paragraphs[0].alignment = WD_ALIGN_PARAGRAPH.RIGHT
    footer = section.footer.paragraphs[0]
    footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
    footer.add_run("Page ")
    add_field(footer, "PAGE", "1")

    doc.add_heading("Riverside Community Garden", level=0)
    doc.add_paragraph("Annual report for the members' meeting, October 2026")
    doc.add_heading("A good year", level=1)
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
    p.add_run("This year the garden grew more than ever before. ")
    p.add_run("Forty-two members").bold = True
    p.add_run(" looked after ")
    p.add_run("eighteen raised beds").italic = True
    p.add_run(", a herb spiral and a small orchard. ")
    run = p.add_run("The pumpkin patch was the surprise of the season.")
    run.underline = True
    run.font.color.rgb = RGBColor(0xC0, 0x50, 0x10)
    p.add_run(" ")
    p.add_run("Please read the notes on watering before next spring.").font.highlight_color = WD_COLOR_INDEX.YELLOW
    p = doc.add_paragraph("More about the garden on ")
    add_link(p, "riverside-garden.example", "https://riverside-garden.example/")
    p.add_run(".")

    doc.add_heading("What we grew", level=2)
    for item in ["Tomatoes, in six varieties", "Beans and peas", "Squash and pumpkins", "Herbs"]:
        doc.add_paragraph(item, style="List Bullet")
        if item.startswith("Herbs"):
            doc.add_paragraph("Basil, parsley and mint", style="List Bullet 2")
    doc.add_heading("Plans for next year", level=2)
    for item in ["Build a second greenhouse", "Start a seed library", "Open the garden on two Sundays a month"]:
        doc.add_paragraph(item, style="List Number")

    picture_paragraph = doc.add_paragraph()
    picture_paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
    picture_paragraph.add_run().add_picture(io.BytesIO(picture), width=Inches(4.5))
    caption = doc.add_paragraph("The beds in June, drawn by a member.")
    caption.alignment = WD_ALIGN_PARAGRAPH.CENTER
    caption.runs[0].italic = True

    doc.add_heading("Harvest", level=1)
    table = doc.add_table(rows=1, cols=4)
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    header = table.rows[0]
    repeat_header(header)
    for cell, text in zip(header.cells, ["Crop", "Beds", "Harvest (kg)", "Notes"]):
        cell.text = ""
        cell.paragraphs[0].add_run(text).bold = True
    crops = [
        ("Tomatoes", 4, 212, "Best year so far"), ("Beans", 3, 64, "Second sowing after the frost"),
        ("Peas", 2, 38, ""), ("Squash", 2, 150, "Shared with the food bank"), ("Pumpkins", 2, 190, "One weighed 21 kg"),
        ("Potatoes", 3, 240, ""), ("Onions", 1, 45, "Kept well into winter"), ("Carrots", 1, 30, "Some lost to slugs"),
        ("Lettuce", 1, 28, "Sown every three weeks"), ("Herbs", 1, 12, "Dried for the winter market"),
        ("Strawberries", 1, 22, "Mostly eaten on the spot"), ("Apples", 0, 85, "From the four young trees"),
    ]
    for crop in crops:
        cells = table.add_row().cells
        for cell, value in zip(cells, crop):
            cell.text = str(value)

    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)
    doc.add_heading("Volunteers", level=1)
    for i in range(14):
        doc.add_paragraph(" ".join(SENTENCES[(i + k) % len(SENTENCES)] for k in range(4)))
    doc.add_heading("हिन्दी में सार", level=2)
    doc.add_paragraph(
        "इस साल हमारे बगीचे में "
        "बहुत सारी सब्ज़ियाँ "
        "उगीं। सभी स्वयंसेवकों "
        "का धन्यवाद।"
    )

    wide = doc.add_section()
    wide.orientation = WD_ORIENT.LANDSCAPE
    wide.page_width, wide.page_height = section.page_height, section.page_width
    doc.add_heading("Watering rota", level=1)
    rota = doc.add_table(rows=1, cols=8)
    rota.style = "Table Grid"
    for cell, text in zip(rota.rows[0].cells, ["Week", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]):
        cell.text = text
    names = ["Asha", "Ben", "Chloe", "Dev", "Elena", "Femi", "Gita"]
    for week in range(1, 7):
        cells = rota.add_row().cells
        cells[0].text = f"Week {week}"
        for day in range(7):
            cells[day + 1].text = names[(week + day) % len(names)]
    doc.save(path)


def deck(path: Path, picture: bytes):
    prs = Presentation()
    prs.core_properties.created = FIXED_DATE
    prs.core_properties.modified = FIXED_DATE
    prs.core_properties.author = "Usugami tests"

    slide = prs.slides.add_slide(prs.slide_layouts[0])
    slide.shapes.title.text = "Riverside Community Garden"
    slide.placeholders[1].text = "Members' meeting, October 2026"

    slide = prs.slides.add_slide(prs.slide_layouts[1])
    slide.shapes.title.text = "What we grew"
    body = slide.placeholders[1].text_frame
    body.text = "Vegetables"
    for text, level in [("Tomatoes, beans and peas", 1), ("Squash and pumpkins", 1), ("Herbs and flowers", 0), ("Fruit from the young orchard", 0)]:
        para = body.add_paragraph()
        para.text = text
        para.level = level

    slide = prs.slides.add_slide(prs.slide_layouts[5])
    slide.shapes.title.text = "The beds in June"
    slide.shapes.add_picture(io.BytesIO(picture), SlideInches(1.5), SlideInches(1.8), width=SlideInches(7))
    box = slide.shapes.add_textbox(SlideInches(1.5), SlideInches(6.6), SlideInches(7), SlideInches(0.5))
    box.text_frame.text = "Drawn by a member of the garden"
    box.text_frame.paragraphs[0].alignment = PP_ALIGN.CENTER
    box.text_frame.paragraphs[0].runs[0].font.italic = True
    box.text_frame.paragraphs[0].runs[0].font.size = SlidePt(14)

    slide = prs.slides.add_slide(prs.slide_layouts[5])
    slide.shapes.title.text = "Harvest"
    rows = [("Crop", "Beds", "Harvest (kg)"), ("Tomatoes", "4", "212"), ("Potatoes", "3", "240"), ("Pumpkins", "2", "190"), ("Apples", "0", "85")]
    table = slide.shapes.add_table(len(rows), 3, SlideInches(1), SlideInches(2), SlideInches(8), SlideInches(3)).table
    for r, row in enumerate(rows):
        for c, text in enumerate(row):
            table.cell(r, c).text = text

    hidden = prs.slides.add_slide(prs.slide_layouts[1])
    hidden.shapes.title.text = "Draft notes, not for the meeting"
    hidden._element.set("show", "0")

    slide = prs.slides.add_slide(prs.slide_layouts[5])
    slide.shapes.title.text = "How the garden works"
    steps = ["Sow", "Grow", "Harvest", "Share"]
    for i, step in enumerate(steps):
        shape = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, SlideInches(0.6 + i * 2.3), SlideInches(2.4), SlideInches(1.9), SlideInches(1.2))
        shape.fill.solid()
        shape.fill.fore_color.rgb = SlideColor(0x3A + i * 0x18, 0x7D, 0x44 + i * 0x10)
        shape.line.color.rgb = SlideColor(0x2E, 0x5E, 0x34)
        shape.text_frame.text = step
        shape.text_frame.paragraphs[0].alignment = PP_ALIGN.CENTER
        shape.text_frame.paragraphs[0].runs[0].font.bold = True
        shape.text_frame.paragraphs[0].runs[0].font.size = SlidePt(20)
        if i < len(steps) - 1:
            arrow = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, SlideInches(2.5 + i * 2.3), SlideInches(3.0), SlideInches(2.9 + i * 2.3), SlideInches(3.0))
            arrow.line.color.rgb = SlideColor(0x55, 0x55, 0x55)
            arrow.line.width = SlidePt(2)
    sun = slide.shapes.add_shape(MSO_SHAPE.OVAL, SlideInches(7.6), SlideInches(4.6), SlideInches(1.4), SlideInches(1.4))
    sun.fill.solid()
    sun.fill.fore_color.rgb = SlideColor(0xFF, 0xC8, 0x3D)
    sun.line.fill.background()
    note = slide.shapes.add_textbox(SlideInches(0.6), SlideInches(4.8), SlideInches(6.5), SlideInches(1.2))
    note.text_frame.word_wrap = True
    note.text_frame.text = "Every member takes a turn at each step, so everyone learns the whole year."
    note.text_frame.paragraphs[0].runs[0].font.size = SlidePt(18)

    slide = prs.slides.add_slide(prs.slide_layouts[5])
    slide.shapes.title.text = "Harvest by month"
    data = CategoryChartData()
    data.categories = ["Jun", "Jul", "Aug", "Sep", "Oct"]
    data.add_series("Harvest (kg)", (60, 180, 320, 290, 150))
    chart = slide.shapes.add_chart(XL_CHART_TYPE.COLUMN_CLUSTERED, SlideInches(1), SlideInches(1.8), SlideInches(8), SlideInches(5), data).chart
    chart.has_title = True
    chart.chart_title.text_frame.text = "Harvest by month (kg)"
    prs.save(path)


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/androidTest/assets")
    out.mkdir(parents=True, exist_ok=True)
    picture = garden_picture()
    report(out / "report.docx", picture)
    deck(out / "deck.pptx", picture)
    for name in ("report.docx", "deck.pptx"):
        print(name, (out / name).stat().st_size, "bytes")


if __name__ == "__main__":
    main()
