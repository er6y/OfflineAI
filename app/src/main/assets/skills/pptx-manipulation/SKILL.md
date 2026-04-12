---
name: pptx-manipulation
description: "用 python-pptx 创建和编辑 PowerPoint 演示文稿（幻灯片/文本框/形状/图表/表格/图片）"
---

# PPTX Manipulation Skill

## Overview

This skill enables programmatic creation, editing, and manipulation of Microsoft PowerPoint (.pptx) presentations using the **python-pptx** library. Create professional slides with text, shapes, images, charts, and tables without manual editing.

## How to Use

1. Describe the presentation you want to create or modify
2. Provide content, data, or images to include
3. I'll generate python-pptx code and execute it

**Example prompts:**
- "Create a 10-slide pitch deck from this outline"
- "Add a chart to slide 3 with this data"
- "Extract all text from this presentation"
- "Generate slides from this markdown content"

## Domain Knowledge

### python-pptx Fundamentals

#### Common Imports (COPY THIS BLOCK)
```python
from pptx import Presentation
from pptx.util import Inches, Pt, Emu        # size/position units ONLY
from pptx.dml.color import RGBColor           # WARNING: RGBColor is NOT in pptx.util!
from pptx.enum.shapes import MSO_SHAPE        # shape types (RECTANGLE, ROUNDED_RECTANGLE, etc.)
from pptx.enum.text import PP_ALIGN           # text alignment (LEFT, CENTER, RIGHT, JUSTIFY)
from pptx.chart.data import CategoryChartData # chart data (only if adding charts)
from pptx.enum.chart import XL_CHART_TYPE     # chart types (only if adding charts)
```

> **CRITICAL**: `RGBColor` must be imported from `pptx.dml.color`, NOT from `pptx.util`.
> Wrong: `from pptx.util import Inches, Pt, RGBColor`  ← ImportError!
> Right: `from pptx.dml.color import RGBColor`

```python
# Create new presentation
prs = Presentation()

# Or open existing
prs = Presentation('existing.pptx')
```

### Presentation Structure
```
Presentation
├── slide_layouts (predefined layouts)
├── slides (individual slides)
│   ├── shapes (text, images, charts)
│   │   ├── text_frame (paragraphs)
│   │   └── table (rows, cells)
│   └── placeholders (title, content)
└── slide_masters (templates)
```

### Slide Layouts
```python
# Common layout indices (may vary by template)
TITLE_SLIDE = 0
TITLE_CONTENT = 1
SECTION_HEADER = 2
TWO_CONTENT = 3
COMPARISON = 4
TITLE_ONLY = 5
BLANK = 6

# Add slide with layout
slide_layout = prs.slide_layouts[TITLE_CONTENT]
slide = prs.slides.add_slide(slide_layout)
```

### Adding Content

#### Title Slide
```python
slide_layout = prs.slide_layouts[0]  # Title slide
slide = prs.slides.add_slide(slide_layout)

title = slide.shapes.title
subtitle = slide.placeholders[1]

title.text = "Quarterly Report"
subtitle.text = "Q4 2024 Performance Review"
```

#### Text Content
```python
# Using placeholder
body = slide.placeholders[1]
tf = body.text_frame
tf.text = "First bullet point"

# Add more paragraphs
p = tf.add_paragraph()
p.text = "Second bullet point"
p.level = 0

p = tf.add_paragraph()
p.text = "Sub-bullet"
p.level = 1
```

#### Text Box
```python
from pptx.util import Inches, Pt

left = Inches(1)
top = Inches(2)
width = Inches(4)
height = Inches(1)

txBox = slide.shapes.add_textbox(left, top, width, height)
tf = txBox.text_frame

p = tf.paragraphs[0]
p.text = "Custom text box"
p.font.bold = True
p.font.size = Pt(18)
```

#### Shapes
```python
from pptx.enum.shapes import MSO_SHAPE

# Rectangle
shape = slide.shapes.add_shape(
    MSO_SHAPE.RECTANGLE,
    Inches(1), Inches(2),  # left, top
    Inches(3), Inches(1.5) # width, height
)
shape.text = "Rectangle with text"

# Common shapes:
# MSO_SHAPE.RECTANGLE, ROUNDED_RECTANGLE
# MSO_SHAPE.OVAL, CHEVRON, ARROW_RIGHT
# MSO_SHAPE.CALLOUT_ROUNDED_RECTANGLE
```

#### Images
```python
# Add image
slide.shapes.add_picture(
    'image.png',
    Inches(1), Inches(2),  # position
    width=Inches(4)        # auto height
)

# Or specify both dimensions
slide.shapes.add_picture(
    'logo.png',
    Inches(8), Inches(0.5),
    Inches(1.5), Inches(0.75)
)
```

### Tables
```python
# Create table
rows, cols = 4, 3
left = Inches(1)
top = Inches(2)
width = Inches(8)
height = Inches(2)

table = slide.shapes.add_table(rows, cols, left, top, width, height).table

# Set column widths
table.columns[0].width = Inches(2)
table.columns[1].width = Inches(3)
table.columns[2].width = Inches(3)

# Add headers
headers = ['Product', 'Q3 Sales', 'Q4 Sales']
for i, header in enumerate(headers):
    cell = table.cell(0, i)
    cell.text = header
    cell.text_frame.paragraphs[0].font.bold = True

# Add data
data = [
    ['Widget A', '$10,000', '$12,500'],
    ['Widget B', '$8,000', '$9,200'],
    ['Widget C', '$15,000', '$18,000'],
]
for row_idx, row_data in enumerate(data, 1):
    for col_idx, value in enumerate(row_data):
        table.cell(row_idx, col_idx).text = value
```

### Charts
```python
from pptx.chart.data import CategoryChartData
from pptx.enum.chart import XL_CHART_TYPE

# Chart data
chart_data = CategoryChartData()
chart_data.categories = ['Q1', 'Q2', 'Q3', 'Q4']
chart_data.add_series('Sales', (19.2, 21.4, 16.7, 23.8))
chart_data.add_series('Expenses', (12.1, 15.3, 14.2, 18.1))

# Add chart
x, y, cx, cy = Inches(1), Inches(2), Inches(8), Inches(4)
chart = slide.shapes.add_chart(
    XL_CHART_TYPE.COLUMN_CLUSTERED,
    x, y, cx, cy, chart_data
).chart

# Customize
chart.has_legend = True
chart.legend.include_in_layout = False
```

### Formatting

#### Text Formatting
```python
from pptx.dml.color import RGBColor

run = p.runs[0]
run.font.name = 'Arial'
run.font.size = Pt(24)
run.font.bold = True
run.font.italic = True
run.font.color.rgb = RGBColor(0x00, 0x66, 0xCC)
```

#### Shape Fill & Line
```python
from pptx.dml.color import RGBColor

shape.fill.solid()
shape.fill.fore_color.rgb = RGBColor(0x00, 0x80, 0x00)

shape.line.color.rgb = RGBColor(0x00, 0x00, 0x00)
shape.line.width = Pt(2)
```

#### Paragraph Alignment
```python
from pptx.enum.text import PP_ALIGN

p.alignment = PP_ALIGN.CENTER  # LEFT, RIGHT, JUSTIFY
```

### Layout & Positioning Best Practices

#### Slide Size (16:9 default)
```python
# Default 16:9 = 13.333 x 7.5 inches (= Emu(12192000) x Emu(6858000))
W = prs.slide_width   # total slide width
H = prs.slide_height  # total slide height
```

#### Full-Screen Background Shape
```python
# Use a rectangle shape covering entire slide as background
bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, prs.slide_width, prs.slide_height)
bg.fill.solid()
bg.fill.fore_color.rgb = RGBColor(0x1B, 0x2A, 0x4A)
bg.line.fill.background()  # remove border
```

#### Text Vertical Centering
```python
from pptx.enum.text import MSO_ANCHOR
tf = shape.text_frame
tf.word_wrap = True
tf.auto_size = None
tf.vertical_anchor = MSO_ANCHOR.MIDDLE  # vertical center
```

#### Multi-Column Equal Layout (N columns)
```python
margin = Inches(0.5)
gap = Inches(0.3)
n_cols = 3
usable = prs.slide_width - 2 * margin - (n_cols - 1) * gap
col_w = int(usable / n_cols)
for i in range(n_cols):
    left = margin + i * (col_w + gap)
    shape = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, left, top, col_w, col_h)
```

#### Shape Without Border
```python
shape.line.fill.background()  # transparent border
# or
shape.line.width = 0
```

#### Inner Padding for Text Inside Shapes
```python
tf = shape.text_frame
tf.margin_left = Inches(0.2)
tf.margin_right = Inches(0.2)
tf.margin_top = Inches(0.15)
tf.margin_bottom = Inches(0.15)
```

## Best Practices

1. **Use Templates**: Start with a .pptx template for consistent branding
2. **Layout First**: Plan slide structure before coding
3. **Reuse Slide Masters**: Maintain consistency across presentations
4. **Optimize Images**: Compress images before adding
5. **Test Output**: Always verify generated presentations
6. **Use Blank Layout**: `prs.slide_layouts[6]` for full custom slides
7. **Background Shape First**: Add bg rectangle before text boxes so text stays on top
8. **Vertical Center**: Use `MSO_ANCHOR.MIDDLE` for text inside shapes

## Common Patterns

### Slide Deck Generator
```python
def create_deck(title, slides_content):
    prs = Presentation()
    
    # Title slide
    slide = prs.slides.add_slide(prs.slide_layouts[0])
    slide.shapes.title.text = title
    
    # Content slides
    for slide_data in slides_content:
        slide = prs.slides.add_slide(prs.slide_layouts[1])
        slide.shapes.title.text = slide_data['title']
        
        body = slide.placeholders[1]
        tf = body.text_frame
        for i, point in enumerate(slide_data['points']):
            if i == 0:
                tf.text = point
            else:
                p = tf.add_paragraph()
                p.text = point
    
    return prs
```

### Data-Driven Charts
```python
def add_bar_chart(slide, title, categories, values):
    from pptx.chart.data import CategoryChartData
    from pptx.enum.chart import XL_CHART_TYPE
    
    chart_data = CategoryChartData()
    chart_data.categories = categories
    chart_data.add_series('Values', values)
    
    chart = slide.shapes.add_chart(
        XL_CHART_TYPE.BAR_CLUSTERED,
        Inches(1), Inches(2),
        Inches(8), Inches(4),
        chart_data
    ).chart
    
    chart.chart_title.text_frame.text = title
    return chart
```

## Examples

### Example 1: Create a Pitch Deck
```python
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor       # IMPORTANT: RGBColor lives here, NOT in pptx.util
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import PP_ALIGN

prs = Presentation()

# Slide 1: Title with colored background
slide = prs.slides.add_slide(prs.slide_layouts[6])  # blank layout
bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, prs.slide_width, prs.slide_height)
bg.fill.solid()
bg.fill.fore_color.rgb = RGBColor(0x1B, 0x2A, 0x4A)
bg.line.fill.background()
txBox = slide.shapes.add_textbox(Inches(1), Inches(2.5), Inches(8), Inches(1.5))
tf = txBox.text_frame
p = tf.paragraphs[0]
p.text = "StartupX"
p.font.size = Pt(44)
p.font.bold = True
p.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)
p.alignment = PP_ALIGN.CENTER
p = tf.add_paragraph()
p.text = "Revolutionizing Document Processing"
p.font.size = Pt(20)
p.font.color.rgb = RGBColor(0xCC, 0xCC, 0xCC)
p.alignment = PP_ALIGN.CENTER

# Slide 2: Problem
slide = prs.slides.add_slide(prs.slide_layouts[1])
slide.shapes.title.text = "The Problem"
body = slide.placeholders[1].text_frame
body.text = "Manual document processing costs businesses $1T annually"
p = body.add_paragraph()
p.text = "Average worker spends 20% of time on document tasks"
p.level = 1

# Slide 3: Solution
slide = prs.slides.add_slide(prs.slide_layouts[1])
slide.shapes.title.text = "Our Solution"
body = slide.placeholders[1].text_frame
body.text = "AI-powered document automation"
body.add_paragraph().text = "90% faster processing"
body.add_paragraph().text = "99.5% accuracy"
body.add_paragraph().text = "Works with existing tools"

# Slide 4: Market
slide = prs.slides.add_slide(prs.slide_layouts[5])  # Title only
slide.shapes.title.text = "Market Opportunity: $50B by 2028"

# Add chart
from pptx.chart.data import CategoryChartData
from pptx.enum.chart import XL_CHART_TYPE

data = CategoryChartData()
data.categories = ['2024', '2025', '2026', '2027', '2028']
data.add_series('Market Size ($B)', [30, 35, 40, 45, 50])

slide.shapes.add_chart(
    XL_CHART_TYPE.LINE,
    Inches(1), Inches(1.5),
    Inches(8), Inches(5),
    data
)

prs.save('pitch_deck.pptx')
```

### Example 2: Report with Data Table
```python
from pptx import Presentation
from pptx.util import Inches, Pt

prs = Presentation()

# Title slide
slide = prs.slides.add_slide(prs.slide_layouts[0])
slide.shapes.title.text = "Sales Performance Report"
slide.placeholders[1].text = "Q4 2024"

# Data slide
slide = prs.slides.add_slide(prs.slide_layouts[5])
slide.shapes.title.text = "Regional Performance"

# Create table
table = slide.shapes.add_table(5, 4, Inches(0.5), Inches(1.5), Inches(9), Inches(4)).table

# Headers
headers = ['Region', 'Revenue', 'Growth', 'Target']
for i, h in enumerate(headers):
    table.cell(0, i).text = h
    table.cell(0, i).text_frame.paragraphs[0].font.bold = True

# Data
data = [
    ['North America', '$5.2M', '+15%', 'Met'],
    ['Europe', '$3.8M', '+12%', 'Met'],
    ['Asia Pacific', '$2.9M', '+28%', 'Exceeded'],
    ['Latin America', '$1.1M', '+8%', 'Below'],
]
for row_idx, row_data in enumerate(data, 1):
    for col_idx, value in enumerate(row_data):
        table.cell(row_idx, col_idx).text = value

prs.save('sales_report.pptx')
```

## Limitations

- Cannot render complex animations
- Limited SmartArt support
- No video embedding via API
- Master slide editing is complex
- Chart types limited to standard Office charts

## Installation

```bash
pip install python-pptx
```

## Resources

- [python-pptx Documentation](https://python-pptx.readthedocs.io/)
- [GitHub Repository](https://github.com/scanny/python-pptx)
- [Slide Layout Guide](https://python-pptx.readthedocs.io/en/latest/user/slides.html)
