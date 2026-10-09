# 测算方案 (Reverse) 引擎接口文档

> 版本: 2026-10-09 v2
> 用途: 封装「测算方案」页面的【运行】按钮调用报文，支持将反算引擎部署到独立服务器
> 源模块: `com.prcp.business.reverse.ReverseController` + `prcp-vue/src/views/reverse/Index.vue`
> 数据库: `prcp_db` (prcp_reverse_scheme / prcp_reverse_target / prcp_kpi_scheme / prcp_kpi_score_rule / prcp_kpi_score_segment / prcp_kpi_definition / prcp_{cet1,lcr,nim,nsfr,roe,eve}_param)

---

## 一、当前调用链路 (现状 → 改造目标)

### 1.1 现状

```
[Vue: 测算方案 Tab]
  └─ 点击【运行】(runScheme, src/views/reverse/Index.vue)
       ├─ 1. POST /reverse/runs          (创建 Run 记录, status=PENDING)
       │       Body: { scheme_id, description }
       │       ← 返回 { id, run_code }
       │
       └─ 2. POST /reverse/runs/{rid}/start  (启动 Run, status → RUNNING)
                ← 后端 CompletableFuture.runAsync(executeReverse, EXEC 池)
                ← 当前 executeReverse() 直接调用 solve() 方法，无 HTTP 调用

[后端 ReverseService.executeReverse(rid)]
       ├─ 加载 run + scheme (ReverseService.java line 502)
       ├─ 加载 targets (ReverseService.java line 517)
       ├─ 加载账户册 + 余额 (prcp_data_basic + prcp_coa_node, line 525)
       ├─ 启发式求解 (line 536)
       └─ 写 prcp_reverse_result + 更新 run.status='SUCCESS'
```

### 1.2 改造目标

把 `executeReverse(rid)` 从进程内调用改成 **HTTP POST 到独立引擎服务器**。
本接口文档就是**引擎服务端**的契约；本服务 (Java) 把第 1.1 节的「加载 + 组装」拆出来 HTTP 化。

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

**Request Body 顶层结构** (4 段 + 4 元数据):

```jsonc
{
  // ----- 4 个 HTTP 元数据字段 (HTTP/链路控制) -----
  "request_id":    "req-20261009-1734567890123-abc123",   // 链路追踪 ID
  "submitted_at":  "2026-10-09T16:13:04+08:00",          // 主服务提交时间 (ISO-8601)
  "submitted_by":  "admin",                              // 操作用户
  "callback": {                                          // 回调配置
    "url":         "https://wxfzhh.online/prcp-java/api/reverse/runs/{rid}/callback",
    "method":      "POST",
    "auth_token":  "Bearer eyJhbGciOiJIUzI1NiJ9...",
    "timeout_sec": 60
  },

  // ----- 4 段报文主体 (按截图顺序) -----
  "scheme":       { ... },   // ① 测算方案实体
  "targets":      [ ... ],   // ② 目标设置 (集合)
  "kpi_schemes":  [ ... ],   // ③ 指标评分方案 (集合)
  "kpi_params":   { ... },   // ④ 指标计量参数 (6 张参数补录表集合)

  // ----- 算法超参 (可选) -----
  "params":       { ... }
}
```

完整报文样例: 见 [§ 三、报文样例 REV_DNN_REG](#三报文样例-rev_dnn_reg)

**Response (同步受理)**:

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

> 引擎采用 **异步处理**: 先返回受理成功 (`accepted: true`)，实际计算完成后通过 [§ 2.2 回调接口](#22-回调结果) 把结果 POST 回主服务。

**Response (异常)**:

```json
{
  "code": 40001,
  "msg": "参数校验失败",
  "data": {
    "field": "scheme.horizon_months",
    "reason": "必须在 [1, 120] 之间"
  }
}
```

### 2.2 回调结果

引擎完成反算后，主动 POST 回主服务。

**Endpoint (主服务侧提供)**: `POST /reverse/runs/{rid}/callback`

**Request Body**:

```json
{
  "run_id":        100,
  "engine_run_id": "ENG-20261009-1734567890123",
  "status":        "SUCCESS",                           // SUCCESS | FAILED | CANCELLED
  "duration_sec":  28.7,
  "optimal_value": 0.0312,                              // 最优解的目标函数值
  "metrics": {                                          // 求解过程指标
    "solve_method":  "DNN_NEURAL_NETWORK",
    "iterations":    1247,
    "converged":     true,
    "final_loss":    0.000123,
    "kpi_actual": {
      "REG_LCR":   152.34,                              // 求解后实际 KPI 值
      "REG_NSFR":  113.45,
      "REG_CET1":  10.78,
      "REG_ROE":   10.32
    },
    "kpi_scores": {                                     // 根据目标③的评分方案打分
      "REG_LCR":   95.5,
      "REG_NSFR":  90.0,
      "REG_CET1":  88.0,
      "REG_ROE":   75.0
    }
  },
  "results": [                                          // 月度余额预测
    {
      "predict_month":  1,
      "predict_date":   "2026-01-31",
      "rpt_item_code":  "S010101000000",
      "rpt_item_name":  "1.境内人民币各项贷款",
      "current_value":  8380847647531.74,
      "adjusted_value": 8548464600482.38,
      "delta_value":    167616952950.64
    }
  ],
  "error_message": null
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

> 触发流程：用户在「测算方案」Tab 点击【运行】，引擎服务接收如下完整报文。
> 测算方案编号 = `REV_DNN_REG`，4 个目标约束，20 个 level≤3 账户节点，6 张参数补录表各 1 个示例行。

### 3.1 完整报文

```json
{
  "//meta": "========================= HTTP/链路元数据 =========================",
  "request_id":   "req-20261009-1734567890123-abc123",
  "submitted_at": "2026-10-09T16:13:04+08:00",
  "submitted_by": "admin",
  "callback": {
    "url":         "https://wxfzhh.online/prcp-java/api/reverse/runs/{rid}/callback",
    "method":      "POST",
    "auth_token":  "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJlbmcifQ.xxx",
    "timeout_sec": 60
  },
  "run": {
    "id":       100,
    "run_code": "RR1734567890123"
  },

  "//scheme": "========================= ① 测算方案实体 (prcp_reverse_scheme) =========================",
  "scheme": {
    "id":              24,
    "scheme_code":     "REV_DNN_REG",
    "scheme_name":     "DNN 神经网络综合反算",
    "scheme_type":     "COMBINED",
    "description":     "基于 DNN_REG 模型对监管指标 + 规模指标做 24 月综合反算",
    "data_date":       "2025-12-31",
    "horizon_months":  24,
    "algorithm":       "DNN_NEURAL_NETWORK",
    "status":          "ACTIVE",
    "model_id":        1,
    "coa_scheme_id":   1,
    "created_by":      1,
    "updated_by":      1,
    "created_at":      "2026-09-15T10:23:11",
    "updated_at":      "2026-10-08T14:02:33",
    "//关联": "附带模型和账户册的最小展示字段，方便引擎校验",
    "model": {
      "id":         1,
      "model_code": "DNN_REG",
      "model_name": "DNN 神经网络回归模型",
      "model_type": "DNN_NEURAL_NETWORK",
      "version":    "V1.0",
      "model_url":  "https://wxfzhh.online/prcp-java/api/model/models/1/download",
      "checksum":   "sha256:abc123..."
    },
    "coa_scheme": {
      "id":          1,
      "scheme_code": "ZX_COA",
      "scheme_name": "中信银行账户册方案 ZX_COA"
    }
  },

  "//targets": "========================= ② 目标设置 (prcp_reverse_target, 按 scheme_code 查) =========================",
  "targets": [
    {
      "id":              53,
      "scheme_id":       24,
      "kpi_id":          1,
      "kpi_code":        "REG_LCR",
      "target_name":     "流动性覆盖率 ≥150%",
      "target_value":    150.0,
      "constraint_type": "GE",
      "weight":          1.5,
      "horizon_month":   24,
      "sort_order":      1,
      "description":     "LCR 期末月须 ≥150%"
    },
    {
      "id":              54,
      "scheme_id":       24,
      "kpi_id":          2,
      "kpi_code":        "REG_NSFR",
      "target_name":     "净稳定资金比例 ≥110%",
      "target_value":    110.0,
      "constraint_type": "GE",
      "weight":          1.5,
      "horizon_month":   24,
      "sort_order":      2,
      "description":     "NSFR 期末月须 ≥110%"
    },
    {
      "id":              55,
      "scheme_id":       24,
      "kpi_id":          3,
      "kpi_code":        "REG_CET1",
      "target_name":     "核心一级资本充足率 ≥10.5%",
      "target_value":    10.5,
      "constraint_type": "GE",
      "weight":          2.0,
      "horizon_month":   24,
      "sort_order":      3,
      "description":     "CET1 期末月须 ≥10.5%"
    },
    {
      "id":              56,
      "scheme_id":       24,
      "kpi_id":          4,
      "kpi_code":        "REG_ROE",
      "target_name":     "净资产收益率 ≥10%",
      "target_value":    10.0,
      "constraint_type": "GE",
      "weight":          1.0,
      "horizon_month":   24,
      "sort_order":      4,
      "description":     "ROE 期末月须 ≥10%"
    }
  ],

  "//kpi_schemes": "========================= ③ 指标评分方案 =========================",
  "//来源": "1. 根据 scheme.algorithm='DNN_NEURAL_NETWORK' 找到对应指标方案 KpiScheme.scheme_code='KPI_REG_V1';",
  "//      2. 关联 KpiScoreRule (scheme_id=KpiScheme.id, kpi_id ∈ {1,2,3,4});",
  "//      3. 每条 Rule 拉 KpiScoreSegment + KpiDefinition 详情;",
  "//      4. 全部封装成集合，供引擎结算时评分使用。",
  "kpi_schemes": [
    {
      "scheme": {
        "id":           3,
        "scheme_code":  "KPI_REG_V1",
        "scheme_name":  "监管指标方案 V1",
        "description":  "LCR/NSFR/CET1/ROE 四项监管指标的评分规则",
        "kpi_count":    4,
        "status":       "ACTIVE",
        "created_at":   "2026-08-12T10:00:00",
        "updated_at":   "2026-10-01T09:00:00"
      },
      "score_rules": [
        {
          "//规则": "LCR 分段评分 (PIECEWISE)，越接近 150% 分数越高",
          "rule": {
            "id":              101,
            "scheme_id":       3,
            "kpi_id":          1,
            "rule_name":       "LCR 监管阈值评分",
            "calc_method":     "PIECEWISE",
            "total_score":     100.00,
            "higher_is_better":1,
            "description":     "[0,100) 0分; [100,130) 60; [130,150) 80; ≥150 100",
            "status":          "ACTIVE"
          },
          "kpi": {
            "id":             1,
            "scheme_id":      3,
            "kpi_code":       "REG_LCR",
            "kpi_name":       "流动性覆盖率",
            "indicator_type": 1,
            "formula":        "HQLA / (现金净流出30天) * 100%",
            "calc_unit":      "%",
            "formula_desc":   "合格优质流动性资产 ÷ 30 天净现金流出",
            "threshold_min":  100.0,
            "threshold_max":  null,
            "status":         "ACTIVE"
          },
          "segments": [
            { "id": 1001, "rule_id": 101, "seg_order": 1, "min_value":    0.0, "max_value": 100.0, "score":  0.0, "segment_desc": "未达标" },
            { "id": 1002, "rule_id": 101, "seg_order": 2, "min_value":  100.0, "max_value": 130.0, "score": 60.0, "segment_desc": "基本达标" },
            { "id": 1003, "rule_id": 101, "seg_order": 3, "min_value":  130.0, "max_value": 150.0, "score": 80.0, "segment_desc": "达标" },
            { "id": 1004, "rule_id": 101, "seg_order": 4, "min_value":  150.0, "max_value":  null, "score":100.0, "segment_desc": "优" }
          ]
        },
        {
          "rule": {
            "id":              102,
            "scheme_id":       3,
            "kpi_id":          2,
            "rule_name":       "NSFR 监管阈值评分 (线性)",
            "calc_method":     "LINEAR",
            "total_score":     100.00,
            "higher_is_better":1,
            "description":     "100%=0, 130%=100, 100-130 线性插值",
            "status":          "ACTIVE"
          },
          "kpi": {
            "id":             2,
            "scheme_id":      3,
            "kpi_code":       "REG_NSFR",
            "kpi_name":       "净稳定资金比例",
            "indicator_type": 1,
            "formula":        "可用稳定资金 ASF / 所需稳定资金 RSF * 100%",
            "calc_unit":      "%",
            "formula_desc":   "ASF / RSF",
            "threshold_min":  100.0,
            "threshold_max":  null,
            "status":         "ACTIVE"
          },
          "anchors": [
            { "id": 2001, "rule_id": 102, "anchor_order": 1, "x_value": 100.0, "score":   0.0, "anchor_desc": "开始" },
            { "id": 2002, "rule_id": 102, "anchor_order": 2, "x_value": 130.0, "score": 100.0, "anchor_desc": "优秀" }
          ]
        },
        {
          "rule": {
            "id":              103,
            "scheme_id":       3,
            "kpi_id":          3,
            "rule_name":       "CET1 监管阈值评分 (分段)",
            "calc_method":     "PIECEWISE",
            "total_score":     100.00,
            "higher_is_better":1,
            "description":     "≥10.5%=100, [9,10.5)=70, [8,9)=40, <8=0",
            "status":          "ACTIVE"
          },
          "kpi": {
            "id":             3,
            "scheme_id":      3,
            "kpi_code":       "REG_CET1",
            "kpi_name":       "核心一级资本充足率",
            "indicator_type": 1,
            "formula":        "(核心一级资本 - 资本扣除项) / 风险加权资产 * 100%",
            "calc_unit":      "%",
            "formula_desc":   "(实缴普通股+资本公积+留存收益-扣项) / RWA",
            "threshold_min":  8.0,
            "threshold_max":  null,
            "status":         "ACTIVE"
          },
          "segments": [
            { "id": 3001, "rule_id": 103, "seg_order": 1, "min_value":    0.0, "max_value":    8.0, "score":   0.0, "segment_desc": "严重不足" },
            { "id": 3002, "rule_id": 103, "seg_order": 2, "min_value":    8.0, "max_value":    9.0, "score":  40.0, "segment_desc": "不足" },
            { "id": 3003, "rule_id": 103, "seg_order": 3, "min_value":    9.0, "max_value":   10.5, "score":  70.0, "segment_desc": "接近达标" },
            { "id": 3004, "rule_id": 103, "seg_order": 4, "min_value":   10.5, "max_value":  null, "score": 100.0, "segment_desc": "达标" }
          ]
        },
        {
          "rule": {
            "id":              104,
            "scheme_id":       3,
            "kpi_id":          4,
            "rule_name":       "ROE 监管阈值评分",
            "calc_method":     "PIECEWISE",
            "total_score":     100.00,
            "higher_is_better":1,
            "description":     "≥12%=100, [10,12)=80, [8,10)=50, <8=20",
            "status":          "ACTIVE"
          },
          "kpi": {
            "id":             4,
            "scheme_id":      3,
            "kpi_code":       "REG_ROE",
            "kpi_name":       "净资产收益率",
            "indicator_type": 1,
            "formula":        "净利润 / 平均净资产 * 100%",
            "calc_unit":      "%",
            "formula_desc":   "净利润 / 平均净资产",
            "threshold_min":  8.0,
            "threshold_max":  null,
            "status":         "ACTIVE"
          },
          "segments": [
            { "id": 4001, "rule_id": 104, "seg_order": 1, "min_value":    0.0, "max_value":    8.0, "score":  20.0, "segment_desc": "低" },
            { "id": 4002, "rule_id": 104, "seg_order": 2, "min_value":    8.0, "max_value":   10.0, "score":  50.0, "segment_desc": "一般" },
            { "id": 4003, "rule_id": 104, "seg_order": 3, "min_value":   10.0, "max_value":   12.0, "score":  80.0, "segment_desc": "达标" },
            { "id": 4004, "rule_id": 104, "seg_order": 4, "min_value":   12.0, "max_value":  null, "score": 100.0, "segment_desc": "优" }
          ]
        }
      ]
    }
  ],

  "//kpi_params": "========================= ④ 指标计量参数 =========================",
  "//来源": "根据 scheme.data_date='2025-12-31' 同时查 6 张参数补录表;",
  "//      每张表返回 is_deleted=0 AND data_date=scheme.data_date AND scheme_code=scheme.data_date 对应方案的全部行",
  "//      实际场景每张表通常含 30~200 行 (按账户册节点); 示例仅展示首行。",
  "kpi_params": {
    "cet1": [
      {
        "id":                "REV_DNN_REG_S010101000000_20251231",
        "scheme_id":         24,
        "scheme_code":       "REV_DNN_REG",
        "node_id":           8,
        "node_code":         "S010101000000",
        "node_name":         "1.境内人民币各项贷款",
        "data_date":         "2025-12-31",
        "is_numerator":      0,
        "numerator_factor":  null,
        "numerator_operator":null,
        "is_rwa":            1,
        "rwa_weight":        1.0000,
        "rwa_operator":      "*",
        "numerator_category":null,
        "rwa_category":      "CREDIT_RWA_STANDARD",
        "current_balance":   8380847647531.74,
        "rule_note":         "对公一般贷款 RWA 权重 100%",
        "status":            "ACTIVE"
      }
    ],
    "lcr": [
      {
        "id":                "REV_DNN_REG_S010101000000_20251231",
        "scheme_id":         24,
        "scheme_code":       "REV_DNN_REG",
        "node_id":           8,
        "node_code":         "S010101000000",
        "node_name":         "1.境内人民币各项贷款",
        "data_date":         "2025-12-31",
        "is_numerator":      0,
        "num_factor":        null,
        "num_operator":      null,
        "is_denominator":    1,
        "den_factor":        0.0500,
        "den_operator":      "*",
        "current_balance":   8380847647531.74,
        "rule_note":         "贷款 30 天净流出率 5%",
        "status":            "ACTIVE"
      }
    ],
    "nim": [
      {
        "id":                "REV_DNN_REG_S010101000000_20251231",
        "scheme_id":         24,
        "scheme_code":       "REV_DNN_REG",
        "node_id":           8,
        "node_code":         "S010101000000",
        "node_name":         "1.境内人民币各项贷款",
        "data_date":         "2025-12-31",
        "is_interest_asset": 1,
        "asset_rate":        0.047500,
        "asset_operator":    "*",
        "asset_category":    "LOAN_CORPORATE",
        "is_interest_liability": 0,
        "liability_rate":    null,
        "liability_operator":null,
        "liability_category":null,
        "current_balance":   8380847647531.74,
        "rule_note":         "对公一般贷款利率 4.75%",
        "status":            "ACTIVE"
      }
    ],
    "nsfr": [
      {
        "id":                "REV_DNN_REG_S010101000000_20251231",
        "scheme_id":         24,
        "scheme_code":       "REV_DNN_REG",
        "node_id":           8,
        "node_code":         "S010101000000",
        "node_name":         "1.境内人民币各项贷款",
        "data_date":         "2025-12-31",
        "is_asf":            0,
        "asf_factor":        null,
        "asf_operator":      null,
        "is_rsf":            1,
        "rsf_factor":        0.8500,
        "rsf_operator":      "*",
        "rule_note":         "未质押贷款 RSF 85%",
        "status":            "ACTIVE"
      }
    ],
    "roe": [
      {
        "id":                "REV_DNN_REG_S030000000000_20251231",
        "scheme_id":         24,
        "scheme_code":       "REV_DNN_REG",
        "node_id":           3,
        "node_code":         "S030000000000",
        "node_name":         "所有者权益",
        "data_date":         "2025-12-31",
        "is_net_profit":     0,
        "net_profit_symbol": null,
        "net_profit_factor": null,
        "net_profit_category":null,
        "is_net_asset":      1,
        "net_asset_symbol":  "+",
        "net_asset_factor":  1.0000,
        "net_asset_category":"TOTAL_EQUITY",
        "current_balance":   786217000000.00,
        "rule_note":         "分母: 全部所有者权益",
        "status":            "ACTIVE"
      }
    ],
    "eve": [
      {
        "id":                "REV_DNN_REG_S010101000000_20251231",
        "scheme_id":         24,
        "scheme_code":       "REV_DNN_REG",
        "node_id":           8,
        "node_code":         "S010101000000",
        "node_name":         "1.境内人民币各项贷款",
        "data_date":         "2025-12-31",
        "is_asset":          1,
        "asset_type":        "LOAN_CORPORATE",
        "asset_category":    "REPRICE_3M",
        "asset_operator":    "*",
        "is_liability":      0,
        "liability_type":    null,
        "liability_category":null,
        "liability_operator":null,
        "duration":          1.8500,
        "current_balance":   8380847647531.74,
        "rule_note":         "对公一般贷款 3M 重定价，久期 1.85",
        "status":            "ACTIVE"
      }
    ]
  },

  "//algo-params": "========================= 算法超参 (DNN 默认) =========================",
  "params": {
    "learning_rate":             0.001,
    "epochs":                    500,
    "batch_size":                32,
    "early_stopping_patience":   20,
    "random_seed":               42,
    "gpu_enabled":               true,
    "precision":                 "float32",
    "constraint_tolerance":      1e-4,
    "max_iterations":            5000
  }
}
```

### 3.2 报文大小估算

| 字段 | 单条 | 数量 | 小计 |
|---|---|---|---|
| `scheme` | ~600B | 1 | 0.6 KB |
| `targets` | ~250B | 4 | 1.0 KB |
| `kpi_schemes[].score_rules` | ~500B | 4 | 2.0 KB |
| `kpi_schemes[].score_rules[].segments` | ~150B | 12 (avg 3/规则) | 1.8 KB |
| `kpi_schemes[].score_rules[].anchors` | ~150B | 2 | 0.3 KB |
| `kpi_params.{cet1,lcr,nim,nsfr,roe,eve}` | ~400B | 6 × 30 = 180 | 72 KB |
| `callback + meta` | — | — | 0.5 KB |
| **合计 (压缩前)** | | | **~78 KB** |
| **合计 (gzip 后)** | | | **~12 KB** |

→ 即便全部 6 张参数补录表×200 行 / 全 4 节点 level≤4，gzip 后仍 < 200 KB，对 HTTP 完全无压力。

---

## 四、字段对照表

### 4.1 元数据 / 链路层

| 字段 | 类型 | 必填 | 含义 |
|---|---|---|---|
| `request_id` | String | ✅ | 链路追踪 ID (UUID/雪花) |
| `submitted_at` | ISO-8601 | ✅ | 主服务提交时间 |
| `submitted_by` | String | ✅ | 操作用户 |
| `callback.url` | String | ✅ | 引擎完成时回调 URL，含 `{rid}` |
| `callback.method` | String | ✅ | 默认 `POST` |
| `callback.auth_token` | String | ✅ | Bearer Token |
| `callback.timeout_sec` | Int | ✅ | 主服务等待回调超时，默认 60 |
| `run.id` | Long | ✅ | 主服务 `prcp_reverse_run` ID |
| `run.run_code` | String | ✅ | RR{timestamp} |

### 4.2 第①段 — 测算方案实体 `scheme`

> 来源 SQL: `SELECT * FROM prcp_reverse_scheme WHERE id=?`

| 字段 | 类型 | 必填 | 含义 |
|---|---|---|---|
| `scheme.id` | Long | ✅ | prcp_reverse_scheme 主键 |
| `scheme.scheme_code` | String | ✅ | 方案编码 (如 `REV_DNN_REG`) |
| `scheme.scheme_name` | String | ✅ | 方案名称 |
| `scheme.scheme_type` | String | ✅ | COMBINED / OPTIMIZE / SOLVE |
| `scheme.description` | String | ✅ | 描述 |
| `scheme.data_date` | Date | ✅ | **数据日期** (用于查 ④ 6 张参数补录表) |
| `scheme.horizon_months` | Int | ✅ | 预测期 1-120，默认 24 |
| `scheme.algorithm` | String | ✅ | HEURISTIC / DNN_NEURAL_NETWORK / ANT_COLONY / NONLINEAR_SOLVER / CVXPY_QP |
| `scheme.status` | String | ✅ | DRAFT / ACTIVE / ARCHIVED |
| `scheme.model_id` | Long | ✅ | `prcp_model.id` (用于选模型) |
| `scheme.coa_scheme_id` | Long | ✅ | `prcp_coa_scheme.id` (账户册方案) |
| `scheme.model.*` | Object | 可选 | 引擎直接读 model_url 下载模型 |
| `scheme.coa_scheme.*` | Object | 可选 | 账户册展示名 |

### 4.3 第②段 — 目标设置 `targets[]`

> 来源 SQL: `SELECT * FROM prcp_reverse_target WHERE scheme_id=? AND is_deleted=0`

| 字段 | 类型 | 必填 | 含义 |
|---|---|---|---|
| `targets[].id` | Long | ✅ | prcp_reverse_target 主键 |
| `targets[].scheme_id` | Long | ✅ | 所属测算方案 |
| `targets[].kpi_id` | Long | ✅ | `prcp_kpi_definition.id` |
| `targets[].kpi_code` | String | ✅ | `prcp_kpi_definition.kpi_code` |
| `targets[].target_name` | String | ✅ | 展示名 (如 `≥150%`) |
| `targets[].target_value` | Decimal | ✅ | 目标值 |
| `targets[].constraint_type` | String | ✅ | `GE` (≥) / `LE` (≤) / `EQ` (=) |
| `targets[].weight` | Decimal | ✅ | 目标权重 (0-10) |
| `targets[].horizon_month` | Int | ✅ | 目标生效月 (1-24) |
| `targets[].sort_order` | Int | ✅ | 展示排序 |
| `targets[].description` | String | 可选 | 说明 |

### 4.4 第③段 — 指标评分方案 `kpi_schemes[]`

> 来源 SQL:
> 1. `SELECT * FROM prcp_kpi_scheme WHERE scheme_code=? AND is_deleted=0`     ← 由 `model_id` 关联表查出
> 2. `SELECT * FROM prcp_kpi_score_rule WHERE scheme_id=? AND kpi_id IN (...)`  ← 关联②中出现的 kpi_id
> 3. `SELECT * FROM prcp_kpi_score_segment WHERE rule_id IN (...)`               ← 每条规则的区间段
> 4. `SELECT * FROM prcp_kpi_definition WHERE id IN (...)`                       ← 关联指标详情

| 字段 | 类型 | 必填 | 含义 |
|---|---|---|---|
| `kpi_schemes[].scheme.*` | Object | ✅ | `prcp_kpi_scheme` 全字段 |
| `kpi_schemes[].scheme.scheme_code` | String | ✅ | 与 prcp_model 关联 |
| `kpi_schemes[].score_rules[]` | Array | ✅ | 该方案下所有评分规则 |
| `kpi_schemes[].score_rules[].rule.*` | Object | ✅ | `prcp_kpi_score_rule` 全字段 |
| `kpi_schemes[].score_rules[].rule.calc_method` | String | ✅ | `PIECEWISE` (分段) / `LINEAR` (线性插值) |
| `kpi_schemes[].score_rules[].rule.total_score` | Decimal | ✅ | 满分 (默认 100) |
| `kpi_schemes[].score_rules[].rule.higher_is_better` | Int | ✅ | 1=越高越好 / 0=越低越好 |
| `kpi_schemes[].score_rules[].kpi.*` | Object | ✅ | `prcp_kpi_definition` 全字段 |
| `kpi_schemes[].score_rules[].segments[]` | Array | 条件 | 仅 PIECEWISE 规则有；`prcp_kpi_score_segment` 全字段 |
| `kpi_schemes[].score_rules[].anchors[]` | Array | 条件 | 仅 LINEAR 规则有；锚点表结构 `{id, rule_id, anchor_order, x_value, score, anchor_desc}` |
| `kpi_schemes[].score_rules[].segments[][].min_value` | Decimal | ✅ | 区间下限 (含) |
| `kpi_schemes[].score_rules[].segments[][].max_value` | Decimal | 可选 | 区间上限 (含)；null=∞ |

> ⚠️ LINEAR 评分 (源码 KpiService.scoreCalcLinear 的 5 步算法):
> 1. 按 `x_value` 升序排列 anchors
> 2. value < 首个 anchor.x → 返回首 anchor.score (下钳位)
> 3. value > 末个 anchor.x → 返回末 anchor.score (上钳位)
> 4. 否则线性插值: `score = y₁ + (value-x₁) / (x₂-x₁) * (y₂-y₁)`
> 5. 保留 4 位小数

### 4.5 第④段 — 指标计量参数 `kpi_params.{cet1,lcr,nim,nsfr,roe,eve}`

> 来源 SQL (6 张表各一条):
> `SELECT * FROM prcp_{cet1|lcr|nim|nsfr|roe|eve}_param WHERE data_date=? AND scheme_code=? AND is_deleted=0`
> 实际场景每张表返回 30~200 行 (按账户册的叶子节点)。

#### 4.5.1 通用字段 (6 表均含)

| 字段 | 类型 | 必填 | 含义 |
|---|---|---|---|
| `id` | String | ✅ | 复合主键 `{scheme_code}_{node_code}_{YYYYMMDD}` |
| `scheme_id` | Long | ✅ | 测算方案 ID |
| `scheme_code` | String | ✅ | 测算方案编码 |
| `node_id` / `node_code` / `node_name` | Long/String/String | ✅ | 账户册节点三件套 |
| `data_date` | Date | ✅ | **数据日期 (与 scheme.data_date 一致)** |
| `current_balance` | Decimal | ✅ | 期末余额 |
| `rule_note` | String | 可选 | 参数说明 |
| `status` | String | ✅ | ACTIVE / DISABLED |

#### 4.5.2 6 表特有字段 (分子/分母/系数因子)

| 指标 | 分子字段 | 分母字段 | 备注 |
|---|---|---|---|
| **cet1** | `is_numerator`, `numerator_factor`, `numerator_operator`, `numerator_category` | `is_rwa`, `rwa_weight`, `rwa_operator`, `rwa_category` | RWA 风险加权 |
| **lcr**  | `is_numerator`, `num_factor`, `num_operator` | `is_denominator`, `den_factor`, `den_operator` | HQLA / 30d 净流出 |
| **nim**  | `is_interest_asset`, `asset_rate`, `asset_operator`, `asset_category` | `is_interest_liability`, `liability_rate`, `liability_operator`, `liability_category` | 生息资产 / 计息负债 |
| **nsfr** | `is_asf`, `asf_factor`, `asf_operator` | `is_rsf`, `rsf_factor`, `rsf_operator` | 可用/所需稳定资金 |
| **roe**  | `is_net_profit`, `net_profit_symbol`, `net_profit_factor`, `net_profit_category` | `is_net_asset`, `net_asset_symbol`, `net_asset_factor`, `net_asset_category` | 净利润/净资产 |
| **eve**  | `is_asset`, `asset_type`, `asset_category`, `asset_operator` | `is_liability`, `liability_type`, `liability_category`, `liability_operator` | 利率敏感性资产/负债 + `duration` 久期 |

> 6 张表的实体分别是 `Cet1ParamEntity` / `LcrParamEntity` / `NimParamEntity` / `NsfrParamEntity` / `RoeParamEntity` / `EveParamEntity`，完整定义见 `prcp-business/src/main/java/com/prcp/business/params/*/`。

### 4.6 算法超参 `params`

| 字段 | 类型 | 算法 | 默认 | 含义 |
|---|---|---|---|---|
| `learning_rate` | Double | DNN | 0.001 | 学习率 |
| `epochs` | Int | DNN | 500 | 训练轮数 |
| `batch_size` | Int | DNN | 32 | 批大小 |
| `early_stopping_patience` | Int | DNN | 20 | 早停耐心值 |
| `random_seed` | Long | * | 42 | 随机种子 |
| `gpu_enabled` | Boolean | DNN | true | GPU 加速 |
| `precision` | String | DNN | float32 | float32 / float64 |
| `constraint_tolerance` | Double | 全部 | 1e-4 | 约束违规容忍度 |
| `max_iterations` | Int | 全部 | 5000 | 最大迭代次数 |

---

## 五、改造实施步骤 (主服务侧)

### 5.1 新增 EnginePayloadBuilder (报文组装)

```java
// com.prcp.business.reverse.engine.EnginePayloadBuilder.java

@Component
@RequiredArgsConstructor
public class EnginePayloadBuilder {

    private final JdbcTemplate jdbc;

    /**
     * 组装 § 三 样例的完整报文
     */
    public Map<String, Object> buildPayload(Long rid, RunCode runCode, Long schemeId) {
        Map<String, Object> payload = new LinkedHashMap<>();

        // ---- 元数据 ----
        payload.put("request_id", "req-" + UUID.randomUUID());
        payload.put("submitted_at", LocalDateTime.now().toString());
        payload.put("submitted_by", SecurityContextHolder.getContext().getAuthentication().getName());
        payload.put("callback", buildCallback(rid));
        payload.put("run", Map.of("id", rid, "run_code", runCode));

        // ---- ① 测算方案实体 ----
        Map<String, Object> scheme = loadScheme(schemeId);
        payload.put("scheme", scheme);

        // ---- ② 目标设置 ----
        Long coaSchemeId = ((Number) scheme.get("coa_scheme_id")).longValue();
        LocalDate dataDate = LocalDate.parse(scheme.get("data_date").toString());
        List<Map<String, Object>> targets = jdbc.queryForList(
            "SELECT * FROM prcp_reverse_target WHERE scheme_id=? AND is_deleted=0 ORDER BY sort_order",
            schemeId);
        payload.put("targets", targets);

        // ---- ③ 指标评分方案 ----
        payload.put("kpi_schemes", loadKpiSchemes(scheme, targets));

        // ---- ④ 指标计量参数 (6 张参数补录表按 data_date) ----
        payload.put("kpi_params", loadKpiParams(scheme));

        // ---- 算法超参 ----
        payload.put("params", defaultAlgoParams((String) scheme.get("algorithm")));

        return payload;
    }

    private List<Map<String, Object>> loadKpiSchemes(Map<String, Object> scheme, List<Map<String, Object>> targets) {
        // 1. 根据 scheme.algorithm 找 KpiScheme
        // 2. 收集 targets 中所有 kpi_id
        // 3. 拉 score_rule + segments/anchors + kpi detail
        // 4. 返回
        return schemes;
    }

    private Map<String, Object> loadKpiParams(Map<String, Object> scheme) {
        String dataDate = scheme.get("data_date").toString();
        String schemeCode = scheme.get("scheme_code").toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("cet1", query("prcp_cet1_param", dataDate, schemeCode));
        params.put("lcr",  query("prcp_lcr_param",  dataDate, schemeCode));
        params.put("nim",  query("prcp_nim_param",  dataDate, schemeCode));
        params.put("nsfr", query("prcp_nsfr_param", dataDate, schemeCode));
        params.put("roe",  query("prcp_roe_param",  dataDate, schemeCode));
        params.put("eve",  query("prcp_eve_param",  dataDate, schemeCode));
        return params;
    }
}
```

### 5.2 新增 EngineClient (HTTP 提交)

```java
// com.prcp.business.reverse.engine.EngineClient.java

@Component
public class EngineClient {

    @Value("${engine.base-url:http://localhost:8009}")
    private String ENGINE_BASE_URL;

    private final RestTemplate restTemplate;

    public Map<String, Object> submitToEngine(Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", payload.get("request_id").toString());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
            ENGINE_BASE_URL + "/api/v1/reverse/execute", entity, Map.class);
        return (Map<String, Object>) resp.getBody().get("data");
    }
}
```

### 5.3 替换 ReverseService.startRun() 内部执行

```java
// ReverseService.java line 487 替换

public R<Map<String, Object>> startRun(Long rid) {
    // ... 同前，更新 status='RUNNING'
    ReverseRun run = runMapper.selectById(rid);

    CompletableFuture.runAsync(() -> {
        try {
            // 1. 组装报文
            Map<String, Object> payload = builder.buildPayload(rid, run.getRunCode(), run.getSchemeId());

            // 2. HTTP 提交引擎
            Map<String, Object> ack = engineClient.submitToEngine(payload);
            writeLog(rid, "INFO", "引擎已受理: engine_run_id=" + ack.get("engine_run_id"), 5);
        } catch (Exception e) {
            svc.markFailed(rid, e.getMessage());
        }
    }, EXEC);
}
```

### 5.4 新增回调接收端点

```java
// ReverseController.java 新增

@PostMapping("/runs/{rid}/callback")
public R<Map<String, Object>> runCallback(@PathVariable Long rid,
                                            @RequestBody Map<String, Object> body) {
    return svc.handleEngineCallback(rid, body);
    // 内部: 校验 token → 写 prcp_reverse_result → 更新 run.status='SUCCESS' + optimal_value + metrics
}
```

### 5.5 配置文件 application.yml

```yaml
engine:
  base-url:           http://engine-server:8009      # 引擎服务地址
  callback-base-url:  https://wxfzhh.online/prcp-java/api
  callback-token:     ${ENGINE_CALLBACK_TOKEN:dev-engine-callback-token}
  timeout-sec:        60
```

---

## 六、引擎服务侧契约 (供引擎工程师实现)

### 6.1 必须实现的端点

| 端点 | 方法 | 用途 |
|---|---|---|
| `/api/v1/reverse/execute` | POST | 接收反算任务 (异步处理) |
| `/api/v1/health` | GET | 健康检查 (主服务可选定时探测) |
| `/api/v1/reverse/{engine_run_id}/cancel` | POST | 取消运行 (主服务调用) |

### 6.2 反算算法要求

按 `scheme.algorithm` 字段分派:
- `HEURISTIC` → 启发式求解器 (默认, Java 已实现, 直接迁移)
- `DNN_NEURAL_NETWORK` → TensorFlow/PyTorch DNN 回归
- `ANT_COLONY` → 蚁群算法
- `NONLINEAR_SOLVER` → SciPy `least_squares` / fsolve
- `CVXPY_QP` → CVXPY 二次规划

### 6.3 评分算法要求 (回写 kpi_scores)

引擎在回写 `metrics.kpi_scores` 时，按第③段 `kpi_schemes.score_rules` 中 `calc_method` 分派:

| `calc_method` | 算法 | 输入 | 输出 |
|---|---|---|---|
| `PIECEWISE` | 分段匹配 | KPI 值 + `segments[]` | 落入第一段 `[min_value, max_value]` 的 `score`；超出返回 0 |
| `LINEAR` | 线性插值 | KPI 值 + `anchors[]` | 见 [§ 4.4 评分算法表](#44-第段--指标评分方案-kpi_schemes) |

### 6.4 结果精度

- 货币金额: BigDecimal 18 位精度, 返回时截断到 4 位小数
- 比率: 保留 6 位小数 (如 110.123456%)
- 评分: 保留 4 位小数
- 整数 (月数/迭代次数): Int

### 6.5 SLA 建议

- 接口响应时间 ≤ 500ms (同步接收)
- 反算任务总耗时 ≤ 5 分钟 (异步)
- 超时主服务会主动查询 run 状态 (主服务侧有补偿机制)

---

## 七、错误码

| 错误码 | 含义 | 处理方式 |
|---|---|---|
| 0 | 成功 | — |
| 40001 | 参数校验失败 | 检查报文 (scheme/kpi_params 必填) |
| 40002 | 方案不存在 | 跳过 |
| 40003 | 账户册数据为空 | 提示用户先导入 |
| 40004 | 模型文件下载失败 | 重新训练模型 |
| 40005 | 计量参数缺失 (6 张参数补录表) | 提示用户补录 |
| 50001 | 引擎内部异常 | 重试 3 次后通知运维 |
| 50002 | 求解不收敛 | 调整算法超参 `params` |
| 50003 | 评分规则与指标不匹配 | 检查 `kpi_schemes` |
| 50004 | 回调失败 (主服务不可达) | 引擎侧本地缓存结果, 待主服务恢复后补偿推送 |

---

## 八、参考资源

- 主服务现状代码: `prcp-business/src/main/java/com/prcp/business/reverse/ReverseService.java`
- 前端现状代码: `prcp-vue/src/views/reverse/Index.vue` (runScheme 方法 line 626)
- Python 源系统参考: `PRCP/backend/app/services/reverse_service.py`
- 实体定义:
  - `prcp-business/src/main/java/com/prcp/business/reverse/entity/{ReverseScheme,ReverseTarget,ReverseRun,ReverseResult}.java`
  - `prcp-business/src/main/java/com/prcp/business/kpi/entity/{KpiScheme,KpiScoreRule,KpiScoreSegment,KpiDefinition}.java`
  - `prcp-business/src/main/java/com/prcp/business/params/{cet1,lcr,nim,nsfr,roe,eve}/{Cet1,Lcr,Nim,Nsfr,Roe,Eve}ParamEntity.java`

### 4 段对应的查询 SQL (参考)

```sql
-- ① 测算方案
SELECT s.*, cs.scheme_code AS coaCode, m.model_code AS modelCode
  FROM prcp_reverse_scheme s
  LEFT JOIN prcp_coa_scheme cs ON cs.id=s.coa_scheme_id
  LEFT JOIN prcp_model m ON m.id=s.model_id
  WHERE s.id=?;

-- ② 目标设置
SELECT t.*, k.kpi_name AS refKpiName
  FROM prcp_reverse_target t
  LEFT JOIN prcp_kpi_definition k ON k.id=t.kpi_id
  WHERE t.scheme_id=? AND t.is_deleted=0
  ORDER BY t.sort_order;

-- ③ 指标评分方案
SELECT ks.*, ksr.*, ksc.*, kd.*
  FROM prcp_kpi_scheme ks
  JOIN prcp_kpi_score_rule ksr ON ksr.scheme_id=ks.id
  LEFT JOIN prcp_kpi_score_segment ksc ON ksc.rule_id=ksr.id
  LEFT JOIN prcp_kpi_definition kd ON kd.id=ksr.kpi_id
  WHERE ks.scheme_code=?
    AND ksr.is_deleted=0
    AND ksr.kpi_id IN (SELECT kpi_id FROM prcp_reverse_target WHERE scheme_id=?);

-- ④ 指标计量参数 (6 张表各一条)
SELECT * FROM prcp_cet1_param WHERE data_date=? AND scheme_code=? AND is_deleted=0;
SELECT * FROM prcp_lcr_param  WHERE data_date=? AND scheme_code=? AND is_deleted=0;
SELECT * FROM prcp_nim_param  WHERE data_date=? AND scheme_code=? AND is_deleted=0;
SELECT * FROM prcp_nsfr_param WHERE data_date=? AND scheme_code=? AND is_deleted=0;
SELECT * FROM prcp_roe_param  WHERE data_date=? AND scheme_code=? AND is_deleted=0;
SELECT * FROM prcp_eve_param  WHERE data_date=? AND scheme_code=? AND is_deleted=0;
```
