package com.prcp.business.rate;

import java.util.Arrays;
import java.util.List;

/**
 * 利率曲线核心常量（13 个期限点）
 * 对齐 Python routers/rate.py TERM_KEYS / TERM_NAMES / KEY_TERMS
 */
public final class RateKeys {

    /** 13 个期限点（短端 + 长端） */
    public static final List<String> TERM_KEYS = Arrays.asList(
        "d1", "d7", "m1", "m3", "m6",
        "y1", "y2", "y3", "y5",
        "y10", "y15", "y20", "y30"
    );

    /** 期限点中文名 */
    public static final String[][] TERM_NAMES_PAIRS = {
        {"d1", "1日"}, {"d7", "7日"},
        {"m1", "1M"}, {"m3", "3M"}, {"m6", "6M"},
        {"y1", "1Y"}, {"y2", "2Y"}, {"y3", "3Y"}, {"y5", "5Y"},
        {"y10", "10Y"}, {"y15", "15Y"}, {"y20", "20Y"}, {"y30", "30Y"}
    };

    /** 关键期限（前端高亮） */
    public static final List<String> KEY_TERMS = Arrays.asList("y1", "y5", "y10");

    /** 表列名后缀（rate_d1, rate_d7, ..., rate_y30） */
    public static final String COL_PREFIX = "rate_";

    public static String colOf(String term) {
        if (!TERM_KEYS.contains(term)) {
            throw new IllegalArgumentException("期限点非法: " + term + ", 应为 " + TERM_KEYS);
        }
        return COL_PREFIX + term;
    }

    public static String nameOf(String term) {
        for (String[] pair : TERM_NAMES_PAIRS) {
            if (pair[0].equals(term)) return pair[1];
        }
        return term;
    }

    private RateKeys() {}
}