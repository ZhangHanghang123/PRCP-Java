package com.prcp.business.engines.new_business;

import com.prcp.business.data.BasicDataBuckets;
import com.prcp.business.engines.EngineBase;
import com.prcp.business.engines.EngineContext;
import com.prcp.business.sim.mapper.SimRunMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

/**
 * <p>新业务模拟引擎 — 5 步按月滚动算法 (对位 Python app/services/calculate_engine/new_business/engine.py <b>v2.4</b>)</p>
 *
 * <p><b>版本对齐说明</b>: 已从 v1 口径升级到 Python v2.4 (2026-10-23, commit fe514d3),
 * 核心变更 3 处 (都会导致全部数值结果错误):</p>
 * <ol>
 *   <li><b>rem_* 桶 = 绝对到期月</b>, 不再每月前移 (见 {@link BucketMath#applyTermSplit})</li>
 *   <li><b>orig_* 桶 = 只进不出</b> (记录投放时的原始期限累计额)</li>
 *   <li><b>投放量 = 固定年增量 × 节奏权重</b> (不再是「余额 × 增长率」)</li>
 * </ol>
 *
 * <p>执行流程:
 * <ol>
 *   <li>校验方案 + 插入 RUNNING 记录 (prcp_sim_run)</li>
 *   <li>取账户册叶子节点 (NOT EXISTS(child) 判定) + LEFT JOIN sim_node_config</li>
 *   <li>逐节点取初始状态 (从 prcp_data_basic) + 批量加载 3 类配置
 *       (term_ratios / growth_config / rhythm)</li>
 *   <li>按月滚动 1..monthCount (v2.4 12 步), 延后 INSERT prcp_sim_result (含 128 桶 + 7 追踪列)</li>
 *   <li>回填「完整 60 月到期排期」到每一行 (v2.4 full_plan)</li>
 *   <li>统一批量 INSERT (500 行/批)</li>
 *   <li>聚合 SUMMARY 节点 (递归 CTE 取后代叶子, 按 date_offset SUM 桶)</li>
 *   <li>更新 run 状态 SUCCESS + duration_ms</li>
 * </ol>
 *
 * <p>算法细节 (桶推算 + 月份推算 + 主指标重算) 见 {@link BucketMath}。</p>
 *
 * <p>对齐 Python: app/services/calculate_engine/new_business/engine.py (v2.4)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Slf4j
@Component
public class NewBusinessEngine extends EngineBase {

    /** 默认模拟月数 */
    public static final int DEFAULT_MONTH_COUNT = 60;
    /** 最小模拟月数 */
    public static final int MIN_MONTH_COUNT = 1;
    /** 最大模拟月数 */
    public static final int MAX_MONTH_COUNT = 60;

    @Autowired
    private SimRunMapper runMapper;

    public NewBusinessEngine() {
        this.engineType = "new_business";
        this.engineName = "新业务模拟引擎";
    }

    // ========================================================================
    // 1. run — 5 步算法主流程
    // ========================================================================

    /**
     * <p>执行引擎主流程 (5 步算法), 返回运行 ID</p>
     * <p>事务: 整个流程包在 @Transactional 里, 失败回滚</p>
     *
     * @param ctx      执行上下文
     * @param schemeId 模拟方案 ID (prcp_sim_scheme.id)
     * @param userId   当前用户 ID
     * @param params   引擎参数, 必含 monthCount (1..60)
     * @return run_id
     * @throws IllegalArgumentException monthCount 越界 / 方案不存在 / 节点无基础数据
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long run(EngineContext ctx, Long schemeId, Long userId, Map<String, Object> params) {
        int monthCount = params.containsKey("monthCount") && params.get("monthCount") != null
                ? ((Number) params.get("monthCount")).intValue()
                : DEFAULT_MONTH_COUNT;
        if (monthCount < MIN_MONTH_COUNT || monthCount > MAX_MONTH_COUNT) {
            throw new IllegalArgumentException("monthCount 必须 1.." + MAX_MONTH_COUNT);
        }

        NamedParameterJdbcTemplate jdbc = ctx.getNamedJdbcTemplate();

        // 步骤 1: 校验方案 + 创建 RUNNING 记录
        Map<String, Object> scheme = loadScheme(jdbc, schemeId);
        if (scheme == null) throw new IllegalArgumentException("方案不存在 id=" + schemeId);

        Long coaSchemeId = ((Number) scheme.get("coa_scheme_id")).longValue();
        LocalDate dataDate = LocalDate.parse(scheme.get("data_date").toString(), BucketMath.DF);
        String schemeCode = (String) scheme.get("scheme_code");
        if (coaSchemeId == null || dataDate == null) {
            throw new IllegalArgumentException("方案缺少 coa_scheme_id 或 data_date");
        }

        LocalDate targetDate = BucketMath.addMonths(dataDate, monthCount);

        MapSqlParameterSource runParams = new MapSqlParameterSource()
                .addValue("sid", schemeId)
                .addValue("sc", schemeCode)
                .addValue("bd", dataDate.format(BucketMath.DF))
                .addValue("mc", monthCount)
                .addValue("td", targetDate.format(BucketMath.DF))
                .addValue("u", userId);

        Long runId = insertRun(jdbc, runParams);
        Timestamp startTs = ctx.now();
        log.info("NewBusinessEngine.run start: runId={}, schemeId={}, monthCount={}", runId, schemeId, monthCount);

        try {
            // 步骤 2: 取叶子节点 + 关联配置 + term_ratios
            List<Map<String, Object>> leafNodes = loadLeafNodes(jdbc, schemeId, coaSchemeId, dataDate);
            if (leafNodes.isEmpty()) {
                throw new IllegalArgumentException("账户册 id=" + coaSchemeId + " 下没有任何 BUSINESS 节点在 "
                        + dataDate + " 月有基础数据，请先维护 prcp_data_basic");
            }

            // 步骤 3: 取每个叶子节点的初始状态 + 加载 3 类配置
            List<NodeData> nodesData = initNodesData(jdbc, leafNodes, dataDate, monthCount);
            int configuredCount = (int) nodesData.stream().filter(n -> n.isConfigured).count();
            int rolledCount = nodesData.size() - configuredCount;
            log.info("叶子节点数: total={}, configured={}, rolled-only={}", nodesData.size(), configuredCount, rolledCount);

            // 步骤 4: 按月滚动（叶子节点）
            int totalInserted = rollAndInsert(jdbc, runId, schemeId, schemeCode, coaSchemeId, dataDate, monthCount, nodesData);

            // 步骤 5: 聚合 SUMMARY 节点
            int aggregatedCount = aggregateSummaryNodes(jdbc, runId, schemeId, schemeCode, coaSchemeId, dataDate, monthCount);

            // 更新 run 汇总
            updateRunSummary(jdbc, runId, nodesData.size() + aggregatedCount, configuredCount, rolledCount, aggregatedCount);

            // 标记 SUCCESS
            markSuccess(jdbc, runId, startTs);

            log.info("NewBusinessEngine.run done: runId={}, totalInserted={}, aggregated={}", runId, totalInserted, aggregatedCount);
            return runId;
        } catch (Exception e) {
            markFailed(jdbc, runId, e.getMessage());
            log.error("NewBusinessEngine.run failed: runId={}", runId, e);
            throw e;
        }
    }

    /** 单个节点的运行时数据 (v2.4) */
    static class NodeData {
        Long coaNodeId;
        String nodeCode;
        String nodeName;
        Integer nodeLevel;
        String category;
        /** v2.1: 是否模型测算 (is_model_calc=1) —— 与「有无配置」共同决定分支 */
        boolean isConfigured;
        BigDecimal growthRate;
        List<Map<String, Object>> termRatios;
        Map<String, BigDecimal> state;

        // ===== v2.1 新增: 增长配置 =====
        /** 增长方式 1=目标增长百分比 2=目标增长绝对值 3=增长百分比 4=增长值 */
        int growthType = 1;
        /** 第 1~5 年的增长参数 */
        BigDecimal[] yearValues = new BigDecimal[5];
        /** 金额单位换算系数 (WAN=1e4, YI=1e8) */
        BigDecimal unitScale = new BigDecimal("10000");
        /** 12 个月节奏权重 (和为 100) */
        List<BigDecimal> rhythmWeights = BucketMath.defaultLinearWeights();

        // ===== v2.4 新增: 完整 60 月到期排期 =====
        /** 完整排期容器 (绝对到期月 → 累计投放落点) */
        Map<Integer, BigDecimal> fullPlan = new LinkedHashMap<>();
        /** 累计计划投放 */
        BigDecimal cumPlanned = BigDecimal.ZERO;
        /** v2.1: 年初余额锚定 (growth_type=4 时每年 1 月记录一次) */
        BigDecimal yearBaseBalance;

        static NodeData of() {
            NodeData n = new NodeData();
            n.termRatios = new ArrayList<>();
            for (int i = 0; i < 5; i++) n.yearValues[i] = BigDecimal.ZERO;
            return n;
        }
    }

    // ========================================================================
    // 步骤实现 — SQL 操作
    // ========================================================================

    private Map<String, Object> loadScheme(NamedParameterJdbcTemplate jdbc, Long schemeId) {
        String sql = "SELECT id, scheme_code, scheme_name, coa_scheme_id, data_date"
                + " FROM prcp_sim_scheme WHERE id=:id AND is_deleted=0";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, new MapSqlParameterSource("id", schemeId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long insertRun(NamedParameterJdbcTemplate jdbc, MapSqlParameterSource params) {
        String sql = "INSERT INTO prcp_sim_run"
                + " (sim_scheme_id, sim_scheme_code, base_data_date, month_count, target_data_date,"
                + "  status, progress, total_nodes, processed_nodes, configured_node_count,"
                + "  rolled_node_count, aggregated_node_count, started_at, created_by)"
                + " VALUES (:sid, :sc, :bd, :mc, :td, 'RUNNING', 0, 0, 0, 0, 0, 0, NOW(), :u)";
        jdbc.update(sql, params);
        // 获取 last_insert_id
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", new MapSqlParameterSource(), Long.class);
    }

    /**
     * <p>步骤 2: 取账户册叶子节点 + 关联配置</p>
     *
     * <p><b>v2.4 修正</b>:
     * <ul>
     *   <li>叶子判定从硬筛 {@code node_type='BUSINESS'} 改为 {@code NOT EXISTS(child)}
     *       (与 Python 一致) —— 硬筛会因账户册 node_type 取值不统一而取不到任何节点</li>
     *   <li>补 {@code n.is_deleted = 0}</li>
     *   <li>补 {@code is_model_calc} / {@code rhythm_type} 字段 (v2.1 分支判定需要)</li>
     * </ul>
     * </p>
     */
    private List<Map<String, Object>> loadLeafNodes(NamedParameterJdbcTemplate jdbc, Long schemeId, Long coaSchemeId, LocalDate baseDate) {
        String sql = "SELECT n.id AS coa_node_id, n.node_code, n.node_name, n.node_level, n.parent_id, n.path,"
                + "       c.id AS cfg_id, c.annual_growth_rate, c.is_model_calc, c.rhythm_type"
                + "  FROM prcp_coa_node n"
                + "  JOIN prcp_data_basic b ON b.coa_node_id = n.id AND b.data_date = :bd AND b.is_deleted = 0"
                + "  LEFT JOIN prcp_sim_node_config c ON c.coa_node_id = n.id AND c.scheme_id = :sid AND c.is_deleted = 0"
                + " WHERE n.scheme_id = :coa_sid AND n.is_deleted = 0"
                + "   AND NOT EXISTS (SELECT 1 FROM prcp_coa_node ch WHERE ch.parent_id = n.id AND ch.is_deleted = 0)"
                + " ORDER BY n.path, n.id";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("sid", schemeId)
                .addValue("bd", baseDate.format(BucketMath.DF))
                .addValue("coa_sid", coaSchemeId);
        return jdbc.queryForList(sql, p);
    }

    /**
     * <p>步骤 3: 取每个叶子节点的初始状态 + 加载 3 类配置</p>
     *
     * <p><b>v2.4 修正</b>: 3 类配置改为<b>批量查询</b> (Python 用 3 次批量, 原 Java 用 3N 次逐节点查询):
     * <ol>
     *   <li>{@code prcp_sim_term_ratio} — 期限占比</li>
     *   <li>{@code prcp_sim_growth_config} — 增长方式 + 5 年参数</li>
     *   <li>{@code prcp_sim_rhythm} — 12 个月节奏权重</li>
     * </ol>
     *
     * <p><b>v2.1 分支判定</b>: {@code is_configured = (cfg_id != null) && (is_model_calc == 1)}
     * —— 原 Java 只看 cfg_id != null, 会把「存量保持」节点误判为「模型测算」节点。</p>
     */
    private List<NodeData> initNodesData(NamedParameterJdbcTemplate jdbc, List<Map<String, Object>> leafNodes,
                                          LocalDate baseDate, int monthCount) {
        // ---- 批量加载 3 类配置 ----
        List<Long> cfgIds = new ArrayList<>();
        for (Map<String, Object> row : leafNodes) {
            Number cfgId = (Number) row.get("cfg_id");
            if (cfgId != null) cfgIds.add(cfgId.longValue());
        }
        Map<Long, List<Map<String, Object>>> ratiosByCfg = loadTermRatiosBatch(jdbc, cfgIds);
        Map<Long, Map<String, Object>> growthByCfg = loadGrowthConfigBatch(jdbc, cfgIds);
        Map<Long, List<Map<String, Object>>> rhythmByCfg = loadRhythmBatch(jdbc, cfgIds);

        List<NodeData> result = new ArrayList<>();
        for (Map<String, Object> row : leafNodes) {
            Long coaNodeId = ((Number) row.get("coa_node_id")).longValue();
            Number cfgIdNum = (Number) row.get("cfg_id");
            Long cfgId = cfgIdNum == null ? null : cfgIdNum.longValue();

            // v2.1: 分支判定 = 有配置 且 is_model_calc == 1
            // ⚠️ is_model_calc / rhythm_type 是 tinyint(1), MySQL 驱动默认映射为 Boolean, 不能直接 (Number) 强转
            int isModelCalc = intOf(row.get("is_model_calc"), 2);
            boolean isConfigured = cfgId != null && isModelCalc == 1;

            Map<String, BigDecimal> state = BucketMath.getInitialState(jdbc, coaNodeId, baseDate);
            if (state == null) continue;

            NodeData nd = NodeData.of();
            nd.coaNodeId = coaNodeId;
            nd.nodeCode = (String) row.get("node_code");
            nd.nodeName = (String) row.get("node_name");
            nd.nodeLevel = row.get("node_level") == null ? 0 : ((Number) row.get("node_level")).intValue();
            nd.category = BucketMath.fetchCategory(jdbc, coaNodeId, baseDate);
            nd.isConfigured = isConfigured;
            nd.growthRate = isConfigured
                    ? BucketMath.toBD(row.get("annual_growth_rate"))
                    : BigDecimal.ZERO;
            nd.state = state;

            if (isConfigured) {
                nd.termRatios = ratiosByCfg.getOrDefault(cfgId, new ArrayList<>());

                // 增长配置 (未配 → 5 年参数全 0, 只滚桶不投放)
                Map<String, Object> gc = growthByCfg.get(cfgId);
                if (gc != null) {
                    // ⚠️ growth_type 也是 tinyint(1) → Boolean, 用 intOf 兼容
                    nd.growthType = intOf(gc.get("growth_type"), 1);
                    for (int i = 0; i < 5; i++) {
                        nd.yearValues[i] = BucketMath.toBD(gc.get("year" + (i + 1) + "_value"));
                    }
                    nd.unitScale = BucketMath.UNIT_SCALE_BY_UNIT.getOrDefault(
                            String.valueOf(gc.getOrDefault("amount_unit", "WAN")), new BigDecimal("10000"));
                }

                // 节奏权重 (三重降级保护见 BucketMath.getRhythmWeights)
                int rhythmType = intOf(row.get("rhythm_type"), 1);
                nd.rhythmWeights = BucketMath.getRhythmWeights(rhythmType, rhythmByCfg.get(cfgId));
            } else {
                nd.rhythmWeights = BucketMath.defaultLinearWeights();
            }

            // v2.4: 完整 60 月到期排期 —— 先装入基准日存量, 后续投放落点都会累加进来
            BucketMath.accumulatePlan(nd.fullPlan, state, monthCount);

            result.add(nd);
        }
        return result;
    }

    /** 批量查 prcp_sim_term_ratio (按 config_id IN) */
    private Map<Long, List<Map<String, Object>>> loadTermRatiosBatch(
            NamedParameterJdbcTemplate jdbc, List<Long> cfgIds) {
        Map<Long, List<Map<String, Object>>> byCfg = new HashMap<>();
        if (cfgIds.isEmpty()) return byCfg;
        String sql = "SELECT config_id, term_value, term_unit, business_ratio, interest_rate, sort_order"
                + "  FROM prcp_sim_term_ratio WHERE config_id IN (:cids) AND is_deleted=0"
                + " ORDER BY config_id, sort_order";
        List<Map<String, Object>> rows = jdbc.queryForList(sql,
                new MapSqlParameterSource("cids", cfgIds));
        for (Map<String, Object> r : rows) {
            Long cid = ((Number) r.get("config_id")).longValue();
            byCfg.computeIfAbsent(cid, k -> new ArrayList<>()).add(r);
        }
        return byCfg;
    }

    /** 批量查 prcp_sim_growth_config (按 config_id IN) */
    private Map<Long, Map<String, Object>> loadGrowthConfigBatch(
            NamedParameterJdbcTemplate jdbc, List<Long> cfgIds) {
        Map<Long, Map<String, Object>> byCfg = new HashMap<>();
        if (cfgIds.isEmpty()) return byCfg;
        String sql = "SELECT config_id, growth_type,"
                + "       year1_value, year2_value, year3_value, year4_value, year5_value,"
                + "       amount_unit"
                + "  FROM prcp_sim_growth_config WHERE config_id IN (:cids) AND is_deleted=0";
        List<Map<String, Object>> rows = jdbc.queryForList(sql,
                new MapSqlParameterSource("cids", cfgIds));
        for (Map<String, Object> r : rows) {
            Long cid = ((Number) r.get("config_id")).longValue();
            byCfg.put(cid, r);
        }
        return byCfg;
    }

    /** 批量查 prcp_sim_rhythm (按 config_id IN) */
    private Map<Long, List<Map<String, Object>>> loadRhythmBatch(
            NamedParameterJdbcTemplate jdbc, List<Long> cfgIds) {
        Map<Long, List<Map<String, Object>>> byCfg = new HashMap<>();
        if (cfgIds.isEmpty()) return byCfg;
        String sql = "SELECT config_id, month_no, weight"
                + "  FROM prcp_sim_rhythm WHERE config_id IN (:cids) AND is_deleted=0"
                + " ORDER BY config_id, month_no";
        List<Map<String, Object>> rows = jdbc.queryForList(sql,
                new MapSqlParameterSource("cids", cfgIds));
        for (Map<String, Object> r : rows) {
            Long cid = ((Number) r.get("config_id")).longValue();
            byCfg.computeIfAbsent(cid, k -> new ArrayList<>()).add(r);
        }
        return byCfg;
    }

    /**
     * <p>步骤 4+5+6: v2.4 主循环 — 按月滚动 + 完整排期回填 + 统一批量 INSERT</p>
     *
     * <p><b>v2.4 相对 v1 的 3 处变更</b>:
     * <ol>
     *   <li>rem_* 改绝对到期月口径 (投放落 {@code rem_m{M+v}}, 到期取走 {@code rem_m{M}})</li>
     *   <li>投放量改「固定年增量 × 节奏权重」, 余额改 {@code base + add}</li>
     *   <li><b>延后 INSERT</b>: 循环内暂存 all_rows, 跑完后回填排期再批量入库 (500 行/批)</li>
     * </ol>
     * </p>
     *
     * @return 插入行数
     */
    private int rollAndInsert(NamedParameterJdbcTemplate jdbc, Long runId, Long simSchemeId, String schemeCode,
                              Long coaSchemeId, LocalDate baseDate, int monthCount, List<NodeData> nodesData) {
        // 构造 INSERT SQL：128 桶 + 18 元数据 + 4 主指标 + 7 追踪列 (v2.1/v2.3/v2.4)
        List<String> allCols = new ArrayList<>();
        allCols.add("sim_scheme_code");
        allCols.add("sim_scheme_id");
        allCols.add("run_id");
        allCols.add("data_date");
        allCols.add("date_offset");
        allCols.add("coa_scheme_id");
        allCols.add("coa_node_id");
        allCols.add("node_code");
        allCols.add("node_name");
        allCols.add("node_level");
        allCols.add("category");
        allCols.add("is_configured");
        allCols.add("is_aggregated");
        // v2.1: 投放分解追溯字段
        allCols.add("growth_type");
        allCols.add("year_target");
        allCols.add("rhythm_weight");
        allCols.add("month_add");
        allCols.add("month_release");
        // v2.3/v2.4: 计划到期 + 累计计划投放
        allCols.add("planned_due");
        allCols.add("cum_planned");
        for (String k : BucketMath.KEYS) {
            allCols.add("orig_" + k);
            allCols.add("rem_" + k);
        }
        allCols.add("current_balance");
        allCols.add("avg_balance");
        allCols.add("weighted_rate");
        allCols.add("interest_amount");
        allCols.add("calc_note");

        String insertSql = buildInsertSql(allCols);

        // v2.4: 延后 INSERT —— 循环内暂存 all_rows, 跑完后回填排期再批量入库
        List<Map<String, Object>> allRows = new ArrayList<>();

        for (int k = 1; k <= monthCount; k++) {
            LocalDate dataDateK = BucketMath.addMonths(baseDate, k);
            String dataDateKStr = dataDateK.format(BucketMath.DF);

            // v2.1: 第 k 月对应的年份索引 (1~5) 与年内月份 (1~12)
            //       month_count 上限 60 → y 恒在 1~5 内, 不存在超出
            int y = (k - 1) / 12 + 1;
            int mInYear = (k - 1) % 12 + 1;

            for (NodeData nd : nodesData) {
                Map<String, BigDecimal> state = nd.state;
                boolean isConfigured = nd.isConfigured;
                BigDecimal baseBalance = state.getOrDefault("current_balance", BigDecimal.ZERO);

                // ===== 步骤 1: 年目标增量 =====
                //  v2.1 修正 (type 4 余额锚定): 目标 = 配置的年末余额 − 年初余额。
                //  年初余额每年锚定一次 (mInYear==1 时记录), 后续月份沿用,
                //  否则余额逐月增长会让目标逐年递减, 导致年末余额偏离配置值。
                BigDecimal yearTarget = BigDecimal.ZERO;
                if (isConfigured) {
                    BigDecimal yearParam = (y - 1 < nd.yearValues.length && nd.yearValues[y - 1] != null)
                            ? nd.yearValues[y - 1] : BigDecimal.ZERO;
                    if (nd.growthType == 4) {
                        if (mInYear == 1) {
                            nd.yearBaseBalance = baseBalance;
                        }
                        BigDecimal yearBase = nd.yearBaseBalance == null ? baseBalance : nd.yearBaseBalance;
                        yearTarget = yearParam.multiply(nd.unitScale).subtract(yearBase);
                    } else {
                        yearTarget = BucketMath.computeYearTarget(
                                nd.growthType, yearParam, baseBalance, nd.unitScale);
                    }
                }

                // ===== 步骤 2: 节奏权重 → 当月投放 =====
                BigDecimal rhythmWeight = (mInYear <= nd.rhythmWeights.size())
                        ? nd.rhythmWeights.get(mInYear - 1) : BigDecimal.ZERO;

                // v2.3: 在 take_due 清零「之前」先快照计划到期额
                BigDecimal plannedDueK = BucketMath.peekDue(state, k);
                BigDecimal release = BucketMath.takeDue(state, k);
                Map<String, BigDecimal> mnMap = BucketMath.computeMonthNewV2(
                        nd.growthType, yearTarget, rhythmWeight, release);
                BigDecimal add = mnMap.get("add");
                BigDecimal monthNew = mnMap.get("month_new");
                nd.cumPlanned = nd.cumPlanned.add(monthNew);

                // ===== 步骤 3: 按期限占比拆分 + 按绝对到期月落桶 =====
                //  第 k 月投放期限 v 的业务 → 直接落到 rem_m{k+v}
                //  v2.4: 同时把落点累加进该节点的「完整 60 月排期」
                List<Map<String, BigDecimal>> additions = new ArrayList<>();
                if (isConfigured && !nd.termRatios.isEmpty()) {
                    additions = BucketMath.applyTermSplit(state, monthNew, nd.termRatios, k, nd.fullPlan);
                }

                // ===== 主表指标重算 =====
                if (isConfigured) {
                    Map<String, BigDecimal> main = BucketMath.computeMainMetrics(
                            state, additions, add, monthNew);
                    state.put("current_balance", main.get("new_current_balance"));
                    state.put("avg_balance",     main.get("new_avg_balance"));
                    state.put("weighted_rate",   main.get("new_weighted_rate"));
                    state.put("interest_amount", main.get("new_interest_amount"));
                } else {
                    // 分支 A (存量保持): 余额与利率都不变
                    BigDecimal avg = state.getOrDefault("avg_balance", BigDecimal.ZERO);
                    BigDecimal wr = state.getOrDefault("weighted_rate", BigDecimal.ZERO);
                    state.put("current_balance", baseBalance);
                    state.put("avg_balance", avg);
                    state.put("weighted_rate", wr);
                    state.put("interest_amount",
                            avg.multiply(wr).divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP));
                }
                nd.state = state;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sim_scheme_code", schemeCode);
                row.put("sim_scheme_id",   simSchemeId);
                row.put("run_id",          runId);
                row.put("data_date",       dataDateKStr);
                row.put("date_offset",     k);
                row.put("coa_scheme_id",   coaSchemeId);
                row.put("coa_node_id",     nd.coaNodeId);
                row.put("node_code",       nd.nodeCode);
                row.put("node_name",       nd.nodeName);
                row.put("node_level",      nd.nodeLevel);
                row.put("category",        nd.category);
                row.put("is_configured",   isConfigured ? 1 : 0);
                row.put("is_aggregated",   0);
                // v2.1: 投放分解追溯字段
                row.put("growth_type",   isConfigured ? nd.growthType : null);
                row.put("year_target",   isConfigured ? yearTarget : null);
                row.put("rhythm_weight", isConfigured ? rhythmWeight : null);
                row.put("month_add",     isConfigured ? add : null);
                row.put("month_release", isConfigured ? release : null);
                // v2.3/v2.4: 计划到期 + 累计计划投放
                row.put("planned_due",  isConfigured ? plannedDueK : BigDecimal.ZERO);
                row.put("cum_planned",  isConfigured ? nd.cumPlanned : BigDecimal.ZERO);
                for (String kk : BucketMath.KEYS) {
                    row.put("orig_" + kk, state.getOrDefault("orig_" + kk, BigDecimal.ZERO));
                    row.put("rem_"  + kk, state.getOrDefault("rem_"  + kk, BigDecimal.ZERO));
                }
                row.put("current_balance", state.get("current_balance"));
                row.put("avg_balance",     state.get("avg_balance"));
                row.put("weighted_rate",   state.get("weighted_rate"));
                row.put("interest_amount", state.get("interest_amount"));
                row.put("calc_note", isConfigured
                        ? "M" + k + "=" + dataDateKStr + "; y" + y + "m" + mInYear
                          + "; gt=" + nd.growthType + "; w=" + rhythmWeight.setScale(4, RoundingMode.HALF_UP) + "%"
                          + "; add=" + add.setScale(2, RoundingMode.HALF_UP)
                          + "; rel=" + release.setScale(2, RoundingMode.HALF_UP)
                        : "M" + k + "=" + dataDateKStr + "; rolled-only (is_model_calc=2)");
                allRows.add(row);
            }

            // 更新进度
            int progress = (int) ((double) k / monthCount * 100);
            jdbc.update("UPDATE prcp_sim_run SET progress=:p, processed_nodes=:pn WHERE id=:id",
                    new MapSqlParameterSource()
                            .addValue("p", progress)
                            .addValue("pn", nodesData.size())
                            .addValue("id", runId));
        }

        // ===== 步骤 5: v2.4 回填「完整 60 月到期排期」到每个节点的每一行 =====
        //   这样任意一行 (含 M1) 都能看到「未来 5 年每个月都有到期」的完整时间轴。
        Map<Long, Map<Integer, BigDecimal>> planByNode = new HashMap<>();
        for (NodeData nd : nodesData) {
            if (nd.isConfigured) planByNode.put(nd.coaNodeId, nd.fullPlan);
        }
        for (Map<String, Object> r : allRows) {
            Object nidObj = r.get("coa_node_id");
            if (nidObj == null) continue;
            Map<Integer, BigDecimal> fp = planByNode.get(((Number) nidObj).longValue());
            if (fp != null && !fp.isEmpty()) {
                BucketMath.applyFinalPlan(r, fp);
                r.put("calc_note", r.get("calc_note") + "; full_plan=" + fp.size() + "M");
            }
        }

        // ===== 步骤 6: 统一批量 INSERT (500 行/批) =====
        int totalInserted = 0;
        final int BATCH = 500;
        for (int i = 0; i < allRows.size(); i += BATCH) {
            List<Map<String, Object>> chunk = allRows.subList(i, Math.min(i + BATCH, allRows.size()));
            MapSqlParameterSource[] batch = chunk.stream()
                    .map(MapSqlParameterSource::new)
                    .toArray(MapSqlParameterSource[]::new);
            int[] counts = jdbc.batchUpdate(insertSql, batch);
            totalInserted += counts.length;
        }
        return totalInserted;
    }

    /** 构造 INSERT INTO prcp_sim_result 语句 */
    private String buildInsertSql(List<String> allCols) {
        StringBuilder sql = new StringBuilder("INSERT INTO prcp_sim_result (");
        sql.append(String.join(", ", allCols));
        sql.append(") VALUES (");
        for (int i = 0; i < allCols.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(":").append(allCols.get(i));
        }
        sql.append(")");
        return sql.toString();
    }

    /**
     * <p>Object → int (兼容 Boolean / Number / String)</p>
     *
     * <p>⚠️ MySQL 的 {@code tinyint(1)} 列会被 Connector/J 默认映射成 {@link Boolean}
     * (见 {@code tinyInt1isBit=true}), 直接 {@code (Number) 强转} 会抛 ClassCastException。
     * 本方法统一处理三种来源。</p>
     *
     * @param o          任意对象
     * @param defaultVal 值为 null 时的默认值
     * @return int 值
     */
    private static int intOf(Object o, int defaultVal) {
        if (o == null) return defaultVal;
        if (o instanceof Boolean) return ((Boolean) o) ? 1 : 0;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return new BigDecimal(o.toString().trim()).intValue();
        } catch (Exception e) {
            return defaultVal;
        }
    }

    /**
     * <p>聚合 SUMMARY 节点 (递归 CTE)</p>
     *
     * <p><b>v2.4 修正</b>:
     * <ul>
     *   <li>SUMMARY 节点筛选补 {@code OR EXISTS(child)} (与 Python 一致) ——
     *       否则「有子节点但 node_type 不是 SUMMARY」的中间层不会被聚合</li>
     *   <li>补 3 个聚合标量: {@code year_target} / {@code month_add} / {@code month_release}</li>
     * </ul>
     * </p>
     */
    private int aggregateSummaryNodes(NamedParameterJdbcTemplate jdbc, Long runId, Long simSchemeId,
                                       String schemeCode, Long coaSchemeId, LocalDate baseDate, int monthCount) {
        String findSummarySql = "SELECT n.id, n.node_code, n.node_name, n.node_level,"
                + "       (SELECT b.category FROM prcp_data_basic b"
                + "         WHERE b.coa_node_id=n.id AND b.data_date=:bd AND b.is_deleted=0 LIMIT 1) AS category"
                + "  FROM prcp_coa_node n"
                + " WHERE n.scheme_id=:sid AND n.is_deleted=0"
                + "   AND (n.node_type='SUMMARY'"
                + "        OR EXISTS (SELECT 1 FROM prcp_coa_node ch"
                + "                   WHERE ch.parent_id = n.id AND ch.is_deleted = 0))"
                + " ORDER BY n.node_level DESC";
        List<Map<String, Object>> summaryNodes = jdbc.queryForList(findSummarySql,
                new MapSqlParameterSource()
                        .addValue("sid", coaSchemeId)
                        .addValue("bd", baseDate.format(BucketMath.DF)));
        if (summaryNodes.isEmpty()) return 0;

        // 简化的聚合：每节点直接聚合所有叶子（一次性 SQL）
        int aggregated = 0;
        for (Map<String, Object> sn : summaryNodes) {
            Long snId = ((Number) sn.get("id")).longValue();
            String snCode = (String) sn.get("node_code");
            String snName = (String) sn.get("node_name");
            Integer snLevel = sn.get("node_level") == null ? 0 : ((Number) sn.get("node_level")).intValue();
            String snCat = (String) sn.get("category");

            // 找所有后代叶子节点（递归 CTE）
            String leafIdsSql = "WITH RECURSIVE descendants AS ("
                    + "  SELECT id FROM prcp_coa_node WHERE parent_id = :pid"
                    + "  UNION ALL"
                    + "  SELECT n.id FROM prcp_coa_node n JOIN descendants d ON n.parent_id = d.id"
                    + ")"
                    + " SELECT d.id FROM descendants d"
                    + "  WHERE NOT EXISTS (SELECT 1 FROM prcp_coa_node c WHERE c.parent_id = d.id)"
                    + "    AND d.id IN (SELECT id FROM prcp_coa_node WHERE scheme_id = :sid)";
            List<Map<String, Object>> leafIdRows = jdbc.queryForList(leafIdsSql,
                    new MapSqlParameterSource().addValue("pid", snId).addValue("sid", coaSchemeId));
            if (leafIdRows.isEmpty()) continue;
            List<Long> leafIds = new ArrayList<>();
            for (Map<String, Object> r : leafIdRows) leafIds.add(((Number) r.get("id")).longValue());

            // 桶聚合（生成 :p0, :p1, ... 占位符）
            StringBuilder bucketSum = new StringBuilder();
            for (int i = 0; i < BucketMath.KEYS.size(); i++) {
                String k = BucketMath.KEYS.get(i);
                if (i > 0) bucketSum.append(", ");
                bucketSum.append("SUM(r.orig_").append(k).append(") AS orig_").append(k);
                bucketSum.append(", SUM(r.rem_").append(k).append(") AS rem_").append(k);
            }
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < leafIds.size(); i++) {
                if (i > 0) placeholders.append(",");
                placeholders.append(":p").append(i);
            }
            String aggSql = "SELECT r.date_offset, SUM(r.current_balance) AS agg_current,"
                    + "       SUM(r.avg_balance) AS agg_avg, SUM(r.interest_amount) AS agg_interest,"
                    + "       SUM(r.current_balance * r.weighted_rate) AS sum_weighted,"
                    + "       SUM(r.year_target) AS agg_year_target,"
                    + "       SUM(r.month_add) AS agg_month_add,"
                    + "       SUM(r.month_release) AS agg_month_release,"
                    + "       " + bucketSum
                    + "  FROM prcp_sim_result r"
                    + " WHERE r.run_id=:rid AND r.coa_node_id IN (" + placeholders + ")"
                    + " GROUP BY r.date_offset ORDER BY r.date_offset";

            MapSqlParameterSource aggParams = new MapSqlParameterSource().addValue("rid", runId);
            for (int i = 0; i < leafIds.size(); i++) aggParams.addValue("p" + i, leafIds.get(i));

            List<Map<String, Object>> aggRows = jdbc.queryForList(aggSql, aggParams);
            if (aggRows.isEmpty()) continue;

            // 构造 INSERT 数据
            List<Map<String, Object>> rowsToInsert = new ArrayList<>();
            for (Map<String, Object> r : aggRows) {
                Integer offset = ((Number) r.get("date_offset")).intValue();
                BigDecimal aggCur = BucketMath.toBD(r.get("agg_current"));
                BigDecimal aggAvg = BucketMath.toBD(r.get("agg_avg"));
                BigDecimal aggInt = BucketMath.toBD(r.get("agg_interest"));
                BigDecimal sumW   = BucketMath.toBD(r.get("sum_weighted"));
                BigDecimal aggWr  = aggCur.compareTo(BigDecimal.ZERO) > 0
                        ? sumW.divide(aggCur, 6, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                LocalDate dk = BucketMath.addMonths(baseDate, offset);
                String dkStr = dk.format(BucketMath.DF);

                Map<String, Object> row = new HashMap<>();
                row.put("sim_scheme_code", schemeCode);
                row.put("sim_scheme_id",   simSchemeId);
                row.put("run_id",          runId);
                row.put("data_date",       dkStr);
                row.put("date_offset",     offset);
                row.put("coa_scheme_id",   coaSchemeId);
                row.put("coa_node_id",     snId);
                row.put("node_code",       snCode);
                row.put("node_name",       snName);
                row.put("node_level",      snLevel);
                row.put("category",        snCat);
                row.put("is_configured",   0);
                row.put("is_aggregated",   1);
                // v2.1: 汇总节点也带 3 个投放追溯标量
                row.put("growth_type",   null);
                row.put("year_target",   BucketMath.toBD(r.get("agg_year_target")));
                row.put("rhythm_weight", null);
                row.put("month_add",     BucketMath.toBD(r.get("agg_month_add")));
                row.put("month_release", BucketMath.toBD(r.get("agg_month_release")));
                // v2.3/v2.4: 汇总节点不参与排期, 置 0
                row.put("planned_due",   BigDecimal.ZERO);
                row.put("cum_planned",   BigDecimal.ZERO);
                for (String k : BucketMath.KEYS) {
                    row.put("orig_" + k, BucketMath.toBD(r.get("orig_" + k)));
                    row.put("rem_"  + k, BucketMath.toBD(r.get("rem_"  + k)));
                }
                row.put("current_balance", aggCur);
                row.put("avg_balance",     aggAvg);
                row.put("weighted_rate",   aggWr);
                row.put("interest_amount", aggInt);
                row.put("calc_note", "Aggregated from " + leafIds.size() + " leaves; M" + offset + "=" + dkStr);
                rowsToInsert.add(row);
            }

            if (!rowsToInsert.isEmpty()) {
                // 构造 INSERT SQL（结构与 rollAndInsert 一致, 含 7 追踪列）
                List<String> cols = new ArrayList<>(Arrays.asList(
                    "sim_scheme_code","sim_scheme_id","run_id","data_date","date_offset",
                    "coa_scheme_id","coa_node_id","node_code","node_name","node_level","category",
                    "is_configured","is_aggregated",
                    "growth_type","year_target","rhythm_weight","month_add","month_release",
                    "planned_due","cum_planned"));
                for (String k : BucketMath.KEYS) {
                    cols.add("orig_" + k);
                    cols.add("rem_" + k);
                }
                cols.addAll(Arrays.asList("current_balance","avg_balance","weighted_rate","interest_amount","calc_note"));
                StringBuilder insertSql = new StringBuilder("INSERT INTO prcp_sim_result (");
                insertSql.append(String.join(", ", cols));
                insertSql.append(") VALUES (");
                for (int i = 0; i < cols.size(); i++) {
                    if (i > 0) insertSql.append(", ");
                    insertSql.append(":").append(cols.get(i));
                }
                insertSql.append(")");
                MapSqlParameterSource[] batch = rowsToInsert.stream()
                        .map(MapSqlParameterSource::new)
                        .toArray(MapSqlParameterSource[]::new);
                jdbc.batchUpdate(insertSql.toString(), batch);
                aggregated++;
            }
        }
        return aggregated;
    }

    private void updateRunSummary(NamedParameterJdbcTemplate jdbc, Long runId, int total, int configured, int rolled, int aggregated) {
        jdbc.update("UPDATE prcp_sim_run SET total_nodes=:tn, processed_nodes=:pn,"
                        + "configured_node_count=:cn, rolled_node_count=:rn, aggregated_node_count=:an"
                        + " WHERE id=:id",
                new MapSqlParameterSource()
                        .addValue("tn", total).addValue("pn", total)
                        .addValue("cn", configured).addValue("rn", rolled).addValue("an", aggregated)
                        .addValue("id", runId));
    }

    private void markSuccess(NamedParameterJdbcTemplate jdbc, Long runId, Timestamp startTs) {
        // 直接用 elapsed 毫秒，避免 MySQL TIMESTAMP 类型转换
        long elapsed = System.currentTimeMillis() - startTs.getTime();
        jdbc.update("UPDATE prcp_sim_run SET status='SUCCESS', progress=100, finished_at=NOW(),"
                        + "duration_ms=:dur WHERE id=:id",
                new MapSqlParameterSource().addValue("dur", elapsed).addValue("id", runId));
    }

    private void markFailed(NamedParameterJdbcTemplate jdbc, Long runId, String error) {
        jdbc.update("UPDATE prcp_sim_run SET status='FAILED', finished_at=NOW(), error_message=:err WHERE id=:id",
                new MapSqlParameterSource().addValue("err", error == null ? null : error.substring(0, Math.min(error.length(), 1000))).addValue("id", runId));
    }

    // ========================================================================
    // 2. getRun — 查询单次执行
    // ========================================================================

    /**
     * <p>查询单次执行状态 (调 runMapper.selectRunById)</p>
     *
     * @param ctx   执行上下文 (未使用, 保留签名)
     * @param runId 运行 ID
     * @return 单行 Map (status/progress/duration_ms/各计数), 不存在返回 null
     */
    @Override
    public Map<String, Object> getRun(EngineContext ctx, Long runId) {
        return runMapper.selectRunById(runId);
    }

    // ========================================================================
    // 3. listRuns — 列出执行历史
    // ========================================================================

    /**
     * <p>列出执行历史 (编程式 SQL, 支持 simSchemeId/simSchemeCode/status/limit 过滤)</p>
     *
     * @param ctx     执行上下文
     * @param filters {simSchemeId, simSchemeCode, status, limit} (后三项可空)
     * @return 运行记录 Map 列表, 按 ID DESC, limit 上限 100
     */
    @Override
    public List<Map<String, Object>> listRuns(EngineContext ctx, Map<String, Object> filters) {
        Long simSchemeId = filters.get("simSchemeId") == null ? null : ((Number) filters.get("simSchemeId")).longValue();
        String simSchemeCode = (String) filters.get("simSchemeCode");
        String status = (String) filters.get("status");
        Integer limit = filters.get("limit") == null ? 20 : ((Number) filters.get("limit")).intValue();
        if (limit > 100) limit = 100;

        NamedParameterJdbcTemplate jdbc = ctx.getNamedJdbcTemplate();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("simSchemeId", simSchemeId)
                .addValue("simSchemeCode", simSchemeCode)
                .addValue("status", status)
                .addValue("limit", limit);
        String sql =
            "SELECT id, sim_scheme_id AS simSchemeId, sim_scheme_code AS simSchemeCode," +
            "       base_data_date AS baseDataDate, month_count AS monthCount," +
            "       target_data_date AS targetDataDate, status, progress," +
            "       total_nodes AS totalNodes, processed_nodes AS processedNodes," +
            "       configured_node_count AS configuredNodeCount," +
            "       rolled_node_count AS rolledNodeCount," +
            "       aggregated_node_count AS aggregatedNodeCount," +
            "       duration_ms AS durationMs," +
            "       started_at AS startedAt, finished_at AS finishedAt," +
            "       created_at AS createdAt" +
            "  FROM prcp_sim_run" +
            " WHERE (:simSchemeId IS NULL OR sim_scheme_id = :simSchemeId)" +
            "   AND (:simSchemeCode IS NULL OR sim_scheme_code = :simSchemeCode)" +
            "   AND (:status IS NULL OR status = :status)" +
            " ORDER BY id DESC LIMIT :limit";
        return jdbc.queryForList(sql, p);
    }

    // ========================================================================
    // 4. listResults — 结果快照（128 桶可选）
    // ========================================================================

    /**
     * <p>查询结果快照 (动态 SQL, 支持 runId/simSchemeCode/dateOffset/coaNodeId/category/withBuckets 过滤)</p>
     * <p>无 runId 但有 simSchemeCode 时, 自动取该方案最近一次 SUCCESS run</p>
     * <p>withBuckets=true 时附加 128 桶字段 (orig_m1..rem_y30), 否则仅 4 主指标</p>
     *
     * @param ctx     执行上下文
     * @param filters 过滤条件 + withBuckets 标志
     * @return 结果行 Map 列表 (LIMIT 20000, v2.4 从 2000 上调)
     */
    @Override
    public List<Map<String, Object>> listResults(EngineContext ctx, Map<String, Object> filters) {
        NamedParameterJdbcTemplate jdbc = ctx.getNamedJdbcTemplate();

        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE r.is_deleted=0");

        String simSchemeCode = (String) filters.get("simSchemeCode");
        if (simSchemeCode != null && !simSchemeCode.isEmpty()) {
            where.append(" AND r.sim_scheme_code=:sc");
            p.addValue("sc", simSchemeCode);
        }

        if (filters.get("runId") != null) {
            where.append(" AND r.run_id=:rid");
            p.addValue("rid", ((Number) filters.get("runId")).longValue());
        } else if (simSchemeCode != null && !simSchemeCode.isEmpty()) {
            where.append(" AND r.run_id=(SELECT MAX(id) FROM prcp_sim_run WHERE sim_scheme_code=:sc AND status='SUCCESS')");
        }

        if (filters.get("dateOffset") != null) {
            where.append(" AND r.date_offset=:do");
            p.addValue("do", ((Number) filters.get("dateOffset")).intValue());
        }
        if (filters.get("coaNodeId") != null) {
            where.append(" AND r.coa_node_id=:nid");
            p.addValue("nid", ((Number) filters.get("coaNodeId")).longValue());
        }
        if (filters.get("category") != null) {
            where.append(" AND r.category=:cat");
            p.addValue("cat", filters.get("category").toString());
        }

        boolean withBuckets = Boolean.TRUE.equals(filters.get("withBuckets"));

        StringBuilder sql = new StringBuilder("SELECT r.id, r.sim_scheme_code AS simSchemeCode,"
                + " r.run_id AS runId, r.data_date AS dataDate, r.date_offset AS dateOffset,"
                + " r.coa_scheme_id AS coaSchemeId, r.coa_node_id AS coaNodeId,"
                + " r.node_code AS nodeCode, r.node_name AS nodeName,"
                + " r.node_level AS nodeLevel, r.category,"
                + " r.is_configured AS isConfigured, r.is_aggregated AS isAggregated,"
                + " r.current_balance AS currentBalance, r.avg_balance AS avgBalance,"
                + " r.weighted_rate AS weightedRate, r.interest_amount AS interestAmount,"
                + " r.calc_note AS calcNote");

        if (withBuckets) {
            for (String k : BucketMath.KEYS) {
                sql.append(", r.orig_").append(k).append(" AS orig").append(camel(k));
                sql.append(", r.rem_").append(k).append(" AS rem").append(camel(k));
            }
        }
        sql.append(" FROM prcp_sim_result r");
        sql.append(where);
        // v2.4: LIMIT 2000 → 20000 (57 节点 × 60 月 = 3420 行, 原值会静默截断 40% 数据)
        sql.append(" ORDER BY r.date_offset, r.coa_node_id LIMIT 20000");

        return jdbc.queryForList(sql.toString(), p);
    }

    private String camel(String k) {
        return k.substring(0, 1).toUpperCase() + k.substring(1);
    }
}
