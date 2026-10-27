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
 * <p>桶推算 + 月份推算 + 主指标重算 (对位 Python new_business/engine.py <b>v2.4</b>)</p>
 *
 * <p><b>版本对齐说明</b>: 本类已从 v1 口径升级到 Python v2.4 (2026-10-23, commit fe514d3),
 * 核心语义变更 3 处, 每处都会导致全部数值结果错误:</p>
 * <ol>
 *   <li><b>rem_* 桶 = 绝对到期月</b> (距基准日第 k 个月到期), <b>不再每月前移</b>。
 *       第 M 月投放期限 v → 直接落 {@code rem_m{M+v}}; 第 M 月到期释放 = 取走 {@code rem_m{M}} 并清零。
 *       基准日时「剩余期限」恰好等于「距基准日到期月数」, 故存量数据无需转换。</li>
 *   <li><b>orig_* 桶 = 只进不出</b> (记录投放时的原始期限累计额), 不随月份变化。</li>
 *   <li><b>投放量 = 固定年增量 × 节奏权重</b>, 不再是「余额 × 增长率」。
 *       且 {@code prcp_sim_term_ratio.interest_rate} 存百分数(3.5),
 *       需 /100 换算成小数后再加权, 否则月利息虚高 100 倍。</li>
 * </ol>
 *
 * <p>核心方法:
 * <ul>
 *   <li>{@link #computeYearTarget} — 步骤 1: 按增长方式换算「当年目标增量」</li>
 *   <li>{@link #getRhythmWeights} — 步骤 2: 取 12 个月节奏权重</li>
 *   <li>{@link #computeMonthNewV2} — 步骤 2: 算当月投放 (add/release/month_new)</li>
 *   <li>{@link #applyTermSplit} — 步骤 3: 按期限拆分 + 按绝对到期月落桶</li>
 *   <li>{@link #takeDue} / {@link #peekDue} — 到期释放 (取走清零 / 快照不清零)</li>
 *   <li>{@link #accumulatePlan} / {@link #applyFinalPlan} — 完整 60 月排期</li>
 *   <li>{@link #computeMainMetrics} — 步骤 5: 主指标重算</li>
 * </ul>
 * </p>
 *
 * <p>已废弃: {@link #rollOneMonth} (v2.2 起引擎不再调用, 仅保留历史参考)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see <a href="file:///C:/银行经营/PRCP/backend/app/services/calculate_engine/new_business/engine.py">Python engine.py v2.4</a>
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

    /** 「目标」类增长方式 (含到期回补) — 对位 Python TARGET_GROWTH_TYPES */
    public static final Set<Integer> TARGET_GROWTH_TYPES = new HashSet<>(Arrays.asList(1, 2));

    /** 金额单位换算 — 对位 Python UNIT_SCALE_BY_UNIT */
    public static final Map<String, BigDecimal> UNIT_SCALE_BY_UNIT = new HashMap<>();
    static {
        UNIT_SCALE_BY_UNIT.put("WAN", new BigDecimal("10000"));    // 万元
        UNIT_SCALE_BY_UNIT.put("YI",  new BigDecimal("100000000")); // 亿元
    }

    /** 12 个月线性节奏默认权重 (100/12 = 8.3333) */
    public static final BigDecimal LINEAR_MONTH_WEIGHT =
            new BigDecimal("100").divide(new BigDecimal("12"), 4, RoundingMode.HALF_UP);

    /** 计算精度 (与 Python float 双精度对齐, 保留 6 位) */
    private static final int SCALE = 6;

    /**
     * <p>日期 + N 月 (月底处理: 1/31 + 1 月 = 2/28)</p>
     *
     * <p><b>⚠️ 与 Python 差异</b>: Python 用 {@code m // 12} (向下取整),
     * Java 原用 {@code m / 12} (向零截断), 负数月数会差 1 年。已改为向下取整对齐。</p>
     *
     * @param d      基准日期
     * @param months 月数 (可正可负)
     * @return 计算后的日期
     */
    public static LocalDate addMonths(LocalDate d, int months) {
        long m = (d.getMonthValue() - 1L) + months;
        int y = d.getYear() + (int) Math.floorDiv(m, 12L);
        int newM = (int) Math.floorMod(m, 12L) + 1;
        java.time.YearMonth ym = java.time.YearMonth.of(y, newM);
        int lastDay = ym.lengthOfMonth();
        int day = Math.min(d.getDayOfMonth(), lastDay);
        return LocalDate.of(y, newM, day);
    }

    // ========================================================================
    // 步骤 1: 取初始状态（从 prcp_data_basic 取基准月数据）
    // ========================================================================

    /**
     * <p>步骤 1: 取节点初始状态 (从 prcp_data_basic 取基准月 date_offset=0 的行)</p>
     * <p>state = {orig_m1..rem_y30 共 128 桶 + current_balance/avg_balance/weighted_rate/interest_amount}</p>
     *
     * @param jdbc     JDBC 模板
     * @param coaNodeId 节点 ID
     * @param baseDate 基础数据日期
     * @return state Map, 节点在基准月无基础数据返回 null
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
    // 步骤 1（v2.1）: 按增长配置换算「当年目标增量」
    // ========================================================================

    /**
     * <p>步骤 1 (v2.1): 把「第 N 年的增长参数」换算成「当年目标增量」(元)</p>
     *
     * <p>growth_type 语义 (v2.1 定稿):
     * <ul>
     *   <li><b>1 目标增长百分比</b>: year_param 是目标增速(%), 增量 = 基数 × 增速</li>
     *   <li><b>2 目标增长绝对值</b>: year_param 是当年新增总额(金额), 增量 = year_param × 单位换算</li>
     *   <li><b>3 增长百分比</b>: year_param 是期间增速(%), 增量 = 基数 × 增速 (语义同 1, 但投放口径不同)</li>
     *   <li><b>4 增长值</b>: year_param 是年末目标余额(金额), 增量 = 目标余额 − 当前余额 → 可为负 (缩表场景)</li>
     * </ul>
     *
     * @param growthType  增长方式 1..4
     * @param yearParam   第 N 年参数
     * @param baseBalance 基数余额 (元)
     * @param unitScale   金额单位换算系数 (WAN=1e4, YI=1e8)
     * @return 当年目标增量 (元), 可能为负 (type 4 缩表)
     */
    public static BigDecimal computeYearTarget(int growthType, BigDecimal yearParam,
                                                BigDecimal baseBalance, BigDecimal unitScale) {
        BigDecimal param = yearParam == null ? BigDecimal.ZERO : yearParam;
        BigDecimal base = baseBalance == null ? BigDecimal.ZERO : baseBalance;
        BigDecimal scale = unitScale == null ? BigDecimal.ONE : unitScale;
        if (growthType == 1 || growthType == 3) {
            // 基数 × 增速%
            return base.multiply(param).divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        }
        if (growthType == 2) {
            // 固定新增额
            return param.multiply(scale);
        }
        if (growthType == 4) {
            // 年末余额锚定 (可为负)
            return param.multiply(scale).subtract(base);
        }
        // 兜底: 未知方式按 0 处理 (不投放)
        return BigDecimal.ZERO;
    }

    // ========================================================================
    // 步骤 2（v2.1）: 取 12 个月节奏权重
    // ========================================================================

    /**
     * <p>步骤 2 (v2.1): 默认线性节奏 — 12 个月均匀, 每个月 100/12 = 8.3333</p>
     *
     * @return 长度 12 的权重列表
     */
    public static List<BigDecimal> defaultLinearWeights() {
        List<BigDecimal> w = new ArrayList<>(12);
        for (int i = 0; i < 12; i++) w.add(LINEAR_MONTH_WEIGHT);
        return w;
    }

    /**
     * <p>步骤 2 (v2.1): 取 12 个月节奏权重</p>
     *
     * <p>rhythm_type:
     * <ul>
     *   <li><b>1 线性</b> → 12 个月均匀 (8.3333%), 忽略表中数据</li>
     *   <li><b>2 自定义</b> → 取 prcp_sim_rhythm 的 12 行权重; 无数据时降级为线性</li>
     * </ul>
     *
     * <p><b>三重降级保护</b> (与 Python 一致):
     * <ol>
     *   <li>未配满 12 个月 → 降级线性</li>
     *   <li>权重和偏差 &gt; 0.1% → 降级线性 (避免脏数据放大)</li>
     * </ol>
     * </p>
     *
     * @param rhythmType 节奏类型 1=线性 2=自定义
     * @param rhythmRows prcp_sim_rhythm 行 [{month_no, weight}, ...], 可空
     * @return 长度固定 12 的权重列表 (单位 %, 和为 100)
     */
    public static List<BigDecimal> getRhythmWeights(int rhythmType, List<Map<String, Object>> rhythmRows) {
        if (rhythmType != 2) return defaultLinearWeights();
        if (rhythmRows == null || rhythmRows.isEmpty()) return defaultLinearWeights();

        List<BigDecimal> weights = defaultLinearWeights();
        int got = 0;
        for (Map<String, Object> r : rhythmRows) {
            try {
                int m = ((Number) r.get("month_no")).intValue();
                BigDecimal w = toBD(r.get("weight"));
                if (m >= 1 && m <= 12) {
                    weights.set(m - 1, w);
                    got++;
                }
            } catch (Exception ignored) { }
        }
        // 未配置满 12 个月 → 降级线性
        if (got < 12) return defaultLinearWeights();

        // 权重和容差校验: 偏差 > 0.1% 时降级线性
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal w : weights) total = total.add(w);
        if (total.subtract(new BigDecimal("100")).abs().compareTo(new BigDecimal("0.1")) > 0) {
            return defaultLinearWeights();
        }
        return weights;
    }

    // ========================================================================
    // 步骤 2（v2.1）: 算当月投放
    // ========================================================================

    /**
     * <p>步骤 2 (v2.1): 计算第 M 月的 (add, release, month_new)</p>
     *
     * <p><b>业务口径 (2026-10-21 确认): 按增长方式名称是否含「目标」判定是否回补到期</b>
     * <ul>
     *   <li>「目标」类 (growth_type 1 目标增长百分比 / 2 目标增长绝对值):
     *       当月新增加值 = 分摊的净增加值 + 当月剩余期限余额 (rem_m1)
     *       → 到期回补, 保证余额净增长达到目标</li>
     *   <li>非「目标」类 (3 增长百分比 / 4 增长值):
     *       当月新增加值 = 分摊的净增加值 → 不回补, 当月到期自然出表</li>
     * </ul>
     *
     * <p>四种方式统一采用「固定年增量 × 节奏权重」分摊到月:
     * {@code Σ(add over 12 months) = year_target × Σ(w)/100 = year_target}
     * → 全年投放额恒等于年目标增量, 不会因余额漂移而放大或衰减。</p>
     *
     * @param growthType   增长方式 1..4
     * @param yearTarget   当年目标增量 (元)
     * @param rhythmWeight 当月节奏权重 (%)
     * @param release      当月到期释放 (元) = 上月末 rem_m1
     * @return Map: {add, release, month_new}
     */
    public static Map<String, BigDecimal> computeMonthNewV2(int growthType, BigDecimal yearTarget,
                                                            BigDecimal rhythmWeight, BigDecimal release) {
        BigDecimal yt = yearTarget == null ? BigDecimal.ZERO : yearTarget;
        BigDecimal rw = rhythmWeight == null ? BigDecimal.ZERO : rhythmWeight;
        BigDecimal rel = release == null ? BigDecimal.ZERO : release;

        // 固定年增量按节奏权重分摊到月 (Σw = 100 → 全年合计 = year_target)
        BigDecimal add = yt.multiply(rw).divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);

        // 按增长方式名称判定: 1/2 含「目标」→ 回补到期; 3/4 → 不回补
        BigDecimal monthNew = TARGET_GROWTH_TYPES.contains(growthType) ? rel.add(add) : add;

        Map<String, BigDecimal> result = new LinkedHashMap<>();
        result.put("add", add);
        result.put("release", rel);
        result.put("month_new", monthNew);
        return result;
    }

    // ========================================================================
    // 步骤 3: 按期限占比拆分 + 按「绝对到期月」落桶
    // ========================================================================

    /**
     * <p>绝对到期月 → 桶名 (不含 rem_ 前缀)</p>
     *
     * <p>≤60 月 → m1..m60; &gt;60 月 → 按超出月数归档到 y10 / y15 / y20 / y30 (10Y/15Y/20Y/30Y)</p>
     *
     * @param targetMonth 绝对到期月
     * @return 桶名 (m1..m60 / y10 / y15 / y20 / y30)
     */
    public static String dueKey(int targetMonth) {
        if (targetMonth <= 60) return "m" + targetMonth;
        if (targetMonth <= 120) return "y10";
        if (targetMonth <= 180) return "y15";
        if (targetMonth <= 240) return "y20";
        return "y30";
    }

    /**
     * <p>绝对到期月 → state / row 中的完整键名 (rem_ 前缀)</p>
     *
     * @param targetMonth 绝对到期月
     * @return 完整键名 (rem_m1 .. rem_m60 / rem_y10 .. rem_y30)
     */
    public static String dueStateKey(int targetMonth) {
        return "rem_" + dueKey(targetMonth);
    }

    /**
     * <p>第 cur_month 的到期释放: 取走 rem_m{cur_month} 并清零</p>
     *
     * <p>v2.2: 不再前移, 直接按绝对到期月取走当月应到期金额。</p>
     *
     * @param state    节点状态 (会被就地修改)
     * @param curMonth 当前月 (1-based)
     * @return 到期释放金额
     */
    public static BigDecimal takeDue(Map<String, BigDecimal> state, int curMonth) {
        String key = dueStateKey(curMonth);
        BigDecimal due = state.getOrDefault(key, BigDecimal.ZERO);
        state.put(key, BigDecimal.ZERO);
        return due;
    }

    /**
     * <p>查看第 cur_month 的计划到期额 (不清零)</p>
     *
     * <p>v2.3: 用于在 take_due 之前快照「计划到期」, 使结果表保留到期信息。</p>
     *
     * @param state    节点状态 (不修改)
     * @param curMonth 当前月 (1-based)
     * @return 计划到期额
     */
    public static BigDecimal peekDue(Map<String, BigDecimal> state, int curMonth) {
        return state.getOrDefault(dueStateKey(curMonth), BigDecimal.ZERO);
    }

    /**
     * <p>步骤 3: 按期限占比拆分 month_new, 并直接落到「距基准日第 cur_month+v 个月到期」的格子</p>
     *
     * <p><b>v2.2 口径</b> (2026-10-21 业务确认):
     * <ul>
     *   <li>取消「剩余期限桶整体前移」, 改为按绝对到期月直接落桶</li>
     *   <li>{@code rem_mk} 语义 = 「距基准日第 k 个月到期的金额」(绝对到期月)</li>
     *   <li>{@code orig_mv} 语义 = 「原始期限为 v 个月的累计投放额」(只进不出)</li>
     *   <li>第 M 月投放期限 v → 直接落 {@code rem_m{M+v}}</li>
     *   <li>第 M 月的到期释放 = 取走 {@code rem_m{M}} 并清零</li>
     * </ul>
     *
     * @param state     节点当前状态 (会被就地修改: orig 只增, rem 按绝对月累加)
     * @param monthNew  当月新增量
     * @param ratios    [{term_value, business_ratio, interest_rate}, ...]
     * @param curMonth  当前月 (1-based)
     * @param fullPlan  完整 60 月排期容器 (可为 null)
     * @return additions [{term_value, amount, interest_rate}, ...] 用于加权平均利率计算
     */
    public static List<Map<String, BigDecimal>> applyTermSplit(
            Map<String, BigDecimal> state, BigDecimal monthNew, List<Map<String, Object>> ratios,
            int curMonth, Map<Integer, BigDecimal> fullPlan) {
        List<Map<String, BigDecimal>> additions = new ArrayList<>();
        if (ratios == null) return additions;
        for (Map<String, Object> r : ratios) {
            int v = ((Number) r.get("term_value")).intValue();
            BigDecimal bPct = toBD(r.get("business_ratio"));
            BigDecimal ir = toBD(r.getOrDefault("interest_rate", BigDecimal.ZERO));
            BigDecimal amount = monthNew.multiply(bPct).divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);

            // ① 原始期限桶: 只进不出 (记录投放时的原始期限)
            String key = TERM_TO_KEY.get(v);
            if (key != null) {
                state.merge("orig_" + key, amount, BigDecimal::add);
            }

            // ② 绝对到期月桶: 落到 cur_month + v
            //    超出 60 月落到长端桶 (y10/y15/y20/y30 按超出月数就近归档)
            int tgtMonth = curMonth + v;
            String tkey = dueKey(tgtMonth);
            state.merge("rem_" + tkey, amount, BigDecimal::add);

            // ③ v2.4: 把每笔投放的落点累加进「完整 60 月排期」(与是否已取走无关)
            if (fullPlan != null && tgtMonth <= 60) {
                fullPlan.merge(tgtMonth, amount, BigDecimal::add);
            }

            Map<String, BigDecimal> add = new LinkedHashMap<>();
            add.put("term_value", new BigDecimal(v));
            add.put("amount", amount);
            add.put("interest_rate", ir);
            additions.add(add);
        }
        return additions;
    }

    // ========================================================================
    // 步骤 4（v2.4）: 完整 60 月到期排期
    // ========================================================================

    /**
     * <p>v2.4: 把 state 中所有剩余桶累加进 plan (用于汇总完整 60 月到期排期)</p>
     *
     * <p>解决「每格都应有值」的问题 ——
     * state 是「截至当前月已投放」的存量, 每取走一格就少一格;
     * plan 则是把 60 个月里<b>每一笔投放的落点</b>都累加起来,
     * 得到「整个 5 年预测期内各月的最终到期额」, 与是否已取走无关。</p>
     *
     * @param plan       排期容器 (可变)
     * @param state      节点状态
     * @param monthCount 总月数
     */
    public static void accumulatePlan(Map<Integer, BigDecimal> plan,
                                       Map<String, BigDecimal> state, int monthCount) {
        for (int tgt = 1; tgt <= monthCount; tgt++) {
            BigDecimal amt = state.getOrDefault(dueStateKey(tgt), BigDecimal.ZERO);
            if (amt.abs().compareTo(new BigDecimal("0.000000001")) > 0) {
                plan.merge(tgt, amt, BigDecimal::add);
            }
        }
    }

    /**
     * <p>v2.4: 把完整 60 月到期排期写入结果行</p>
     *
     * <p>让每一行 (含 M1) 都能看到「未来 5 年每个月都有到期」的完整时间轴,
     * 每个格子都有值 — 因为它包含存量 + 全部 60 个月投放的落点。</p>
     *
     * @param row       结果行 (会被就地修改, rem_* 键)
     * @param finalPlan 完整排期
     */
    public static void applyFinalPlan(Map<String, Object> row, Map<Integer, BigDecimal> finalPlan) {
        if (finalPlan == null) return;
        for (Map.Entry<Integer, BigDecimal> e : finalPlan.entrySet()) {
            row.put(dueStateKey(e.getKey()), e.getValue());
        }
    }

    // ========================================================================
    // 步骤 4: 桶往前推一月（v2.2 起已废弃）
    // ========================================================================

    /**
     * <p>【已废弃 · v2.2】所有桶往前推一月</p>
     *
     * <p>⚠️ 业务口径 2026-10-21 变更: 不再需要「剩余期限桶整体前移」。
     * rem_* 语义已改为「距基准日第 k 个月到期」, 投放时直接落到 rem_m{M+v},
     * 到期时按月取走 rem_m{M}。<b>本方法保留仅作历史参考, 引擎不再调用。</b></p>
     *
     * @param state 节点当前状态
     * @return 推一个月后的新 state (原 state 不变)
     * @deprecated v2.2 起不再使用, 请用 {@link #takeDue} + {@link #applyTermSplit}
     */
    @Deprecated
    public static Map<String, BigDecimal> rollOneMonth(Map<String, BigDecimal> state) {
        Map<String, BigDecimal> next = new LinkedHashMap<>(state);

        // 原始期限月桶
        for (int v = 1; v < 60; v++) {
            next.put("orig_m" + v, state.get("orig_m" + (v + 1)));
        }
        next.put("orig_m60", state.get("orig_y10").divide(BigDecimal.valueOf(60), SCALE, RoundingMode.HALF_UP));
        next.put("orig_y10", state.get("orig_y10").subtract(state.get("orig_y10").divide(BigDecimal.valueOf(60), SCALE, RoundingMode.HALF_UP)).add(state.get("orig_y15").divide(BigDecimal.valueOf(120), SCALE, RoundingMode.HALF_UP)));
        next.put("orig_y15", state.get("orig_y15").subtract(state.get("orig_y15").divide(BigDecimal.valueOf(120), SCALE, RoundingMode.HALF_UP)).add(state.get("orig_y20").divide(BigDecimal.valueOf(120), SCALE, RoundingMode.HALF_UP)));
        next.put("orig_y20", state.get("orig_y20").subtract(state.get("orig_y20").divide(BigDecimal.valueOf(120), SCALE, RoundingMode.HALF_UP)).add(state.get("orig_y30").divide(BigDecimal.valueOf(240), SCALE, RoundingMode.HALF_UP)));
        next.put("orig_y30", state.get("orig_y30").subtract(state.get("orig_y30").divide(BigDecimal.valueOf(240), SCALE, RoundingMode.HALF_UP)));

        // 剩余期限月桶
        for (int v = 1; v < 60; v++) {
            next.put("rem_m" + v, state.get("rem_m" + (v + 1)));
        }
        next.put("rem_m60", state.get("rem_y10").divide(BigDecimal.valueOf(60), SCALE, RoundingMode.HALF_UP));
        next.put("rem_y10", state.get("rem_y10").subtract(state.get("rem_y10").divide(BigDecimal.valueOf(60), SCALE, RoundingMode.HALF_UP)).add(state.get("rem_y15").divide(BigDecimal.valueOf(120), SCALE, RoundingMode.HALF_UP)));
        next.put("rem_y15", state.get("rem_y15").subtract(state.get("rem_y15").divide(BigDecimal.valueOf(120), SCALE, RoundingMode.HALF_UP)).add(state.get("rem_y20").divide(BigDecimal.valueOf(120), SCALE, RoundingMode.HALF_UP)));
        next.put("rem_y20", state.get("rem_y20").subtract(state.get("rem_y20").divide(BigDecimal.valueOf(120), SCALE, RoundingMode.HALF_UP)).add(state.get("rem_y30").divide(BigDecimal.valueOf(240), SCALE, RoundingMode.HALF_UP)));
        next.put("rem_y30", state.get("rem_y30").subtract(state.get("rem_y30").divide(BigDecimal.valueOf(240), SCALE, RoundingMode.HALF_UP)));

        return next;
    }

    // ========================================================================
    // 步骤 5: 主指标重算
    // ========================================================================

    /**
     * <p>步骤 5 (v2.4): 主指标重算</p>
     *
     * <p>公式 (对位 Python engine.py:997-1022):
     * <pre>
     *   new_cb = base_balance + add                       (余额 = 原 + 当月投放)
     *   new_ab = avg_balance + add / 2                    (月均余额 = 原 + 投放/2)
     *   weighted_amount = Σ (month_new × ratio/100) × (ir/100)   ← ⚠️ ir 必须 /100
     *   denom = avg_balance + month_new
     *   new_wr = (avg_balance × weighted_rate + weighted_amount) / denom
     *   new_ia = new_ab × new_wr / 12
     * </pre>
     *
     * <p><b>⚠️ 利率单位口径 (曾导致月利息虚高 100 倍)</b>:
     * <ul>
     *   <li>{@code prcp_data_basic.weighted_rate} 存「小数」(0.045 = 4.5%)</li>
     *   <li>{@code prcp_sim_term_ratio.interest_rate} 存「百分数」(3.5 = 3.5%)</li>
     *   <li>两者直接相加会让新投放利率被放大 100 倍, 故统一除以 100 换算成小数后再加权</li>
     * </ul>
     * </p>
     *
     * @param state     节点状态 (含旧 current/avg/wr)
     * @param additions 步骤 3 返回的 [{term_value, amount, interest_rate}, ...]
     * @param add       步骤 2 的当月净新增投放
     * @param monthNew  步骤 2 的 month_new (用于加权平均分母)
     * @return {new_current_balance, new_avg_balance, new_weighted_rate, new_interest_amount}
     */
    public static Map<String, BigDecimal> computeMainMetrics(
            Map<String, BigDecimal> state, List<Map<String, BigDecimal>> additions,
            BigDecimal add, BigDecimal monthNew) {

        BigDecimal cur = state.getOrDefault("current_balance", BigDecimal.ZERO);
        BigDecimal avg = state.getOrDefault("avg_balance", BigDecimal.ZERO);
        BigDecimal wr  = state.getOrDefault("weighted_rate", BigDecimal.ZERO);
        BigDecimal a = add == null ? BigDecimal.ZERO : add;
        BigDecimal mn = monthNew == null ? BigDecimal.ZERO : monthNew;

        // 余额 = 原余额 + 当月投放; 月均余额 = 原月均 + 投放/2
        BigDecimal newCurrent = cur.add(a).setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal newAvg = avg.add(a.divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP));

        // ⚠️ 利率单位: interest_rate 存百分数, 必须 /100 换算成小数
        BigDecimal weightedAmount = BigDecimal.ZERO;
        if (additions != null) {
            for (Map<String, BigDecimal> ad : additions) {
                BigDecimal amt = ad.getOrDefault("amount", BigDecimal.ZERO);
                BigDecimal ir  = ad.getOrDefault("interest_rate", BigDecimal.ZERO);
                weightedAmount = weightedAmount.add(amt.multiply(ir).divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP));
            }
        }
        BigDecimal denom = avg.add(mn);
        BigDecimal newWr;
        if (denom.compareTo(BigDecimal.ZERO) > 0) {
            newWr = avg.multiply(wr).add(weightedAmount).divide(denom, SCALE, RoundingMode.HALF_UP);
        } else {
            newWr = wr;
        }

        BigDecimal newInterest = newAvg.multiply(newWr).divide(BigDecimal.valueOf(12), SCALE, RoundingMode.HALF_UP);

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
