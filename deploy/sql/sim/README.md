# 新业务模拟模块 — 数据库脚本

> 生成日期: 2026-10-27
> 数据源: 生产库 `prcp_db` (43.143.253.186) 实际表结构与数据
> 引擎口径: 对齐 Python `new_business/engine.py` v2.4 (commit `fe514d3`)

## 一、文件说明

| 文件 | 行数 | 大小 | 说明 |
|---|---|---|---|
| `01_sim_schema.sql` | 382 | 21 KB | **表结构**：7 张表的 `DROP TABLE` + `CREATE TABLE` |
| `02_sim_test_data.sql` | 863 | 696 KB | **测试数据**：`INSERT` 语句（一条一行，便于 review） |

## 二、执行方式

```bash
# 1) 建表 (⚠️ 会 DROP 并重建这 7 张表)
mysql -uprcp -pPrcp@2026 prcp_db < 01_sim_schema.sql

# 2) 灌测试数据
mysql -uprcp -pPrcp@2026 prcp_db < 02_sim_test_data.sql
```

> ⚠️ **外键依赖**：`prcp_sim_scheme.coa_scheme_id → prcp_coa_scheme.id`
> 新库需先存在 `prcp_coa_scheme` 表且有 `id=1`（中信银行账户册方案 ZX_COA）。
> 脚本内已用 `SET FOREIGN_KEY_CHECKS = 0/1` 包裹，执行顺序不受影响。

## 三、表清单与职责

| # | 表 | 职责 | 关键字段 |
|---|---|---|---|
| 1 | `prcp_sim_scheme` | 测算方案 | `coa_scheme_id` / `data_date`(基准月) / `horizon_months` / `algorithm` / `config_node_count` |
| 2 | `prcp_sim_node_config` | 节点参数配置 | `is_model_calc`(1=模型测算 2=存量保持) / `rhythm_type`(1=线性 2=自定义) / `annual_growth_rate` |
| 3 | `prcp_sim_term_ratio` | 期限占比明细 | `term_value`(月) / `business_ratio`(%) / `interest_rate`(%) — **应用层校验合计=100%** |
| 4 | `prcp_sim_rhythm` | 12 月投放节奏权重 | `month_no`(1~12) / `weight`(%) — 唯一键 `uk_config_month(config_id, month_no)` |
| 5 | `prcp_sim_growth_config` | 新业务增长配置 | `growth_type`(1~4) / `year1_value`~`year5_value` / `amount_unit`(WAN/YI) — 唯一键 `uk_config(config_id)` |
| 6 | `prcp_sim_run` | 引擎运行记录 | `status`(PENDING/RUNNING/SUCCESS/FAILED/CANCELLED) / `progress` / `duration_sec` |
| 7 | `prcp_sim_result` | 引擎结果快照 | 128 桶 + 4 主指标 + **7 追踪列** |

### 7 张表的关系

```
prcp_sim_scheme (1) ──┬── (N) prcp_sim_node_config
                      │            │
                      │            ├── (N) prcp_sim_term_ratio   (config_id)
                      │            ├── (1) prcp_sim_rhythm        (config_id, 12 行)
                      │            └── (1) prcp_sim_growth_config (config_id)
                      │
                      └── (N) prcp_sim_run ── (N) prcp_sim_result
```

## 四、结果表关键口径（v2.4）

### 128 桶构成

| 桶 | 数量 | 语义 | 行为 |
|---|---|---|---|
| `orig_m1`~`orig_m60` | 60 | 原始期限累计投放额 | **只进不出**（不随月份变化） |
| `orig_y10/y15/y20/y30` | 4 | 原始期限长端 (>60 月) | 同上 |
| `rem_m1`~`rem_m60` | 60 | 距基准日第 k 个月到期的金额 | **绝对到期月口径** |
| `rem_y10/y15/y20/y30` | 4 | 长端到期 (10Y/15Y/20Y/30Y) | 同上 |

**关键规则**：
- 第 M 月投放期限 v 的业务 → 直接落 `rem_m{M+v}`（**不每月前移**）
- 第 M 月的到期释放 = 取走 `rem_m{M}` 并清零
- 基准日时「剩余期限」恰好等于「距基准日到期月数」，故存量数据无需转换

### 7 个追踪列（v2.1 / v2.3 / v2.4）

| 列 | 类型 | 含义 |
|---|---|---|
| `growth_type` | tinyint(1) | 增长方式 1~4 |
| `year_target` | decimal(20,4) | 当年目标增量 (元) |
| `rhythm_weight` | decimal(8,4) | 当月节奏权重 (%) |
| `month_add` | decimal(20,4) | 当月净新增投放 (元) |
| `month_release` | decimal(20,4) | 当月到期释放 (元) |
| `planned_due` | decimal(20,4) | 计划到期额（take_due 前快照） |
| `cum_planned` | decimal(20,4) | 累计计划投放 |

### 投放量公式

```
add          = year_target × rhythm_weight / 100
year_target  = 1/3 → 基数 × 增速%
             = 2   → 参数 × 单位换算
             = 4   → 参数 × 单位换算 − 年初余额（可为负，缩表）
month_new    = 目标类(1/2): release + add
             = 非目标类(3/4): add
new_cb       = base_balance + add
new_ab       = avg_balance + add / 2
new_wr       = (avg_balance × weighted_rate + Σ(amount × ir/100)) / (avg_balance + month_new)
new_ia       = new_ab × new_wr / 12
```

> ⚠️ `interest_rate` 存百分数（3.5 = 3.5%），`weighted_rate` 存小数（0.045 = 4.5%），
> 加权前必须 `/100` 换算，否则月利息虚高 100 倍。

## 五、测试数据范围

| 表 | 行数 | 抽样策略 |
|---|---|---|
| `prcp_sim_scheme` | 4 | 全量 |
| `prcp_sim_node_config` | 38 | 全量 |
| `prcp_sim_term_ratio` | 128 | 全量 |
| `prcp_sim_rhythm` | 444 | 全量（37 config × 12 月） |
| `prcp_sim_growth_config` | 37 | 全量 |
| `prcp_sim_run` | 15 | 全量（含 SUCCESS / FAILED 样本） |
| `prcp_sim_result` | **116** | **抽样**：<br/>· `date_offset=1` 全部 57 节点 → 验证节点矩阵 / 驾驶舱 KPI 汇总<br/>· 代表性节点 `11` (S010201000000) 60 月 → 验证趋势 / 到期排期 / 投放分解 |

> 生产库 `prcp_sim_result` 共 41040 行（12 个 run × 3420 行），全量导出会达 7 MB，
> 故按上述策略抽样，覆盖「节点矩阵」与「时间序列」两种视角。

### 数据说明

- 保留了 `is_deleted=1` 的历史行（配置更新时「软删 + 插入」产生的残留），引擎与所有查询均带 `is_deleted=0` 过滤，不影响功能
- 已知：`config_id=1`（scheme 6）有 2 行软删残留，故该 config 的占比合计显示 200%，**有效行合计为 100%**

## 六、验证记录

已在临时库 `prcp_sim_verify` 实测：

```
01_sim_schema.sql       exit=0   7 张表全部创建成功
02_sim_test_data.sql    exit=0   846 行数据全部导入成功

表行数校验:
  prcp_sim_scheme         4      prcp_sim_rhythm          444
  prcp_sim_node_config    38     prcp_sim_growth_config   37
  prcp_sim_term_ratio    128     prcp_sim_run              15
                                   prcp_sim_result         116

约束校验:
  ✓ is_model_calc 分布: 模型测算 37 (线性 16 + 自定义 21) / 存量保持 1
  ✓ 有效 term_ratio 占比合计全部 = 100%
  ✓ 12 月节奏权重合计全部 = 100% (±0.1)
  ✓ result 追踪列非空 115 行
  ✓ rem 桶非零 114 行 (证明绝对到期月落桶生效)
```

## 七、相关文档

| 文档 | 路径 |
|---|---|
| 差异分析 | `prcp-java/docs/design/04-新业务模拟_原系统vsJava差异分析.md` |
| 引擎契约 | `PRCP-Java/docs/api/reverse-engine-api.md` |
| 算法测试 | `PRCP-Java/prcp-business/src/test/java/com/prcp/business/engines/new_business/BucketMathV24Test.java` |
| Python 引擎源码 | `PRCP/backend/app/services/calculate_engine/new_business/engine.py` |
