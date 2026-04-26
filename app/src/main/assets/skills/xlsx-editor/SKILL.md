---
name: xlsx-editor
description: 用 xlsx_helper.py 增量构造 Excel。流程 new-book → 多次 add-sheet/add-row → 可选 set-cell。永远写公式，绝不内联 JSON。
---

# Xlsx-Editor

每条命令前缀：`python ${SKILL_DIR}/xlsx-editor/scripts/xlsx_helper.py`

## 命令

| 命令 | 用途 |
|---|---|
| `new-book FILE` | 起空 workbook |
| `add-sheet FILE NAME HEADERS ROW1 ROW2 ...` | 加 sheet（位置参，每个 ROW 是 CSV 字符串） |
| `add-row FILE SHEET "v1,v2,..." [--at IDX]` | 给现有 sheet 追加 / 覆盖一行 |
| `add-assumptions FILE --name N --kv "Item;Val;Src" ...` | 3 列假设表 |
| `add-dashboard FILE --name N --kpi "=Formula;Label" ...` | KPI 卡片 |
| `set-cell FILE SHEET A1 VALUE [--numfmt F]` | 改单格（`=` = 公式；纯数字 = 数字；其余 = 文本） |
| `set-range FILE SHEET A1 "v1,v2,v3" ["v4,v5,v6" ...]` | 批量写矩形区域；每个 ROW 是 CSV 串；`=` 开头 = 公式；空单元格保留原值 |
| `inspect FILE [--sheet S]` | 看结构 + 公式 |
| `extract FILE [--sheet S]` | 导出 TSV 文本 |

可选旗标：`--title` `--totals "Total,=SUM(...),..."` `--numfmt B:0.0%` `--width A:18` `--header-fill HEX` `--allow-dup-rename`（重名改 `_2`）。

## 主路径（inline batch，**首选**）

**一次 `python` action 内**用 `runpy` 串起所有 helper 调用，省轮数省 context。本端 Python 是 Chaquopy 嵌入解释器（**没有 subprocess / 没有 `python` 可执行**）。`${SKILL_DIR}` / `${WORKSPACE}` 是 Kotlin 端字符串替换，**直接写字面量**：

```python
import sys, runpy
HELPER = "${SKILL_DIR}/xlsx-editor/scripts/xlsx_helper.py"
WB     = "${WORKSPACE}/book.xlsx"

def run(*args):
    sys.argv = [HELPER, *args]
    try:
        runpy.run_path(HELPER, run_name="__main__")
    except SystemExit as e:
        if e.code:
            print(f"FAILED: {args[0]} exit={e.code}")
            raise

run("new-book",  WB)
run("add-sheet", WB, "Q3",
    "Region,Jan,Feb,Mar,Q1",
    "NA,100,110,120,=SUM(B2:D2)",
    "EU,80,85,90,=SUM(B3:D3)")
run("add-row",   WB, "Q3", "Asia,60,70,75,=SUM(B4:D4)")
run("set-range", WB, "Q3", "A5", "Total,=SUM(B2:B4),=SUM(C2:C4),=SUM(D2:D4),=SUM(E2:E4)")
run("inspect",   WB)  # 自校验：结构打回来给模型看
```

**坑点**：
- 不要 `os.environ["WORKSPACE"]`（KeyError）；不要 `subprocess.run([sys.executable, ...])`（Chaquopy 没 python 可执行）。
- 任一 helper 失败会 `sys.exit(N)` → `SystemExit` raise → 整批立停。
- 结尾 `inspect` 自校验，模型读了再决定要不要补 `set-cell` / `set-range`。

简单任务（≤2 条）才一条条直接发独立 python action。

## 行号约定

- 没传 `--title`：第 1 行 = headers，第 2 行起为数据。
- 传了 `--title`：第 1 行 title，第 2 行空，第 3 行 headers，第 4 行起为数据。

## 公式速查

| 用途 | 公式 |
|---|---|
| 求和 | `=SUM(B2:B10)` / `=SUMIFS(B2:B10,A2:A10,"NA")` |
| 跨 sheet | `=Inputs!$B$5` |
| 兜底 | `=IFERROR(A2/B2, 0)` |

常用 numfmt：`#,##0` / `$#,##0` / `0.0%` / `yyyy-mm-dd`。

## 铁律

1. 必须走 `xlsx_helper.py`，禁止 `from openpyxl import ...` / `pandas`。
2. 永远写公式，不要在 Python 里算好再 hardcode。
3. 一个任务一个 .xlsx 路径，不中途换名。
4. 改已有文件先 `inspect` 再 `set-cell` / `set-range`。
5. `set-range` 用 CSV 字符串（每个位置参一行），不要 JSON。
6. **>2 条 helper 调用必须用主路径的 inline batch (runpy) 形态**，不要拆成多个独立 python action。
