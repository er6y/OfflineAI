---
name: stockquant
description: "A股量化一站式：选股 + 持仓策略 + 个股分析（代码/名称查询，返回行情+日K+15分钟K+资金面+板块+公告）。Python 出数据+hint，LLM 按 [TASK] 推导决策。"
---

# Stockquant Skill

A 股一站式：**选股 / 持仓策略 / 个股分析**。Python 只出**数据 + 对象化 hint 矩阵**，LLM 按 `[TASK]` 过程化推导决策。

> ⛔ **严禁 read_file 本脚本源码 / README**（6000+ 行，浪费 context）；下面子命令复制即可。

## 三大策略引擎

| 引擎 | 思路 | 适用场景 |
|---|---|---|
| **C 板块滞涨股** | 近 5 日板块涨幅 Top5 × 板块内涨幅后 40% 的滞涨股 | 板块轮动第二波补涨 |
| **D 主力资金累积** | 5 日累计主力净流入 > 0 + 均线多头 + 健康换手 | 跟随聪明钱多日建仓 |
| **E 60 日箱体突破** | 60 日窄箱（<25%）+ 放量突破箱体上沿 | 长期横盘后大级别启动 |

## 子命令

### 1. 选股（PICK_BUY）

```sh
python ${SKILL_DIR}/stockquant/scripts/stockquant.py --capital <元> --market main --top 30
```

默认同跑 C/D/E。读输出 `═══ ## NEXT_STEP ═══`：`[STATE] / [DATA] / [TASK]`，按 `[TASK]` S1~S6 执行。状态位 `should_terminate` / `session_not_for_entry` 命中即 terminate，不入场。

参数速查：`--capital`(默认 10000)  `--market`(main/all/gem/star/sh/sz，默认 main)  `--top`(默认 30)  `--strategy`(C,D,E 任意子集，默认三个都跑)。其他阈值已固化。

> **策略原理 / 三引擎流程图 / 统一打分公式** 见 `README.md` §选股（PICK_BUY）。

### 2. 持仓策略（SELL）

```sh
python ${SKILL_DIR}/stockquant/scripts/stockquant.py sell-plan \
  <code>:<qty>/<avail>@<cost> [...]
# 例：sell-plan 000949:1200/800@7.97 002324:500/500@17.11
```

参数：`qty`=总持仓 / `avail`=T+1 可卖 / `cost`=成本价（全部必填）；`--target-yuan`(默认 10000，单笔目标总价 ≈1万元控手续费占比)。

读输出 `═══ SELL_PLAN Phase 1/2 ═══`：`[REGIME_CLASSIFY]` 表已给每只**默认建议 --order**，按 `=== NEXT_STEP ===` 提示走即可（默认拷贝 → 必要时按 S2/S3 微调或 override → 调 Phase 2 验证）。

> **策略原理 / 11 档 regime 流程图 / 震荡对冲 / 止损定价逻辑** 见 `README.md` §持仓策略（SELL）。

### 3. 个股分析（ANALYZE）—— 用户主动查询

```sh
python ${SKILL_DIR}/stockquant/scripts/stockquant.py analyze <query> [<query>...]
# 例：analyze 600519 茅台 比亚迪 002594
```

查询词：6 位代码 / 中文名（完整或简称，`茅台` → `贵州茅台`）。一次可传多只，同码自动 dedup，未匹配名回显在末尾不阻断。**LLM 不要传任何 flag**，参数已固化。

输出：大盘背景 + 每只个股的多维原始数据（基本/行情/日K/15分K/资金/板块/公告），末尾一段 `═══ ANALYSIS_HINT ═══` 给出解读框架。LLM 照着 hint 结合用户 prompt 分析即可；Python 不给买卖结论。

> **数据维度 / 解读框架 / 常见用户意图回答套路** 见 `README.md` §个股分析（ANALYZE）。
