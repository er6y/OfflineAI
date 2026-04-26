---
name: docx-editor
description: 用 docx_helper.py 增量构造 Word。流程 new-doc → 多次 add-* → 可选 set-paragraph/set-cell。
---

# Docx-Editor

每条命令前缀：`python ${SKILL_DIR}/docx-editor/scripts/docx_helper.py`

## 命令

| 命令 | 用途 |
|---|---|
| `new-doc FILE.docx [--preset cjk\|business\|academic\|report]` | 起空文档，锁 preset（默认 cjk） |
| `add-title FILE --text "T" [--subtitle "S"]` | 顶部标题（仅一次） |
| `add-heading FILE --text "Section" [--level 1..9]` | 标题（默认 level 1） |
| `add-paragraph FILE --text "body..." [--style S] [--align left\|center\|right\|justify]` | 正文段 |
| `add-list FILE --type bullet\|number --item "..." [--item ...]` | 列表（1..50 项，每项 ≤200 字符） |
| `add-table FILE --headers "a,b" --row "1,2" [--row ...]` | 表（最多 10 列 × 100 行，单元格 ≤500） |
| `add-image FILE --path P [--width-in N]` | 嵌入图（默认 5 寸） |
| `add-page-break FILE` | 分页 |
| `inspect FILE` | 列出每段每表 cell 的 idx |
| `extract FILE` | 纯文本 dump |
| `set-paragraph FILE IDX "new" [--style S]` | 改段落（保留原 run 格式） |
| `set-cell FILE TABLE_IDX ROW COL "new"` | 改表格单元格 |

`add-*` 系列均支持位置参形式：`add-title FILE "Title" "Subtitle"`、`add-paragraph FILE "body"`、`add-table FILE "h1,h2" "v1,v2" "v3,v4"` 等。

## 主路径（inline batch，**首选**）

**一次 `python` action 内**用 `runpy` 串起所有 helper 调用，省轮数省 context。本端 Python 是 Chaquopy 嵌入解释器（**没有 subprocess / 没有 `python` 可执行**）。`${SKILL_DIR}` / `${WORKSPACE}` 是 Kotlin 端字符串替换，**直接写字面量**：

```python
import sys, runpy
HELPER = "${SKILL_DIR}/docx-editor/scripts/docx_helper.py"
DOC    = "${WORKSPACE}/doc.docx"

def run(*args):
    sys.argv = [HELPER, *args]
    try:
        runpy.run_path(HELPER, run_name="__main__")
    except SystemExit as e:
        if e.code:
            print(f"FAILED: {args[0]} exit={e.code}")
            raise

run("new-doc",       DOC, "--preset", "cjk")
run("add-title",     DOC, "Q1 周报", "2026-04-27")             # 位置参
run("add-heading",   DOC, "1", "Executive Summary")
run("add-paragraph", DOC, "整体平稳...")
run("add-table",     DOC, "产品,价格", "QLC,$27.00", "TLC,$29.00")  # HEADERS ROW1 ROW2 ...
run("inspect",       DOC)  # 自校验
```

**坑点**：
- 不要 `os.environ["WORKSPACE"]`（KeyError）；不要 `subprocess.run([sys.executable, ...])`（Chaquopy 没 python 可执行）。
- 任一 helper 失败会 `sys.exit(N)` → `SystemExit` raise → 整批立停。
- 结尾 `inspect` 自校验，模型读了再决定要不要补 `set-paragraph` / `set-cell`。

简单任务（≤2 条）才一条条直接发独立 python action。

## Preset（new-doc 时锁定，全文不可改）

| preset | 适用 |
|---|---|
| `cjk` | 中文（A4 + 微软雅黑） |
| `business` | 英文企业（US Letter + Calibri 深蓝） |
| `academic` | 学术（US Letter + Times New Roman 12pt） |
| `report` | 正式报告（US Letter + Arial 深红） |

## 铁律

1. 必须走 `docx_helper.py`，禁止 `from docx import ...`。
2. 流程是 `new-doc → add-* × N`，没有 outline.json。
3. 一个任务一个 .docx 路径，不中途换名。
4. 改已有 doc 必先 `inspect`，按 idx 操作；不要 `paragraph.text=`。
5. **>2 条 helper 调用必须用主路径的 inline batch (runpy) 形态**，不要拆成多个独立 python action。
