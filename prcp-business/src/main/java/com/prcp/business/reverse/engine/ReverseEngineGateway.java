package com.prcp.business.reverse.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p>反算引擎网关 (单类封装: 报文组装 + HTTP 提交)</p>
 *
 * <p>设计动机: 把 § 五 实施步骤里的 EnginePayloadBuilder + EngineClient 合并到一个类,
 * 便于集中维护 (改字段时不用跨文件同步)。主服务 ReverseService.startRun() 调用
 * {@link #submit(Long, Long)} 即可完成「组装 4 段报文 → POST 引擎 → 同步受理 ACK」全流程。</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>{@link #buildPayload(Long, Long)} - 从 prcp_db 拉数据组装 § 〇 报文 (4 段 + 元数据)</li>
 *   <li>{@link #submitToEngine(Map)} - HTTP POST 到独立引擎服务器, 同步等受理 ACK</li>
 *   <li>{@link #submit(Long, Long)} - 一键组装 + 提交 (主服务用得最多)</li>
 * </ol>
 * </p>
 *
 * <p>报文 4 段对应数据库:
 * <ul>
 *   <li>① scheme - prcp_reverse_scheme (单条) + prcp_model + prcp_coa_scheme</li>
 *   <li>② targets[] - prcp_reverse_target (按 scheme_id 查, 关联 kpi_definition)</li>
 *   <li>③ kpi_schemes[] - prcp_kpi_scheme + prcp_kpi_score_rule + segment/anchor + kpi_definition</li>
 *   <li>④ kpi_params.{cet1,lcr,nim,nsfr,roe,eve} - 6 张参数补录表 (按 data_date + scheme_code)</li>
 * </ul>
 * </p>
 *
 * <p>配置 (application.yml):
 * <pre>
 * engine:
 *   base-url:           http://localhost:8009
 *   callback-base-url:  https://wxfzhh.online/prcp-java/api
 *   callback-token:     dev-engine-callback-token
 *   timeout-sec:        60
 * </pre>
 * </p>
 *
 * <p>报文契约详见: {@code docs/api/reverse-engine-api.md} § 〇 ~ § 三</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.reverse.ReverseService
 */
@Slf4j
@Component
public class ReverseEngineGateway {

    private final JdbcTemplate jdbc;

    @Value("${engine.base-url:http://localhost:8009}")
    private String engineBaseUrl;

    @Value("${engine.callback-base-url:http://localhost:8008/prcp-java/api}")
    private String callbackBaseUrl;

    @Value("${engine.callback-token:dev-engine-callback-token}")
    private String callbackToken;

    @Value("${engine.timeout-sec:60}")
    private int timeoutSec;

    private RestTemplate restTemplate;
    private final ObjectMapper json = new ObjectMapper();

    public ReverseEngineGateway(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(timeoutSec * 1000);
        this.restTemplate = new RestTemplate(factory);
        log.info("[ReverseEngineGateway] 初始化完成, engine={}, callback={}, timeout={}s",
                engineBaseUrl, callbackBaseUrl, timeoutSec);
    }

    // ================================================================
    //  顶层入口: 一键组装 + 提交
    // ================================================================

    /**
     * <p>一键组装 4 段报文并 HTTP 提交到引擎, 同步等待受理 ACK</p>
     *
     * @param runId    prcp_reverse_run.id (用于 callback 关联)
     * @param schemeId prcp_reverse_scheme.id (主键, 用于查 4 段)
     * @return 引擎受理响应 data 部分 ({@code {accepted, engine_run_id, estimated_seconds, callback_url}})
     * @throws RuntimeException 报文组装或 HTTP 提交失败时抛出
     */
    public Map<String, Object> submit(Long runId, Long schemeId) {
        Map<String, Object> payload = buildPayload(runId, schemeId);
        return submitToEngine(payload);
    }

    // ================================================================
    //  报文组装 (4 段 + 元数据)
    // ================================================================

    /**
     * <p>组装 § 〇 报文 (4 段主体 + 4 字段元数据)</p>
     *
     * <p>组装流程:
     * <ol>
     *   <li>查 run (取 run_code)</li>
     *   <li>① 查 scheme + model + coa_scheme</li>
     *   <li>② 查 targets (按 scheme_id)</li>
     *   <li>③ 查 kpi_schemes (按 scheme.algorithm 关联 KpiScheme, 再 4 步展开)</li>
     *   <li>④ 查 kpi_params (6 张参数补录表, 按 scheme.data_date + scheme_code)</li>
     *   <li>拼装算法超参 (按 algorithm 分派默认值)</li>
     * </ol>
     * </p>
     *
     * @param runId    Run 主键
     * @param schemeId Scheme 主键
     * @return 完整报文 Map (LinkedHashMap 保证字段顺序)
     */
    public Map<String, Object> buildPayload(Long runId, Long schemeId) {
        Map<String, Object> payload = new LinkedHashMap<>();

        // ---- ① 测算方案实体 (含 model + coa_scheme 关联) ----
        Map<String, Object> scheme = loadScheme(schemeId);
        if (scheme.isEmpty()) {
            throw new IllegalArgumentException("测算方案不存在: schemeId=" + schemeId);
        }
        payload.put("scheme", scheme);

        // ---- ② 目标设置集合 (按 scheme_id 查) ----
        List<Map<String, Object>> targets = loadTargets(schemeId);
        payload.put("targets", targets);

        // ---- ③ 指标评分方案集合 (按 algorithm 找 KpiScheme) ----
        List<Map<String, Object>> kpiSchemes = loadKpiSchemes(scheme, targets);
        payload.put("kpi_schemes", kpiSchemes);

        // ---- ④ 指标计量参数集合 (6 张参数补录表) ----
        Map<String, Object> kpiParams = loadKpiParams(scheme);
        payload.put("kpi_params", kpiParams);

        // ---- 元数据 (4 字段) ----
        String runCode = lookupRunCode(runId);
        payload.put("request_id", "req-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8));
        payload.put("submitted_at", LocalDateTime.now().toString());
        payload.put("submitted_by", "system");  // 后续可改为 SecurityContext 用户
        payload.put("callback", buildCallback(runId));
        payload.put("run", Map.of("id", runId, "run_code", runCode));

        // ---- 算法超参 (按 algorithm 分派默认值) ----
        payload.put("params", defaultAlgoParams((String) scheme.get("algorithm")));

        log.info("[ReverseEngineGateway] 报文组装完成 runId={} schemeCode={} targets={} kpiSchemes={} kpiParamsSize={}",
                runId, scheme.get("scheme_code"), targets.size(), kpiSchemes.size(), kpiParams.size());
        return payload;
    }

    // ================================================================
    //  HTTP 提交
    // ================================================================

    /**
     * <p>HTTP POST 报文到独立引擎服务器, 同步等受理 ACK</p>
     *
     * <p>引擎采用异步处理, 立即返回 {@code {accepted: true, engine_run_id: ...}} 即可,
     * 实际计算完成后通过 POST callback URL 回传结果。</p>
     *
     * @param payload 已组装好的报文 (§ 〇 4 段 + 元数据)
     * @return 引擎响应 data 部分 (LinkedHashMap, 字段顺序与引擎返回一致)
     * @throws RuntimeException HTTP 4xx/5xx 抛出, 含引擎错误码
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> submitToEngine(Map<String, Object> payload) {
        String url = engineBaseUrl + "/api/v1/reverse/execute";
        String requestId = (String) payload.get("request_id");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Auth-Token", "Bearer " + callbackToken);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        log.info("[ReverseEngineGateway] POST {} requestId={} payloadSize={}B",
                url, requestId, approxSize(payload));

        ResponseEntity<Map> resp = restTemplate.postForEntity(url, entity, Map.class);
        Map<String, Object> body = resp.getBody();
        if (body == null) {
            throw new IllegalStateException("引擎返回空响应");
        }
        Integer code = (Integer) body.get("code");
        if (code == null || code != 0) {
            log.error("[ReverseEngineGateway] 引擎拒绝 requestId={} code={} msg={}",
                    requestId, code, body.get("msg"));
            throw new IllegalStateException("引擎受理失败: code=" + code + " msg=" + body.get("msg"));
        }
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        log.info("[ReverseEngineGateway] 引擎已受理 requestId={} engineRunId={}",
                requestId, data.get("engine_run_id"));
        return data;
    }

    // ================================================================
    //  私有助手: 报文各段加载
    // ================================================================

    /**
     * <p>① 加载测算方案 (含 model + coa_scheme 关联展示字段)</p>
     */
    private Map<String, Object> loadScheme(Long schemeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id, s.scheme_code, s.scheme_name, s.scheme_type, s.description,"
              + " s.data_date, s.horizon_months, s.algorithm, s.status, s.model_id, s.coa_scheme_id,"
              + " s.created_by, s.updated_by, s.created_at, s.updated_at,"
              + " m.model_code, m.model_name, m.model_type, m.kpi_scheme_id AS model_kpi_scheme_id,"
              + " cs.scheme_code AS coa_code, cs.scheme_name AS coa_name"
              + " FROM prcp_reverse_scheme s"
              + " LEFT JOIN prcp_model m ON m.id = s.model_id AND m.is_deleted = 0"
              + " LEFT JOIN prcp_coa_scheme cs ON cs.id = s.coa_scheme_id"
              + " WHERE s.id = ? AND s.is_deleted = 0",
                schemeId);
        if (rows.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> s = rows.get(0);

        // 嵌套 model + coa_scheme 子对象
        Map<String, Object> model = new LinkedHashMap<>();
        if (s.get("model_id") != null) {
            model.put("id", s.remove("model_id"));
            model.put("model_code", s.remove("model_code"));
            model.put("model_name", s.remove("model_name"));
            model.put("model_type", s.remove("model_type"));
            model.put("kpi_scheme_id", s.get("model_kpi_scheme_id"));  // 给 § 0.2 第 3 步用
            s.put("model", model);
        } else {
            s.remove("model_kpi_scheme_id");
        }

        Map<String, Object> coa = new LinkedHashMap<>();
        if (s.get("coa_scheme_id") != null) {
            coa.put("scheme_code", s.remove("coa_code"));
            coa.put("scheme_name", s.remove("coa_name"));
        }
        s.put("coa_scheme", coa);
        return s;
    }

    /**
     * <p>② 加载目标设置 (按 scheme_id 查, 关联 kpi_definition 查 KPI 名称)</p>
     */
    private List<Map<String, Object>> loadTargets(Long schemeId) {
        return jdbc.queryForList(
                "SELECT t.id, t.scheme_id, t.kpi_id, t.kpi_code, t.target_name,"
              + " t.target_value, t.constraint_type, t.weight, t.horizon_month, t.sort_order, t.description,"
              + " k.kpi_name AS ref_kpi_name, k.formula AS ref_formula"
              + " FROM prcp_reverse_target t"
              + " LEFT JOIN prcp_kpi_definition k ON k.id = t.kpi_id"
              + " WHERE t.scheme_id = ? AND t.is_deleted = 0"
              + " ORDER BY t.sort_order",
                schemeId);
    }

    /**
     * <p>③ 加载指标评分方案 (4 步查询链路)</p>
     *
     * <p>查询逻辑:
     * <ol>
     *   <li>优先按 model.kpi_scheme_id 找到关联的 KpiScheme (对位 prcp_model 表的 measure model 字段)</li>
     *   <li>回退: 按 scheme.algorithm 匹配 scheme_code (兼容历史数据无 kpi_scheme_id 的情况)</li>
     *   <li>按 KpiScheme.id 拉所有 score_rule</li>
     *   <li>每条 rule 拉 segments (PIECEWISE) / anchors (LINEAR) + kpi_definition</li>
     *   <li>封装为 List&lt;Map&gt;</li>
     * </ol>
     * </p>
     */
    private List<Map<String, Object>> loadKpiSchemes(Map<String, Object> scheme, List<Map<String, Object>> targets) {
        if (targets.isEmpty()) {
            return new ArrayList<>();
        }

        // 第 1 步: 优先按 model.kpi_scheme_id 找 KpiScheme (最准确)
        Long kpiSchemeId = null;
        Map<String, Object> kpiScheme = null;
        Object modelObj = scheme.get("model");
        if (modelObj instanceof Map) {
            Object kpiSchemeIdObj = ((Map<?, ?>) modelObj).get("kpi_scheme_id");
            if (kpiSchemeIdObj == null) {
                // 用 model 表里存的 (我们 select 时叫 model_kpi_scheme_id, 已塞到 scheme 顶层)
                kpiSchemeIdObj = scheme.get("model_kpi_scheme_id");
            }
            if (kpiSchemeIdObj != null) {
                kpiSchemeId = ((Number) kpiSchemeIdObj).longValue();
            }
        }
        if (kpiSchemeId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, scheme_code, scheme_name, description, kpi_count, status,"
                  + " created_at, updated_at"
                  + " FROM prcp_kpi_scheme WHERE id = ? AND is_deleted = 0",
                    kpiSchemeId);
            if (!rows.isEmpty()) {
                kpiScheme = rows.get(0);
            }
        }
        // 回退: 按 algorithm 匹配 scheme_code
        if (kpiScheme == null) {
            String algorithm = (String) scheme.get("algorithm");
            String kpiSchemeCode = mapAlgoToKpiSchemeCode(algorithm);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, scheme_code, scheme_name, description, kpi_count, status,"
                  + " created_at, updated_at"
                  + " FROM prcp_kpi_scheme"
                  + " WHERE scheme_code = ? AND is_deleted = 0"
                  + " ORDER BY id LIMIT 1",
                    kpiSchemeCode);
            if (!rows.isEmpty()) {
                kpiScheme = rows.get(0);
                kpiSchemeId = ((Number) kpiScheme.get("id")).longValue();
            }
        }
        if (kpiScheme == null) {
            log.warn("[ReverseEngineGateway] 未找到指标方案 (model.kpi_scheme_id={} algorithm={})",
                    scheme.get("model_kpi_scheme_id"), scheme.get("algorithm"));
            return new ArrayList<>();
        }

        // 第 2 步: 收集 targets 中的 kpi_id (用 IN 子句过滤, 避免拉无关规则)
        StringBuilder kpiIdIn = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(kpiSchemeId);
        for (int i = 0; i < targets.size(); i++) {
            Object kid = targets.get(i).get("kpi_id");
            if (kid == null) continue;
            if (i > 0) kpiIdIn.append(",");
            kpiIdIn.append("?");
            args.add(((Number) kid).longValue());
        }
        if (kpiIdIn.length() == 0) {
            return List.of(Map.of("scheme", kpiScheme, "score_rules", List.of()));
        }

        // 查 score_rule
        String ruleSql = "SELECT id, scheme_id, kpi_id, rule_name, calc_method, total_score,"
                       + " higher_is_better, description, status"
                       + " FROM prcp_kpi_score_rule"
                       + " WHERE scheme_id = ? AND is_deleted = 0 AND kpi_id IN (" + kpiIdIn + ")"
                       + " ORDER BY kpi_id";
        List<Map<String, Object>> rules = jdbc.queryForList(ruleSql, args.toArray());

        // 第 3 步: 每条 rule 拉 segment/anchor + kpi_definition
        List<Map<String, Object>> scoreRules = new ArrayList<>();
        for (Map<String, Object> rule : rules) {
            Long ruleId = ((Number) rule.get("id")).longValue();
            Long kpiId = ((Number) rule.get("kpi_id")).longValue();
            String calcMethod = (String) rule.get("calc_method");

            Map<String, Object> bundle = new LinkedHashMap<>();
            bundle.put("rule", rule);
            bundle.put("kpi", loadKpiDefinition(kpiId));

            if ("LINEAR".equals(calcMethod)) {
                // LINEAR: 锚点存放在 prcp_kpi_score_segment 表中 (x = min_value, y = score)
                List<Map<String, Object>> anchors = jdbc.queryForList(
                        "SELECT id, rule_id, seg_order AS anchor_order, min_value AS x_value, score,"
                      + " segment_desc AS anchor_desc"
                      + " FROM prcp_kpi_score_segment"
                      + " WHERE rule_id = ? AND is_deleted = 0"
                      + " ORDER BY x_value",
                        ruleId);
                bundle.put("anchors", anchors);
                bundle.put("segments", List.of());
            } else {
                // PIECEWISE (默认): 查 segments
                List<Map<String, Object>> segments = jdbc.queryForList(
                        "SELECT id, rule_id, seg_order, min_value, max_value, score, segment_desc"
                      + " FROM prcp_kpi_score_segment"
                      + " WHERE rule_id = ? AND is_deleted = 0"
                      + " ORDER BY seg_order",
                        ruleId);
                bundle.put("segments", segments);
                bundle.put("anchors", List.of());
            }
            scoreRules.add(bundle);
        }

        // 第 4 步: 封装
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("scheme", kpiScheme);
        wrapped.put("score_rules", scoreRules);
        return List.of(wrapped);
    }

    /**
     * <p>查 prcp_kpi_definition 单条 (封装为 Map)</p>
     */
    private Map<String, Object> loadKpiDefinition(Long kpiId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, scheme_id, indicator_type, kpi_code, kpi_name, rpt_id, formula,"
              + " script_path, script_name, calc_unit, formula_desc, threshold_min, threshold_max, status"
              + " FROM prcp_kpi_definition WHERE id = ? AND is_deleted = 0",
                kpiId);
        return rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
    }

    /**
     * <p>④ 加载 6 张参数补录表 (按 data_date + scheme_code)</p>
     *
     * <p>6 张表结构相同 (scheme_id, scheme_code, node_id, node_code, node_name, data_date,
     * current_balance + 分子/分母字段)。一次连接查 6 张表, 封装为 Map&lt;String, List&gt;。</p>
     */
    private Map<String, Object> loadKpiParams(Map<String, Object> scheme) {
        Object dataDateObj = scheme.get("data_date");
        Object schemeCodeObj = scheme.get("scheme_code");
        if (dataDateObj == null || schemeCodeObj == null) {
            log.warn("[ReverseEngineGateway] scheme 缺 data_date 或 scheme_code, 跳过 kpi_params");
            return emptyKpiParams();
        }
        LocalDate dataDate = parseDate(dataDateObj);
        String schemeCode = schemeCodeObj.toString();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cet1", queryParamTable("prcp_cet1_param", dataDate, schemeCode));
        result.put("lcr",  queryParamTable("prcp_lcr_param",  dataDate, schemeCode));
        result.put("nim",  queryParamTable("prcp_nim_param",  dataDate, schemeCode));
        result.put("nsfr", queryParamTable("prcp_nsfr_param", dataDate, schemeCode));
        result.put("roe",  queryParamTable("prcp_roe_param",  dataDate, schemeCode));
        result.put("eve",  queryParamTable("prcp_eve_param",  dataDate, schemeCode));
        return result;
    }

    /**
     * <p>查单张参数补录表 (按 data_date + scheme_code, 限定 is_deleted=0)</p>
     */
    private List<Map<String, Object>> queryParamTable(String tableName, LocalDate dataDate, String schemeCode) {
        String sql = "SELECT * FROM " + tableName
                   + " WHERE data_date = ? AND scheme_code = ? AND is_deleted = 0"
                   + " ORDER BY node_id LIMIT 1000";
        try {
            return jdbc.queryForList(sql, java.sql.Date.valueOf(dataDate), schemeCode);
        } catch (Exception e) {
            log.error("[ReverseEngineGateway] 查参数表失败 table={} dataDate={} schemeCode={}",
                    tableName, dataDate, schemeCode, e);
            return new ArrayList<>();
        }
    }

    // ================================================================
    //  私有助手: 元数据 / 超参 / 工具
    // ================================================================

    /**
     * <p>查 Run 主表取 run_code</p>
     */
    private String lookupRunCode(Long runId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT run_code FROM prcp_reverse_run WHERE id = ?", runId);
            return rows.isEmpty() ? "RR" + runId : (String) rows.get(0).get("run_code");
        } catch (Exception e) {
            log.warn("[ReverseEngineGateway] 查 run_code 失败 runId={}, 使用 RR{runId}", runId, e);
            return "RR" + runId;
        }
    }

    /**
     * <p>构造 callback 段 (引擎完成时回传结果用)</p>
     */
    private Map<String, Object> buildCallback(Long runId) {
        Map<String, Object> cb = new LinkedHashMap<>();
        cb.put("url",         callbackBaseUrl + "/reverse/runs/" + runId + "/callback");
        cb.put("method",      "POST");
        cb.put("auth_token",  "Bearer " + callbackToken);
        cb.put("timeout_sec", timeoutSec);
        return cb;
    }

    /**
     * <p>算法 → 指标方案编码 映射</p>
     *
     * <p>生产可在 prcp_model 加 measure_kpi_scheme_code 字段做精确关联;
     * 当前简化用 algorithm 前缀匹配。</p>
     */
    private String mapAlgoToKpiSchemeCode(String algorithm) {
        if (algorithm == null) return "KPI_REG_V1";
        if (algorithm.startsWith("DNN") || algorithm.startsWith("CVXPY")) return "KPI_REG_V1";
        if (algorithm.startsWith("ANT")) return "KPI_SCALE_V1";
        if (algorithm.startsWith("HEURISTIC")) return "KPI_BASIC_V1";
        return "KPI_REG_V1";
    }

    /**
     * <p>算法超参默认值 (按 algorithm 分派)</p>
     */
    private Map<String, Object> defaultAlgoParams(String algorithm) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("random_seed", 42);
        p.put("constraint_tolerance", 1e-4);
        p.put("max_iterations", 5000);
        if (algorithm != null && algorithm.startsWith("DNN")) {
            p.put("learning_rate", 0.001);
            p.put("epochs", 500);
            p.put("batch_size", 32);
            p.put("early_stopping_patience", 20);
            p.put("gpu_enabled", true);
            p.put("precision", "float32");
        }
        return p;
    }

    /**
     * <p>空 kpi_params 骨架 (缺 data_date 时用, 引擎不会 NPE)</p>
     */
    private Map<String, Object> emptyKpiParams() {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("cet1", new ArrayList<>());
        empty.put("lcr",  new ArrayList<>());
        empty.put("nim",  new ArrayList<>());
        empty.put("nsfr", new ArrayList<>());
        empty.put("roe",  new ArrayList<>());
        empty.put("eve",  new ArrayList<>());
        return empty;
    }

    /**
     * <p>日期对象转 LocalDate (兼容 Date / LocalDate / String)</p>
     */
    private LocalDate parseDate(Object obj) {
        if (obj == null) return LocalDate.now();
        if (obj instanceof LocalDate) return (LocalDate) obj;
        if (obj instanceof java.sql.Date) return ((java.sql.Date) obj).toLocalDate();
        if (obj instanceof java.util.Date) return ((java.util.Date) obj).toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return LocalDate.parse(obj.toString().substring(0, 10));
    }

    /**
     * <p>估算报文 JSON 序列化大小 (字节, 用于日志)</p>
     */
    private int approxSize(Map<String, Object> payload) {
        try {
            return json.writeValueAsString(payload).getBytes("UTF-8").length;
        } catch (Exception e) {
            return -1;
        }
    }
}
