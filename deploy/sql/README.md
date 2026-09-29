# PRCP 数据库脚本说明

## 文件清单

| 文件 | 行数 | 大小 | 用途 |
|---|---|---|---|
| `01_schema_all.sql` | ~1460 | ~68 KB | 全库 46 张表的 CREATE TABLE 语句 |
| `02_sample_data.sql` | ~4570 | ~6.5 MB | 13 张核心表的样本数据 INSERT |
| `upgrade_sys_audit.sql` | — | ~2.5 KB | 增量升级：新增 sys_op_log / sys_login_log |
| `insert_dashboard_history.sql` | — | ~9.5 KB | 增量升级：Dashboard 5 指标 24 月历史数据 |

## 数据库信息

| 项 | 值 |
|---|---|
| 数据库名 | `prcp_db` |
| 字符集 | `utf8mb4` |
| 排序规则 | `utf8mb4_0900_ai_ci` |
| 引擎 | `InnoDB` |

## 46 张表分类

### 系统表（8 张）
- `sys_user`、`sys_role`、`sys_user_role`
- `sys_dict`、`sys_dict_item`
- `sys_op_log`、`sys_login_log`

### 账户册 / 基础数据（6 张）
- `prcp_coa_scheme`、`prcp_coa_node`
- `prcp_data_basic`、`prcp_data_balance`
- `prcp_data_maint_value`、`prcp_data_reverse`

### 报表 / 指标（10 张）
- `prcp_rpt_report`、`prcp_rpt_item`、`prcp_rpt_value`
- `prcp_kpi_scheme`、`prcp_kpi_definition`、`prcp_kpi_value`
- `prcp_kpi_calc_rule`、`prcp_kpi_score_rule`、`prcp_kpi_score_segment`
- `prcp_metric_item`、`prcp_metric_coefficient`

### 利率 / ESG（4 张）
- `prcp_rate_scheme`、`prcp_rate_point`
- `prcp_esg_scheme`、`prcp_esg_scenario`、`prcp_esg_curve_point`、`prcp_esg_run`（注：含 4 张）

### 模型 / 引擎 / 模拟（10 张）
- `prcp_model`、`prcp_model_version`、`prcp_model_param`
- `prcp_model_train`、`prcp_model_train_log`、`prcp_model_train_result`
- `prcp_sim_scheme`、`prcp_sim_node_config`、`prcp_sim_term_ratio`
- `prcp_sim_run`、`prcp_sim_result`

### 反算（8 张）
- `prcp_reverse_scheme`、`prcp_reverse_target`
- `prcp_reverse_run`、`prcp_reverse_run_log`
- `prcp_reverse_result`、`prcp_data_reverse`

## 使用方法

### 全新初始化数据库

```bash
# 1. 创建数据库 + 表结构
mysql -u root -p'Root@2026' -e "CREATE DATABASE IF NOT EXISTS prcp_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
mysql -u root -p'Root@2026' prcp_db < 01_schema_all.sql

# 2. 插入样本数据
mysql -u root -p'Root@2026' prcp_db < 02_sample_data.sql

# 3. （可选）增量升级（已是新库可跳过）
mysql -u root -p'Root@2026' prcp_db < upgrade_sys_audit.sql
mysql -u root -p'Root@2026' prcp_db < insert_dashboard_history.sql
```

### 重置特定表的数据

```bash
# 重置账户册节点
mysql -u root -p'Root@2026' prcp_db -e "DELETE FROM prcp_coa_node"
mysql -u root -p'Root@2026' prcp_db < 02_sample_data.sql  # 重放整批
```

或者使用 sed/awk 提取单表 INSERT 块后重放。

### 增量升级（已部署的生产库）

```bash
# 新增审计日志表
mysql -u root -p'Root@2026' prcp_db < upgrade_sys_audit.sql

# 插入 Dashboard 测试数据
mysql -u root -p'Root@2026' prcp_db < insert_dashboard_history.sql
```

## 生成方式

```bash
# 全库 schema
mysqldump -u prcp -p'Prcp@2026' --no-data --skip-comments \
  --skip-set-charset --skip-add-drop-table prcp_db > 01_schema_all.sql

# 关键表样本数据（单行 INSERT 形式）
mysqldump -u prcp -p'Prcp@2026' --no-create-info --skip-comments \
  --skip-set-charset --skip-extended-insert --complete-insert \
  prcp_db prcp_coa_scheme prcp_coa_node prcp_kpi_scheme prcp_kpi_definition \
  prcp_kpi_value sys_user sys_role sys_dict sys_dict_item \
  prcp_data_balance prcp_data_basic prcp_reverse_scheme prcp_reverse_run \
  > 02_sample_data.sql
```

## 13 张样本数据表的数据规模

| 表 | 行数 | 说明 |
|---|---|---|
| `prcp_data_balance` | 2682 | 余额快照（多 data_date × 多 coa_node） |
| `prcp_data_basic` | 1398 | 基础数据（64 桶 × 多节点 × 多日期） |
| `prcp_coa_node` | 141 | 账户册节点（L1~L5 层级） |
| `prcp_kpi_value` | 125 | 指标值（5 PNN 指标 × 24 月） |
| `sys_dict` | 86 | 系统字典 |
| `prcp_kpi_definition` | 10 | KPI 定义 |
| `prcp_reverse_run` | 10 | 反算运行 |
| `prcp_reverse_scheme` | 8 | 反算方案 |
| `prcp_coa_scheme` | 5 | 账户册方案 |
| `prcp_kpi_scheme` | 3 | 指标方案 |
| `sys_dict_item` | 5 | 字典项 |
| `sys_role` | 1 | 角色 |
| `sys_user` | 2 | 用户（admin + demo） |
| **合计** | **4476** | — |

## 注意事项

1. **DROP 顺序**：重置数据库时建议 `DROP DATABASE prcp_db;` 后再 `CREATE DATABASE`
2. **字符集**：必须使用 `utf8mb4`，否则 emoji / 部分中文会乱码
3. **外键**：脚本默认 `SET FOREIGN_KEY_CHECKS = 0`，导入完成后会自动 `= 1`
4. **LOCK TABLES**：样本数据使用 `LOCK TABLES ... WRITE;` / `UNLOCK TABLES;` 包裹，并发导入安全
5. **自增 ID**：所有 INSERT 使用显式 `id=...`，重放时会触发"重复键"错误，建议先 DELETE

## 维护记录

| 日期 | 操作 |
|---|---|
| 2026-09-29 | 首次生成全库 schema + 样本数据 |