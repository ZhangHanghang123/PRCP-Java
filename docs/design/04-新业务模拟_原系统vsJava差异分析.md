# 新业务模拟模块 — 原系统 vs Java 版差异分析

> **分析日期**: 2026-10-26
> **原系统基线**: `C:\银行经营\PRCP\` commit `fe514d3` (2026-10-23, v2.4)
> **Java 版基线**: `C:\银行经营\PRCP-Java\` + `C:\银行经营\prcp-vue\`
> **目的**: 明确 Java 移植缺口，输出可执行补齐清单

---

## 一、总览

### 1.1 差距规模

| 维度 | 原系统 (Python + React) | Java 版 (Spring Boot + Vue) | 缺口 |
|---|---|---|---|
| 引擎算法版本 | **v2.4** (2026-10-23) | **v1** (2026-09-26) | ⬅ 落后 2 个大版本 |
| 引擎核心函数 | 20+ | 6 | 缺 14+ |
| 引擎依赖表 | 9 张 | 6 张 | 缺 3 张 |
| 结果表字段 | 153 列 | 146 列 | 缺 7 列 |
| 后端端点 | — | 4 (EngineController) | 已有，前端未接 |
| 前端页面 | **4 个** (方案/配置/驾驶舱/汇总表) | **1 个** (列表+抽屉) | 缺 3 个 (~1650 行 React) |
| API 封装函数 | 21 | 18 | 缺 4 (引擎链路) |
| 评分插值 | `score_min` 段内插值 | 纯阶梯 | 完全未实现 |

### 1.2 差异分级

| 级别 | 数量 | 说明 |
|---|---|---|
| 🔴 **P0 阻断** | 8 项 | 数值结果错误，引擎结果不可信 |
| 🟠 **P1 缺失** | 11 项 | 功能缺失，需要新表/新列/新页面 |
| 🟡 **P2 不一致** | 9 项 | 命名/字段/交互差异，不影响正确性 |
| ⚪ **P3 文档偏差** | 3 项 | 需求文档与实现不符 |

### 1.3 一句话结论

> **Java 版引擎复刻的是 Python v1，而 Python 生产环境已跑到 v2.4。**
> 核心差异集中在「剩余期限桶语义」「投放量口径」「利息率单位」3 处，**任一处都会导致全部数值结果错误**。

---

## 二、后端引擎差异 (P0)

### 2.1 三大致命差异

#### ① 剩余期限桶语义完全不同 🔴

| | 原系统 v2.4 | Java 版 |
|---|---|---|
| `rem_*` 语义 | **绝对到期月**（距基准日第 k 个月到期） | **相对剩余期限** |
| 投放落点 | 第 M 月投期限 v → 直接落 `rem_m{M+v}` | 落 `rem_m{v}` |
| 每月前移 | **不前移**，到期时按月取走 | `rollOneMonth` 整体前移 |
| `orig_*` 语义 | **只进不出**（累计投放额） | 每月跟着前移 |

**后果**：Java 的 `rem_*` 与 `orig_*` 桶**结构性不可比**，不是精度问题而是定义不同。

**对应代码**：
- Python `engine.py:497-543` (`apply_term_split`)、`:546-565` (`_due_key`/`_due_state_key`)、`:568-584` (`take_due`/`peek_due`)
- Java `BucketMath.java:166-186` (`applyTermSplit`)、`:212-236` (`rollOneMonth`)

#### ② 投放量口径完全不同 🔴

| | 原系统 v2.4 | Java 版 |
|---|---|---|
| 公式 | `add = year_target × (rhythm_weight / 100)` | `netNew = current_balance × (annual_growth_rate / 1200)` |
| 数据源 | `prcp_sim_growth_config` (5 年参数) + `prcp_sim_rhythm` (12 月权重) | 仅 `prcp_sim_node_config.annual_growth_rate` |
| 特性 | **固定年增量**，余额漂移不影响投放 | **比例外推**，余额越大投放越多 |
| 到期回补 | 目标类 (growth_type 1/2) 回补释放 | 无回补概念 |

**后果**：投放量、桶分配、余额轨迹**全错**。

**对应代码**：
- Python `engine.py:449-491` (`compute_month_new_v2`)、`:369-394` (`compute_year_target`)、`:400-443` (`get_rhythm_weights`)
- Java `BucketMath.java:142-151` (`computeMonthNew`)

#### ③ 利息率单位错误（100 倍）🔴

| | 原系统 v2.4 | Java 版 |
|---|---|---|
| 公式 | `Σ (amount × business_ratio/100) × (interest_rate/**100**)` | `Σ (amount × interest_rate)` |
| 位置 | `engine.py:1006-1010` | `BucketMath.java:266-268` |
| 后果 | 正确 | **`weighted_rate` / `interest_amount` 虚高 100 倍** |

> 注：Java 复刻的是 Python 已废弃的 `compute_main_metrics` (v1, `engine.py:674-702`)，而 Python 生产路径早已改用内联 v2.1 版本。

### 2.2 完整函数级对比

| Python 函数 (engine.py) | 行号 | Java 对应 | 状态 |
|---|---|---|---|
| `get_initial_state` | 294-338 | `BucketMath.getInitialState` (73-106) | ⚠️ Python JOIN node 元数据，Java 不 JOIN（多 1 次 N+1） |
| `compute_month_new` (v1) | 344-352 | `BucketMath.computeMonthNew` (142-151) | ☠️ **已被 v2 取代，Java 停在 v1** |
| `compute_year_target` | 369-394 | — | ❌ **缺** |
| `default_linear_weights` | 400-402 | — | ❌ **缺** |
| `get_rhythm_weights` | 405-443 | — | ❌ **缺**（含三重降级保护） |
| `compute_month_new_v2` | 449-491 | — | ❌ **缺** |
| `apply_term_split` | 497-543 | `BucketMath.applyTermSplit` (166-186) | ☠️ Python 绝对落点 + full_plan，Java 相对落点 |
| `_due_key` | 546-560 | — | ❌ **缺**（长端归档 y10~y30） |
| `_due_state_key` | 563-565 | — | ❌ **缺** |
| `take_due` | 568-576 | — | ❌ **缺**（取走+清零） |
| `peek_due` | 579-584 | — | ❌ **缺**（快照不清零） |
| `snapshot_future_plan` | 587-599 | — | ⚪ Python 已死代码，**不需要移植** |
| `accumulate_plan` | 602-613 | — | ❌ **缺**（full_plan 排期） |
| `apply_final_plan` | 616-623 | — | ❌ **缺**（排期回填） |
| `roll_one_month` | 629-668 | `BucketMath.rollOneMonth` (212-236) | ☠️ Python 已废弃，**Java 不应实现** |
| `compute_main_metrics` | 674-702 | `BucketMath.computeMainMetrics` (252-285) | ☠️ Python 已死代码 + 利息率 bug |
| `_run_engine` (12 步) | 708-1149 | `NewBusinessEngine.run` (76-144) | ☠️ 步骤框架在，算法细节全旧 |
| `_aggregate_summary_nodes` | 1152-1298 | `NewBusinessEngine.aggregateSummaryNodes` (347-479) | ⚠️ Java 缺 3 个聚合标量 + SUMMARY 筛选条件 |
| `cleanup_run_results` | 1301-1316 | — | ⚪ Python 无调用点，可选 |

### 2.3 主流程步骤对比

| 步骤 | Python (`_run_engine`) | Java (`run`) | 差异 |
|---|---|---|---|
| 1. 创建 run | `:728-754` | `:99-107` | ✅ 等价 |
| 2. 取叶子节点 | `:757-784` `NOT EXISTS(child)` | `:113-117` `node_type='BUSINESS'` | 🔴 Java 硬筛 node_type，账户册取值不统一时**取不到任何节点直接抛异常**；且 Java 缺 `n.is_deleted=0` |
| 3. 取配置 | `:792-844` **3 次批量查询** | `:120-123` **逐节点 3 次查询** | 🔴 N+1（3N vs 3） |
| 4. 构造节点态 | `:846-901` | `:202-230` | ⚠️ Python 有 `is_model_calc` 判定，Java 只看 `cfg_id != null` |
| 5. 构造 INSERT SQL | `:903-919` 153 列 | `:241-273` 146 列 | ⚠️ 缺 7 列 |
| 6. 主循环 | `:924-1078` | `:276-344` | 🔴 逐月 INSERT vs **延后批量**（延后是 full_plan 回填前提） |
| 7. 回填排期 | `:1080-1093` | — | ❌ 缺 |
| 8. 批量 INSERT | `:1095-1098` 500 行/批 | `:327-333` 逐月 batchUpdate | 🔴 |
| 9. 聚合汇总 | `:1100-1111` | `:129` | ⚠️ Java SUMMARY 筛选少 `OR EXISTS(child)` |
| 10-12. 统计/成功/异常 | `:1113-1149` | `:132-143` | ✅ 等价 |

### 2.4 其他后端差异

| # | 项 | 原系统 | Java | 影响 |
|---|---|---|---|---|
| 1 | `addMonths` 负数 | `m // 12` 向下取整 (`engine.py:284`) | `m / 12` 截断向零 (`BucketMath.java:52`) | 🟡 潜伏 bug（当前只传正数） |
| 2 | 结果集 LIMIT | 20000 + 截断告警 (`engine.py:202,225`) | **硬编码 2000** (`NewBusinessEngine.java:630`) | 🔴 57 节点 × 60 月 = 3420 行 > 2000，**静默丢数据** |
| 3 | 返回字段命名 | snake_case | `listRuns` camelCase / `listResults` snake_case | 🟡 **混合**，前端需分叉处理 |
| 4 | `withBuckets` 参数 | `filters.get("with_buckets")` | 需显式 `Boolean` (`NewBusinessEngine.java:610`) | 🟡 字符串 `"true"` 不生效 |
| 5 | `dateOffset=0` | Python truthiness 跳过 | Java `!= null` 命中 (`:597`) | 🟡 行为不同 |
| 6 | 汇总节点 3 标量 | `SUM(year_target/month_add/month_release)` | 无 | 🟡 汇总缺投放追溯 |
| 7 | `EngineController` userId | `Depends(get_current_user)` 真实用户 | **硬编码 `1L`** (`:90`) | 🟡 所有 run 的 created_by 都是 1 |
| 8 | 事务边界 | FastAPI `get_db` 请求级 | 引擎方法 `@Transactional` 单 run | 🟡 失败时 run 记录也被回滚（两侧等价，但需知悉） |

---

## 三、数据库差异

### 3.1 缺 3 张表

| 表 | 用途 | Java 状态 |
|---|---|---|
| `prcp_sim_growth_config` | `growth_type` + `year1_value..year5_value` + `amount_unit` — **年目标增量的数据源** | ❌ 完全没有 |
| `prcp_sim_rhythm` | `month_no` + `weight` (12 个月) — **投放节奏的数据源** | ❌ 完全没有 |
| `prcp_sim_growth` (旧名) | v1 命名，已被 growth_config 取代 | ❌ 不需要 |

### 3.2 缺 2 个字段

| 表 | 字段 | 用途 |
|---|---|---|
| `prcp_sim_node_config` | `is_model_calc` | 区分「模型测算」(1) vs「存量保持」(2) — **v2.1 关键字段** |
| `prcp_sim_node_config` | `rhythm_type` | 线性(1) vs 自定义(2) 节奏 |

### 3.3 结果表缺 7 列

| 列 | 类型 | Python 用途 | Java 影响 |
|---|---|---|---|
| `growth_type` | tinyint(1) | 追溯增长方式 | 前端无法展示投放口径 |
| `year_target` | decimal(20,4) | 当年目标增量 | 无法对账；**汇总节点也无法聚合** |
| `rhythm_weight` | decimal(8,4) | 当月节奏权重 | 「投放节奏」区无数据 |
| `month_add` | decimal(20,4) | 当月纯新增投放 | 无法拆解投放 |
| `month_release` | decimal(20,4) | 当月到期释放 | 无法拆解释放 |
| `planned_due` | decimal(20,4) | 计划到期（绝对到期月口径） | **未来 5 年到期时间轴全空** |
| `cum_planned` | decimal(20,4) | 累计计划投放 | 同上 |

> Python 侧由 `add_sim_result_trace_fields.sql` (5 列) + `add_planned_due_fields.sql` (2 列) 两个 ALTER 脚本添加。

### 3.4 字段存在但引擎不写

| 列 | 说明 |
|---|---|
| `parent_code` | Python 从 state 带出，Java 恒 NULL |
| `is_leaf` | Python 恒 1，Java 恒 NULL |
| `asf_rsf` / `hqla_factor` / `risk_weight` | 两侧都不写 |

### 3.5 `BasicDataBuckets` 双份实现

| 路径 | 行数 | 状态 |
|---|---|---|
| `PRCP-Java/prcp-business/.../data/BasicDataBuckets.java` | 165 | ✅ 完整版（KEYS/NAMES/ORIG_COLS/REM_COLS/LONG_KEYS/DISPLAY_BUCKETS） |
| `PRCP/java-poC/prcp-business/.../data/reverse/BasicDataBuckets.java` | 15 | ⚠️ 精简版（只有 `keys()`）→ **同名不同包，合并时会符号歧义** |

> `LONG_KEYS` Java 用 `LinkedHashSet`（保序），Python 用普通 `set`（无序）。

---

## 四、前端差异

### 4.1 页面清单

| 页面 | 原系统 React | Java Vue | 状态 |
|---|---|---|---|
| 方案列表 | `SimSchemeList.tsx` (581 行) | `views/sim/Index.vue` (512 行) | ⚠️ 功能少 |
| 参数配置 | `SimConfigPage.tsx` (1321 行，独立路由) | 抽屉内嵌 `Index.vue:73-166` | ⚠️ 架构不同 + 功能少 |
| **结果驾驶舱** | `SimResultPage.tsx` (1264 行) | — | ❌ **完全缺失** |
| **结果汇总表** | `SimResultTable.tsx` (384 行) | — | ❌ **完全缺失** |
| 路由 | `App.tsx:60-63`（4 条） | `router/index.js:76-81`（1 条） | ⚠️ 4:1 |

### 4.2 方案列表页差异

| 维度 | React | Vue |
|---|---|---|
| KPI 统计卡 | 4 张 (`:326-347`) | ❌ 无 |
| 表格列 | 8 列（含创建时间） | 7 列 |
| 状态交互 | Tag + 表头 filters | Tag 点击即切换 |
| 分页 | pageSize 20 + sizeChanger + quickJumper | el-table 默认 10 条 |
| 操作按钮 | 6 个（查看结果/参数配置/**引擎计量**/编辑/启停/删除） | 3 个（配置节点/编辑/删除） |
| **引擎计量入口** | ✅ 有（Modal + Progress + 1.5s 自动跳转） | ❌ **无**（后端 `/sim/schemes/{sid}/run` 已就绪，前端未接） |
| 字段校验 | 正则 `/^[A-Za-z0-9_]+$/` + maxLength | 仅必填 |

### 4.3 参数配置页差异

| 维度 | React (`SimConfigPage.tsx`) | Vue 抽屉 |
|---|---|---|
| 区块数 | 4 个内嵌 Card | 2 个 |
| 期限占比表 | 6 列 + footer 占比合计 | 4 列（无序号/无合计 footer） |
| 增长方式 | `DictSelect` (PRCP_GROWTH_TYPE 4 种) + 单位联动 | ❌ 无 |
| 5 年参数 | Y1~Y5 + 清零 + 快捷填充 | ❌ 无 |
| 节奏配置 | 12 月权重 Modal + 归一化 + 柱状预览 | ❌ 无 |
| 模型测算开关 | ✅ (PRCP_BIZ_YES_NO) | ❌ 无 |
| 未保存拦截 | `Modal.confirm` | ❌ 无 |
| 非末级拦截 | 引导页 + `LockOutlined` 标 | 静默 return，无提示 |
| 树搜索 | ❌ 无 | ✅ 有 |

> **注意**：参数配置缺的这 4 项（增长方式/5 年参数/节奏/模型测算开关）**不是前端没做**，是 Java 后端 + DB 整条链路缺失（见 §3.1、§3.2）。

### 4.4 结果驾驶舱「6 段式」实证

> commit `fe514d3` 原文：「新业务模拟：驾驶舱 6 段式（Run卡/KPI/汇总/趋势/节点明细）」
> 需求文档 `PRCP/docs/新业务模拟方案_需求设计文档.html:603` 记为「四段式」— 措辞不一致，以代码为准。

| # | 段 | 代码位置 | 内容 | Java 版 |
|---|---|---|---|---|
| 1 | 顶部信息条 | `SimResultPage.tsx:731-783` | 返回 + 方案 Select + Run Select + 刷新 | ❌ |
| 2 | **Run 卡** | `:786-836` | 8 个 Statistic（Run ID/状态/总节点/⚡已配置/🔄仅滚动/📊汇总/生成月份/耗时）+ 基准月→目标月 + FAILED 错误 | ❌ |
| 3 | **KPI + 汇总** | `:839-969` | 资产/负债/权益 三组 + 全部合计 + 资产负债差额 Alert | ❌ |
| 4 | 节点级 KPI | `:1101-1135` | 选中节点的 4 指标，**跟随月份下拉**切换 | ❌ |
| 5 | **趋势** | `:1176-1258` | 4 KPI + 图A「月度投放分解」双轴 + 图B「12月节奏权重」柱+基准虚线 + 年度投放汇总表 | ❌ **无数据源**（结果表缺 7 列） |
| 6 | **节点明细** | `:972-1173` | 左账户册树（3 色标 + has_config）+ 右 64+64 桶表；列组 4 模式切换（度量/原始期限/剩余期限/全部），`scroll.x` 700/3460/7100 联动 | ❌ |

**v2.4 相对 v1 的具体改动**（`git show fe514d3 -- SimResultPage.tsx`）：
- KPI 从「4 个扁平 Statistic」重构为「资产/负债/权益/合计」**四组分组汇总**
- 节点级 KPI 从固定 M1/M末 改为**跟随月份下拉**
- 月份切换从 24 个 `Tabs` 改为单个 `Select`
- 新增表头**列组切换**（`activeColumnGroup`）
- 新增**投放节奏区**（v2.4 之前 grep「投放节奏」命中数为 0）

⚠️ **实现与文档仍有偏差**：需求文档 `:626` 描述趋势区含「余额 60 月走势 / 加权利率走势 / 利息走势」三张图，代码里只有投放节奏相关的 2 张图，**缺这 3 条走势线**。

### 4.5 API 封装对比

| React `api/index.ts:295-336` (21) | Vue `api/sim.js:14-156` (18) | 状态 |
|---|---|---|
| `listCoaSchemes` | `coaSchemes` | ⚠️ 命名不同 |
| `coaTree(coaSchemeId, simSchemeId?)` | `coaTree(coaSchemeId)` | ⚠️ **缺 `sim_scheme_id`** → 无「⚡已配置」标绿 |
| `listSchemes` | `listSchemes` | 🔴 **参数名不兼容**（snake vs camel） |
| `deleteScheme` | `removeScheme` | ⚠️ 命名不同 |
| `deleteNodeConfig` | `removeNodeConfig` | ⚠️ 命名不同 |
| `getRhythm` / `saveRhythm` / `resetRhythmLinear` | — | ❌ 缺 3 |
| `getGrowth` / `saveGrowth` / `deleteGrowth` | — | ❌ 缺 3 |
| `runEngine` / `getRun` / `listRuns` / `listResults` | — | ❌ **缺 4**（后端已就绪） |
| — | `simTree` / `getSchemeNode` / `saveSchemeAll` / `validateScheme` / `listTermRatios` / `updateTermRatio` / `deleteTermRatio` | 🆕 Java 独有 7 |

**字段命名风格**（移植最大坑）：

| 语义 | React（对接 Python，snake_case） | Java（对接 Java，camelCase） |
|---|---|---|
| 方案列表 | `scheme_code` / `coa_scheme_id` / `data_date` / `config_node_count` | `schemeCode` / `coaSchemeId` / `dataDate` / `configNodeCount` |
| 节点配置 | `config.annual_growth_rate` / `is_model_calc` / `rhythm_type` | `config.annualGrowthRate` / `termUnit`（**缺后两个**） |
| Run 列表 | `total_nodes` / `duration_ms` / `base_data_date` | `totalNodes` / `durationMs` / `baseDataDate` |
| 结果明细 | `sim_scheme_code` / `run_id` / `orig_m1` | **同名 snake_case** ✅（唯一一致） |

### 4.6 Vue 侧实测级 Bug

| # | 位置 | 问题 | 现象 |
|---|---|---|---|
| 1 | `Index.vue:392` | `treeRes.data && treeRes.data.items` | 响应拦截器已解包（`request.js:57-75`）→ **节点树恒为空** |
| 2 | `Index.vue:415` | `simApi.nodeInfo(node.id).then(r => r.data)` | → 节点信息 undefined |
| 3 | `Index.vue:417` | `const d = cfgRes.data` | → `d.exists` 抛 TypeError |
| 4 | `Index.vue:293/301` | 用的是 `res.items`（✅ 正确） | 同一文件解包**自相矛盾** |
| 5 | `api/sim.js` listSchemes | 传 `coa_scheme_id`，Java 收 `coaSchemeId` | 按账户册筛选**失效** |
| 6 | `api/sim.js` coaTree | 不传 `sim_scheme_id` | 无「⚡已配置」标绿 |

### 4.7 布局与样式

| 维度 | React v2.4 | Vue |
|---|---|---|
| 根容器 | `.app-layout { height:100vh; overflow:hidden }`（父级锁死） | `.main-layout { height:100vh }`（**无 overflow:hidden**） |
| 内容区 | `.ant-layout-content { overflow-y:auto }` | `.el-main { overflow:auto }` ✅ 接近 |
| 侧栏 | `.ant-menu { overflow-y:auto }`（**独立滚动**） | ❌ **无独立滚动**，菜单展开撑长整页 |
| 表格 | `size=middle/small` + `scroll.x` 锁定 + `scroll.y=400` 固定表头 + 层级缩进 | 纯 `border stripe`，无固定表头/无缩进 |
| 图表 | `echarts-for-react` + 手写 option | `vue-echarts` + `utils/chartTheme.js`；**sim 页面零图表** |
| 色板 | AntD 语义色 `#722ed1`/`#52c41a`/`#fa8c16`/`#2f54eb`/`#13c2c2` | Element 语义色 + `chartTheme.js` `#5B8FF9` 系 — **两套不通用** |
| 金额单位 | 亿元（`toYi` = /1e8） | **元** — 口径不一致 |

---

## 五、指标评分 `score_min` 段内插值差异

> 属 commit `fe514d3` 的「指标评分：分数拆两列 + 段内插值」子项，与新业务模拟共用评分链路。

### 5.1 Python/React 实现

**DB 迁移脚本** `backend/scripts/add_seg_score_min.sql:26-29`：
```sql
ALTER TABLE prcp_kpi_score_segment
   ADD COLUMN score_min DECIMAL(8,2) DEFAULT NULL
     COMMENT '最小值分数（下界 min_value 处的得分），NULL 表示不启用段内插值'
   AFTER `score`
```
> NULL = 不启用插值，退化为阶梯评分 → 向后兼容

**PIECEWISE 段内插值算法** `backend/app/routers/kpi.py:778-807`：
```
score = score_min + (x − min_value) / (max_value − min_value) × (score − score_min)
```
**四个条件同时满足才插值**，否则退化为 STEPPED：
1. `score_min IS NOT NULL`
2. `min_value IS NOT NULL`
3. `max_value IS NOT NULL`
4. `max_value > min_value`（零宽峰点段不插值）

**回填脚本** `backend/scripts/backfill_score_min.py:69-89`：
- 第 N 段 `score_min` = 第 N-1 段的 `score`（边界连续）
- 首段 `score_min` = 自身 `score`（恒定）
- 零宽段 `score_min` = 自身 `score`（强制恒定）
- 范围：5 规则 / 110 段
- 备份到 `prcp_kpi_score_segment_bak_20261022` + 连续性校验

**`_linear_interpolate` 修复** `kpi.py:948-981`：
- 原用元组解包 `mn, _mx, sc, _smin = row`（期望 4 列）
- 但 `kpi.py:745` 传 3 列、`kpi.py:930` 传 4 列 → **HTTP 500**
- 修复：改索引访问 `row[0], row[1], row[2]`

**前端** `KpiScore.tsx`：
- PIECEWISE 分段表 **7 列**（序号/最小值/最大值/**最小值分数**/**最大值分数**/描述/操作），橙+蓝区分（`:581-651`）
- Modal **1000px**（`:443`）
- 插值提示 2 处（`:352-356` 列表 + `:513-515` Modal），含示例「[0,10] → 60~100，输入 5 得 80 分」
- 规则列表 `[插值]` 橙 Tag + `score_min ~ score 分`（`:391-401`）

### 5.2 Java/Vue 现状

| 维度 | Python/React | Java/Vue |
|---|---|---|
| `score_min` DB 列 | ✅ `DECIMAL(8,2) DEFAULT NULL` | ❌ **不存在**（`01_schema_all.sql:642-655`） |
| Entity 字段 | ✅ | ❌ `KpiScoreSegment.java:17-31` 无 `scoreMin` |
| Mapper SELECT | ✅ 含 `score_min` | ❌ `KpiScoreSegmentMapper.java:35` 无该列 |
| **全仓 grep** | — | `score_min\|scoreMin` → **0 命中** |
| PIECEWISE 取分 | 插值/阶梯二选一 | **纯阶梯** `matched.getScore()`（`KpiService.java:394`） |
| `interpolate_mode` | `INTERPOLATED`/`STEPPED` | ❌ 无 |
| `matched_range`(PIECEWISE) | 8 字段 | 3 字段（`min_value/max_value/segment_desc`） |
| `matched_range`(LINEAR) | `{from,to,from_score,to_score,from_seg_desc,to_seg_desc,clamped}` | `{x1,y1,x2,y2}` + `anchor_count` — **字段名完全不同** |
| **LINEAR 算法本体** | 锚点+插值+clamp，4 位小数 | **同构** ✅（唯一对齐的部分） |
| Service 映射 | ✅ | ❌ `KpiService.java:314-318` 不映射 `score_min`（即便前端传了也丢） |
| Service 输出 | ✅ 含 `score_min` | ❌ `KpiService.java:517-522` 不含 |
| Vue 分段表列 | 7 列 | **4 列**（单一「分数」） |
| Vue Modal 宽度 | 1000px | 780px |
| Vue 插值提示 | 2 处 | ❌ 无 |
| Vue `[插值]` 标记 | ✅ | ❌ 无 |
| Vue 提交 payload | 含 `score_min` | ❌ 不含（`Index.vue:820-827`） |
| Vue `scoreCalc` API | ✅ 定义（`api/index.ts:151`，但无调用点） | ❌ **无此方法**（`api/kpi.js:121-143`） |

**行为差异实证**（LCR 规则 `seg2 [120,122] score=15, score_min=0`，输入 `value=121`）：

| 实现 | 结果 |
|---|---|
| Python | `0 + (121−120)/(122−120) × (15−0) = ` **`7.5`** |
| Java | **`15`** |

差异幅度最大可达整段跨度（LCR 段 `[150,160] score=480 score_min=500` 时差 20 分）。

### 5.3 ⚠️ 另一处独立算法差异（非插值范畴，影响更大）

| | Python `/kpi/values/calc-score` | Java `recalcScore` |
|---|---|---|
| 位置 | `kpi.py:905-945` (`_calc_score_for_value`) | `KpiService.java:180-230` |
| 数据源 | **读评分规则分段表**做分段/插值 | **不读分段表**，用 `prcp_kpi_definition.threshold_min/max` |
| 算法 | 分段匹配 + 段内插值 | 归一化线性映射 `ratio × total` |
| 默认分 | — | 50.00 |

→ **Java 的「重算分数」与 Python 的「试算分数」是完全不同的两套算法**，即使不考虑插值也不等价。

---

## 六、移植优先级清单

### 🔴 P0 — 数值正确性（必须先修）

| # | 项 | 改动范围 |
|---|---|---|
| 1 | `rem_*` 改绝对到期月口径 | 新增 `_dueKey`/`_dueStateKey`/`takeDue`/`peekDue`；`applyTermSplit` 改落点；**移除 `rollOneMonth` 调用** |
| 2 | `orig_*` 改只进不出 | `applyTermSplit` 只写 orig；`rollOneMonth` 不再前移 orig |
| 3 | 利息率 ÷100 | `BucketMath.computeMainMetrics:267` |
| 4 | 投放量改 `year_target × rhythm_weight/100` | 新增 `computeYearTarget` + `computeMonthNewV2` + `getRhythmWeights` |
| 5 | 结果集 LIMIT 2000 → 20000 | `NewBusinessEngine.java:630` |
| 6 | 叶子节点判定改 `NOT EXISTS(child)` | `NewBusinessEngine.java:193`（去 `node_type='BUSINESS'`）+ 补 `n.is_deleted=0` |
| 7 | 汇总节点筛选补 `OR EXISTS(child)` | `NewBusinessEngine.java:353` |
| 8 | `score_min` 段内插值 | 加 DB 列 + Entity + Mapper + Service(映射/输出/算法) |

### 🟠 P1 — 功能缺失

| # | 项 | 改动范围 |
|---|---|---|
| 9 | 建 `prcp_sim_growth_config` 表 | SQL + Entity + Mapper + 批量查询 |
| 10 | 建 `prcp_sim_rhythm` 表 | SQL + Entity + Mapper + 批量查询 |
| 11 | `prcp_sim_node_config` 加 `is_model_calc` / `rhythm_type` | SQL ALTER + Entity + 判定逻辑 |
| 12 | `prcp_sim_result` 加 7 列 | SQL ALTER + 写入 + 汇总聚合 + 列表输出 |
| 13 | full_plan 排期机制 | `accumulatePlan` / `applyFinalPlan` + 延后 INSERT（500/批） |
| 14 | 前端结果驾驶舱页 | 新建 `views/sim/Result.vue`（6 段式） |
| 15 | 前端结果汇总表页 | 新建 `views/sim/ResultTable.vue` |
| 16 | 前端 `api/sim.js` 补 4 个引擎函数 | `runEngine`/`getRun`/`listRuns`/`listResults` |
| 17 | 前端参数配置补 4 区块 | 增长方式 / 5 年参数 / 节奏配置 / 模型测算开关 |
| 18 | 前端方案列表补「引擎计量」入口 | Modal + Progress + 跳转 |

### 🟡 P2 — 一致性

| # | 项 | 改动范围 |
|---|---|---|
| 19 | `listSchemes` 参数名统一 | 前端 `coa_scheme_id` → `coaSchemeId`（或后端加别名） |
| 20 | Vue `Index.vue` 响应解包统一 | `:392`/`:415`/`:417` 去掉多余 `.data` |
| 21 | Vue `coaTree` 传 `sim_scheme_id` | 后端 `coaTree` 补参数 + 返回 `has_config` |
| 22 | 返回字段命名统一 | `listRuns` 的 camelCase → snake_case（或前端适配） |
| 23 | `withBuckets` 兼容字符串 | `NewBusinessEngine.java:610` |
| 24 | `EngineController` userId 取真实用户 | `:90` 硬编码 `1L` |
| 25 | 侧栏独立滚动 | `MainLayout.vue:243-247` 补 overflow 约束 |
| 26 | 金额单位口径统一 | 元 vs 亿元 |
| 27 | 色板统一 | AntD 语义色 vs `chartTheme.js` |

### ⚪ P3 — 文档/清理

| # | 项 |
|---|---|
| 28 | 需求文档「四段式」vs commit「6 段式」措辞统一 |
| 29 | 需求文档提到的「余额/利率/利息 60 月走势」3 张图，代码未实现 |
| 30 | `PRCP/java-poC` 的 `BasicDataBuckets` 精简版与主版合并去歧义 |

---

## 七、附录

### 7.1 关键文件对照表

| 职责 | 原系统 | Java 版 |
|---|---|---|
| 引擎主体 | `PRCP/backend/app/services/calculate_engine/new_business/engine.py` (1316 行) | `PRCP-Java/prcp-business/.../engines/new_business/NewBusinessEngine.java` (640 行) |
| 桶数学 | 同上（内联） | `PRCP-Java/prcp-business/.../engines/new_business/BucketMath.java` (300 行) |
| 引擎基类 | `calculate_engine/base.py` (86 行) | `PRCP-Java/.../engines/EngineBase.java` (113 行) |
| 引擎注册 | `calculate_engine/registry.py` (96 行) | `PRCP-Java/.../engines/EngineRegistry.java` (102 行) |
| 自动注册 | `calculate_engine/__init__.py`（手动） | `PRCP-Java/.../engines/EngineAutoRegister.java` (58 行) |
| 64 桶常量 | `PRCP/backend/app/services/buckets.py` (61 行) | `PRCP-Java/.../data/BasicDataBuckets.java` (165 行) |
| 引擎路由 | `PRCP/backend/app/routers/engines.py` | `PRCP-Java/.../engines/EngineController.java` (238 行) |
| 评分引擎 | `PRCP/backend/app/routers/kpi.py` (1000+ 行) | `PRCP-Java/.../kpi/service/KpiService.java` (540 行) |
| 前端-方案 | `PRCP/frontend/src/pages/SimSchemeList.tsx` (581 行) | `prcp-vue/src/views/sim/Index.vue` (512 行) |
| 前端-配置 | `PRCP/frontend/src/pages/SimConfigPage.tsx` (1321 行) | 同上（抽屉内嵌） |
| 前端-驾驶舱 | `PRCP/frontend/src/pages/SimResultPage.tsx` (1264 行) | ❌ 无 |
| 前端-汇总表 | `PRCP/frontend/src/pages/SimResultTable.tsx` (384 行) | ❌ 无 |
| API 封装 | `PRCP/frontend/src/api/index.ts:295-336` (21 函数) | `prcp-vue/src/api/sim.js:14-156` (18 函数) |
| 建表 SQL | `PRCP/backend/scripts/2026_init/00_setup_schema.sql` | `PRCP-Java/deploy/sql/01_schema_all.sql` |

### 7.2 核心公式速查

| 指标 | 公式 |
|---|---|
| **投放量** (v2.4) | `add = year_target × rhythm_weight / 100` |
| **年目标** (type 1/3) | `base_balance × year_param / 100` |
| **年目标** (type 2) | `year_param × unit_scale` |
| **年目标** (type 4) | `year_param × unit_scale − year_base_balance`（年初锚定） |
| **月投放** (目标类 1/2) | `month_new = release + add` |
| **月投放** (非目标类 3/4) | `month_new = add` |
| **期限拆分** | `amount_v = month_new × business_ratio / 100` |
| **rem 落点** | `rem_m{M+v}`（M=当前月, v=期限）；>60 → y10/y15/y20/y30 |
| **余额** | `new_cb = base_balance + add` |
| **平均余额** | `new_ab = avg_balance + add / 2` |
| **加权利率** | `(avg × wr + Σ(amount × ratio/100 × ir/100)) / (avg + month_new)` |
| **月利息** | `new_ia = new_ab × new_wr / 12` |
| **段内插值** | `score = score_min + (x−min)/(max−min) × (score−score_min)` |
| **LINEAR 插值** | `score = y₁ + (x−x₁)/(x₂−x₁) × (y₂−y₁)`，4 位小数，两端 clamp |

### 7.3 验证用 SQL

```sql
-- 1. 6 张参数/配置表存在性
SELECT 'growth_config' t, COUNT(*) FROM prcp_sim_growth_config
UNION ALL SELECT 'rhythm', COUNT(*) FROM prcp_sim_rhythm
UNION ALL SELECT 'node_config.is_model_calc', COUNT(*) FROM information_schema.columns
  WHERE table_name='prcp_sim_node_config' AND column_name='is_model_calc';

-- 2. 结果表 7 列存在性
SELECT column_name FROM information_schema.columns
 WHERE table_name='prcp_sim_result'
   AND column_name IN ('growth_type','year_target','rhythm_weight','month_add',
                       'month_release','planned_due','cum_planned');

-- 3. score_min 列
SELECT column_name FROM information_schema.columns
 WHERE table_name='prcp_kpi_score_segment' AND column_name='score_min';

-- 4. 结果表行数（验证 LIMIT 2000 是否截断）
SELECT COUNT(*) FROM prcp_sim_result WHERE run_id = ?;
-- 期望: 57 节点 × 60 月 = 3420  (Java LIMIT 2000 会截断)
```

---

_分析日期: 2026-10-26_
_分析范围: 后端引擎 + 数据库 + 前端 + 评分插值，共 4 个专项_
_原系统基线: commit `fe514d3` (v2.4, 2026-10-23)_
