# PRCP 模块 — 数据库全量脚本

> 生成日期: 2026-10-27
> 数据源: 生产库 `prcp_db` (43.143.253.186)
> 覆盖范围: PRCP 模块 **49 张表**（45 张业务表 + 字典 2 张 + 3 张大表）
> 引擎口径: 新业务模拟对齐 Python `new_business/engine.py` v2.4 (commit `fe514d3`)

## 一、文件说明

| 文件 | 行数 | 大小 | 说明 |
|---|---|---|---|
| `01_prcp_schema.sql` | 1,831 | 89 KB | **表结构**：49 张表的 `DROP TABLE` + `CREATE TABLE` |
| `02_prcp_data_core.sql` | 6,780 | 3.9 MB | **核心数据**：46 张小表全量 |
| `03_prcp_data_extend.sql` | 9,343 | 7.7 MB | **大表数据**：3 张大表（1 全量 + 2 抽样） |

## 二、执行方式

```bash
# 1) 建表 (⚠️ 会 DROP 并重建这 49 张表)
mysql -uprcp -pPrcp@2026 prcp_db < 01_prcp_schema.sql

# 2) 核心数据
mysql -uprcp -pPrcp@2026 prcp_db < 02_prcp_data_core.sql

# 3) 大表数据
mysql -uprcp -pPrcp@2026 prcp_db < 03_prcp_data_extend.sql
```

> ⚠️ **执行前务必备份**：
> ```bash
> mysqldump -uprcp -pPrcp@2026 prcp_db prcp_% > prcp_backup_$(date +%Y%m%d).sql
> ```
> ⚠️ 脚本用 `SET FOREIGN_KEY_CHECKS = 0/1` 包裹，执行顺序不受外键约束影响。
> ⚠️ `01` 可重复执行（DROP + CREATE 幂等）。

## 三、表清单（49 张）

### A 账户册（2 张）

| 表 | 说明 |
|---|---|
| `prcp_coa_scheme` | 账户册方案（ZX_COA 中信银行方案） |
| `prcp_coa_node` | 账户册节点（树形，57 个节点 / 6 层） |

### B 基础数据（3 张）

| 表 | 说明 |
|---|---|
| `prcp_data_basic` | 基础数据表（152 列 = 24 元数据 + 128 桶） |
| `prcp_data_balance` | 资产负债表（24 月缺口，当前为空表） |
| `prcp_data_maint_value` | 数据维护值（损益/非息/市场参数，当前为空表） |

### C 指标（7 张 + 1 大表）

| 表 | 说明 |
|---|---|
| `prcp_kpi_definition` | 指标定义（含公式） |
| `prcp_kpi_value` | 指标值（按日期 + 版本） |
| `prcp_kpi_scheme` | 指标方案（SCH_REG_2026 等） |
| `prcp_kpi_score_rule` | 评分规则（15 条，PIECEWISE / LINEAR） |
| `prcp_kpi_score_segment` | 评分分段区间（1028 段，110 段已回填 `score_min`） |
| `prcp_kpi_calc_rule` | 指标取数逻辑（当前为空表） |
| `prcp_metric_item` | 6 类指标项定义 |
| `prcp_metric_coefficient` | **指标计量系数**（8,281 行 / 4.7 MB — 驾驶舱指标矩阵数据源） |

### D 模型（6 张）

`prcp_model` · `prcp_model_param` · `prcp_model_version` · `prcp_model_train` · `prcp_model_train_log` · `prcp_model_train_result`

### E 反算（6 张 + 1 大表）

| 表 | 说明 |
|---|---|
| `prcp_reverse_scheme` | 组合反算方案 |
| `prcp_reverse_target` | 反算目标指标 |
| `prcp_reverse_run` | 反算执行记录 |
| `prcp_reverse_run_log` | 反算日志 |
| `prcp_reverse_result` | 反算结果（每期预测值） |
| `prcp_data_reverse` | **反算结果查询表**（4,491 行 / 13.4 MB — 抽样） |

### F 新业务模拟（7 张）

`prcp_sim_scheme` · `prcp_sim_node_config` · `prcp_sim_term_ratio` · `prcp_sim_rhythm` · `prcp_sim_growth_config` · `prcp_sim_run` · `prcp_sim_result`（**抽样**）

### G 指标参数补录（6 张）

`prcp_cet1_param` · `prcp_lcr_param` · `prcp_nim_param` · `prcp_nsfr_param` · `prcp_roe_param` · `prcp_eve_param`

### H 利率（2 张）

`prcp_rate_scheme` · `prcp_rate_point`

### I ESG（4 张）

`prcp_esg_scheme` · `prcp_esg_scenario` · `prcp_esg_run` · `prcp_esg_curve_point`（Svensson 6 参数曲线）

### J 报表（3 张）

`prcp_rpt_report` · `prcp_rpt_item` · `prcp_rpt_value`

### K 字典（2 张）

`sys_dict` · `sys_dict_item`（PRCP 通用字典，被各模块引用）

## 四、抽样说明

生产库 3 张大表合计 **169 MB**，全量导出会超 150 MB，按用途抽样：

| 表 | 生产 | 脚本 | 抽样策略 | 覆盖场景 |
|---|---|---|---|---|
| `prcp_metric_coefficient` | 8,281 | **8,281** | 全量 | 驾驶舱指标矩阵 / 趋势图（必需） |
| `prcp_data_reverse` | 4,491 | 900 | `run_id=31` 全部 | 反算驾驶舱节点矩阵 / KPI 汇总 |
| `prcp_sim_result` | 41,040 | 116 | `date_offset=1` 全部节点 + 节点 11 的 60 月 | 新业务模拟趋势 / 到期排期 / 投放分解 |

> 其余 46 张表**行数与生产完全一致**。

## 五、关键业务口径

### 剩余期限桶（128 桶）

| 桶 | 语义 | 行为 |
|---|---|---|
| `orig_m1`~`orig_m60` + `orig_y10/y15/y20/y30` | 原始期限累计投放额 | **只进不出**（不随月份变化） |
| `rem_m1`~`rem_m60` + `rem_y10/y15/y20/y30` | 距基准日第 k 个月到期的金额 | **绝对到期月口径** |

**关键规则**：第 M 月投放期限 v → 直接落 `rem_m{M+v}`（不每月前移）；第 M 月到期释放 = 取走 `rem_m{M}` 并清零。

### 指标评分段内插值（`score_min`）

```
score = score_min + (x − min_value) / (max_value − min_value) × (score − score_min)
```
四个条件同时满足才插值，否则退化为阶梯评分：
`score_min NOT NULL` && `min_value NOT NULL` && `max_value NOT NULL` && `max_value > min_value`

### 投放量公式

```
add         = year_target × rhythm_weight / 100
year_target = 1/3 → 基数 × 增速%   |  2 → 参数 × 单位换算   |  4 → 参数 × 单位换算 − 年初余额
month_new   = 目标类(1/2): release + add   |   非目标类(3/4): add
```

> ⚠️ `interest_rate` 存百分数（3.5 = 3.5%），`weighted_rate` 存小数（0.045 = 4.5%），加权前必须 `/100`。

## 六、验证记录

临时库 `prcp_full_verify` 实测：

```
01_prcp_schema.sql      exit=0   49 张表全部创建成功
02_prcp_data_core.sql   exit=0   46 张表数据全部导入
03_prcp_data_extend.sql exit=0   3 张大表导入成功

表数量:  49
行数比对: 47 张与生产完全一致
          仅 2 张有意抽样 (data_reverse 900/4491, sim_result 116/41040)

关键表完整性:
  prcp_coa_node            57      prcp_metric_coefficient   8281
  prcp_data_basic          57      prcp_esg_scenario           14
  prcp_kpi_score_segment  1028      prcp_esg_curve_point      613
  prcp_sim_rhythm         444

幂等性: 重复执行 01 → exit=0，表数仍为 49
```

## 七、已知事项

| 事项 | 说明 |
|---|---|
| 空表 | `prcp_data_balance` / `prcp_data_maint_value` / `prcp_kpi_calc_rule` / `prcp_model_train*` 生产库本身为空，脚本同样为空 |
| 软删历史行 | 保留 `is_deleted=1` 的行（配置更新时「软删+插入」残留），引擎与所有查询均带 `is_deleted=0` 过滤 |
| `prcp_esg_scenario` | 含 `longblob` 字段（`paths_blob`），脚本已用 `grep -a` 处理二进制内容 |
| 备份表 | `prcp_data_basic_bucket_bak_20261021` / `prcp_kpi_score_segment_bak_20261022` **未导出**（历史备份，非业务数据） |
| 系统表 | `sys_login_log` / `sys_op_log` / `sys_role` / `sys_user` / `sys_user_role` **未导出**（登录鉴权，不属于 PRCP 业务域） |

## 八、相关文档

| 文档 | 路径 |
|---|---|
| 新业务模拟差异分析 | `prcp-java/docs/design/04-新业务模拟_原系统vsJava差异分析.md` |
| 新业务模拟专项脚本 | `deploy/sql/sim/` |
| 引擎 HTTP 契约 | `PRCP-Java/docs/api/reverse-engine-api.md` |
| 算法对齐测试 | `PRCP-Java/prcp-business/src/test/java/com/prcp/business/engines/new_business/BucketMathV24Test.java` |
| Python 引擎源码 | `PRCP/backend/app/services/calculate_engine/new_business/engine.py` |
