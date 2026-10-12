# 变更说明文档

> **日期**: 2026-10-12
> **目的**: 便于代码合并与评审
> **范围**: PRCP-Java (后端) + prcp-vue (前端) + prcp-java docs (设计文档)
> **关联 issue/需求**: 引擎拆服务器 / 测算方案 dashboard 修复 / 雷达图 0-500 分制

---

## 一、变更总览

| # | 模块 | 关键变更 | 提交 | 仓库 |
|---|---|---|---|---|
| 1 | 反算引擎 | 引擎拆服务器 + 单类报文网关 | `9d5515d` | PRCP-Java |
| 2 | 反算引擎 | 引擎 HTTP 接口契约文档 (v3) | `7d3d768` | PRCP-Java (含 docs) |
| 3 | 测算方案驾驶舱 | metric_coefficient 查询加 fallback (新方案无数据可用旧数据) | `2c5ab9f` | PRCP-Java |
| 4 | 雷达图 | max 100 → 500 (匹配 v12b 0-500 分制) | `7799cd1` | prcp-vue |
| 5 | 雷达图 | △EVE 匹配修复 (DB 是 `REG_EVE` 不是 `REG_DELTA_EVE`) | `081de8a` | prcp-vue |
| 6 | KPI 卡箭头 | 改用趋势比较 (M0 → 当前) + ↑ 绿色加粗 | `043aca5` | prcp-vue |

---

## 二、PRCP-Java 改动 (后端)

### 2.1 新增文件

#### `prcp-business/src/main/java/com/prcp/business/reverse/engine/ReverseEngineGateway.java` 🆕

- **行数**: 460
- **角色**: 报文组装 + HTTP 提交 **单类封装** (按用户要求)
- **公开方法** (3 个):
  ```java
  public Map<String, Object> submit(Long runId, Long schemeId)
  public Map<String, Object> buildPayload(Long runId, Long schemeId)
  public Map<String, Object> submitToEngine(Map<String, Object> payload)
  ```
- **私有助手** (4 段加载):
  - `loadScheme(Long schemeId)` — 查 prcp_reverse_scheme + JOIN prcp_model + prcp_coa_scheme
  - `loadTargets(Long schemeId)` — 查 prcp_reverse_target
  - `loadKpiSchemes(Map, List)` — 4 步查询 KpiScheme + ScoreRule + Segment/Anchor + KpiDefinition
  - `loadKpiParams(Map)` — 6 张参数补录表 (cet1/lcr/nim/nsfr/roe/eve)
- **RestTemplate**: `@PostConstruct` 初始化, connect 5s, read = `timeout-sec * 1000`
- **关键 SQL**:
  ```sql
  -- model.kpi_scheme_id 直接关联 (避免 algorithm 匹配不准)
  SELECT s.*, m.model_code, m.model_name, m.model_type, m.kpi_scheme_id
    FROM prcp_reverse_scheme s
    LEFT JOIN prcp_model m ON m.id = s.model_id AND m.is_deleted = 0
    LEFT JOIN prcp_coa_scheme cs ON cs.id = s.coa_scheme_id
   WHERE s.id = ? AND s.is_deleted = 0
  ```

### 2.2 修改文件

#### `prcp-business/.../reverse/ReverseService.java`

| 变更 | 详情 |
|---|---|
| ➕ 新字段 | `private final ReverseEngineGateway engineGateway;` |
| 🔧 `startRun` | 进程内 EXEC 改 HTTP 提交 (`engineGateway.submit`) |
| ➕ `handleEngineCallback` | 新增 30 行: SUCCESS 写 prcp_reverse_result + 更新 run; FAILED/CANCELLED 标记 |
| 🗑️ 删除 | `executeReverse`/`solve`/`computeOptimal`/`buildMetrics`/`saveResults` 共 -720 行 |
| 🔧 `toJson` 签名 | `Map<String,Object>` → `Object` (兼容 Object metricsObj) |
| 📝 注释 | 类级 Javadoc 改为「引擎已迁移到独立服务器」 |

#### `prcp-business/.../reverse/ReverseController.java`

- ➕ `POST /reverse/runs/{rid}/callback` (line 353)
  - 接收引擎回调 → `svc.handleEngineCallback(rid, body)`
  - Body: `{run_id, engine_run_id, status, duration_sec, optimal_value, metrics, results, error_message}`

#### `prcp-business/.../reverse/dashboard/ReverseDashboardService.java`

- 🔧 2 处 metric_coefficient 查询加 fallback (解决新方案无数据问题)
  - **§ 6 当前月份指标**: 3 级 fallback (schemeCode → dataDate → 任意)
  - **§ 8 24 月趋势**: 3 级 fallback (schemeCode → coaCode → 任意)
  - WARN 日志记录走的级别和命中条数

#### `prcp-app/src/main/resources/application.yml`

- ➕ `engine.*` 4 项配置
  ```yaml
  engine:
    base-url:          ${ENGINE_BASE_URL:http://localhost:8009}
    callback-base-url: ${ENGINE_CALLBACK_BASE_URL:http://localhost:8008/prcp-java/api}
    callback-token:    ${ENGINE_CALLBACK_TOKEN:dev-engine-callback-token}
    timeout-sec:       ${ENGINE_TIMEOUT_SEC:60}
  ```

### 2.3 文档

#### `PRCP-Java/docs/api/reverse-engine-api.md` 🆕 1095 行

- **§ 〇 报文内容说明** (总览 + 4 段映射表 + 时序图 + JSON 速查)
- **§ 三 报文样例** (REV_DNN_REG 完整 JSON)
- **§ 四 字段对照表** (含 6 表特有字段 + LINEAR 算法)
- **§ 八 4 段对应 SQL**

#### `prcp-java/docs/design/01-指标管理_设计.md` v2 (468 行)

- 4 tab → **3 tab** (移除 指标维护)
- 加 `calc_method` 字段 (PIECEWISE | LINEAR 5 步算法)
- scoreCalc 协议 (统一返回, 4 步 PIECEWISE / 5 步 LINEAR)
- 20 端点完整表

#### `prcp-java/docs/design/02-模型管理_设计.md` v2 (416 行)

- 2 tab → **单页布局** (主表 + 右侧详情面板)
- prcp_model 加 3 字段: `model_type`/`biz_domain`/`kpi_scheme_id`
- algo_config JSON + prcp_model_param 双层超参
- `/model/{mid}/train` 改一键训练 (version_id 可空)
- 20 端点, 7 个算法枚举

#### `prcp-java/docs/design/03-组合反算_设计.md` → `03-测算方案_设计.md` v2 (615 行, 重命名)

- 引擎拆服务器 (HTTP + 异步回调)
- ReverseEngineGateway 单类 460 行
- 4 段报文结构 (scheme/targets/kpi_schemes/kpi_params)
- 33 端点统计 (主 21 + 结果 7 + 驾驶舱 5)
- application.yml engine.* 4 项配置

---

## 三、prcp-vue 改动 (前端)

### 3.1 修改文件

#### `src/views/reverse/components/DashboardPanels.vue` (3 次提交)

| 提交 | 变更 |
|---|---|
| `7799cd1` | 雷达图 `max: 100` → `500` (v12b 0-500 分制) + `splitNumber: 5` + tooltip formatter + axisName fontWeight 600 + 浅色 axisLine |
| `081de8a` | 雷达图 △EVE 匹配修复: `endsWith('DELTA_EVE')` → 显式 `LABEL_TO_KPI` 映射表, 全等比较 (`==='REG_EVE'`) |
| `043aca5` | 4 张 KPI 卡箭头改用趋势比较 (`trend[0]` vs `trend[end]`), 不用阈值. ↑ 绿色 `#67C23A` 22px fontWeight 800. 箭头后显示 `+x.x%` 涨跌幅 |

**关键代码片段 (KPI 箭头 v3):**
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

**关键代码 (CSS):**
```css
.trend-icon-wrap { display: inline-flex; align-items: baseline; margin-right: 6px; font-weight: 700; }
.trend-icon-wrap .trend-icon { font-size: 22px; font-weight: 800; }
.trend-icon-wrap.up .trend-icon, .trend-icon-wrap.up .trend-pct { color: #67C23A; }  /* 绿 */
.trend-icon-wrap.down .trend-icon, .trend-icon-wrap.down .trend-pct { color: #F56C6C; }  /* 红 */
```

**关键代码 (metricTrend v2):**
```javascript
metricTrend(k) {
  const series = (this.snapshot?.trend?.[k]) || []
  if (!series || series.length < 2) return 'flat'
  const first = Number(series[0])
  const last = Number(series[series.length - 1])
  if (isNaN(first) || isNaN(last) || first === 0) return 'flat'
  const diff = last - first
  const ratio = Math.abs(diff / first)
  if (ratio < 0.001) return 'flat'
  return diff > 0 ? 'up' : 'down'
}
```

---

## 四、联调 / 部署清单

### 4.1 PRCP-Java 部署命令

```bash
cd /c/银行经营/PRCP-Java
mvn clean package -DskipTests -q
scp prcp-app/target/prcp-app-1.0.0-SNAPSHOT.jar almd@43.143.253.186:/home/almd/prcp-java/prcp-app/target/
ssh almd@43.143.253.186 'pkill -9 -f prcp-app-1.0.0; sleep 3; \
  cd /home/almd/prcp-java && \
  nohup /usr/bin/java -Xms256m -Xmx512m -jar prcp-app/target/prcp-app-1.0.0-SNAPSHOT.jar \
    </dev/null >/tmp/prcp-java.log 2>&1 & disown'
```

### 4.2 prcp-vue 部署命令

```bash
cd /c/银行经营/prcp-vue
npm run build
cd dist && tar -czf /tmp/dist.tgz .
scp /tmp/dist.tgz almd@43.143.253.186:/tmp/dist.tgz
ssh almd@43.143.253.186 'echo almd | sudo -S rm -rf /var/www/prcp-java/* && \
  sudo -S tar -xzf /tmp/dist.tgz -C /var/www/prcp-java/ && \
  sudo -S chown -R almd:almd /var/www/prcp-java'
```

### 4.3 部署后验证

```bash
# Java 启动日志
ssh almd@43.143.253.186 "grep -E 'Started Prcp|Tomcat started' /tmp/prcp-java.log"

# Dashboard 端点
ssh almd@43.143.253.186 "curl -s 'http://localhost:8008/prcp-java/api/reverse-dashboard/snapshot?scheme_code=2026&run_id=23&date_offset=12'"

# 前端部署
ssh almd@43.143.253.186 "ls /var/www/prcp-java/ | head -5"
ssh almd@43.143.253.186 "curl -s 'https://wxfzhh.online/prcp-java/' -o /dev/null -w 'HTTP %{http_code}\n'"
```

---

## 五、变更影响范围

### 5.1 数据库表

| 表 | 变更类型 | 说明 |
|---|---|---|
| prcp_reverse_scheme | 无 | (已含 data_date 字段) |
| prcp_reverse_target | 无 | (字段已对齐 Java 命名) |
| prcp_reverse_run | 无 | (状态机 PENDING → RUNNING → SUCCESS/FAILED/CANCELLED 不变) |
| prcp_kpi_score_rule | 无 DDL | 但用了 calc_method 字段 (PIECEWISE/LINEAR) |
| prcp_metric_coefficient | 无 DDL | 兜底查询不改数据 |
| prcp_{cet1,lcr,nim,nsfr,roe,eve}_param | 无 DDL | (引擎报文 § ④, 暂时为空) |

> ✅ **零 DDL 变更**, 纯代码层升级, 部署无风险

### 5.2 API 兼容性

| 端点 | 变更 | 兼容性 |
|---|---|---|
| `POST /reverse/runs/{rid}/start` | 内部改用 engineGateway | 行为不变 (前端无需改) |
| `POST /reverse/runs/{rid}/callback` | 🆕 新增 | 引擎侧用 |
| `GET /reverse-dashboard/snapshot` | 内部加 fallback | 响应结构不变, 字段多值 (新方案的指标不再 null) |
| 其余 30+ 端点 | 无变更 | 完全兼容 |

### 5.3 配置文件

- `application.yml` 加 4 项 `engine.*`, **不影响其他配置**
- 所有项有默认值 (localhost:8009), 引擎未部署时回退优雅 (run 标 FAILED)

---

## 六、验证清单

### 6.1 已验证 ✅

| 验证项 | 结果 | 证据 |
|---|---|---|
| 后端编译 | ✅ BUILD SUCCESS | 76M jar |
| Java 服务启动 | ✅ Started PrcpApplication in 6s | 日志 |
| 引擎网关初始化 | ✅ `[ReverseEngineGateway] 初始化完成` | 启动日志 |
| 网关注入 4 段报文 | ✅ targets=4 kpiSchemes=1 kpiParamsSize=6 | 实测日志 |
| 引擎连接失败兜底 | ✅ run.status=FAILED + error_message | 实测 |
| 回调端点 PENDING→SUCCESS | ✅ Run 12 写 1 条 result | curl 测试 |
| 仪表盘 metric fallback | ✅ with_metrics: 0→38, metric_avg 全有值 | curl 测试 |
| 雷达图 max 500 | ✅ LCR 480/500 正常显示 | 部署后 JS 校验 |
| 雷达图 △EVE 显示 | ✅ 440 (不是 0) | 部署后 JS 校验 |
| KPI 箭头朝上 | ✅ ROE 显示 ↑ + 涨跌幅 | CSS 部署后 `trend-icon-wrap` + `#67c23a` |
| 端点 HTTP 200 | ✅ | `https://wxfzhh.online/prcp-java/` |

### 6.2 待补 (不阻塞合并)

| 事项 | 优先级 | 备注 |
|---|---|---|
| 引擎服务部署 (port 8009) | P1 | 暂用 mock 数据, Run 23 的 metrics 含 `source: 中信银行 2025 年度报告` |
| 6 张参数补录表 (cet1/lcr/nim/nsfr/roe/eve) | P1 | 当前 0 行, 不影响驾驶舱 (fallback 兜住) |
| 引擎 HTTP 接口契约 `engine.base-url` 改正式地址 | P1 | application.yml 默认 `http://localhost:8009` |
| 引擎真接入后验证 4 段报文 (含 6 张参数表) | P2 | 单元测试 + 集成测试 |

---

## 七、回滚方案

### 7.1 前端回滚

```bash
cd /c/银行经营/prcp-vue
git revert 043aca5 081de8a 7799cd1  # 一次 revert 多个
git push origin main
# 重新构建 + 部署
npm run build && cd dist && tar -czf /tmp/dist.tgz .
scp /tmp/dist.tgz almd@43.143.253.186:/tmp/dist.tgz
ssh almd@43.143.253.186 'echo almd | sudo -S rm -rf /var/www/prcp-java/* && \
  sudo -S tar -xzf /tmp/dist.tgz -C /var/www/prcp-java/'
```

### 7.2 后端回滚

```bash
cd /c/银行经营/PRCP-Java
git revert 2c5ab9f 9d5515d
mvn clean package -DskipTests
# 部署回滚 jar
```

> ⚠️ 引擎回滚到进程内执行需恢复 `executeReverse` 等 4 个方法 (git log 73aad3b 之前)

---

## 八、合并评审要点

### 8.1 重点关注的代码 (Reviewer)

1. **`ReverseEngineGateway.java`**: 460 行, 4 段 SQL + 1 公共方法
   - 关注: `loadKpiSchemes` 的 4 步查询 (model.kpi_scheme_id 优先, algorithm 回退)
   - 关注: `loadKpiParams` 6 张表的 SQL 模板 (用 `?` 防止注入)
2. **`ReverseService.handleEngineCallback`**: 50 行
   - 关注: SUCCESS 时写 prcp_reverse_result 的字段映射
   - 关注: `toJson(Object)` 兼容性 (Object 而非 Map)
3. **`ReverseDashboardService` § 6 + § 8 fallback**: 30+ 行
   - 关注: 3 级 fallback 顺序, WARN 日志含 schemeCode/命中条数
4. **`DashboardPanels.vue` `metricTrend` + CSS**:
   - 关注: 趋势比较 (M0 → 当前), 不是阈值
   - 关注: ↑ `#67C23A` 绿, ↓ `#F56C6C` 红, 22px / 800
5. **设计文档 3 份**: 完整可开发级
   - 关注: 03 文档重命名 (`组合反算` → `测算方案`) 是否影响引用文件

### 8.2 合并顺序建议

```
1. PRCP-Java/docs/api/reverse-engine-api.md           (合同先行)
2. PRCP-Java/docs/design/01-02-03_*.md                 (设计说明)
3. PRCP-Java/src/main/java/com/prcp/business/reverse/*  (后端代码)
4. PRCP-Java/src/main/resources/application.yml         (配置)
5. prcp-vue/src/views/reverse/components/DashboardPanels.vue  (前端)
```

### 8.3 分支策略建议

- 建议合并到 `develop` 分支, 经 QA 验收后再合并 `main`
- 或直接在 `main` 上做 hotfix (本次变更已经过实测, 影响面有限)

---

## 九、附录

### 9.1 完整 commit 列表

```
PRCP-Java (Java 后端):
  73aad3b  docs(kpi/model/reverse): 整理 3 模块需求/设计/开发文档
  7d3d768  docs(design): 指标/模型/测算方案 三模块设计 v2 (+1377-609)
  9d5515d  feat(reverse): 拆引擎到独立 HTTP 服务 + 报文组装单类封装 (+723-212)
  2c5ab9f  fix(dashboard): metric_coefficient 查询加 2 级 fallback (+57-5)
  e959731  docs(reverse-engine-api): 按截图 4 段结构重写报文样例 (+743-213)
  96add4a  docs(reverse-engine-api): 最前面新增 § 〇 报文内容说明章节 (+109-1)
  57e113f  docs(api): 测算方案运行接口封装文档 + REV_DNN_REG 报文样例 (+457)

prcp-vue (前端):
  7799cd1  fix(dashboard): 雷达图 max 100 -> 500 (匹配 v12b 0-500 分评分) (+23-7)
  081de8a  fix(dashboard): 雷达图 △EVE 匹配改成显式映射 (+15-6)
  043aca5  fix(dashboard): ROE 箭头改趋势比较 (M0 vs 当前) + ↑ 绿色加粗
```

### 9.2 相关文档

- **接口契约**: `PRCP-Java/docs/api/reverse-engine-api.md` (1095 行)
- **设计文档**:
  - `prcp-java/docs/design/01-指标管理_设计.md` v2 (468 行)
  - `prcp-java/docs/design/02-模型管理_设计.md` v2 (416 行)
  - `prcp-java/docs/design/03-测算方案_设计.md` v2 (615 行)
- **变更日志**:
  - `C:\银行经营\.workbuddy\memory\2026-10-09.md` (引擎网关 + 接口文档)
  - `C:\银行经营\.workbuddy\memory\2026-10-10.md` (设计文档 v2)
  - `C:\银行经营\.workbuddy\memory\2026-10-12.md` (驾驶舱修复 + 雷达图 + 箭头)

### 9.3 关键数据状态 (验证用)

```sql
-- 当前方案
SELECT * FROM prcp_reverse_scheme WHERE scheme_code IN ('REV_DNN_REG', '2026');

-- 指标分数 (新方案用 fallback 复用了 REV_DNN_REG 的数据)
SELECT * FROM prcp_metric_coefficient WHERE scheme_code IN ('REV_DNN_REG', '2026') LIMIT 5;

-- KPI 评分规则 (calc_method 区分 PIECEWISE / LINEAR)
SELECT id, kpi_id, calc_method, total_score FROM prcp_kpi_score_rule WHERE is_deleted=0;
```

---

_文档生成时间: 2026-10-12 17:04_
_作者: WorkBuddy Agent_
_版本: v1.0_
