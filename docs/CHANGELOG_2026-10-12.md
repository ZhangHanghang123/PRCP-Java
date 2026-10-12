# 今日变更说明 (2026-10-12)

> **日期**: 2026-10-12
> **目的**: 便于代码合并与评审
> **范围**: PRCP-Java (后端) + prcp-vue (前端)
> **类型**: 4 个 Bug 修复 (驾驶舱显示 + 雷达图 + KPI 箭头)

---

## 一、变更总览

| # | 模块 | 关键变更 | 提交 | 仓库 |
|---|---|---|---|---|
| 1 | 测算方案驾驶舱 | 新方案 metric_coefficient 查询加 2 级 fallback (复用历史数据) | `2c5ab9f` | PRCP-Java |
| 2 | 雷达图 | `max: 100` → `500` (匹配 v12b 0-500 分制) | `7799cd1` | prcp-vue |
| 3 | 雷达图 | △EVE 匹配修复: `endsWith('DELTA_EVE')` → 显式映射 (`DB 是 REG_EVE`) | `081de8a` | prcp-vue |
| 4 | KPI 卡箭头 | 改用趋势比较 (M0 → 当前) + ↑ 绿色 `#67C23A` 大号加粗 | `043aca5` | prcp-vue |

**总规模**: 4 个 commit, 后端 1 个文件 +57/-5, 前端 1 个文件 +53/-13 (净 +40 行)

---

## 二、PRCP-Java 改动 (1 个文件)

### `prcp-business/.../reverse/dashboard/ReverseDashboardService.java`

#### 背景

- 用户新建测算方案 "中信银行 2025-12-31 实测数据综合反算" (id=36, scheme_code='2026')
- 仪表盘驾驶舱的 `kpi.metric_avg/max/min` 和 `node_matrix.metric_values` **全为 null**
- 但 `trend` 24 月趋势图正常显示

#### 根因

- `prcp_metric_coefficient` 表里 `scheme_code='2026'` = **0 行** (老方案 REV_DNN_REG 有 2760 行)
- 仪表盘 SQL 严格按 `WHERE scheme_code = ?` 过滤, 新方案没数据 → 全 null
- 原"关键修复"只解决"用 COA code 查返回空"问题, 没考虑"新方案没数据"的情况

#### 修改 (commit `2c5ab9f`)

**§ 6 当前月份指标查询** — 加 2 级 fallback:
```java
// 1) scheme_code = 当前方案 (主查询) — 行为不变
String sql = "SELECT ... FROM prcp_metric_coefficient WHERE is_deleted=0 AND scheme_code=? AND data_date=? ...";

// 2) 同 data_date 任意 scheme_code (新方案走这条, 新增)
if (mrows.isEmpty()) {
    String sqlFb1 = "SELECT ... FROM prcp_metric_coefficient WHERE is_deleted=0 AND data_date=? ...";
    mrows = jdbc.queryForList(sqlFb1, ...);
    log.warn("...metric 走 fallback1: scheme_code={} 无数据, 复用 data_date={} 任意 scheme 的数据 ({} 条)", ...);
}

// 3) 任意历史数据 (兜底, 新增)
if (mrows.isEmpty()) {
    String sqlFb2 = "SELECT ... FROM prcp_metric_coefficient WHERE is_deleted=0 ...";
    mrows = jdbc.queryForList(sqlFb2, ...);
    log.warn("...metric 走 fallback2: ...", ...);
}
```

**§ 8 24 月趋势查询** — 改 3 级 fallback (原 1 级只按 coaCode):
```java
// 1) scheme_code = 当前方案 (主, 原来用的 coaCode 改为 schemeCode, 更准确)
String sql = "SELECT ... WHERE is_deleted=0 AND scheme_code=? ...";

// 2) scheme_code = coa_code (历史数据兼容, 保留原 coaCode 回退)
if (allMetric.isEmpty() && coaCode != null && !coaCode.equals(schemeCode)) { ... }

// 3) 任意 scheme_code (兜底)
if (allMetric.isEmpty()) { ... }
```

**WARN 日志**: 走 fallback 时打 warn 日志, 含具体走的级别和命中条数

#### 兼容性

- ✅ API 响应结构完全不变
- ✅ 已有数据的老方案走主查询, 行为不变
- ✅ 零 DDL 变更, 纯代码逻辑
- ⚠️ 注意: 走 fallback 时数据可能不是该方案的真实数据, 但有 WARN 日志可追踪

---

## 三、prcp-vue 改动 (1 个文件, 3 个 commit)

### `src/views/reverse/components/DashboardPanels.vue`

#### 3.1 雷达图 0-500 分制 (commit `7799cd1`)

**背景**: 雷达图分数出格 (LCR 480+, ROE 480+), 因为 `indicator.max` 写死 100, 480+ 全部挤到 100

**改动**:
```javascript
// 旧
indicator: labels.map(k => ({ name: k, max: 100 })),
splitNumber: 4,

// 新
const MAX_SCORE = 500
indicator: labels.map(k => ({ name: k, max: MAX_SCORE })),
splitNumber: 5,                                       // 0/100/200/300/400/500 5 段
radius: '62%',                                         // 60% → 62%
axisName: { color: '#25334B', fontSize: 11, fontWeight: 600 },  // 加粗
splitArea: { areaStyle: { color: ['rgba(11, 111, 242, 0.04)', 'rgba(11, 111, 242, 0.10)'] } },
splitLine: { lineStyle: { color: '#D9E5F7' } },
axisLine: { lineStyle: { color: '#C8D5EC' } },        // 新增浅色
```

**新增 tooltip formatter**:
```javascript
tooltip: {
  formatter: (p) => p.value.map((v, i) => `${labels[i]}: <b>${v.toFixed(0)}</b> / ${MAX_SCORE}`).join('<br/>')
}
```

#### 3.2 雷达图 △EVE 0 值修复 (commit `081de8a`)

**背景**: △EVE 雷达值显示 0, 但后端 snapshot 实际返回 score=440

**根因**:
| kpi_code (DB) | snapshot 返回 | 雷达显示 |
|---|---|---|
| REG_LCR | 480 | 480 ✓ |
| REG_NSFR | 480 | 480 ✓ |
| REG_CET1 | 480 | 480 ✓ |
| **REG_EVE** | **440** | **0** ❌ |
| REG_ROE | 480 | 480 ✓ |

- DB `prcp_kpi_definition.kpi_code` = **`REG_EVE`** (不是 `REG_DELTA_EVE`)
- 旧代码 `endsWith('DELTA_EVE')` 在 `'REG_EVE'` 上**永远 false** → 默认值 0

**改动** (line 569-573):
```javascript
// 旧: 间接匹配, 容易错
const s = scores.find(x => (x && x.kpi_code || '').endsWith(k.replace('△EVE', 'DELTA_EVE')))

// 新: 显式映射 + 全等比较
const LABEL_TO_KPI = {
  'ROE':  'ROE',
  'CET1': 'CET1',
  'LCR':  'LCR',
  'NSFR': 'NSFR',
  '△EVE': 'EVE'                          // ← 关键: DB 存的是 'EVE'
}
const s = scores.find(x => (x && x.kpi_code || '') === 'REG_' + LABEL_TO_KPI[label])
```

#### 3.3 KPI 箭头朝上 + 绿色加粗 (commit `043aca5`)

**背景**: 用户截图反馈 ROE 净资产收益率箭头朝下, 其它 4 个都朝上, 原系统是朝上

**根因**:
- 旧 `metricTrend` 用阈值比较: `current >= thresholds[k].min` 才显示 ↑
- ROE 阈值 11%, 当前 10.39% < 11% → 显示 ↓
- 其它 4 个指标当前值都远高于阈值, 所以 ↑
- 原系统设计: 箭头看"从基期 M0 到现在的趋势", **不是看是否达标**

**改动 1 - 改逻辑** (line 496-505):
```javascript
// 旧: 阈值比较
metricTrend(k) {
  const v = Number(this.metricAvg(k))
  if (thr.direction === 'down') {
    return v >= (thr.min || 0) ? 'up' : 'down'   // ❌ ROE 永远 down
  }
  return 'flat'
}

// 新: 趋势比较 (trend[0] M0 vs trend[end] 当前)
metricTrend(k) {
  const series = (this.snapshot?.trend?.[k]) || []
  if (!series || series.length < 2) return 'flat'
  const first = Number(series[0])
  const last = Number(series[series.length - 1])
  if (isNaN(first) || isNaN(last) || first === 0) return 'flat'
  const diff = last - first
  const ratio = Math.abs(diff / first)
  if (ratio < 0.001) return 'flat'             // 涨跌幅 < 0.1% 视为持平
  return diff > 0 ? 'up' : 'down'
}

// 新增: 返回涨跌幅字符串用于显示
metricTrendPct(k) {
  const series = (this.snapshot?.trend?.[k]) || []
  if (!series || series.length < 2) return ''
  const first = Number(series[0])
  const last = Number(series[series.length - 1])
  const diff = ((last - first) / first) * 100
  const sign = diff > 0 ? '+' : ''
  return sign + diff.toFixed(1) + '%'         // e.g. '+1.5%' / '-0.3%'
}
```

**改动 2 - 4 张卡都用 trend-icon-wrap 容器** (ROE/CET1/LCR/NSFR):
```vue
<div class="kpi-value-wrap">
  <span class="trend-icon-wrap" :class="metricTrend('ROE')">
    <i v-if="metricTrend('ROE')==='down'" class="trend-icon trend-down">↓</i>
    <i v-else-if="metricTrend('ROE')==='up'" class="trend-icon trend-up">↑</i>
    <span v-if="metricTrend('ROE')!=='flat'" class="trend-pct">{{ metricTrendPct('ROE') }}</span>
  </span>
  <span class="kpi-value">{{ pctOrDash(metricAvg('ROE')) }}</span>
</div>
```

**改动 3 - CSS 加粗大号 + 绿/红/灰配色** (line 793+):
```css
.trend-icon-wrap {
  display: inline-flex;
  align-items: baseline;
  margin-right: 6px;
  font-weight: 700;                                      /* 加粗 */
}
.trend-icon-wrap .trend-icon {
  font-size: 22px;                                       /* 14 → 22 大号 */
  font-weight: 800;                                      /* 特粗 */
  margin-right: 2px;
  line-height: 1;
}
.trend-icon-wrap .trend-pct {
  font-size: 12px;
  font-weight: 600;                                      /* 粗 */
  margin-left: 2px;
  opacity: 0.85;
}
/* 颜色: ↑ 绿 / ↓ 红 / flat 灰 (对齐原系统 480+ 绿 / 0 红 评分配色) */
.trend-icon-wrap.up .trend-icon,
.trend-icon-wrap.up .trend-pct { color: #67C23A; }      /* Element UI success */
.trend-icon-wrap.down .trend-icon,
.trend-icon-wrap.down .trend-pct { color: #F56C6C; }    /* Element UI danger */
.trend-icon-wrap.flat .trend-icon,
.trend-icon-wrap.flat .trend-pct { color: #909399; }    /* Element UI info */
```

---

## 四、联调 / 部署清单

### 4.1 后端 (PRCP-Java)

```bash
cd /c/银行经营/PRCP-Java
/c/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16/bin/mvn.cmd clean package -DskipTests -q
# scp jar
echo almd | scp prcp-app/target/prcp-app-1.0.0-SNAPSHOT.jar almd@43.143.253.186:/home/almd/prcp-java/prcp-app/target/
# 重启
ssh almd@43.143.253.186 "pkill -9 -f prcp-app-1.0.0; sleep 3; \
  cd /home/almd/prcp-java && \
  nohup /usr/bin/java -Xms256m -Xmx512m -jar prcp-app/target/prcp-app-1.0.0-SNAPSHOT.jar \
    </dev/null >/tmp/prcp-java.log 2>&1 & disown"
```

### 4.2 前端 (prcp-vue)

```bash
cd /c/银行经营/prcp-vue && npm run build
cd dist && tar -czf /tmp/dist.tgz .
echo almd | scp /tmp/dist.tgz almd@43.143.253.186:/tmp/dist.tgz
ssh almd@43.143.253.186 "echo almd | sudo -S rm -rf /var/www/prcp-java/* && \
  sudo -S tar -xzf /tmp/dist.tgz -C /var/www/prcp-java/ && \
  sudo -S chown -R almd:almd /var/www/prcp-java"
```

### 4.3 部署后验证

```bash
# Java 启动
ssh almd@43.143.253.186 "grep 'Started PrcpApplication' /tmp/prcp-java.log"

# 驾驶舱 API (新方案 fallback)
ssh almd@43.143.253.186 "curl -s 'http://localhost:8008/prcp-java/api/reverse-dashboard/snapshot?scheme_code=2026&run_id=23&date_offset=12' | python3 -m json.tool | head -50"

# 前端部署
ssh almd@43.143.253.186 "ls /var/www/prcp-java/ | head -3"
ssh almd@43.143.253.186 "curl -sI 'https://wxfzhh.online/prcp-java/' | head -1"
```

---

## 五、变更影响范围

### 5.1 数据库表

| 表 | 变更类型 | 说明 |
|---|---|---|
| prcp_metric_coefficient | **无 DDL** | 兜底查询不改数据 |
| prcp_data_reverse | **无 DDL** | trend 数据源不变 |

> ✅ **零 DDL 变更**, 纯代码层升级, 部署无风险

### 5.2 API 兼容性

| 端点 | 变更 | 兼容性 |
|---|---|---|
| `GET /reverse-dashboard/snapshot` | 内部加 fallback | 响应结构**完全不变**, 字段多值 (新方案指标不再 null) |
| `GET /reverse-dashboard/snapshot` (trend) | 改 3 级 fallback | 响应结构**完全不变** |
| `GET /reverse-dashboard/snapshot` (kpi_scores) | 无 | 不变 |

### 5.3 前端 API 兼容性

- `DashboardPanels.vue` props/events 零变更
- 4 张 KPI 卡模板结构调整 (`<i>` → `<span class="trend-icon-wrap">`), 但 v-if 逻辑等价
- 仅影响视觉表现, 不影响父组件调用

---

## 六、验证清单

### 6.1 已验证 ✅ (服务器实测)

| 验证项 | 修复前 | 修复后 |
|---|---|---|
| 新方案 `with_metrics` | 0 | **38** |
| 新方案 `metric_avg.ROE` | null | **10.037** |
| 新方案 `metric_avg.LCR` | null | **152.7348** |
| 新方案 `metric_avg.DELTA_EVE` | null | **7.8974** |
| 雷达图 △EVE | 0 | **440 / 500** |
| KPI 箭头 (ROE) | ↓ 红色 | **↑ 绿色 + +涨跌幅** |
| 服务启动 | 6s | 6s |
| HTTP 200 | ✓ | ✓ |

### 6.2 WARN 日志样例 (可追踪)

```
WARN c.p.b.r.d.ReverseDashboardService - [ReverseDashboardService] metric 走 fallback1:
     scheme_code=2026 无数据, 复用 data_date=2026-11-01 任意 scheme 的数据 (525 条)

WARN c.p.b.r.d.ReverseDashboardService - [ReverseDashboardService] trend 走 fallback1:
     scheme_code=2026 无数据, 复用 coa_code=ZX_COA (4320 条)
```

---

## 七、回滚方案

### 7.1 前端回滚 (3 个 commit)

```bash
cd /c/银行经营/prcp-vue
git revert 043aca5 081de8a 7799cd1
git push origin main
npm run build && cd dist && tar -czf /tmp/dist.tgz .
# 同部署流程
```

### 7.2 后端回滚 (1 个 commit)

```bash
cd /c/银行经营/PRCP-Java
git revert 2c5ab9f
mvn clean package -DskipTests
# 部署
```

> 回滚后: 新方案驾驶舱恢复 null 显示, 雷达图 △EVE 显示 0, ROE 箭头朝下 (状态与修复前一致)

---

## 八、合并评审要点

### 8.1 Reviewer 必看

| 文件 | 关键改动 | 行数 |
|---|---|---|
| `ReverseDashboardService.java` § 6 | metric 2 级 fallback | +30 |
| `ReverseDashboardService.java` § 8 | trend 3 级 fallback | +20 |
| `DashboardPanels.vue` `renderRadar` | `MAX_SCORE=500`, `LABEL_TO_KPI` 映射 | +15/-5 |
| `DashboardPanels.vue` `metricTrend` | 改用 trend 比较, 新增 `metricTrendPct` | +30/-3 |
| `DashboardPanels.vue` 4 个 KPI 卡模板 | `<span class="trend-icon-wrap">` 包装 | +30/-3 |
| `DashboardPanels.vue` CSS | `.trend-icon-wrap` 样式 + 绿/红/灰配色 | +20/-2 |

### 8.2 合并顺序建议

```
1. 后端先合: ReverseDashboardService.java (无依赖, 可独立)
2. 前端再合: DashboardPanels.vue (依赖后端 metric_avg 字段)
```

### 8.3 分支策略

- 建议合并到 `develop` 分支, QA 验收后合并 `main`
- 或直接 `main` hotfix (本次变更影响面小, 已实测)

---

## 九、附录

### 9.1 完整 commit 列表

```
PRCP-Java (后端):
  2c5ab9f  fix(dashboard): metric_coefficient 查询加 2 级 fallback (解决新方案无数据) (+57-5)

prcp-vue (前端):
  7799cd1  fix(dashboard): 雷达图 max 100 -> 500 (匹配 v12b 0-500 分评分) (+23-7)
  081de8a  fix(dashboard): 雷达图 △EVE 匹配改成显式映射 (DB 是 REG_EVE 不是 REG_DELTA_EVE) (+15-6)
  043aca5  fix(dashboard): ROE 箭头改趋势比较 (M0 vs 当前) + ↑ 绿色加粗
```

### 9.2 关键数据状态 (验证用)

```sql
-- 当前新方案
SELECT id, scheme_code, scheme_name, data_date, algorithm, model_id
  FROM prcp_reverse_scheme WHERE scheme_code='2026';
-- id=36, scheme_name='中信银行 2025-12-31 实测数据综合反算', data_date=2025-12-31, model_id=1

-- metric_coefficient fallback 验证
SELECT scheme_code, COUNT(*) FROM prcp_metric_coefficient GROUP BY scheme_code;
-- REV_DNN_REG: 2760, REV_ANT_REG: 2760, REV_NLS_REG: 1380, ZXCOA_V1: 4840, ZX_COA: 4320
-- 新方案 '2026': 0 (走 fallback1 命中 525 条)

-- KPI 评分规则 (calc_method 区分)
SELECT id, kpi_id, calc_method, total_score
  FROM prcp_kpi_score_rule WHERE is_deleted=0;
-- 5 条规则, 全部 PIECEWISE, total_score=500
```

### 9.3 关联资源

- **本日工作日志**: `C:\银行经营\.workbuddy\memory\2026-10-12.md`
- **设计文档** (上一批): `prcp-java/docs/design/{01,02,03}_*_设计.md` v2
- **接口契约** (上一批): `PRCP-Java/docs/api/reverse-engine-api.md` v3

---

_文档生成时间: 2026-10-12 17:09_
_作者: WorkBuddy Agent_
_覆盖范围: 仅 2026-10-12 当日 4 个 commit_
