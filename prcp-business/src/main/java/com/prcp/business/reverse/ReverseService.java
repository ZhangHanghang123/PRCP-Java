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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    /**
     * <p>各求解器的标称轮次 (对位 Python 版 reverse_calc_engine.ALGO_ROUNDS)</p>
     *
     * <p>用于 /progress 接口在轮次表缺数据时兜底推算总轮次:
     * CVXPY_QP/LP 一次求解 → 1 轮; HEURISTIC 启发式多轮迭代 → 5 轮。</p>
     */
    private static final Map<String, Integer> ALGO_ROUNDS = Map.of(
            "CVXPY_QP", 1,
            "CVXPY_LP", 1,
            "HEURISTIC", 5);

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
     * <p>创建 Run 记录 (默认 status='PENDING', progress=0)</p>
     *
     * <p><strong>run_code 格式</strong>: {@code R{yyyyMMdd}_{6位大写HEX}}, 例 {@code R20261111_0794AE}
     * <ul>
     *   <li>{@code R} — 固定前缀, 标识 Reverse Run</li>
     *   <li>{@code yyyyMMdd} — <strong>运行创建当天</strong>的服务器本地日期 (非方案数据日期 data_date,
     *       也非 start_at — 后者在 /start 接口才写入, 跨日时两者可能不同天)</li>
     *   <li>{@code _} — 分隔符</li>
     *   <li>{@code 6位大写HEX} — {@link UUID#randomUUID()} 前 6 位十六进制转大写,
     *       字符集严格为 {@code 0-9A-F}</li>
     * </ul>
     * 对位 Python 版 {@code backend/app/routers/reverse.py} 的
     * {@code f"R{datetime.now().strftime('%Y%m%d')}_{uuid.uuid4().hex[:6].upper()}"}。
     * 之所以不用纯时间戳 (原 Java 版 {@code RR{millis}}): 毫秒数需人工换算才能读出运行日期,
     * 且无法在列表页快速识别「今天跑了哪几轮」。</p>
     *
     * @param body 含 scheme_id (必填) + description (可选)
     * @return R.ok(Map.of("id"/"run_code"))
     */
    @Transactional
    public R<Map<String, Object>> createRun(Map<String, Object> body) {
        Object sid = body.get("scheme_id");
        if (sid == null) throw BizException.badRequest("scheme_id 必填");
        ReverseRun r = new ReverseRun();
        r.setRunCode(generateRunCode());
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
     * <p>生成运行编码 {@code R{yyyyMMdd}_{6位大写HEX}}</p>
     *
     * <p>对位 Python 版 reverse.py 的 create_run。日期取运行创建当天 (服务器本地时区),
     * 随机段用 UUID4 前 6 位十六进制 (字符集 0-9A-F) —— 依赖 {@code prcp_reverse_run.run_code}
     * 上的 UNIQUE 索引保证同日多轮不冲突。</p>
     *
     * @return 运行编码, 例 {@code R20261111_0794AE}
     */
    private String generateRunCode() {
        String datePart = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String randPart = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "R" + datePart + "_" + randPart;
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
     * <p>对位 Python 版 {@code GET /reverse/runs/{rid}/logs}。返回结构化字段
     * {@code stage / roundNo / algo / roundResult}, 供前端渲染「阶段 / 轮次 / 算法」标签
     * 与逐指标达成度明细。</p>
     *
     * @param rid     Run ID (必填)
     * @param sinceId 增量起点日志 ID (可选, 默认 0)
     * @param stage   阶段过滤 (可选, 如 SOLVE); null 表示不过滤
     * @param limit   单次返回条数 (可选, 默认 500, 上限 3000)
     * @return R.ok(Map.of("items", list, "count", n))
     */
    public R<Map<String, Object>> runLogs(Long rid, Long sinceId, String stage, Integer limit) {
        if (rid == null) throw BizException.badRequest("id 必填");
        Long since = sinceId == null ? 0L : sinceId;
        int lim = (limit == null || limit <= 0) ? 500 : Math.min(limit, 3000);
        StringBuilder sql = new StringBuilder(
                "SELECT id, run_id AS runId, log_level AS logLevel, log_message AS logMessage, progress,"
              + " stage, round_no AS roundNo, algo, round_result AS roundResult, created_at AS createdAt"
              + " FROM prcp_reverse_run_log WHERE run_id=? AND id > ?");
        List<Object> params = new ArrayList<>();
        params.add(rid);
        params.add(since);
        if (stage != null && !stage.isEmpty()) {
            sql.append(" AND stage = ?");
            params.add(stage);
        }
        sql.append(" ORDER BY id ASC LIMIT ?");
        params.add(lim);
        List<Map<String, Object>> items = jdbc.queryForList(sql.toString(), params.toArray());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("count", items.size());
        return R.ok(resp);
    }

    /**
     * <p>运行进度 — 对位 Python 版 {@code GET /reverse/runs/{rid}/progress}</p>
     *
     * <p>一次请求返回前端状态条所需的全部信息: 状态三态 + 进度百分比 + 当前阶段/轮次
     * + 轮次清单 + 耗时预估 + 日志条数。</p>
     *
     * <p><strong>关键计算口径</strong>:</p>
     * <ul>
     *   <li>{@code done} — status ∈ (SUCCESS, FAILED, CANCELLED)</li>
     *   <li>{@code percent} — max(run.progress, 最新日志.progress); run 表在运行中不更新进度,
     *       必须用日志进度兜底</li>
     *   <li>{@code stage / roundNo / algo} — 取<b>最后一条日志</b> (ORDER BY id DESC LIMIT 1)</li>
     *   <li>{@code currentRoundNo} — 优先取 status='RUNNING' 的轮次, 否则取最后一轮,
     *       都没有则回退到日志的 round_no</li>
     *   <li>{@code totalRounds} — 轮次表 total_rounds → 轮次表 max → 引擎常量兜底</li>
     *   <li>{@code roundsCompleted} — 轮次表中 status='SUCCESS' 的计数</li>
     *   <li>{@code elapsedSec} — 运行中 = now − start_at; 已结束用 duration_sec</li>
     *   <li>{@code etaTotalSec} — 优先取最后一轮的 eta_total_sec; 否则按 elapsed/(percent/100) 线性外推</li>
     * </ul>
     *
     * @param rid Run ID (必填)
     * @return R.ok(进度契约 Map)
     */
    public R<Map<String, Object>> runProgress(Long rid) {
        if (rid == null) throw BizException.badRequest("id 必填");
        List<Map<String, Object>> runRows = jdbc.queryForList(
                "SELECT r.id, r.run_code AS runCode, r.status, r.progress, r.start_at AS startAt,"
              + " r.end_at AS endAt, r.duration_sec AS durationSec, r.optimal_value AS optimalValue,"
              + " r.metrics, r.error_message AS errorMessage,"
              + " s.scheme_code AS schemeCode, s.scheme_name AS schemeName, s.algorithm"
              + " FROM prcp_reverse_run r JOIN prcp_reverse_scheme s ON s.id = r.scheme_id"
              + " WHERE r.id = ? AND r.is_deleted = 0", rid);
        if (runRows.isEmpty()) throw BizException.notFound("反算不存在");
        Map<String, Object> run = runRows.get(0);

        String status = String.valueOf(run.getOrDefault("status", "PENDING")).toUpperCase();
        boolean done = "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);

        // 最后一条日志 → 当前阶段 / 轮次 / 算法 / 日志进度
        List<Map<String, Object>> curRows = jdbc.queryForList(
                "SELECT stage, round_no AS roundNo, algo, progress FROM prcp_reverse_run_log"
              + " WHERE run_id=? ORDER BY id DESC LIMIT 1", rid);
        Map<String, Object> cur = curRows.isEmpty() ? null : curRows.get(0);

        // 轮次清单
        List<Map<String, Object>> roundItems = new ArrayList<>();
        try {
            roundItems = jdbc.queryForList(
                    "SELECT round_no AS roundNo, algo, stage, status, progress, score,"
                  + " optimal_value AS optimalValue, target_hit AS targetHit, message,"
                  + " duration_sec AS durationSec, total_rounds AS totalRounds,"
                  + " eta_total_sec AS etaTotalSec, score_details AS scoreDetails,"
                  + " started_at AS startedAt, ended_at AS endedAt"
                  + " FROM prcp_reverse_run_round WHERE run_id=? AND is_deleted=0"
                  + " ORDER BY round_no ASC, id ASC", rid);
        } catch (Exception e) {
            // 轮次表未迁移时降级: 不阻塞进度接口
            log.warn("[ReverseService] 轮次表不可用 runId={}, 降级为空清单: {}", rid, e.getMessage());
            roundItems = new ArrayList<>();
        }

        // 当前轮次: 优先 RUNNING, 否则最后一轮
        Map<String, Object> curRound = null;
        for (Map<String, Object> r : roundItems) {
            if ("RUNNING".equals(String.valueOf(r.get("status")))) { curRound = r; break; }
        }
        if (curRound == null && !roundItems.isEmpty()) curRound = roundItems.get(roundItems.size() - 1);
        Integer curRoundNo = curRound == null ? null : toInt(curRound.get("roundNo"));
        if (curRoundNo == null && cur != null) curRoundNo = toInt(cur.get("roundNo"));

        // 总轮次: 轮次表 → 引擎常量兜底
        Integer totalRounds = null;
        if (curRound != null && curRound.get("totalRounds") != null) {
            totalRounds = toInt(curRound.get("totalRounds"));
        } else if (!roundItems.isEmpty()) {
            int mx = 0;
            for (Map<String, Object> r : roundItems) {
                int v = toInt(r.get("totalRounds"));
                if (v > mx) mx = v;
            }
            totalRounds = mx > 0 ? mx : null;
        }
        if (totalRounds == null || totalRounds == 0) {
            String algo = cur != null && cur.get("algo") != null
                    ? String.valueOf(cur.get("algo"))
                    : String.valueOf(run.getOrDefault("algorithm", ""));
            totalRounds = ALGO_ROUNDS.getOrDefault(algo, 1);
        }
        int roundsRemaining = Math.max(0, totalRounds - (curRoundNo == null ? 0 : curRoundNo));
        int roundsCompleted = 0;
        for (Map<String, Object> r : roundItems) {
            if ("SUCCESS".equals(String.valueOf(r.get("status")))) roundsCompleted++;
        }

        // 进度: run 表进度与最新日志进度取大 (run 表运行中不更新)
        double percent = toDouble(run.get("progress"));
        if (cur != null && cur.get("progress") != null) {
            percent = Math.max(percent, toDouble(cur.get("progress")));
        }

        // 耗时
        int elapsedSec = 0;
        Object startAt = run.get("startAt");
        if (startAt instanceof java.sql.Timestamp) {
            long diff = System.currentTimeMillis() - ((java.sql.Timestamp) startAt).getTime();
            elapsedSec = (int) Math.max(0, diff / 1000);
        }
        if (done) {
            Integer dur = toInt(run.get("durationSec"));
            elapsedSec = dur == null ? elapsedSec : dur;
        }

        // 预计总耗时: 优先最后一轮的 eta_total_sec, 否则按进度线性外推
        Integer etaTotalSec = null;
        for (int i = roundItems.size() - 1; i >= 0; i--) {
            Integer v = toInt(roundItems.get(i).get("etaTotalSec"));
            if (v != null && v > 0) { etaTotalSec = v; break; }
        }
        if (etaTotalSec == null && percent > 0 && elapsedSec > 0) {
            etaTotalSec = (int) (elapsedSec / (percent / 100.0));
        }
        Integer etaRemainingSec = etaTotalSec == null ? null : Math.max(0, etaTotalSec - elapsedSec);

        // 日志条数
        Integer histCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM prcp_reverse_run_log WHERE run_id=?", Integer.class, rid);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runId", run.get("id"));
        resp.put("runCode", run.get("runCode"));
        resp.put("status", status);
        resp.put("done", done);
        resp.put("success", "SUCCESS".equals(status));
        resp.put("cancelled", "CANCELLED".equals(status));
        resp.put("failed", "FAILED".equals(status));
        resp.put("percent", percent);
        resp.put("stage", cur == null ? null : cur.get("stage"));
        resp.put("roundNo", curRoundNo);
        resp.put("totalRounds", totalRounds);
        resp.put("roundsRemaining", roundsRemaining);
        resp.put("roundsCompleted", roundsCompleted);
        resp.put("elapsedSec", elapsedSec);
        resp.put("etaTotalSec", etaTotalSec);
        resp.put("etaRemainingSec", etaRemainingSec);
        resp.put("algo", cur != null && cur.get("algo") != null ? cur.get("algo") : run.get("algorithm"));
        resp.put("pollIntervalMs", 2000);
        resp.put("historyCount", histCount == null ? 0 : histCount);
        resp.put("rounds", roundItems);
        resp.put("startAt", run.get("startAt"));
        resp.put("endAt", run.get("endAt"));
        resp.put("durationSec", run.get("durationSec"));
        resp.put("optimalValue", run.get("optimalValue"));
        resp.put("metrics", run.get("metrics"));
        resp.put("errorMessage", run.get("errorMessage"));
        resp.put("schemeCode", run.get("schemeCode"));
        resp.put("schemeName", run.get("schemeName"));
        return R.ok(resp);
    }

    /**
     * <p>整数转换 (null / 非数字 → null)</p>
     *
     * @param v 原始值
     * @return 整数值或 null
     */
    private Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * <p>浮点转换 (null / 非数字 → 0)</p>
     *
     * @param v 原始值
     * @return 浮点值
     */
    private double toDouble(Object v) {
        if (v == null) return 0d;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
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
