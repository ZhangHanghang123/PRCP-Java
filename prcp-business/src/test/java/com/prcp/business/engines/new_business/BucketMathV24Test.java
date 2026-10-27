package com.prcp.business.engines.new_business;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <p>BucketMath v2.4 算法对齐验证测试 (对位 Python engine.py)</p>
 *
 * <p>所有期望值来自 Python 端实测输出 (服务器 {@code /home/almd/prcp/backend} venv):
 * <pre>
 *   py_ref.json  ← /tmp/py_algo_test.py 生成
 * </pre>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-26
 */
public class BucketMathV24Test {

    private static final BigDecimal BILLION = new BigDecimal("1000000000");
    private static final BigDecimal MILLION  = new BigDecimal("1000000");
    private static final BigDecimal TENK     = new BigDecimal("10000");

    // ================================================================
    // 1. computeYearTarget — 对位 Python compute_year_target
    // ================================================================
    @Test
    public void testComputeYearTarget() {
        // gt1 (目标增长百分比 5%): 基数 × 增速% = 1e9 × 5% = 50,000,000
        assertEquals(0, new BigDecimal("50000000")
                .compareTo(BucketMath.computeYearTarget(1, new BigDecimal("5"), BILLION, TENK)));
        // gt2 (目标增长绝对值 100万): 100 × 1e4 = 1,000,000
        assertEquals(0, new BigDecimal("1000000")
                .compareTo(BucketMath.computeYearTarget(2, new BigDecimal("100"), BILLION, TENK)));
        // gt3 (增长百分比 5%): 同 gt1
        assertEquals(0, new BigDecimal("50000000")
                .compareTo(BucketMath.computeYearTarget(3, new BigDecimal("5"), BILLION, TENK)));
        // gt4 (增长值 200万): 200×1e4 − 1e9 = −998,000,000 (可为负 = 缩表)
        assertEquals(0, new BigDecimal("-998000000")
                .compareTo(BucketMath.computeYearTarget(4, new BigDecimal("200"), BILLION, TENK)));
        // gt9 (未知方式): 兜底 0
        assertEquals(0, BigDecimal.ZERO
                .compareTo(BucketMath.computeYearTarget(9, new BigDecimal("1"), BILLION, TENK)));
    }

    // ================================================================
    // 2. getRhythmWeights — 对位 Python get_rhythm_weights
    // ================================================================
    @Test
    public void testGetRhythmWeights() {
        BigDecimal w = new BigDecimal("8.3333");
        // 线性
        assertEquals(12, BucketMath.getRhythmWeights(1, null).size());
        for (BigDecimal x : BucketMath.getRhythmWeights(1, null)) assertEquals(0, w.compareTo(x));
        // 自定义, 12 个月各 10 → 和=120 偏差>0.1% → 降级线性
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("month_no", i);
            r.put("weight", new BigDecimal("10"));
            rows.add(r);
        }
        for (BigDecimal x : BucketMath.getRhythmWeights(2, rows)) assertEquals(0, w.compareTo(x));
        // 自定义, 只配 1 个月 → 降级线性
        List<Map<String, Object>> shortRows = new ArrayList<>();
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("month_no", 1);
        r1.put("weight", new BigDecimal("100"));
        shortRows.add(r1);
        for (BigDecimal x : BucketMath.getRhythmWeights(2, shortRows)) assertEquals(0, w.compareTo(x));
    }

    // ================================================================
    // 3. computeMonthNewV2 — 对位 Python compute_month_new_v2
    // ================================================================
    @Test
    public void testComputeMonthNewV2() {
        BigDecimal yearTarget = MILLION;
        BigDecimal rhythmW = new BigDecimal("8.3333");
        BigDecimal release = new BigDecimal("500000");

        // 「目标」类 (1/2): month_new = release + add
        for (int gt : new int[]{1, 2}) {
            Map<String, BigDecimal> r = BucketMath.computeMonthNewV2(gt, yearTarget, rhythmW, release);
            assertEquals(0, new BigDecimal("83333").compareTo(r.get("add").setScale(0, java.math.RoundingMode.HALF_UP)));
            assertEquals(0, release.compareTo(r.get("release")));
            assertEquals(0, new BigDecimal("583333").compareTo(r.get("month_new").setScale(0, java.math.RoundingMode.HALF_UP)));
        }
        // 非「目标」类 (3/4): month_new = add (不回补)
        for (int gt : new int[]{3, 4}) {
            Map<String, BigDecimal> r = BucketMath.computeMonthNewV2(gt, yearTarget, rhythmW, release);
            assertEquals(0, release.compareTo(r.get("release")));
            assertEquals(0, new BigDecimal("83333").compareTo(r.get("month_new").setScale(0, java.math.RoundingMode.HALF_UP)));
        }
    }

    // ================================================================
    // 4. applyTermSplit — 绝对到期月落桶 + orig 只进不出
    // ================================================================
    @Test
    public void testApplyTermSplitAbsoluteDueMonth() {
        Map<String, BigDecimal> state = newState();
        List<Map<String, Object>> ratios = new ArrayList<>();
        ratios.add(ratio(3,  "50.0", "3.5"));
        ratios.add(ratio(12, "30.0", "4.2"));
        ratios.add(ratio(36, "20.0", "4.8"));

        Map<Integer, BigDecimal> plan = new LinkedHashMap<>();
        // 第 1 月投放 1,000,000
        List<Map<String, BigDecimal>> adds =
                BucketMath.applyTermSplit(state, MILLION, ratios, 1, plan);

        assertEquals(3, adds.size());
        assertEquals(0, new BigDecimal("500000").compareTo(adds.get(0).get("amount")));
        assertEquals(0, new BigDecimal("300000").compareTo(adds.get(1).get("amount")));
        assertEquals(0, new BigDecimal("200000").compareTo(adds.get(2).get("amount")));

        // orig_* 只进不出, 按原始期限
        assertEquals(0, new BigDecimal("500000").compareTo(state.get("orig_m3")));
        assertEquals(0, new BigDecimal("300000").compareTo(state.get("orig_m12")));
        assertEquals(0, new BigDecimal("200000").compareTo(state.get("orig_m36")));

        // rem_* 绝对到期月: 第 1 月投期限 3 → rem_m4 (1+3), 期限 12 → rem_m13, 期限 36 → rem_m37
        assertEquals(0, new BigDecimal("500000").compareTo(state.get("rem_m4")));
        assertEquals(0, new BigDecimal("300000").compareTo(state.get("rem_m13")));
        assertEquals(0, new BigDecimal("200000").compareTo(state.get("rem_m37")));
        // 关键: rem_m3 不应有值 (那才是「相对期限」口径的错误落点)
        assertEquals(0, BigDecimal.ZERO.compareTo(state.getOrDefault("rem_m3", BigDecimal.ZERO)));

        // full_plan 排期
        assertEquals(3, plan.size());
        assertEquals(0, new BigDecimal("500000").compareTo(plan.get(4)));
        assertEquals(0, new BigDecimal("300000").compareTo(plan.get(13)));
        assertEquals(0, new BigDecimal("200000").compareTo(plan.get(37)));
    }

    // ================================================================
    // 5. takeDue / peekDue — 对位 Python
    // ================================================================
    @Test
    public void testTakeDuePeekDue() {
        Map<String, BigDecimal> state = newState();
        state.put("rem_m4", new BigDecimal("500000"));

        assertEquals(0, new BigDecimal("500000").compareTo(BucketMath.peekDue(state, 4)));
        assertEquals(0, new BigDecimal("500000").compareTo(BucketMath.takeDue(state, 4)));
        // take 后清零
        assertEquals(0, BigDecimal.ZERO.compareTo(BucketMath.peekDue(state, 4)));
    }

    // ================================================================
    // 6. dueKey / dueStateKey — 对位 Python _due_key / _due_state_key
    // ================================================================
    @Test
    public void testDueKey() {
        assertEquals("m1",   BucketMath.dueKey(1));
        assertEquals("m60",  BucketMath.dueKey(60));
        assertEquals("y10",  BucketMath.dueKey(61));
        assertEquals("y10",  BucketMath.dueKey(120));
        assertEquals("y15",  BucketMath.dueKey(121));
        assertEquals("y15",  BucketMath.dueKey(180));
        assertEquals("y20",  BucketMath.dueKey(181));
        assertEquals("y20",  BucketMath.dueKey(240));
        assertEquals("y30",  BucketMath.dueKey(241));
        assertEquals("rem_m37", BucketMath.dueStateKey(37));
    }

    // ================================================================
    // 7. accumulatePlan — 对位 Python accumulate_plan
    // ================================================================
    @Test
    public void testAccumulatePlan() {
        Map<String, BigDecimal> st = new LinkedHashMap<>();
        for (int i = 1; i <= 60; i++) {
            st.put("rem_m" + i, i <= 5 ? new BigDecimal(i * 100L) : BigDecimal.ZERO);
        }
        Map<Integer, BigDecimal> plan = new LinkedHashMap<>();
        BucketMath.accumulatePlan(plan, st, 10);
        assertEquals(5, plan.size());
        for (int i = 1; i <= 5; i++) {
            assertEquals(0, new BigDecimal(i * 100L).compareTo(plan.get(i)));
        }
    }

    // ================================================================
    // 8. computeMainMetrics — 对位 Python engine.py:997-1022 (利息率 /100)
    // ================================================================
    @Test
    public void testComputeMainMetricsInterestRateScaled() {
        Map<String, BigDecimal> state = new LinkedHashMap<>();
        state.put("current_balance", MILLION);
        state.put("avg_balance", MILLION);
        state.put("weighted_rate", new BigDecimal("0.045"));
        state.put("interest_amount", new BigDecimal("3750"));

        List<Map<String, BigDecimal>> additions = new ArrayList<>();
        additions.add(add("500000", "3.5"));
        additions.add(add("300000", "4.2"));
        additions.add(add("200000", "4.8"));

        BigDecimal monthNew = MILLION;
        BigDecimal add = new BigDecimal("80000");
        Map<String, BigDecimal> r = BucketMath.computeMainMetrics(state, additions, add, monthNew);

        // weighted_amount = Σ(amount × ir/100) = 500000×0.035 + 300000×0.042 + 200000×0.048
        //                = 17500 + 12600 + 9600 = 39700
        // ⚠️ 若不除 100 则是 3,970,000 (虚高 100 倍) —— 本测试锁定该口径
        // new_wr = (1e6 × 0.045 + 39700) / (1e6 + 1e6) = 0.04235
        assertEquals(0, new BigDecimal("0.04235").compareTo(r.get("new_weighted_rate")));
        // new_ab = 1e6 + 80000/2 = 1,040,000
        assertEquals(0, new BigDecimal("1040000").compareTo(r.get("new_avg_balance")));
        // new_cb = 1e6 + 80000 = 1,080,000
        assertEquals(0, new BigDecimal("1080000").compareTo(r.get("new_current_balance")));
        // new_ia = 1,040,000 × 0.04235 / 12 = 3670.3333...
        assertEquals(0, new BigDecimal("3670.333333").compareTo(
                r.get("new_interest_amount").setScale(6, java.math.RoundingMode.HALF_UP)));
    }

    // ================================================================
    // 9. addMonths 负数向下取整 (对齐 Python m // 12)
    // ================================================================
    @Test
    public void testAddMonthsNegative() {
        assertEquals(java.time.LocalDate.of(2024, 12, 31),
                BucketMath.addMonths(java.time.LocalDate.of(2025, 1, 31), -1));
        assertEquals(java.time.LocalDate.of(2024, 1, 15),
                BucketMath.addMonths(java.time.LocalDate.of(2025, 1, 15), -12));
        assertEquals(java.time.LocalDate.of(2026, 1, 31),
                BucketMath.addMonths(java.time.LocalDate.of(2025, 1, 31), 12));
    }

    // ================================================================
    // helpers
    // ================================================================
    private static Map<String, BigDecimal> newState() {
        Map<String, BigDecimal> s = new LinkedHashMap<>();
        for (String k : new String[]{"m1","m2","m3","m5","m10","m60","y10","y15","y20","y30"}) {
            s.put("orig_" + k, BigDecimal.ZERO);
            s.put("rem_"  + k, BigDecimal.ZERO);
        }
        s.put("current_balance", MILLION);
        s.put("avg_balance", MILLION);
        s.put("weighted_rate", new BigDecimal("0.045"));
        s.put("interest_amount", new BigDecimal("3750"));
        return s;
    }

    private static Map<String, Object> ratio(int term, String pct, String rate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("term_value", term);
        m.put("business_ratio", new BigDecimal(pct));
        m.put("interest_rate", new BigDecimal(rate));
        return m;
    }

    private static Map<String, BigDecimal> add(String amount, String rate) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put("term_value", BigDecimal.ZERO);
        m.put("amount", new BigDecimal(amount));
        m.put("interest_rate", new BigDecimal(rate));
        return m;
    }
}
