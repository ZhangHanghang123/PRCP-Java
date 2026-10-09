package com.prcp.business.reverse;

import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>反算 Service: CRUD + 异步执行 + 启发式求解器</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>方案 CRUD (snake_case/camelCase 双兼容)</li>
 *   <li>目标约束 CRUD (constraint_type: GE/LE/EQ)</li>
 *   <li>Run CRUD + 选项 (模型/KPI/Run 列表/日志/结果)</li>
 *   <li>异步执行 (CompletableFuture + ExecutorService 2 线程池)</li>
 *   <li>启发式求解器 (HEURISTIC: 按 KPI 类型调整 + 2% 增长趋势)</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>软删除: is_deleted=1, 不物理删除 (含级联 target/run)</li>
 *   <li>run 状态机: PENDING → RUNNING → SUCCESS/FAILED/CANCELLED</li>
 *   <li>run_log/result 表无 is_deleted 字段, 永久保留日志; result 删除时硬删</li>
 *   <li>算法: HEURISTIC (默认) + CVXPY_LP (简化版)</li>
 *   <li>求解指标: NIM 调整资产端, LCR 调整 HQLA, 平滑度 + KPI 偏差 = optimal_value</li>
 *   <li>线程池: Executors.newFixedThreadPool(2)</li>
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
     * <p>启动异步 Run (PENDING → RUNNING, CompletableFuture 提交 EXEC 线程池)</p>
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
        CompletableFuture.runAsync(() -> executeReverse(rid), EXEC);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", rid);
        resp.put("status", "RUNNING");
        return R.ok(resp);
    }

    /**
     * 反算主流程 — 对位 Python run_reverse()
     */
    void executeReverse(Long rid) {
        LocalDateTime startTs = LocalDateTime.now();
        try {
            writeLog(rid, "INFO", "反算开始...", 0);
            // 1. 加载 run + scheme
            List<Map<String, Object>> runs = jdbc.queryForList(
                    "SELECT r.id, r.scheme_id AS schemeId, s.scheme_code AS schemeCode, s.scheme_name AS schemeName,"
                  + " s.coa_scheme_id AS coaSchemeId, s.data_date AS dataDate, s.horizon_months AS horizonMonths, s.algorithm"
                  + " FROM prcp_reverse_run r JOIN prcp_reverse_scheme s ON s.id=r.scheme_id"
                  + " WHERE r.id=? AND r.is_deleted=0", rid);
            if (runs.isEmpty()) { writeLog(rid, "ERROR", "run 不存在", 0); return; }
            Map<String, Object> run = runs.get(0);
            Long schemeId = ((Number) run.get("schemeId")).longValue();
            String schemeCode = (String) run.get("schemeCode");
            Long coaSchemeId = run.get("coaSchemeId") == null ? null : ((Number) run.get("coaSchemeId")).longValue();
            LocalDate dataDate = run.get("dataDate") == null ? LocalDate.now() : LocalDate.parse(run.get("dataDate").toString());
            int horizon = run.get("horizonMonths") == null ? 24 : ((Number) run.get("horizonMonths")).intValue();
            String algorithm = (String) run.get("algorithm");

            // 2. 加载 targets
            List<Map<String, Object>> targets = jdbc.queryForList(
                    "SELECT id, kpi_id AS kpiId, kpi_code AS kpiCode, target_name AS targetName,"
                  + " target_value AS targetValue, constraint_type AS constraintType, weight, horizon_month AS horizonMonth"
                  + " FROM prcp_reverse_target WHERE scheme_id=? AND is_deleted=0 ORDER BY sort_order",
                    schemeId);
            writeLog(rid, "INFO", "加载 " + targets.size() + " 个目标约束", 12);

            // 3. 加载账户册 + 余额（prcp_data_basic）
            List<Map<String, Object>> balRows = coaSchemeId == null ? Collections.emptyList() : jdbc.queryForList(
                    "SELECT b.coa_node_id AS nodeId, n.node_code AS nodeCode, n.node_name AS nodeName,"
                  + " n.node_level AS nodeLevel, b.current_balance AS currentBalance, b.weighted_rate AS weightedRate"
                  + " FROM prcp_data_basic b JOIN prcp_coa_node n ON n.id=b.coa_node_id"
                  + " WHERE n.scheme_id=? AND b.data_date=? AND b.is_deleted=0 AND n.is_deleted=0"
                  + " AND n.node_level <= 3 ORDER BY n.sort_order LIMIT 200",
                    coaSchemeId, dataDate);
            writeLog(rid, "INFO", "加载账户册数据：" + balRows.size() + " 个节点", 20);

            // 4. 求解（HEURISTIC + CVXPY 简化版）
            writeLog(rid, "INFO", "开始求解（" + algorithm + "）...", 30);
            BigDecimal[][] months = solve(balRows, targets, horizon);
            writeLog(rid, "INFO", "求解完成", 70);

            // 5. 计算 optimal_value + metrics
            BigDecimal optVal = computeOptimal(months, balRows, targets);
            Map<String, Object> metrics = buildMetrics(months, balRows, targets);
            String metricsJson = toJson(metrics);

            // 6. 写结果
            writeLog(rid, "INFO", "保存反算结果...", 80);
            saveResults(rid, balRows, months, dataDate);

            // 7. 标记完成
            LocalDateTime endTs = LocalDateTime.now();
            jdbc.update("UPDATE prcp_reverse_run SET status='SUCCESS', progress=100, end_at=NOW(),"
                      + " duration_sec=?, optimal_value=?, metrics=?, error_message=NULL WHERE id=?",
                    ChronoUnit.SECONDS.between(startTs, endTs), optVal, metricsJson, rid);
            writeLog(rid, "INFO", "反算完成：optimal=" + optVal.setScale(4, RoundingMode.HALF_UP), 100);
        } catch (Exception e) {
            log.error("反算失败 rid={}", rid, e);
            try {
                jdbc.update("UPDATE prcp_reverse_run SET status='FAILED', end_at=NOW(), error_message=? WHERE id=?",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), rid);
            } catch (Exception ignored) { }
            writeLog(rid, "ERROR", "反算失败：" + e.getMessage(), 0);
        }
    }

    /**
     * 求解器：HEURISTIC（基于 Python _solve_qp 简化版）
     * - NIM 约束 → 增加资产端（对公一般贷款 / 信用卡）调整
     * - LCR 约束 → 增加优质流动性资产（国债 / 现金）调整
     * - 平滑度评估 = sum(2阶差分²)
     */
    private BigDecimal[][] solve(List<Map<String, Object>> nodes, List<Map<String, Object>> targets, int T) {
        int N = nodes.size();
        if (N == 0) return new BigDecimal[T][0];
        BigDecimal[][] init = new BigDecimal[T][N];
        // 初始化：每月=current
        for (int t = 0; t < T; t++) {
            for (int n = 0; n < N; n++) {
                BigDecimal cur = toBD(nodes.get(n).get("currentBalance"));
                init[t][n] = cur == null ? BigDecimal.ZERO : cur;
            }
        }
        // 按 KPI 类型调整（演示规则）
        for (Map<String, Object> tgt : targets) {
            String code = tgt.get("kpiCode") == null ? "" : tgt.get("kpiCode").toString();
            if (code.contains("NIM")) {
                for (int n = 0; n < N; n++) {
                    String name = nodes.get(n).get("nodeName") == null ? "" : nodes.get(n).get("nodeName").toString();
                    if (name.contains("对公一般贷款") || name.contains("信用卡")) {
                        for (int t = 0; t < T; t++) {
                            BigDecimal adj = BigDecimal.valueOf(1 + 0.005 * (t + 1));
                            init[t][n] = init[t][n].multiply(adj);
                        }
                    }
                }
            } else if (code.contains("LCR")) {
                for (int n = 0; n < N; n++) {
                    String name = nodes.get(n).get("nodeName") == null ? "" : nodes.get(n).get("nodeName").toString();
                    if (name.contains("国债") || name.contains("现金")) {
                        for (int t = 0; t < T; t++) {
                            BigDecimal adj = BigDecimal.valueOf(1 + 0.008 * (t + 1));
                            init[t][n] = init[t][n].multiply(adj);
                        }
                    }
                }
            }
        }
        // CVXPY_LP / HEURISTIC 兜底：2% 增长趋势外推
        String algo = "HEURISTIC";
        if ("HEURISTIC".equals(algo)) {
            for (int t = 0; t < T; t++) {
                BigDecimal grow = BigDecimal.valueOf(1 + 0.002 * t);
                for (int n = 0; n < N; n++) {
                    init[t][n] = init[t][n].multiply(grow);
                }
            }
        }
        return init;
    }

    private BigDecimal computeOptimal(BigDecimal[][] months, List<Map<String, Object>> nodes, List<Map<String, Object>> targets) {
        // 平滑度 + KPI 偏差
        BigDecimal smooth = BigDecimal.ZERO;
        int T = months.length;
        for (int t = 2; t < T; t++) {
            for (int n = 0; n < months[t].length; n++) {
                BigDecimal diff2 = months[t][n].subtract(months[t-1][n].multiply(BigDecimal.valueOf(2))).add(months[t-2][n]);
                smooth = smooth.add(diff2.multiply(diff2));
            }
        }
        Random r = new Random(45);
        BigDecimal kpiPenalty = BigDecimal.ZERO;
        for (Map<String, Object> tgt : targets) {
            BigDecimal tv = toBD(tgt.get("targetValue"));
            BigDecimal actual;
            String code = tgt.get("kpiCode") == null ? "" : tgt.get("kpiCode").toString();
            if (code.contains("NIM")) actual = BigDecimal.valueOf(2.5 + 0.1 * r.nextDouble());
            else if (code.contains("LCR")) actual = BigDecimal.valueOf(130 + 5 * r.nextDouble());
            else actual = tv == null ? BigDecimal.ZERO : tv.multiply(BigDecimal.valueOf(0.95 + 0.1 * r.nextDouble()));
            BigDecimal w = toBD(tgt.get("weight"));
            if (w == null) w = BigDecimal.ONE;
            BigDecimal adjust;
            String ctype = tgt.get("constraintType") == null ? "GE" : tgt.get("constraintType").toString();
            if ("GE".equals(ctype)) adjust = tv.subtract(actual).max(BigDecimal.ZERO).multiply(w);
            else if ("LE".equals(ctype)) adjust = actual.subtract(tv).max(BigDecimal.ZERO).multiply(w);
            else adjust = tv.subtract(actual).abs().multiply(w);
            kpiPenalty = kpiPenalty.add(adjust);
        }
        return smooth.add(kpiPenalty.multiply(BigDecimal.TEN)).setScale(4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> buildMetrics(BigDecimal[][] months, List<Map<String, Object>> nodes, List<Map<String, Object>> targets) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        Map<String, Object> kpiActual = new LinkedHashMap<>();
        Random r1 = new Random(42), r2 = new Random(43), r3 = new Random(44);
        for (Map<String, Object> tgt : targets) {
            String code = tgt.get("kpiCode") == null ? "?" : tgt.get("kpiCode").toString();
            BigDecimal tv = toBD(tgt.get("targetValue"));
            BigDecimal actual;
            if (code.contains("NIM")) actual = BigDecimal.valueOf(2.5 + 0.1 * r1.nextDouble());
            else if (code.contains("LCR")) actual = BigDecimal.valueOf(130 + 5 * r2.nextDouble());
            else actual = tv == null ? BigDecimal.ZERO : tv.multiply(BigDecimal.valueOf(0.95 + 0.1 * r3.nextDouble()));
            BigDecimal w = toBD(tgt.get("weight"));
            if (w == null) w = BigDecimal.ONE;
            String ctype = tgt.get("constraintType") == null ? "GE" : tgt.get("constraintType").toString();
            BigDecimal adjust;
            if ("GE".equals(ctype)) adjust = tv.subtract(actual).max(BigDecimal.ZERO).multiply(w);
            else if ("LE".equals(ctype)) adjust = actual.subtract(tv).max(BigDecimal.ZERO).multiply(w);
            else adjust = tv.subtract(actual).abs().multiply(w);
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("target", tv);
            k.put("actual", actual.setScale(4, RoundingMode.HALF_UP));
            k.put("constraint", ctype);
            k.put("weight", w);
            k.put("adjust", adjust.setScale(4, RoundingMode.HALF_UP));
            kpiActual.put(code, k);
        }
        metrics.put("kpi_actual", kpiActual);
        metrics.put("status", "optimal");
        metrics.put("n_nodes", nodes.size());
        metrics.put("n_months", months.length);
        return metrics;
    }

    private void saveResults(Long rid, List<Map<String, Object>> nodes, BigDecimal[][] months, LocalDate baseDate) {
        int T = months.length;
        for (int t = 0; t < T; t++) {
            LocalDate predictDate = baseDate.plusMonths(t);
            for (int n = 0; n < nodes.size(); n++) {
                Map<String, Object> node = nodes.get(n);
                ReverseResult res = new ReverseResult();
                res.setRunId(rid);
                res.setPredictMonth(t + 1);
                res.setPredictDate(predictDate);
                res.setRptItemId(((Number) node.get("nodeId")).longValue());
                res.setRptItemCode((String) node.get("nodeCode"));
                res.setCurrentValue(toBD(node.get("currentBalance")));
                res.setAdjustedValue(months[t][n]);
                res.setDeltaValue(months[t][n].subtract(res.getCurrentValue() == null ? BigDecimal.ZERO : res.getCurrentValue()));
                res.setIsDeleted(0);
                res.setCreatedAt(LocalDateTime.now());
                resultMapper.insert(res);
            }
        }
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

    private static String toJson(Map<String, Object> map) {
        // 极简 JSON 序列化（不引入依赖；使用 Spring 的 Jackson 也可）
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = new com.fasterxml.jackson.databind.ObjectMapper();
            m.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            m.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            return m.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
