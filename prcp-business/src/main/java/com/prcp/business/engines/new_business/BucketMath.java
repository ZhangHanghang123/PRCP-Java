package com.prcp.business.engines.new_business;

import com.prcp.business.data.BasicDataBuckets;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <p>桶推算 + 月份推算 + 主指标重算 (对位 Python new_business/engine.py 步骤 2/3/4/5)</p>
 *
 * <p>纯函数工具, 无状态。算法严格对齐 Python:
 * <ul>
 *   <li>{@link #computeMonthNew} — 步骤 2: 算当月新增量</li>
 *   <li>{@link #applyTermSplit} — 步骤 3: 按期限拆分叠加</li>
 *   <li>{@link #rollOneMonth} — 步骤 4: 桶往前推一月</li>
 *   <li>{@link #computeMainMetrics} — 步骤 5: 主指标重算</li>
 * </ul>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
public final class BucketMath {

    private BucketMath() {}

    /** 日期格式化: yyyy-MM-dd */
    public static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 64 桶列表 (含 m1..m60 + y10/y15/y20/y30) */
    public static final List<String> KEYS = BasicDataBuckets.KEYS;

    /** 期限 → 桶 key (term_value 1..60 → "m1".."m60") */
    public static final Map<Integer, String> TERM_TO_KEY = new HashMap<>();
    static {
        for (int i = 1; i <= 60; i++) TERM_TO_KEY.put(i, "m" + i);
    }

    /**
     * <p>日期 + N 月 (月底处理: 1/31 + 1 月 = 2/28)</p>
     *
     * @param d      基准日期
     * @param months 月数 (可正可负)
     * @return 计算后的日期
     */
    public static LocalDate addMonths(LocalDate d, int months) {
        long m = (d.getMonthValue() - 1L) + months;
        int y = d.getYear() + (int) (m / 12);
        int newM = (int) (m % 12) + 1;
        java.time.YearMonth ym = java.time.YearMonth.of(y, newM);
        int lastDay = ym.lengthOfMonth();
        int day = Math.min(d.getDayOfMonth(), lastDay);
        return LocalDate.of(y, newM, day);
    }

    // ========================================================================
    // 步骤 1: 取初始状态（从 prcp_data_basic 取 T 月数据）
    // ========================================================================

    /**
     * <p>步骤 1: 取节点初始状态 (从 prcp_data_basic 取 T 月 date_offset=0 的行)</p>
     * <p>state = {orig_m1..rem_y30 共 128 桶 + current_balance/avg_balance/weighted_rate/interest_amount}</p>
     *
     * @param jdbc     JDBC 模板
     * @param coaNodeId 节点 ID
     * @param baseDate 基础数据日期
     * @return state Map, 节点在 T 月无基础数据返回 null
     */
    public static Map<String, BigDecimal> getInitialState(
            NamedParameterJdbcTemplate jdbc, Long coaNodeId, LocalDate baseDate) {
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < KEYS.size(); i++) {
            String k = KEYS.get(i);
            if (i > 0) sql.append(", ");
            sql.append("b.orig_").append(k).append(", b.rem_").append(k);
        }
        sql.append(", b.current_balance, b.avg_balance, b.weighted_rate, b.interest_amount");
        sql.append(", b.category");
        sql.append("  FROM prcp_data_basic b");
        sql.append(" WHERE b.coa_node_id = :nid AND b.data_date = :d AND b.is_deleted = 0");
        sql.append(" ORDER BY b.date_offset ASC LIMIT 1");

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("nid", coaNodeId)
                .addValue("d", baseDate.format(DF));

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), p);
        if (rows.isEmpty()) return null;

        Map<String, Object> row = rows.get(0);
        Map<String, BigDecimal> state = new LinkedHashMap<>();
        // 按 key 名访问（LinkedCaseInsensitiveMap 不支持 Integer 索引）
        for (String k : KEYS) {
            state.put("orig_" + k, toBD(row.get("orig_" + k)));
            state.put("rem_"  + k, toBD(row.get("rem_"  + k)));
        }
        state.put("current_balance", toBD(row.get("current_balance")));
        state.put("avg_balance",     toBD(row.get("avg_balance")));
        state.put("weighted_rate",   toBD(row.get("weighted_rate")));
        state.put("interest_amount", toBD(row.get("interest_amount")));
        return state;
    }

    /**
     * <p>取节点的 category (字符串) — state 是 BigDecimal map, category 单独返回</p>
     *
     * @param jdbc     JDBC 模板
     * @param coaNodeId 节点 ID
     * @param baseDate 基础数据日期
     * @return category (ASSET/LIABILITY/EQUITY/OFF_BALANCE 等), 无数据返回 null
     */
    public static String fetchCategory(NamedParameterJdbcTemplate jdbc, Long coaNodeId, LocalDate baseDate) {
        String sql = "SELECT category FROM prcp_data_basic WHERE coa_node_id=:nid AND data_date=:d AND is_deleted=0 LIMIT 1";
        try {
            return jdbc.queryForObject(sql,
                new MapSqlParameterSource().addValue("nid", coaNodeId).addValue("d", baseDate.format(DF)),
                String.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ========================================================================
    // 步骤 2: 算当月新增量
    // ========================================================================

    /**
     * <p>步骤 2: 算当月新增量</p>
     * <ul>
     *   <li>net_new = current_balance × annual_growth_rate / 100 / 12</li>
     *   <li>month_new = rem_m1 + net_new</li>
     * </ul>
     *
     * @param state            节点当前状态 (含 current_balance 和 rem_m1)
     * @param annualGrowthRate 年增长率 (%)
     * @return {net_new, month_new}
     */
    public static Map<String, BigDecimal> computeMonthNew(Map<String, BigDecimal> state, BigDecimal annualGrowthRate) {
        BigDecimal monthlyRate = annualGrowthRate.divide(BigDecimal.valueOf(1200), 12, RoundingMode.HALF_UP);
        BigDecimal cur = state.get("current_balance");
        BigDecimal netNew = cur.multiply(monthlyRate).setScale(6, RoundingMode.HALF_UP);
        BigDecimal monthNew = state.get("rem_m1").add(netNew);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        result.put("net_new", netNew);
        result.put("month_new", monthNew);
        return result;
    }

    // ========================================================================
    // 步骤 3: 按期限拆分 + 叠加
    // ========================================================================

    /**
     * <p>步骤 3: 按期限拆分 + 叠加到桶</p>
     * <p>遍历 term_ratios, amount_v = month_new × business_ratio% / 100, 加到 orig_mv + rem_mv</p>
     *
     * @param state    节点当前状态 (会被就地修改, 累加 orig/rem)
     * @param monthNew 当月新增量 (来自步骤 2)
     * @param ratios   [{term_value, business_ratio, interest_rate}, ...]
     * @return additions [{term_value, amount, interest_rate}, ...] 用于步骤 5 加权平均利率
     */
    public static List<Map<String, BigDecimal>> applyTermSplit(
            Map<String, BigDecimal> state, BigDecimal monthNew, List<Map<String, Object>> ratios) {
        List<Map<String, BigDecimal>> additions = new ArrayList<>();
        for (Map<String, Object> r : ratios) {
            int v = ((Number) r.get("term_value")).intValue();
            BigDecimal bPct = toBD(r.get("business_ratio"));
            BigDecimal ir = toBD(r.getOrDefault("interest_rate", BigDecimal.ZERO));
            BigDecimal amount = monthNew.multiply(bPct).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            String key = TERM_TO_KEY.get(v);
            if (key != null) {
                state.merge("orig_" + key, amount, BigDecimal::add);
                state.merge("rem_"  + key, amount, BigDecimal::add);
                Map<String, BigDecimal> add = new LinkedHashMap<>();
                add.put("term_value", new BigDecimal(v));
                add.put("amount", amount);
                add.put("interest_rate", ir);
                additions.add(add);
            }
        }
        return additions;
    }

    // ========================================================================
    // 步骤 4: 桶往前推一月
    // ========================================================================

    /**
     * <p>步骤 4: 桶往前推一月</p>
     *
     * <p>所有桶往前推一月:
     * <ul>
     *   <li>月桶: 新 m_v (1..59) = 旧 m_(v+1), 新 m60 = 旧 y10 / 60</li>
     *   <li>长端自循环:
     *     <ul>
     *       <li>新 y10 = 旧 y10 - 旧 y10/60 + 旧 y15/120</li>
     *       <li>新 y15 = 旧 y15 - 旧 y15/120 + 旧 y20/120</li>
     *       <li>新 y20 = 旧 y20 - 旧 y20/120 + 旧 y30/240</li>
     *       <li>新 y30 = 旧 y30 - 旧 y30/240</li>
     *     </ul>
     *   </li>
     * </ul>
     * 同时应用到 rem_* 桶</p>
     *
     * @param state 节点当前状态
     * @return 推一个月后的新 state (原 state 不变)
     */
    public static Map<String, BigDecimal> rollOneMonth(Map<String, BigDecimal> state) {
        Map<String, BigDecimal> next = new LinkedHashMap<>(state);

        // 原始期限月桶
        for (int v = 1; v < 60; v++) {
            next.put("orig_m" + v, state.get("orig_m" + (v + 1)));
        }
        next.put("orig_m60", state.get("orig_y10").divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP));
        next.put("orig_y10", state.get("orig_y10").subtract(state.get("orig_y10").divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP)).add(state.get("orig_y15").divide(BigDecimal.valueOf(120), 6, RoundingMode.HALF_UP)));
        next.put("orig_y15", state.get("orig_y15").subtract(state.get("orig_y15").divide(BigDecimal.valueOf(120), 6, RoundingMode.HALF_UP)).add(state.get("orig_y20").divide(BigDecimal.valueOf(120), 6, RoundingMode.HALF_UP)));
        next.put("orig_y20", state.get("orig_y20").subtract(state.get("orig_y20").divide(BigDecimal.valueOf(120), 6, RoundingMode.HALF_UP)).add(state.get("orig_y30").divide(BigDecimal.valueOf(240), 6, RoundingMode.HALF_UP)));
        next.put("orig_y30", state.get("orig_y30").subtract(state.get("orig_y30").divide(BigDecimal.valueOf(240), 6, RoundingMode.HALF_UP)));

        // 剩余期限月桶
        for (int v = 1; v < 60; v++) {
            next.put("rem_m" + v, state.get("rem_m" + (v + 1)));
        }
        next.put("rem_m60", state.get("rem_y10").divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP));
        next.put("rem_y10", state.get("rem_y10").subtract(state.get("rem_y10").divide(BigDecimal.valueOf(60), 6, RoundingMode.HALF_UP)).add(state.get("rem_y15").divide(BigDecimal.valueOf(120), 6, RoundingMode.HALF_UP)));
        next.put("rem_y15", state.get("rem_y15").subtract(state.get("rem_y15").divide(BigDecimal.valueOf(120), 6, RoundingMode.HALF_UP)).add(state.get("rem_y20").divide(BigDecimal.valueOf(120), 6, RoundingMode.HALF_UP)));
        next.put("rem_y20", state.get("rem_y20").subtract(state.get("rem_y20").divide(BigDecimal.valueOf(120), 6, RoundingMode.HALF_UP)).add(state.get("rem_y30").divide(BigDecimal.valueOf(240), 6, RoundingMode.HALF_UP)));
        next.put("rem_y30", state.get("rem_y30").subtract(state.get("rem_y30").divide(BigDecimal.valueOf(240), 6, RoundingMode.HALF_UP)));

        return next;
    }

    // ========================================================================
    // 步骤 5: 主指标重算
    // ========================================================================

    /**
     * <p>步骤 5: 主指标重算 (current_balance / avg_balance / weighted_rate / interest_amount)</p>
     *
     * @param state            步骤 4 后的 state (含旧 current/avg/wr)
     * @param annualGrowthRate 年增长率 (%)
     * @param additions        步骤 3 返回的 [{term_value, amount, interest_rate}, ...]
     * @param netNew           步骤 2 的 net_new (保留参数, 当前未直接使用)
     * @param monthNew         步骤 2 的 month_new (用于加权平均分母)
     * @return {new_current_balance, new_avg_balance, new_weighted_rate, new_interest_amount}
     */
    public static Map<String, BigDecimal> computeMainMetrics(
            Map<String, BigDecimal> state, BigDecimal annualGrowthRate,
            List<Map<String, BigDecimal>> additions, BigDecimal netNew, BigDecimal monthNew) {

        BigDecimal cur = state.get("current_balance");
        BigDecimal avg = state.get("avg_balance");
        BigDecimal wr  = state.get("weighted_rate");
        BigDecimal monthlyRate = annualGrowthRate.divide(BigDecimal.valueOf(1200), 12, RoundingMode.HALF_UP);

        BigDecimal newCurrent = cur.multiply(BigDecimal.ONE.add(monthlyRate)).setScale(6, RoundingMode.HALF_UP);
        BigDecimal newAvg = avg.add(cur.multiply(monthlyRate).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP));

        // 加权平均利率 = Σ(amount × rate) / Σ(amount)
        BigDecimal weightedAmount = BigDecimal.ZERO;
        for (Map<String, BigDecimal> a : additions) {
            weightedAmount = weightedAmount.add(a.get("amount").multiply(a.get("interest_rate")));
        }
        BigDecimal denom = avg.add(monthNew);
        BigDecimal newWr;
        if (denom.compareTo(BigDecimal.ZERO) > 0) {
            newWr = avg.multiply(wr).add(weightedAmount).divide(denom, 6, RoundingMode.HALF_UP);
        } else {
            newWr = wr;
        }

        BigDecimal newInterest = newAvg.multiply(newWr).divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP);

        Map<String, BigDecimal> result = new LinkedHashMap<>();
        result.put("new_current_balance", newCurrent);
        result.put("new_avg_balance",     newAvg);
        result.put("new_weighted_rate",   newWr);
        result.put("new_interest_amount", newInterest);
        return result;
    }

    // ========================================================================
    // 工具
    // ========================================================================

    /**
     * <p>Object → BigDecimal (容忍 null/Number/String)</p>
     *
     * @param o 任意对象 (含 null)
     * @return BigDecimal (null 或解析失败返回 ZERO)
     */
    public static BigDecimal toBD(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return new BigDecimal(o.toString());
        try {
            return new BigDecimal(o.toString().trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
