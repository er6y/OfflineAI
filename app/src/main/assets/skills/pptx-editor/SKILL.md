---
name: pptx-editor
description: 用 pptx_helper.py 构造 PPT。流程 new-deck → 多次 add-* 一条一页 → 可选 inspect/set-text。所有参数有硬上限，超了自动截断 + [WARN]，不报错。
---

# Pptx-Editor

每条命令前缀：`python ${SKILL_DIR}/pptx-editor/scripts/pptx_helper.py`

## 命令（一条 add-* = 一页）

| 命令 | 用途 |
|---|---|
| `new-deck FILE.pptx --theme T [--template PATH]` | 起空 deck，锁 theme；可选 `--template` 继承用户 .pptx/.potx 母版（logo / 字体 / 已有页都保留，新内容追加在后） |
| `add-cover FILE --title "T" [--subtitle "S"] [--meta "M"]` | 封面页 |
| `add-toc FILE --item "..." [--item ...]` | 目录（3..6 项） |
| `add-section FILE --title "T"` | 章节分隔 |
| `add-text FILE --title "T" --bullet "..." [--bullet ...]` | bullets（1..5 项） |
| `add-table FILE TITLE HEADERS ROW1 ROW2 ...` | 表（≤5 列 × 8 行；位置参形态，与 xlsx `add-sheet` 对齐） |
| `add-stats FILE --title "T" --stat "Val;Label" [--stat ...]` | 数字 KPI（2..4 项） |
| `add-compare FILE --title "T" --left "A;p1;p2" --right "B;p1;p2"` | 左右对照 |
| `add-timeline FILE --title "T" --step "..." [--step ...]` | 时间线（3..5 步） |
| `add-summary FILE --title "T" --takeaway "..." [--takeaway ...]` | 总结（1..5 项） |
| `add-text-table FILE ...` / `add-text-image FILE ...` | 文字+表 / 文字+图 |
| `inspect FILE` / `extract FILE` | 看结构 / 纯文本 |
| `set-text FILE SLIDE SHAPE "新文字"` | 改 shape 文字（保留原格式） |
| `set-cell FILE SLIDE SHAPE ROW COL "新值"` | 改表格单元格 |

## Theme（new-deck 时锁定，全 deck 不变）

| theme | 适用 |
|---|---|
| `business` | 企业报告 |
| `tech` | 科技 / AI / 产品 |
| `wellness` | 健康 / 医疗 / 教育 |
| `elegant` | 奢侈品 / 咨询 |
| `education` | 培训 / 统计 |
| `platinum` | 金融 / 高端 |

## 主路径（inline batch，**首选**）

**一次 `python` action 内**用 `runpy` 串起所有 helper 调用，省轮数省 context。本端 Python 是 Chaquopy 嵌入解释器（**没有 subprocess / 没有 `python` 可执行**）。`${SKILL_DIR}` / `${WORKSPACE}` 是 Kotlin 端字符串替换，**直接写字面量**：

```python
import sys, runpy
HELPER = "${SKILL_DIR}/pptx-editor/scripts/pptx_helper.py"
DECK   = "${WORKSPACE}/deck.pptx"

def run(*args):
    sys.argv = [HELPER, *args]
    try:
        runpy.run_path(HELPER, run_name="__main__")
    except SystemExit as e:
        if e.code:
            print(f"FAILED: {args[0]} exit={e.code}")
            raise

run("new-deck",    DECK, "--theme", "business")  # 或加 "--template", "${WORKSPACE}/corp.potx"
run("add-cover",   DECK, "--title", "Q1 周报", "--subtitle", "2026-04-27")
run("add-table",   DECK, "价格", "产品,价格", "QLC,$27", "TLC,$29")  # 位置参 TITLE HEADERS ROW...
run("add-summary", DECK, "--title", "结论", "--takeaway", "...", "--takeaway", "...")
run("inspect",     DECK)  # 自校验
```

**坑点**：
- 不要 `os.environ["WORKSPACE"]`（KeyError）；不要 `subprocess.run([sys.executable, ...])`（Chaquopy 没 python 可执行）。
- 任一 helper 失败会 `sys.exit(N)` → `SystemExit` raise → 整批立停。
- 结尾 `inspect` 自校验，模型读了再决定要不要补 `set-text` / `set-cell`。

简单任务（≤2 页）才一条条直接发独立 python action。

## 硬约束（自动截断，不报错）

单页 title ≤40 字符；bullets / takeaways 1..5 each ≤80；stats 2..4；表 ≤5 列 × 8 行 cell ≤24；timeline 3..5；toc 3..6；compare 每边 2..5。

## 铁律

1. 必须走 `pptx_helper.py`，禁止 `from pptx import ...`。
2. 一条 `add-*` = 一页；分页内容靠多次调用。
3. theme 在 `new-deck` 锁定，全 deck 不变。
4. 改已有 deck 必先 `inspect`，按 (slide,shape) 操作。
5. **>2 页 helper 调用必须用主路径的 inline batch (runpy) 形态**，不要拆成多个独立 python action。
