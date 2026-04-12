---
name: pdf
description: "用 pypdf/pdfplumber/fpdf2 处理 PDF（读取/提取文本表格/合并/拆分/旋转/水印/加密/创建）"
---

# PDF Processing Guide

## Overview

This guide covers essential PDF processing operations using pure Python libraries. If you need to fill out a PDF form, read FORMS.md and follow its instructions.

## Quick Start

```python
from pypdf import PdfReader, PdfWriter

# Read a PDF
reader = PdfReader("document.pdf")
print(f"Pages: {len(reader.pages)}")

# Extract text
text = ""
for page in reader.pages:
    text += page.extract_text()
```

## Python Libraries

### pypdf - Basic Operations

#### Merge PDFs
```python
from pypdf import PdfWriter, PdfReader

writer = PdfWriter()
for pdf_file in ["doc1.pdf", "doc2.pdf", "doc3.pdf"]:
    reader = PdfReader(pdf_file)
    for page in reader.pages:
        writer.add_page(page)

with open("merged.pdf", "wb") as output:
    writer.write(output)
```

#### Split PDF
```python
reader = PdfReader("input.pdf")
for i, page in enumerate(reader.pages):
    writer = PdfWriter()
    writer.add_page(page)
    with open(f"page_{i+1}.pdf", "wb") as output:
        writer.write(output)
```

#### Extract Metadata
```python
reader = PdfReader("document.pdf")
meta = reader.metadata
print(f"Title: {meta.title}")
print(f"Author: {meta.author}")
print(f"Subject: {meta.subject}")
print(f"Creator: {meta.creator}")
```

#### Rotate Pages
```python
reader = PdfReader("input.pdf")
writer = PdfWriter()

page = reader.pages[0]
page.rotate(90)  # Rotate 90 degrees clockwise
writer.add_page(page)

with open("rotated.pdf", "wb") as output:
    writer.write(output)
```

### pdfplumber - Text and Table Extraction

#### Extract Text with Layout
```python
import pdfplumber

with pdfplumber.open("document.pdf") as pdf:
    for page in pdf.pages:
        text = page.extract_text()
        print(text)
```

#### Extract Tables
```python
with pdfplumber.open("document.pdf") as pdf:
    for i, page in enumerate(pdf.pages):
        tables = page.extract_tables()
        for j, table in enumerate(tables):
            print(f"Table {j+1} on page {i+1}:")
            for row in table:
                print(row)
```

#### Advanced Table Extraction
```python
import pandas as pd

with pdfplumber.open("document.pdf") as pdf:
    all_tables = []
    for page in pdf.pages:
        tables = page.extract_tables()
        for table in tables:
            if table:  # Check if table is not empty
                df = pd.DataFrame(table[1:], columns=table[0])
                all_tables.append(df)

# Combine all tables
if all_tables:
    combined_df = pd.concat(all_tables, ignore_index=True)
    combined_df.to_excel("extracted_tables.xlsx", index=False)
```

### fpdf2 - Create PDFs (Pure Python)

#### Basic PDF Creation
```python
from fpdf import FPDF

pdf = FPDF()
pdf.add_page()
pdf.set_font('Helvetica', size=16)
pdf.cell(text='Hello World!')
pdf.ln(10)
pdf.set_font('Helvetica', size=12)
pdf.cell(text='This is a PDF created with fpdf2')
pdf.line(10, 50, 150, 50)
pdf.output('hello.pdf')
```

#### Create PDF with Multiple Pages and Tables
```python
from fpdf import FPDF

pdf = FPDF()
pdf.set_auto_page_break(auto=True, margin=15)

# Page 1 - Title and content
pdf.add_page()
pdf.set_font('Helvetica', 'B', 20)
pdf.cell(0, 10, 'Report Title', align='C', new_x='LMARGIN', new_y='NEXT')
pdf.ln(5)
pdf.set_font('Helvetica', size=12)
pdf.multi_cell(0, 7, 'This is the body of the report. ' * 20)

# Page 2 - Table
pdf.add_page()
pdf.set_font('Helvetica', 'B', 14)
pdf.cell(0, 10, 'Data Table', new_x='LMARGIN', new_y='NEXT')
pdf.ln(3)

# Table header
pdf.set_font('Helvetica', 'B', 10)
headers = ['Product', 'Q1', 'Q2', 'Q3', 'Q4']
col_widths = [40, 30, 30, 30, 30]
for i, h in enumerate(headers):
    pdf.cell(col_widths[i], 8, h, border=1, align='C')
pdf.ln()

# Table rows
pdf.set_font('Helvetica', size=10)
data = [
    ['Widgets', '120', '135', '142', '158'],
    ['Gadgets', '85', '92', '98', '105'],
]
for row in data:
    for i, val in enumerate(row):
        pdf.cell(col_widths[i], 8, val, border=1, align='C')
    pdf.ln()

pdf.output('report.pdf')
```

#### Add Images to PDF
```python
from fpdf import FPDF

pdf = FPDF()
pdf.add_page()
pdf.image('chart.png', x=10, y=10, w=100)
pdf.output('with_image.pdf')
```

#### Chinese/Unicode Text Support
```python
from fpdf import FPDF

pdf = FPDF()
pdf.add_page()
# Load a TTF font that supports Chinese characters
pdf.add_font('NotoSansSC', '', '/path/to/NotoSansSC-Regular.ttf', uni=True)
pdf.set_font('NotoSansSC', size=14)
pdf.cell(text='Hello World')
pdf.output('chinese.pdf')
```

## Common Tasks

### Add Watermark
```python
from pypdf import PdfReader, PdfWriter

# Create watermark (or load existing)
watermark = PdfReader("watermark.pdf").pages[0]

# Apply to all pages
reader = PdfReader("document.pdf")
writer = PdfWriter()

for page in reader.pages:
    page.merge_page(watermark)
    writer.add_page(page)

with open("watermarked.pdf", "wb") as output:
    writer.write(output)
```

### Password Protection
```python
from pypdf import PdfReader, PdfWriter

reader = PdfReader("input.pdf")
writer = PdfWriter()

for page in reader.pages:
    writer.add_page(page)

# Add password
writer.encrypt("userpassword", "ownerpassword")

with open("encrypted.pdf", "wb") as output:
    writer.write(output)
```

## Quick Reference

| Task | Best Tool | Command/Code |
|------|-----------|--------------|
| Merge PDFs | pypdf | `writer.add_page(page)` |
| Split PDFs | pypdf | One page per file |
| Extract text | pdfplumber | `page.extract_text()` |
| Extract tables | pdfplumber | `page.extract_tables()` |
| Create PDFs | fpdf2 | `FPDF()` + `output()` |
| Rotate pages | pypdf | `page.rotate(90)` |
| Password protect | pypdf | `writer.encrypt()` |
| Fill PDF forms | pypdf (see FORMS.md) | See FORMS.md |

## Next Steps

- If you need to fill out a PDF form, follow the instructions in FORMS.md
