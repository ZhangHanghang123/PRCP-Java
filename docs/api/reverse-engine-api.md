# 测算方案 (Reverse) 引擎接口文档

> 版本: 2026-10-09
> 用途: 封装「测算方案」页面的【运行】按钮调用报文，支持将反算引擎部署到独立服务器
> 源模块: `com.prcp.business.reverse.ReverseController` + `reverse/Index.vue`

---

## 一、当前调用链路 (现状)

```
[Vue: 测算方案 Tab]
  └─ 点击【运行】(runScheme)
       ├─ 1. POST /prcp-java/api/reverse/runs          (创建 Run 记录, status=PENDING)
       │       Body: { scheme_id, description }
       │       ← 返回 { id, run_code }
       │
       └─ 2. POST /prcp-java/api/reverse/runs/{rid}/start  (启动 Run, 改为 RUNNING)
                ← 后端用 CompletableFuture 在进程内 EXEC 线程池执行
                ← 当前 executeReverse() 直接调用 solve() 方法，无 HTTP 调用

[后端 ReverseService.executeReverse(rid)]  ← 即将拆出来变成独立 HTTP 服务
```

### 改造目标

把 `executeReverse(rid)` 从进程内调用改成 **HTTP POST 到独立引擎服务器**。
本接口文档就是引擎服务端的契约。

---

## 二、引擎服务端 HTTP 接口

### 2.1 提交反算任务

**Endpoint**: `POST {ENGINE_BASE_URL}/api/v1/reverse/execute`

**Headers**:

| 字段 | 必填 | 示例 | 说明 |
|---|---|---|---|
| `Content-Type` | ✅ | `application/json` | JSON 报文 |
| `X-Request-Id` | 推荐 | `req-1734567890123` | 链路追踪 ID |
| `X-Auth-Token` | 可选 | `Bearer xxx` | 引擎服务认证 (JWT/OAuth2) |

**Request Body**: 完整报文见 [§ 三、报文样例](#三报文样例-rev_dnn_reg)

**Response (同步, 引擎同步返回 Run 已受理)**:

```json
{
  "code": 0,
  "msg": "OK",
  "data": {
    "accepted": true,
    "engine_run_id": "ENG-20261009-1734567890123",
    "estimated_seconds": 30,
    "callback_url": "https://wxfzhh.online/prcp-java/api/reverse/runs/100/callback"
  }
}
```

> 引擎服务采用 **异步处理**: 先返回受理成功 (`accepted: true`)，实际计算完成后通过 [§ 2.2 回调接口](#22-回调结果) 把结果 POST 回主服务。

**Response (异常)**:

```json
{
  "code": 40001,
  "msg": "参数校验失败",
  "data": {
    "field": "horizon_months",
    "reason": "必须在 [1, 120] 之间"
  }
}
```

### 2.2 回调结果

引擎完成反算后，主动 POST 回主服务。

**Endpoint (主服务侧提供)**: `POST /prcp-java/api/reverse/runs/{rid}/callback`

**Request Body**:

```json
{
  "run_id": 100,
  "engine_run_id": "ENG-20261009-1734567890123",
  "status": "SUCCESS",                          // SUCCESS | FAILED | CANCELLED
  "duration_sec": 28.7,
  "optimal_value": 0.0312,                       // 最优解的目标函数值
  "metrics": {                                   // JSON 序列化指标
    "solve_method": "DNN_NEURAL_NETWORK",
    "iterations": 1247,
    "converged": true,
    "final_loss": 0.000123,
    "kpi_actual": {
      "REG_LCR": 152.34,                         // 求解后实际 KPI 值
      "REG_NSFR": 113.45,
      "REG_CET1": 10.78,
      "REG_ROE":  10.32
    }
  },
  "results": [                                   // 反算结果 (每月每节点的预测余额)
    {
      "predict_month": 1,
      "predict_date": "2026-01-31",
      "rpt_item_code": "S010101000000",
      "rpt_item_name": "1.境内人民币各项贷款",
      "current_value":   8380847647531.74,
      "adjusted_value":  8548464600482.38,
      "delta_value":     167616952950.64
    }
  ],
  "error_message": null                          // FAILED 时填具体错误
}
```

**Response (主服务侧确认)**:

```json
{
  "code": 0,
  "msg": "OK",
  "data": { "received": true }
}
```

---

## 三、报文样例 (REV_DNN_REG)

### 3.1 触发命令 (前端 → 后端 → 引擎)

前端【运行】按钮触发后，主服务组装完整报文如下:

```json
{
  "request_id": "req-20261009-1734567890123-abc123",
  "submitted_at": "2026-10-09T16:13:04+08:00",
  "submitted_by": "admin",

  "callback": {
    "url": "https://wxfzhh.online/prcp-java/api/reverse/runs/{rid}/callback",
    "method": "POST",
    "auth_token": "Bearer eyJhbGciOiJIUzI1NiJ9...",
    "timeout_sec": 60
  },

  "run": {
    "id": 100,
    "run_code": "RR1734567890123"
  },

  "scheme": {
    "id": 24,
    "scheme_code": "REV_DNN_REG",
    "scheme_name": "DNN 神经网络综合反算",
    "scheme_type": "COMBINED",
    "description": "基于 DNN_REG 模型对监管指标 + 规模指标做 24 月综合反算",
    "data_date": "2025-12-31",
    "horizon_months": 24,
    "algorithm": "DNN_NEURAL_NETWORK",
    "status": "ACTIVE"
  },

  "model": {
    "id": 1,
    "model_code": "DNN_REG",
    "model_name": "DNN 神经网络回归模型",
    "model_type": "DNN_NEURAL_NETWORK",
    "version": "V1.0",
    "model_url": "https://wxfzhh.online/prcp-java/api/model/models/1/download",
    "model_checksum": "sha256:abc123..."
  },

  "coa_scheme": {
    "id": 1,
    "scheme_code": "ZX_COA",
    "scheme_name": "中信银行账户册方案 ZX_COA"
  },

  "targets": [
    {
      "id": 53,
      "kpi_code": "REG_LCR",
      "target_name": "≥150%",
      "target_value": 150.0,
      "constraint_type": "GE",
      "weight": 1.5,
      "horizon_month": 24,
      "sort_order": 1
    },
    {
      "id": 54,
      "kpi_code": "REG_NSFR",
      "target_name": "≥110%",
      "target_value": 110.0,
      "constraint_type": "GE",
      "weight": 1.5,
      "horizon_month": 24,
      "sort_order": 2
    },
    {
      "id": 55,
      "kpi_code": "REG_CET1",
      "target_name": "≥10.5%",
      "target_value": 10.5,
      "constraint_type": "GE",
      "weight": 2.0,
      "horizon_month": 24,
      "sort_order": 3
    },
    {
      "id": 56,
      "kpi_code": "REG_ROE",
      "target_name": "≥10%",
      "target_value": 10.0,
      "constraint_type": "GE",
      "weight": 1.0,
      "horizon_month": 24,
      "sort_order": 4
    }
  ],

  "initial_balances": {
    "data_date": "2025-12-31",
    "snapshot_id": "snap-2025-12-31-zx-coa",
    "nodes": [
      { "node_id": 1,  "node_code": "S010000000000", "node_name": "总资产",                  "node_level": 1, "current_balance":        0.00, "weighted_rate": 0.0000 },
      { "node_id": 4,  "node_code": "S010100000000", "node_name": "（一）人民币小计",      "node_level": 2, "current_balance":        0.00, "weighted_rate": 0.0000 },
      { "node_id": 8,  "node_code": "S010101000000", "node_name": "1.境内人民币各项贷款","node_level": 3, "current_balance": 8380847647531.74, "weighted_rate": 0.0000 },
      { "node_id": 9,  "node_code": "S010102000000", "node_name": "2.人民币非信贷类业务","node_level": 3, "current_balance": 2613045206589.05, "weighted_rate": 0.0000 },
      { "node_id": 10, "node_code": "S010103000000", "node_name": "3.人民币非生息资产",  "node_level": 3, "current_balance":  867778998074.61, "weighted_rate": 0.0000 },
      { "node_id": 5,  "node_code": "S010200000000", "node_name": "（二）外币小计（美元）","node_level": 2, "current_balance":        0.00, "weighted_rate": 0.0000 },
      { "node_id": 11, "node_code": "S010201000000", "node_name": "1.外币贷款",           "node_level": 3, "current_balance":   80000000000.00, "weighted_rate": 0.0550 },
      { "node_id": 12, "node_code": "S010202000000", "node_name": "2.外币非信贷资产",     "node_level": 3, "current_balance":        0.00, "weighted_rate": 0.0000 },
      { "node_id": 13, "node_code": "S010203000000", "node_name": "3.外币非生息资产",     "node_level": 3, "current_balance":   10000000000.00, "weighted_rate": 0.0000 },
      { "node_id": 2,  "node_code": "S020000000000", "node_name": "总负债",                  "node_level": 1, "current_balance":        0.00, "weighted_rate": 0.0000 },
      { "node_id": 6,  "node_code": "S020100000000", "node_name": "（一）人民币小计",      "node_level": 2, "current_balance":        0.00, "weighted_rate": 0.0000 },
      { "node_id": 14, "node_code": "S020101000000", "node_name": "1.境内人民币自营存款","node_level": 3, "current_balance":        0.00, "weighted_rate": 0.0000 },
      { "node_id": 15, "node_code": "S020102000000", "node_name": "2.人民币市场化负债",  "node_level": 3, "current_balance":        0.00, "weighted_rate": 0.0000 },
      { "node_id": 7,  "node_code": "S020200000000", "node_name": "（二）外币小计（美元）","node_level": 2, "current_balance":        0.00, "weighted_rate": 0.0000 },
      { "node_id": 16, "node_code": "S020201000000", "node_name": "1.外币自营存款",       "node_level": 3, "current_balance":        0.00, "weighted_rate": 0.0000 },
      { "node_id": 17, "node_code": "S020202000000", "node_name": "2.外币市场化负债",     "node_level": 3, "current_balance":        0.00, "weighted_rate": 0.0000 },
      { "node_id": 3,  "node_code": "S030000000000", "node_name": "所有者权益",              "node_level": 1, "current_balance":  786217000000.00, "weighted_rate": 0.0000 }
    ]
  },

  "params": {
    "learning_rate": 0.001,
    "epochs": 500,
    "batch_size": 32,
    "early_stopping_patience": 20,
    "random_seed": 42,
    "gpu_enabled": true,
    "precision": "float32"
  }
}
```

> ⚠️ 实际报文可能更长 — 上面只展示前 17 个 level≤3 节点；如包含 level=4 节点会更多。建议引擎支持 `Accept-Encoding: gzip`，报文可能 500KB+。

### 3.2 字段对照表

| 字段 | 类型 | 必填 | 含义 |
|---|---|---|---|
| `request_id` | String | ✅ | 链路追踪 ID (UUID) |
| `submitted_at` | ISO-8601 | ✅ | 主服务提交时间 |
| `submitted_by` | String | ✅ | 操作用户 |
| `callback.url` | String | ✅ | 引擎完成时回调主服务的 URL |
| `callback.auth_token` | String | ✅ | 回调时的 Bearer Token |
| `run.id` | Long | ✅ | 主服务 run 表的 ID (用于回调关联) |
| `run.run_code` | String | ✅ | 人类可读 run 编码 (RR{timestamp}) |
| `scheme.id` | Long | ✅ | 方案主键 |
| `scheme.algorithm` | String | ✅ | 算法枚举 (HEURISTIC / DNN_NEURAL_NETWORK / ANT_COLONY / NONLINEAR_SOLVER / CVXPY_QP) |
| `scheme.horizon_months` | Int | ✅ | 预测期 (1-120) |
| `model.model_url` | URL | ✅ | 引擎下载模型文件的 URL (可选, 用 CVXPY/HEURISTIC 时可空) |
| `targets[]` | Array | ✅ | 目标约束列表 (≥1 条) |
| `targets[].kpi_code` | String | ✅ | 监管/规模指标编码 (REG_LCR / REG_NSFR / REG_CET1 / REG_ROE / SCALE_LOAN 等) |
| `targets[].target_value` | Decimal | ✅ | 目标值 |
| `targets[].constraint_type` | String | ✅ | GE (≥) / LE (≤) / EQ (=) |
| `targets[].weight` | Decimal | ✅ | 目标权重 (0-10) |
| `targets[].horizon_month` | Int | ✅ | 目标生效月 (1-24) |
| `initial_balances.nodes[]` | Array | ✅ | 当前余额快照 (≥1 条) |
| `initial_balances.nodes[].node_code` | String | ✅ | 账户册节点编码 |
| `initial_balances.nodes[].current_balance` | Decimal | ✅ | 当前余额 (元) |
| `initial_balances.nodes[].weighted_rate` | Decimal | ✅ | 加权平均利率 (0-1) |
| `params` | Object | 可选 | 算法超参 (learning_rate / epochs / seed 等) |

### 3.3 报文大小估算

| 字段 | 单条大小 | 数量 | 小计 |
|---|---|---|---|
| targets | ~150B | 4 | 0.6 KB |
| nodes (level≤3) | ~120B | 17 | 2.0 KB |
| nodes (level≤4) | ~120B | ~80 | 9.6 KB |
| 元数据 + scheme + model | — | — | ~2 KB |
| **合计 (level≤3)** | | | **~5 KB** |
| **合计 (level≤4 + gzip)** | | | **~3 KB** |

→ 即使节点多到 500 条，gzip 后仍 < 100KB，对 HTTP 完全无压力。

---

## 四、改造实施步骤 (主服务侧)

### 4.1 新增 /api/v1/reverse/run 内部接口 (封装报文 + HTTP 调用)

```java
// com.prcp.business.reverse.ReverseController (新增方法)

@PostMapping("/run-by-engine")
public R<Map<String, Object>> runByEngine(@RequestBody Map<String, Object> body) {
    Long schemeId = ((Number) body.get("scheme_id")).longValue();
    String description = (String) body.get("description");

    // 1. 创建 Run 记录 (status=PENDING)
    R<Map<String, Object>> runResp = svc.createRun(body);
    Long rid = ((Number) runResp.getData().get("id")).longValue();

    // 2. 组装引擎报文 (参看 § 三)
    Map<String, Object> enginePayload = engineClient.buildReversePayload(rid, schemeId, description);

    // 3. 异步提交引擎
    CompletableFuture.runAsync(() -> {
        try {
            engineClient.submitToEngine(enginePayload);  // POST {ENGINE_URL}/api/v1/reverse/execute
        } catch (Exception e) {
            svc.markFailed(rid, e.getMessage());
        }
    }, EXEC);

    return R.ok(Map.of("id", rid, "status", "PENDING"));
}
```

### 4.2 新增 EngineClient (HTTP 客户端)

```java
// com.prcp.business.reverse.engine.EngineClient.java

@Component
public class EngineClient {
    @Value("${engine.base-url:http://localhost:8009}")
    private String ENGINE_BASE_URL;

    private final RestTemplate restTemplate;
    private final TokenProvider tokenProvider;

    public Map<String, Object> buildReversePayload(Long rid, Long schemeId, String description) {
        // 1. 加载 scheme + model + coa_scheme + targets + balances
        // 2. 组装 § 三 的 JSON 结构
        return payload;
    }

    public Map<String, Object> submitToEngine(Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Auth-Token", "Bearer " + tokenProvider.getEngineToken());
        headers.set("X-Request-Id", payload.get("request_id").toString());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
            ENGINE_BASE_URL + "/api/v1/reverse/execute", entity, Map.class);
        return resp.getBody();
    }
}
```

### 4.3 新增回调接收端点

```java
// ReverseController (新增)

@PostMapping("/runs/{rid}/callback")
public R<Map<String, Object>> runCallback(@PathVariable Long rid,
                                            @RequestBody Map<String, Object> body) {
    return svc.handleEngineCallback(rid, body);
    // 内部: 写 prcp_reverse_result + 更新 prcp_reverse_run.status='SUCCESS' + 写 metrics
}
```

### 4.4 配置文件 application.yml

```yaml
engine:
  base-url: http://engine-server:8009    # 引擎服务地址
  callback-base-url: https://wxfzhh.online/prcp-java/api
  callback-token: ${ENGINE_CALLBACK_TOKEN:dev-engine-callback-token}
  timeout-sec: 60
```

---

## 五、引擎服务侧契约 (供引擎工程师实现)

### 5.1 必须实现的端点

| 端点 | 方法 | 用途 |
|---|---|---|
| `/api/v1/reverse/execute` | POST | 接收反算任务 (异步处理) |
| `/api/v1/health` | GET | 健康检查 (主服务可选定时探测) |
| `/api/v1/reverse/{engine_run_id}/cancel` | POST | 取消运行 (主服务调用) |

### 5.2 反算算法要求

按 `algorithm` 字段分派:
- `HEURISTIC` → 启发式求解器 (默认, Java 已实现, 直接迁移)
- `DNN_NEURAL_NETWORK` → TensorFlow/PyTorch DNN 回归
- `ANT_COLONY` → 蚁群算法
- `NONLINEAR_SOLVER` → SciPy `least_squares` / fsolve
- `CVXPY_QP` → CVXPY 二次规划

### 5.3 结果精度

- 货币金额: BigDecimal 18 位精度, 返回时截断到 4 位小数
- 比率: 保留 6 位小数 (如 110.123456%)
- 整数 (月数/迭代次数): Int

### 5.4 SLA 建议

- 接口响应时间 ≤ 500ms (同步接收)
- 反算任务总耗时 ≤ 5 分钟 (异步)
- 超时主服务会主动查询 run 状态 (主服务侧有补偿机制)

---

## 六、错误码

| 错误码 | 含义 | 处理方式 |
|---|---|---|
| 0 | 成功 | — |
| 40001 | 参数校验失败 | 检查报文 |
| 40002 | 方案不存在 | 跳过 |
| 40003 | 账户册数据为空 | 提示用户先导入 |
| 40004 | 模型文件下载失败 | 重新训练模型 |
| 50001 | 引擎内部异常 | 重试 3 次后通知运维 |
| 50002 | 求解不收敛 | 调整算法超参 |
| 50003 | 回调失败 (主服务不可达) | 引擎侧本地缓存结果, 待主服务恢复后补偿推送 |

---

## 七、参考资源

- 主服务现状代码: `prcp-business/src/main/java/com/prcp/business/reverse/ReverseService.java`
- 前端现状代码: `prcp-vue/src/views/reverse/Index.vue` (runScheme 方法 line 626)
- Python 源系统参考: `PRCP/backend/app/services/reverse_service.py`
- 当前指标方案样本 SQL:
  ```sql
  SELECT s.*, cs.scheme_code AS coaCode, m.model_code AS modelCode
    FROM prcp_reverse_scheme s
    LEFT JOIN prcp_coa_scheme cs ON cs.id=s.coa_scheme_id
    LEFT JOIN prcp_model m ON m.id=s.model_id
    WHERE s.scheme_code='REV_DNN_REG' AND s.is_deleted=0;
  ```
