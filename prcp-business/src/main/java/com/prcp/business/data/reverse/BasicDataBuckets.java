package com.prcp.business.data.reverse;

import java.util.ArrayList;
import java.util.List;

/**
 * 桶字段白名单（64 + 4 = 68）
 */
public class BasicDataBuckets {
    public static List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (int i = 1; i <= 60; i++) keys.add("m" + i);
        keys.add("y10"); keys.add("y15"); keys.add("y20"); keys.add("y30");
        return keys;
    }
}