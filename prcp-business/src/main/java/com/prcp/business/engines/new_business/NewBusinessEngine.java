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
 * <p>新业务模拟引擎 — 5 步按月滚动算法 (对位 Python app/services/calculate_engine/new_business/engine.py)</p>
 *
 * <p>执行流程:
 * <ol>
 *   <li>校验方案 + 插入 RUNNING 记录 (prcp_sim_run)</li>
 *   <li>取账户册下 BUSINESS 叶子节点 + LEFT JOIN sim_node_config + 加载 term_ratios</li>
 *   <li>逐节点取初始状态 (从 prcp_data_basic, T 月)</li>
 *   <li>按月滚动 1..monthCount, 批量 INSERT prcp_sim_result (含 128 桶)</li>
 *   <li>聚合 SUMMARY 节点 (递归 CTE 取后代叶子, 按 date_offset SUM 桶)</li>
 *   <li>更新 run 状态 SUCCESS + duration_ms</li>
 * </ol>
 *
 * <p>算法细节 (桶推算 + 月份推算 + 主指标重算) 见 {@link BucketMath}。</p>
 *
 * <p>对齐 Python: app/services/calculate_engine/new_business/engine.py</p>
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

            // 步骤 3: 取每个叶子节点的初始状态 + 加载 term_ratios
            List<NodeData> nodesData = initNodesData(jdbc, leafNodes, dataDate);
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

    /** 单个节点的运行时数据 */
    static class NodeData {
        Long coaNodeId;
        String nodeCode;
        String nodeName;
        Integer nodeLevel;
        String category;
        boolean isConfigured;
        BigDecimal growthRate;
        List<Map<String, Object>> termRatios;
        Map<String, BigDecimal> state;

        static NodeData of() {
            NodeData n = new NodeData();
            n.termRatios = new ArrayList<>();
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

    private List<Map<String, Object>> loadLeafNodes(NamedParameterJdbcTemplate jdbc, Long schemeId, Long coaSchemeId, LocalDate baseDate) {
        String sql = "SELECT n.id AS coa_node_id, n.node_code, n.node_name, n.node_level, n.parent_id, n.path,"
                + "       c.id AS cfg_id, c.annual_growth_rate"
                + "  FROM prcp_coa_node n"
                + "  JOIN prcp_data_basic b ON b.coa_node_id = n.id AND b.data_date = :bd AND b.is_deleted = 0"
                + "  LEFT JOIN prcp_sim_node_config c ON c.coa_node_id = n.id AND c.scheme_id = :sid AND c.is_deleted = 0"
                + " WHERE n.scheme_id = :coa_sid AND n.node_type = 'BUSINESS'"
                + " ORDER BY n.path, n.id";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("sid", schemeId)
                .addValue("bd", baseDate.format(BucketMath.DF))
                .addValue("coa_sid", coaSchemeId);
        return jdbc.queryForList(sql, p);
    }

    private List<NodeData> initNodesData(NamedParameterJdbcTemplate jdbc, List<Map<String, Object>> leafNodes, LocalDate baseDate) {
        List<NodeData> result = new ArrayList<>();
        for (Map<String, Object> row : leafNodes) {
            Long coaNodeId = ((Number) row.get("coa_node_id")).longValue();
            Number cfgId = (Number) row.get("cfg_id");
            boolean isConfigured = cfgId != null;

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
                nd.termRatios = loadTermRatios(jdbc, cfgId.longValue());
            }
            result.add(nd);
        }
        return result;
    }

    private List<Map<String, Object>> loadTermRatios(NamedParameterJdbcTemplate jdbc, Long cfgId) {
        String sql = "SELECT term_value, term_unit, business_ratio, interest_rate, sort_order"
                + "  FROM prcp_sim_term_ratio WHERE config_id=:cid AND is_deleted=0 ORDER BY sort_order";
        return jdbc.queryForList(sql, new MapSqlParameterSource("cid", cfgId));
    }

    private int rollAndInsert(NamedParameterJdbcTemplate jdbc, Long runId, Long simSchemeId, String schemeCode,
                              Long coaSchemeId, LocalDate baseDate, int monthCount, List<NodeData> nodesData) {
        // 构造 INSERT SQL：128 桶 + 18 元数据 + 4 主指标
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
        for (String k : BucketMath.KEYS) {
            allCols.add("orig_" + k);
            allCols.add("rem_" + k);
        }
        allCols.add("current_balance");
        allCols.add("avg_balance");
        allCols.add("weighted_rate");
        allCols.add("interest_amount");
        allCols.add("calc_note");

        StringBuilder sql = new StringBuilder("INSERT INTO prcp_sim_result (");
        sql.append(String.join(", ", allCols));
        sql.append(") VALUES (");
        for (int i = 0; i < allCols.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(":").append(allCols.get(i));
        }
        sql.append(")");
        String insertSql = sql.toString();

        int totalInserted = 0;
        for (int k = 1; k <= monthCount; k++) {
            LocalDate dataDateK = BucketMath.addMonths(baseDate, k);
            String dataDateKStr = dataDateK.format(BucketMath.DF);
            List<Map<String, Object>> rowsK = new ArrayList<>();

            for (NodeData nd : nodesData) {
                Map<String, BigDecimal> state = nd.state;
                Map<String, BigDecimal> monthNewMap = BucketMath.computeMonthNew(state, nd.growthRate);
                BigDecimal netNew = monthNewMap.get("net_new");
                BigDecimal monthNew = monthNewMap.get("month_new");

                List<Map<String, BigDecimal>> additions = new ArrayList<>();
                if (nd.isConfigured && !nd.termRatios.isEmpty()) {
                    additions = BucketMath.applyTermSplit(state, monthNew, nd.termRatios);
                }

                nd.state = BucketMath.rollOneMonth(state);
                Map<String, BigDecimal> main = BucketMath.computeMainMetrics(nd.state, nd.growthRate, additions, netNew, monthNew);
                nd.state.put("current_balance", main.get("new_current_balance"));
                nd.state.put("avg_balance",     main.get("new_avg_balance"));
                nd.state.put("weighted_rate",   main.get("new_weighted_rate"));
                nd.state.put("interest_amount", main.get("new_interest_amount"));

                Map<String, Object> row = new HashMap<>();
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
                row.put("is_configured",   nd.isConfigured ? 1 : 0);
                row.put("is_aggregated",   0);
                for (String kk : BucketMath.KEYS) {
                    row.put("orig_" + kk, nd.state.getOrDefault("orig_" + kk, BigDecimal.ZERO));
                    row.put("rem_"  + kk, nd.state.getOrDefault("rem_"  + kk, BigDecimal.ZERO));
                }
                row.put("current_balance", nd.state.get("current_balance"));
                row.put("avg_balance",     nd.state.get("avg_balance"));
                row.put("weighted_rate",   nd.state.get("weighted_rate"));
                row.put("interest_amount", nd.state.get("interest_amount"));
                row.put("calc_note", nd.isConfigured
                        ? "M" + k + "=" + dataDateKStr + "; growth=" + nd.growthRate + "%; configured"
                        : "M" + k + "=" + dataDateKStr + "; growth=0.0%; rolled-only");
                rowsK.add(row);
            }

            if (!rowsK.isEmpty()) {
                MapSqlParameterSource[] batch = rowsK.stream()
                        .map(MapSqlParameterSource::new)
                        .toArray(MapSqlParameterSource[]::new);
                int[] counts = jdbc.batchUpdate(insertSql, batch);
                totalInserted += counts.length;
            }

            // 更新进度
            int progress = (int) ((double) k / monthCount * 100);
            jdbc.update("UPDATE prcp_sim_run SET progress=:p, processed_nodes=:pn WHERE id=:id",
                    new MapSqlParameterSource()
                            .addValue("p", progress)
                            .addValue("pn", nodesData.size())
                            .addValue("id", runId));
        }
        return totalInserted;
    }

    /** 聚合 SUMMARY 节点（递归 CTE） */
    private int aggregateSummaryNodes(NamedParameterJdbcTemplate jdbc, Long runId, Long simSchemeId,
                                       String schemeCode, Long coaSchemeId, LocalDate baseDate, int monthCount) {
        String findSummarySql = "SELECT n.id, n.node_code, n.node_name, n.node_level,"
                + "       (SELECT b.category FROM prcp_data_basic b"
                + "         WHERE b.coa_node_id=n.id AND b.data_date=:bd AND b.is_deleted=0 LIMIT 1) AS category"
                + "  FROM prcp_coa_node n"
                + " WHERE n.scheme_id=:sid AND n.node_type='SUMMARY'"
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
                // 构造 INSERT SQL（重用 rollAndInsert 同款结构）
                List<String> cols = new ArrayList<>(Arrays.asList(
                    "sim_scheme_code","sim_scheme_id","run_id","data_date","date_offset",
                    "coa_scheme_id","coa_node_id","node_code","node_name","node_level","category",
                    "is_configured","is_aggregated"));
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
     * @return 结果行 Map 列表 (LIMIT 2000)
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
        sql.append(" ORDER BY r.date_offset, r.coa_node_id LIMIT 2000");

        return jdbc.queryForList(sql.toString(), p);
    }

    private String camel(String k) {
        return k.substring(0, 1).toUpperCase() + k.substring(1);
    }
}
