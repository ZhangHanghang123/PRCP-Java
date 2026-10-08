package com.prcp.business.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 共享期限桶定义（v3 改造 2026-09-18）
 *
 * 新结构（64 桶）：
 *   5 年内按月      60 桶：m1, m2, ..., m60
 *   长端固定桶      4 桶：y10, y15, y20, y30
 *
 * 与 Python 版 backend/app/services/buckets.py 完全对齐
 *   - KEYS / NAMES / ORIG_COLS / REM_COLS
 *   - YEAR1_KEYS..YEAR5_KEYS / LONG_KEYS 分组常量
 *   - origColsSql / remColsSql / allColsSql 拼接 SELECT 列片段
 *
 * 变更点：
 *   - 5 年内全部按月拆分（m1~m60）
 *   - 删除 y1（因 m12 = 12 月 = 1Y）
 *   - 长端固定 y10/y15/y20/y30 不变
 */
public final class BasicDataBuckets {

    private BasicDataBuckets() {}

    /** 64 桶 key 列表：m1, m2, ..., m60, y10, y15, y20, y30 */
    public static List<String> keys() {
        List<String> k = new ArrayList<>(64);
        for (int i = 1; i <= 60; i++) k.add("m" + i);
        k.add("y10"); k.add("y15"); k.add("y20"); k.add("y30");
        return Collections.unmodifiableList(k);
    }

    /** 64 桶 key 不可变列表（与 keys() 等价，常量形式） */
    public static final List<String> KEYS = Collections.unmodifiableList(keys());

    /** 显示名映射：m1 -> "1M", m12 -> "12M", y10 -> "10Y" */
    public static final Map<String, String> NAMES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : keys()) {
            m.put(k, k.startsWith("m") ? k.substring(1) + "M" : k.substring(1) + "Y");
        }
        NAMES = Collections.unmodifiableMap(m);
    }

    /** 64 个原始期限列：orig_m1, orig_m2, ..., orig_y30 */
    public static final List<String> ORIG_COLS;
    static {
        List<String> c = new ArrayList<>(64);
        for (String k : keys()) c.add("orig_" + k);
        ORIG_COLS = Collections.unmodifiableList(c);
    }

    /** 64 个剩余期限列：rem_m1, rem_m2, ..., rem_y30 */
    public static final List<String> REM_COLS;
    static {
        List<String> c = new ArrayList<>(64);
        for (String k : keys()) c.add("rem_" + k);
        REM_COLS = Collections.unmodifiableList(c);
    }

    /** 全 128 个桶列（orig + rem） */
    public static final List<String> ALL_BUCKET_COLS;
    static {
        List<String> c = new ArrayList<>(128);
        c.addAll(ORIG_COLS);
        c.addAll(REM_COLS);
        ALL_BUCKET_COLS = Collections.unmodifiableList(c);
    }

    /** 桶分组常量（用于按年聚合 / 布局分组） */
    public static final Set<String> YEAR1_KEYS = Collections.unmodifiableSet(rangeMonths(1, 13));
    public static final Set<String> YEAR2_KEYS = Collections.unmodifiableSet(rangeMonths(13, 25));
    public static final Set<String> YEAR3_KEYS = Collections.unmodifiableSet(rangeMonths(25, 37));
    public static final Set<String> YEAR4_KEYS = Collections.unmodifiableSet(rangeMonths(37, 49));
    public static final Set<String> YEAR5_KEYS = Collections.unmodifiableSet(rangeMonths(49, 61));
    public static final Set<String> LONG_KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("y10", "y15", "y20", "y30")));

    private static Set<String> rangeMonths(int fromInclusive, int toExclusive) {
        Set<String> s = new LinkedHashSet<>();
        for (int n = fromInclusive; n < toExclusive; n++) s.add("m" + n);
        return s;
    }

    /** 8 个代表桶（前端二级表头 / 列表 / 简略视图） */
    public static final String[] DISPLAY_BUCKETS = { "m1", "m3", "m6", "m12", "y10", "y15", "y20", "y30" };

    /** bucket key → 列名：m1 → orig_m1, rem_m1；y10 → orig_y10, rem_y10 */
    public static String toOrigCol(String k) { return "orig_" + k; }
    public static String toRemCol(String k)  { return "rem_"  + k; }
    public static String toOrigAlias(String k) { return "orig" + camel(k); }
    public static String toRemAlias(String k)  { return "rem"  + camel(k); }

    /**
     * 生成 SELECT 中 orig_* 列片段，可带表别名前缀（如 "d."）
     *   cols = origColsSql("d.")  →  "d.orig_m1, d.orig_m2, ..., d.orig_y30"
     */
    public static String origColsSql(String prefix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ORIG_COLS.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(prefix).append(ORIG_COLS.get(i));
        }
        return sb.toString();
    }

    /** 生成 SELECT 中 rem_* 列片段 */
    public static String remColsSql(String prefix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < REM_COLS.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(prefix).append(REM_COLS.get(i));
        }
        return sb.toString();
    }

    /** 生成 SELECT 中 orig_* + rem_* 列片段（用 ", " 拼接两个分组） */
    public static String allColsSql(String prefix) {
        return origColsSql(prefix) + ", " + remColsSql(prefix);
    }

    /** 拼接 SELECT 全部元数据 + 64 桶（驼峰别名）+ 7 度量（用表别名 d.） */
    public static String buildSelectColumns() {
        StringBuilder sb = new StringBuilder();
        sb.append("d.id, d.data_date AS dataDate, d.coa_node_id AS coaNodeId,")
          .append(" d.node_code AS nodeCode, d.node_name AS nodeName,")
          .append(" d.node_level AS nodeLevel, d.parent_code AS parentCode,")
          .append(" d.is_leaf AS isLeaf, d.category,")
          .append(" d.date_offset AS dateOffset, d.offset_unit AS offsetUnit,")
          .append(" d.asf_rsf AS asfRsf, d.hqla_factor AS hqlaFactor,")
          .append(" d.current_balance AS currentBalance, d.avg_balance AS avgBalance,")
          .append(" d.weighted_rate AS weightedRate, d.interest_amount AS interestAmount,")
          .append(" d.risk_weight AS riskWeight, d.calc_note AS calcNote");
        for (String k : keys()) {
            sb.append(", d.orig_").append(k).append(" AS orig").append(camel(k))
              .append(", d.rem_").append(k).append(" AS rem").append(camel(k));
        }
        return sb.toString();
    }

    /** 拼接 SELECT 元数据 + 8 代表桶（用表别名 d.） */
    public static String buildMatrixSelectColumns() {
        StringBuilder sb = new StringBuilder();
        sb.append("d.coa_node_id AS coaNodeId, n.node_code AS nodeCode, n.node_name AS nodeName,")
          .append(" CAST(d.data_date AS CHAR) AS dataDate, d.category, d.node_level AS nodeLevel,")
          .append(" d.date_offset AS dateOffset, d.offset_unit AS offsetUnit");
        for (String b : DISPLAY_BUCKETS) {
            sb.append(", d.orig_").append(b).append(" AS orig").append(camel(b))
              .append(", d.rem_").append(b).append(" AS rem").append(camel(b));
        }
        return sb.toString();
    }

    private static String camel(String k) {
        return k.substring(0, 1).toUpperCase() + k.substring(1);
    }
}