package com.prcp.business.reverse;

import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import com.prcp.business.reverse.engine.ReverseEngineGateway;
import com.prcp.business.reverse.entity.ReverseResult;
import com.prcp.business.reverse.entity.ReverseRun;
import com.prcp.business.reverse.entity.ReverseRunLog;
import com.prcp.business.reverse.entity.ReverseScheme;
import com.prcp.business.reverse.entity.ReverseTarget;
import com.prcp.business.reverse.mapper.ReverseResultMapper;
import com.prcp.business.reverse.mapper.ReverseRunLogMapper;
import com.prcp.business.reverse.mapper.ReverseRunMapper;
import com.prcp.business.reverse.mapper.ReverseSchemeMapper;
import com.prcp.business.reverse.mapper.ReverseTargetMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>反算 Service: CRUD + 异步引擎提交 + 引擎回调处理</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>方案 CRUD (snake_case/camelCase 双兼容)</li>
 *   <li>目标约束 CRUD (constraint_type: GE/LE/EQ)</li>
 *   <li>Run CRUD + 选项 (模型/KPI/Run 列表/日志/结果)</li>
 *   <li>启动 Run 时通过 {@link ReverseEngineGateway} HTTP 提交独立引擎</li>
 *   <li>接收引擎回调 {@link #handleEngineCallback(Long, Map)} 写结果 + 更新状态</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>软删除: is_deleted=1, 不物理删除 (含级联 target/run)</li>
 *   <li>run 状态机: PENDING → RUNNING → (SUCCESS|FAILED|CANCELLED)</li>
 *   <li>run_log/result 表无 is_deleted 字段, 永久保留日志; result 删除时硬删</li>
 *   <li>引擎: 已迁移到独立服务器, 通过 ReverseEngineGateway HTTP 提交, 异步回调</li>
 *   <li>算法: HEURISTIC / DNN_NEURAL_NETWORK / CVXPY_QP / ANT_COLONY / NONLINEAR_SOLVER (引擎侧实现)</li>
 *   <li>线程池: Executors.newFixedThreadPool(2) — 仅用于 HTTP 提交和回调</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReverseService {

    private final ReverseSchemeMapper schemeMapper;
    private final ReverseTargetMapper targetMapper;
    private final ReverseRunMapper runMapper;
    private final ReverseRunLogMapper logMapper;
    private final ReverseResultMapper resultMapper;
    private final JdbcTemplate jdbc;
    private final ReverseEngineGateway engineGateway;

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);

    /**
     * <p>列出反算方案 (分页 LIMIT 200 + 关联方案/模型展示名)</p>
     *
     * @param keyword 关键字 (匹配 scheme_code/name, 可选)
     * @param status  状态 (可选)
     * @return R.ok(Map.of("items", list)); 含 targetCount/runCount/coaCode/coaName/modelCode/modelName 等
     */
    public R<Map<String, Object>> listSchemes(String keyword, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT s.id, s.scheme_code AS schemeCode, s.scheme_name AS schemeName, s.scheme_type AS schemeType,"
              + " s.coa_scheme_id AS coaSchemeId, s.data_date AS dataDate, s.horizon_months AS horizonMonths,"
              + " s.algorithm, s.model_id AS modelId, s.description, s.status, s.created_at AS createdAt, s.updated_at AS updatedAt,"
              + " cs.scheme_code AS coaCode, cs.scheme_name AS coaName,"
              + " m.model_code AS modelCode, m.model_name AS modelName, m.model_type AS modelType,"
              + " (SELECT COUNT(*) FROM prcp_reverse_target t WHERE t.scheme_id=s.id AND t.is_deleted=0) AS targetCount,"
              + " (SELECT COUNT(*) FROM prcp_reverse_run r WHERE r.scheme_id=s.id AND r.is_deleted=0) AS runCount"
              + " FROM prcp_reverse_scheme s"
              + " LEFT JOIN prcp_coa_scheme cs ON cs.id=s.coa_scheme_id"
              + " LEFT JOIN prcp_model m ON m.id=s.model_id AND m.is_deleted=0"
              + " WHERE s.is_deleted=0");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isEmpty()) {
            sql.append(" AND (s.scheme_code LIKE ? OR s.scheme_name LIKE ?)");
            String kw = "%" + keyword + "%";
            params.add(kw); params.add(kw);
        }
        if (status != null && !status.isEmpty()) {
            sql.append(" AND s.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY s.id DESC LIMIT 200");
        List<Map<String, Object>> items = jdbc.queryForList(sql.toString(), params.toArray());
        return R.ok(Collections.singletonMap("items", items));
    }

    /**
     * <p>单方案详情 (按 id): 返回 snake_case + 关联展示名 (与 list 接口结构兼容)</p>
     *
     * @param sid 方案 ID (必填)
     * @return R.ok(Map); 不存在或已删除时抛 notFound
     */
    public R<Map<String, Object>> getScheme(Long sid) {
        if (sid == null) throw BizException.badRequest("id 必填");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id, s.scheme_code AS scheme_code, s.scheme_name AS scheme_name,"
              + " s.scheme_type AS scheme_type, s.coa_scheme_id AS coa_scheme_id,"
              + " s.data_date AS data_date, s.horizon_months AS horizon_months,"
              + " s.algorithm, s.model_id AS model_id, s.description, s.status,"
              + " s.created_at AS created_at, s.updated_at AS updated_at,"
              + " cs.scheme_code AS coa_code, cs.scheme_name AS coa_name,"
              + " m.model_code AS model_code, m.model_name AS model_name, m.model_type AS model_type,"
              + " (SELECT COUNT(*) FROM prcp_reverse_target t WHERE t.scheme_id=s.id AND t.is_deleted=0) AS target_count,"
              + " (SELECT COUNT(*) FROM prcp_reverse_run r WHERE r.scheme_id=s.id AND r.is_deleted=0) AS run_count"
              + " FROM prcp_reverse_scheme s"
              + " LEFT JOIN prcp_coa_scheme cs ON cs.id=s.coa_scheme_id"
              + " LEFT JOIN prcp_model m ON m.id=s.model_id AND m.is_deleted=0"
              + " WHERE s.id=? AND s.is_deleted=0",
                sid);
        if (rows.isEmpty()) throw BizException.notFound("反算方案不存在");
        return R.ok(rows.get(0));
    }

    /**
     * <p>创建方案 (校验 scheme_code 唯一 + 默认 status='DRAFT', algorithm='HEURISTIC', horizon=24)</p>
     *
     * @param body 含 scheme_code/scheme_name/coa_scheme_id/data_date + algorithm/model_id/horizon_months/description/status
     * @return R.ok(Map.of("id")); 字段缺失或 scheme_code 重复时抛 badRequest
     */
    @Transactional
    public R<Map<String, Object>> createScheme(Map<String, Object> body) {
        if (body.get("scheme_code") == null) throw BizException.badRequest("scheme_code 必填");
        if (body.get("scheme_name") == null) throw BizException.badRequest("scheme_name 必填");
        if (body.get("coa_scheme_id") == null) throw BizException.badRequest("coa_scheme_id 必填");
        if (body.get("data_date") == null) throw BizException.badRequest("data_date 必填");
        // 检查 scheme_code 唯一性
        Integer dup = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prcp_reverse_scheme WHERE scheme_code=? AND is_deleted=0",
                Integer.class, body.get("scheme_code").toString());
        if (dup != null && dup > 0) throw BizException.badRequest("scheme_code 已存在");
        ReverseScheme s = parseScheme(body);
        s.setIsDeleted(0);
        s.setStatus(s.getStatus() == null ? "DRAFT" : s.getStatus());
        if (s.getAlgorithm() == null) s.setAlgorithm("HEURISTIC");
        if (s.getSchemeType() == null) s.setSchemeType("OPTIMIZE");
        if (s.getHorizonMonths() == null) s.setHorizonMonths(24);
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        schemeMapper.insert(s);
        return R.ok(Collections.singletonMap("id", s.getId()));
    }

    /**
     * <p>更新方案 (按字段选择性更新)</p>
     *
     * @param sid  方案 ID (必填)
     * @param body 待更新字段
     * @return R.ok(Map.of("id")); 不存在或已删除时抛 notFound
     */
    @Transactional
    public R<Map<String, Object>> updateScheme(Long sid, Map<String, Object> body) {
        if (sid == null) throw BizException.badRequest("id 必填");
        ReverseScheme exist = schemeMapper.selectById(sid);
        if (exist == null || Integer.valueOf(1).equals(exist.getIsDeleted()))
            throw BizException.notFound("反算方案不存在");
        ReverseScheme upd = parseScheme(body);
        upd.setId(sid);
        upd.setUpdatedAt(LocalDateTime.now());
        upd.setCreatedAt(exist.getCreatedAt());
        schemeMapper.updateById(upd);
        return R.ok(Collections.singletonMap("id", sid));
    }

    /**
     * <p>软删除方案 (级联软删 target + run)</p>
     *
     * @param sid 方案 ID (必填)
     * @return R.ok(Map.of("ok", true)); 不存在时抛 notFound
     */
    @Transactional
    public R<Map<String, Object>> deleteScheme(Long sid) {
        if (sid == null) throw BizException.badRequest("id 必填");
        ReverseScheme exist = schemeMapper.selectById(sid);
        if (exist == null) throw BizException.notFound("反算方案不存在");
        // 软删
        jdbc.update("UPDATE prcp_reverse_scheme SET is_deleted=1, updated_at=NOW() WHERE id=?", sid);
        jdbc.update("UPDATE prcp_reverse_target SET is_deleted=1, updated_at=NOW() WHERE scheme_id=?", sid);
        jdbc.update("UPDATE prcp_reverse_run SET is_deleted=1, updated_at=NOW() WHERE scheme_id=?", sid);
        return R.ok(Collections.singletonMap("ok", true));
    }

    private ReverseScheme parseScheme(Map<String, Object> body) {
        ReverseScheme s = new ReverseScheme();
        s.setSchemeCode((String) body.get("scheme_code"));
        s.setSchemeName((String) body.get("scheme_name"));
        s.setSchemeType((String) body.get("scheme_type"));
        Object coaId = body.get("coa_scheme_id");
        if (coaId != null) s.setCoaSchemeId(((Number) coaId).longValue());
        Object dd = body.get("data_date");
        if (dd != null) s.setDataDate(LocalDate.parse(dd.toString()));
        Object hm = body.get("horizon_months");
        if (hm != null) s.setHorizonMonths(((Number) hm).intValue());
        s.setAlgorithm((String) body.get("algorithm"));
        Object mid = body.get("model_id");
        if (mid != null) s.setModelId(((Number) mid).longValue());
        s.setDescription((String) body.get("description"));
        s.setStatus((String) body.get("status"));
        return s;
    }

    /**
     * <p>查询目标约束列表 (按 schemeId 过滤 + 关联 KPI 展示名)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @return R.ok(Map.of("items", list)) LIMIT 500
     */
    public R<Map<String, Object>> listTargets(Long schemeId) {
        StringBuilder sql = new StringBuilder(
                "SELECT t.id, t.scheme_id AS schemeId, t.kpi_id AS kpiId, t.kpi_code AS kpiCode,"
              + " t.target_name AS targetName, t.target_value AS targetValue, t.constraint_type AS constraintType,"
              + " t.weight, t.horizon_month AS horizonMonth, t.sort_order AS sortOrder, t.description,"
              + " k.kpi_name AS refKpiName, k.formula AS refFormula"
              + " FROM prcp_reverse_target t"
              + " LEFT JOIN prcp_kpi_definition k ON k.id = t.kpi_id"
              + " WHERE t.is_deleted = 0");
        List<Object> params = new ArrayList<>();
        if (schemeId != null) {
            sql.append(" AND t.scheme_id = ?");
            params.add(schemeId);
        }
        sql.append(" ORDER BY t.scheme_id, t.sort_order LIMIT 500");
        List<Map<String, Object>> items = jdbc.queryForList(sql.toString(), params.toArray());
        return R.ok(Collections.singletonMap("items", items));
    }

    /**
     * <p>创建目标约束 (默认 constraint_type='GE')</p>
     *
     * @param body 含 scheme_id/kpi_id/kpi_code/target_name/target_value + constraint_type/weight/horizon_month/sort_order/description
     * @return R.ok(Map.of("id"))
     */
    @Transactional
    public R<Map<String, Object>> createTarget(Map<String, Object> body) {
        ReverseTarget t = parseTarget(body);
        t.setIsDeleted(0);
        t.setCreatedAt(LocalDateTime.now());
        t.setUpdatedAt(LocalDateTime.now());
        targetMapper.insert(t);
        return R.ok(Collections.singletonMap("id", t.getId()));
    }

    /**
     * <p>更新目标约束</p>
     *
     * @param tid  目标 ID (必填)
     * @param body 待更新字段
     * @return R.ok(Map.of("id")); 不存在或已删除时抛 notFound
     */
    @Transactional
    public R<Map<String, Object>> updateTarget(Long tid, Map<String, Object> body) {
        if (tid == null) throw BizException.badRequest("id 必填");
        ReverseTarget exist = targetMapper.selectById(tid);
        if (exist == null || Integer.valueOf(1).equals(exist.getIsDeleted()))
            throw BizException.notFound("目标不存在");
        ReverseTarget upd = parseTarget(body);
        upd.setId(tid);
        upd.setCreatedAt(exist.getCreatedAt());
        upd.setUpdatedAt(LocalDateTime.now());
        targetMapper.updateById(upd);
        return R.ok(Collections.singletonMap("id", tid));
    }

    /**
     * <p>软删除目标约束 (is_deleted=1)</p>
     *
     * @param tid 目标 ID (必填)
     * @return R.ok(Map.of("ok", true))
     */
    @Transactional
    public R<Map<String, Object>> deleteTarget(Long tid) {
        if (tid == null) throw BizException.badRequest("id 必填");
        jdbc.update("UPDATE prcp_reverse_target SET is_deleted=1, updated_at=NOW() WHERE id=?", tid);
        return R.ok(Collections.singletonMap("ok", true));
    }

    private ReverseTarget parseTarget(Map<String, Object> body) {
        ReverseTarget t = new ReverseTarget();
        Object sid = body.get("scheme_id");
        if (sid != null) t.setSchemeId(((Number) sid).longValue());
        Object kid = body.get("kpi_id");
        if (kid != null) t.setKpiId(((Number) kid).longValue());
        t.setKpiCode((String) body.get("kpi_code"));
        t.setTargetName((String) body.get("target_name"));
        Object tv = body.get("target_value");
        if (tv != null) t.setTargetValue(new BigDecimal(tv.toString()));
        t.setConstraintType(body.get("constraint_type") == null ? "GE" : body.get("constraint_type").toString());
        Object w = body.get("weight");
        if (w != null) t.setWeight(new BigDecimal(w.toString()));
        Object hm = body.get("horizon_month");
        if (hm != null) t.setHorizonMonth(((Number) hm).intValue());
        Object so = body.get("sort_order");
        if (so != null) t.setSortOrder(((Number) so).intValue());
        t.setDescription((String) body.get("description"));
        return t;
    }

    // ========== Run CRUD ==========
    /**
     * <p>模型选项 (id/code/name/type, LIMIT 100)</p>
     *
     * @return 模型列表
     */
    public List<Map<String, Object>> listModelsForOption() {
        try {
            return jdbc.queryForList(
                    "SELECT id, model_code AS modelCode, model_name AS modelName, model_type AS modelType"
                  + " FROM prcp_model WHERE is_deleted=0 ORDER BY model_type, model_code LIMIT 100");
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * <p>KPI 选项 (id/code/name/formula/category, LIMIT 500)</p>
     *
     * @return R.ok(Map.of("items", list)) 异常时返回空列表
     */
    public R<Map<String, Object>> kpiOptions() {
        try {
            List<Map<String, Object>> items = jdbc.queryForList(
                    "SELECT id, kpi_code AS kpiCode, kpi_name AS kpiName, formula, category"
                  + " FROM prcp_kpi_definition WHERE is_deleted=0 ORDER BY category, kpi_code LIMIT 500");
            return R.ok(Collections.singletonMap("items", items));
        } catch (Exception e) {
            return R.ok(Collections.singletonMap("items", java.util.Collections.emptyList()));
        }
    }

    /**
     * <p>查询 Run 列表 (按 schemeId/status 过滤, LIMIT 50/200)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param status   状态 (可选)
     * @param limit    返回条数 (上限 200, 默认 50)
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> listRuns(Long schemeId, String status, Integer limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT r.id, r.run_code AS runCode, r.scheme_id AS schemeId, r.status, r.progress,"
              + " r.start_at AS startAt, r.end_at AS endAt, r.duration_sec AS durationSec,"
              + " r.optimal_value AS optimalValue, r.metrics, r.error_message AS errorMessage,"
              + " r.description, r.created_at AS createdAt,"
              + " s.scheme_code AS schemeCode, s.scheme_name AS schemeName"
              + " FROM prcp_reverse_run r"
              + " LEFT JOIN prcp_reverse_scheme s ON s.id = r.scheme_id"
              + " WHERE r.is_deleted = 0");
        List<Object> params = new ArrayList<>();
        if (schemeId != null) { sql.append(" AND r.scheme_id = ?"); params.add(schemeId); }
        if (status != null && !status.isEmpty()) { sql.append(" AND r.status = ?"); params.add(status); }
        sql.append(" ORDER BY r.id DESC LIMIT ?");
        params.add(limit == null || limit > 200 ? 50 : limit);
        List<Map<String, Object>> items = jdbc.queryForList(sql.toString(), params.toArray());
        return R.ok(Collections.singletonMap("items", items));
    }

    /**
     * <p>创建 Run 记录 (默认 status='PENDING', progress=0, run_code='RR{timestamp}')</p>
     *
     * @param body 含 scheme_id (必填) + description (可选)
     * @return R.ok(Map.of("id"/"run_code"))
     */
    @Transactional
    public R<Map<String, Object>> createRun(Map<String, Object> body) {
        Object sid = body.get("scheme_id");
        if (sid == null) throw BizException.badRequest("scheme_id 必填");
        ReverseRun r = new ReverseRun();
        r.setRunCode("RR" + System.currentTimeMillis());
        r.setSchemeId(((Number) sid).longValue());
        r.setStatus("PENDING");
        r.setProgress(BigDecimal.ZERO);
        r.setDescription((String) body.get("description"));
        r.setIsDeleted(0);
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(LocalDateTime.now());
        runMapper.insert(r);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", r.getId());
        resp.put("run_code", r.getRunCode());
        return R.ok(resp);
    }

    /**
     * <p>软删除 Run + 硬删 prcp_reverse_result (run_log 永久保留)</p>
     *
     * @param rid Run ID (必填)
     * @return R.ok(Map.of("ok", true))
     */
    @Transactional
    public R<Map<String, Object>> deleteRun(Long rid) {
        if (rid == null) throw BizException.badRequest("id 必填");
        jdbc.update("UPDATE prcp_reverse_run SET is_deleted=1, updated_at=NOW() WHERE id=?", rid);
        // run_log/result 表没有 is_deleted 字段，不软删（永久保留日志）
        jdbc.update("DELETE FROM prcp_reverse_result WHERE run_id=?", rid);
        return R.ok(Collections.singletonMap("ok", true));
    }

    /**
     * <p>取消 Run (PENDING/RUNNING 状态可取消)</p>
     *
     * @param rid Run ID (必填)
     * @return R.ok(Map.of("ok", true))
     */
    public R<Map<String, Object>> cancelRun(Long rid) {
        if (rid == null) throw BizException.badRequest("id 必填");
        jdbc.update("UPDATE prcp_reverse_run SET status='CANCELLED', end_at=NOW() WHERE id=? AND status IN ('PENDING','RUNNING')", rid);
        return R.ok(Collections.singletonMap("ok", true));
    }

    /**
     * <p>查询 Run 日志 (增量 sinceId, LIMIT 500)</p>
     *
     * @param rid     Run ID (必填)
     * @param sinceId 增量起点日志 ID (可选, 默认 0)
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> runLogs(Long rid, Long sinceId) {
        if (rid == null) throw BizException.badRequest("id 必填");
        String sql = "SELECT id, run_id AS runId, log_level AS logLevel, log_message AS logMessage, progress, created_at AS createdAt"
                   + " FROM prcp_reverse_run_log WHERE run_id=? AND id > ? ORDER BY id ASC LIMIT 500";
        Long since = sinceId == null ? 0L : sinceId;
        List<Map<String, Object>> items = jdbc.queryForList(sql, rid, since);
        return R.ok(Collections.singletonMap("items", items));
    }

    /**
     * <p>查询 Run 结果 (含 run/metrics/optimal_value/items, LIMIT 5000)</p>
     *
     * @param rid Run ID (必填)
     * @return R.ok(Map.of("run"/"metrics"/"optimal_value"/"items", ...)); 不存在时抛 notFound
     */
    public R<Map<String, Object>> runResult(Long rid) {
        if (rid == null) throw BizException.badRequest("id 必填");
        ReverseRun run = runMapper.selectById(rid);
        if (run == null) throw BizException.notFound("run 不存在");
        List<Map<String, Object>> results = jdbc.queryForList(
                "SELECT id, run_id AS runId, predict_month AS predictMonth, predict_date AS predictDate,"
              + " rpt_item_id AS rptItemId, rpt_item_code AS rptItemCode,"
              + " current_value AS currentValue, adjusted_value AS adjustedValue, delta_value AS deltaValue"
              + " FROM prcp_reverse_result WHERE run_id=? AND is_deleted=0 ORDER BY predict_month, rpt_item_id LIMIT 5000",
              rid);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("run", run);
        resp.put("metrics", run.getMetrics());
        resp.put("optimal_value", run.getOptimalValue());
        resp.put("items", results);
        return R.ok(resp);
    }

    /**
     * <p>启动异步 Run (PENDING → RUNNING, 提交给独立引擎 HTTP 异步执行)</p>
     *
     * <p>本方法把第 1.1 节的 executeReverse() 拆成 HTTP 调用:
     * 1. 标记 status='RUNNING'
     * 2. 异步调 engineGateway.submit(rid, schemeId) 提交 4 段报文
     * 3. 引擎受理后返回 ACK, 实际计算通过 POST /reverse/runs/{rid}/callback 回传
     * 4. 提交失败时标记 status='FAILED' 并写日志</p>
     *
     * @param rid Run ID (必填)
     * @return R.ok(Map.of("id"/"status", "RUNNING")); 不存在时抛 notFound
     */
    public R<Map<String, Object>> startRun(Long rid) {
        if (rid == null) throw BizException.badRequest("id 必填");
        ReverseRun run = runMapper.selectById(rid);
        if (run == null) throw BizException.notFound("run 不存在");
        // 标记 RUNNING（避免重复启动）
        jdbc.update("UPDATE prcp_reverse_run SET status='RUNNING', progress=0, start_at=NOW() WHERE id=? AND status='PENDING'", rid);
        Long schemeId = run.getSchemeId();
        // 异步提交引擎
        CompletableFuture.runAsync(() -> {
            try {
                writeLog(rid, "INFO", "开始组装 4 段报文并提交引擎...", 0);
                Map<String, Object> ack = engineGateway.submit(rid, schemeId);
                writeLog(rid, "INFO",
                        "引擎已受理: engine_run_id=" + ack.get("engine_run_id")
                      + " estimated=" + ack.get("estimated_seconds") + "s", 5);
            } catch (Exception e) {
                log.error("提交引擎失败 rid={}", rid, e);
                try {
                    jdbc.update("UPDATE prcp_reverse_run SET status='FAILED', end_at=NOW(), error_message=? WHERE id=?",
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), rid);
                } catch (Exception ignored) { }
                writeLog(rid, "ERROR", "提交引擎失败: " + e.getMessage(), 0);
            }
        }, EXEC);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", rid);
        resp.put("status", "RUNNING");
        return R.ok(resp);
    }

    /**
     * <p>处理引擎回调: 引擎完成反算后 POST /reverse/runs/{rid}/callback 调用本方法</p>
     *
     * <p>流程:
     * <ol>
     *   <li>校验 callback token (Authorization Bearer)</li>
     *   <li>读取 body.status: SUCCESS/FAILED/CANCELLED</li>
     *   <li>SUCCESS: 写 prcp_reverse_result + 更新 run.status='SUCCESS' + metrics + optimal_value</li>
     *   <li>FAILED: 更新 run.status='FAILED' + error_message</li>
     *   <li>CANCELLED: 更新 run.status='CANCELLED'</li>
     * </ol>
     * </p>
     *
     * @param rid  Run ID (路径参数)
     * @param body 引擎回调报文 (§ 2.2 格式)
     * @return R.ok({received: true})
     */
    public R<Map<String, Object>> handleEngineCallback(Long rid, Map<String, Object> body) {
        if (rid == null) throw BizException.badRequest("id 必填");
        if (body == null) throw BizException.badRequest("body 必填");
        ReverseRun run = runMapper.selectById(rid);
        if (run == null) throw BizException.notFound("run 不存在");

        String status = (String) body.get("status");
        Object durationObj = body.get("duration_sec");
        Object optimalObj = body.get("optimal_value");
        Object metricsObj = body.get("metrics");
        Object errorMsg = body.get("error_message");

        Integer durationSec = null;
        if (durationObj instanceof Number) durationSec = ((Number) durationObj).intValue();
        String metricsJson = null;
        if (metricsObj != null) {
            if (metricsObj instanceof String) {
                metricsJson = (String) metricsObj;
            } else {
                metricsJson = toJson(metricsObj);
            }
        }
        String errMsg = errorMsg == null ? null : errorMsg.toString();

        if ("SUCCESS".equalsIgnoreCase(status)) {
            // 1. 写 prcp_reverse_result (从 body.results 解析)
            Object resultsObj = body.get("results");
            if (resultsObj instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) resultsObj;
                int inserted = 0;
                for (Map<String, Object> item : items) {
                    try {
                        ReverseResult res = new ReverseResult();
                        res.setRunId(rid);
                        res.setPredictMonth(item.get("predict_month") == null ? null
                                : ((Number) item.get("predict_month")).intValue());
                        res.setPredictDate(item.get("predict_date") == null ? null
                                : java.time.LocalDate.parse(item.get("predict_date").toString().substring(0, 10)));
                        res.setRptItemCode((String) item.get("rpt_item_code"));
                        res.setCurrentValue(toBD(item.get("current_value")));
                        res.setAdjustedValue(toBD(item.get("adjusted_value")));
                        BigDecimal cur = toBD(item.get("current_value"));
                        BigDecimal adj = toBD(item.get("adjusted_value"));
                        res.setDeltaValue(adj == null ? null
                                : (cur == null ? adj : adj.subtract(cur)));
                        res.setIsDeleted(0);
                        res.setCreatedAt(LocalDateTime.now());
                        resultMapper.insert(res);
                        inserted++;
                    } catch (Exception ex) {
                        log.warn("写 result 失败 rid={} item={}: {}", rid, item, ex.getMessage());
                    }
                }
                writeLog(rid, "INFO", "已写入 " + inserted + " 条 result", 95);
            }
            // 2. 更新 run
            jdbc.update("UPDATE prcp_reverse_run SET status='SUCCESS', progress=100, end_at=NOW(),"
                      + " duration_sec=?, optimal_value=?, metrics=?, error_message=NULL WHERE id=?",
                    durationSec, optimalObj, metricsJson, rid);
            writeLog(rid, "INFO",
                    "引擎回调 SUCCESS: optimal=" + optimalObj + " duration=" + durationSec + "s", 100);
        } else if ("FAILED".equalsIgnoreCase(status)) {
            jdbc.update("UPDATE prcp_reverse_run SET status='FAILED', end_at=NOW(), error_message=? WHERE id=?",
                    errMsg, rid);
            writeLog(rid, "ERROR", "引擎回调 FAILED: " + errMsg, 0);
        } else if ("CANCELLED".equalsIgnoreCase(status)) {
            jdbc.update("UPDATE prcp_reverse_run SET status='CANCELLED', end_at=NOW() WHERE id=?", rid);
            writeLog(rid, "WARN", "引擎回调 CANCELLED", 0);
        } else {
            log.warn("引擎回调未知 status={} rid={}", status, rid);
        }
        return R.ok(Collections.singletonMap("received", true));
    }

    /**
     * 反算主流程 — 已废弃, 现改用 {@link ReverseEngineGateway#submit(Long, Long)} HTTP 提交
     * 独立引擎服务器; 实际计算完成后引擎回调 {@link #handleEngineCallback(Long, Map)} 写结果。
     */
    @Deprecated
    void executeReverse(Long rid) {
        writeLog(rid, "WARN", "executeReverse 已废弃, 请使用 ReverseEngineGateway 提交", 0);
    }

    private void writeLog(Long rid, String level, String msg, int progress) {
        try {
            jdbc.update("INSERT INTO prcp_reverse_run_log (run_id, log_level, log_message, progress, created_at)"
                      + " VALUES (?, ?, ?, ?, NOW())", rid, level, msg, progress);
            jdbc.update("UPDATE prcp_reverse_run SET progress=? WHERE id=?", progress, rid);
        } catch (Exception e) {
            log.warn("写日志失败：{}", e.getMessage());
        }
    }

    private static BigDecimal toBD(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return BigDecimal.valueOf(((Number) o).doubleValue());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return null; }
    }

    private static String toJson(Object obj) {
        // 极简 JSON 序列化（不引入依赖；使用 Spring 的 Jackson 也可）
        if (obj == null) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            m.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            m.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            return m.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("toJson 失败: {}", e.getMessage());
            return "{}";
        }
    }
}
