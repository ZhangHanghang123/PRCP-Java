package com.prcp.business.reversemt.service;

import com.prcp.business.reversemt.mapper.ReverseMetricTableMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReverseMetricTableService {

    private final ReverseMetricTableMapper mapper;

    /**
     * 反算指标结果表：把行转成 行 = (scheme_code, node_code, metric_code, metric_label, node_name)，
     * 列 = 12 个月度数据日期，单元 = current_value
     * 同时附上 y1/y2/y3/y4/y5 → 5 个指标（currentValue + 未来 5 年）的透视结果
     */
    public Map<String, Object> query(String schemeCode, String metricCode, String fromDate, String toDate) {
        List<Map<String, Object>> raw = mapper.queryTable(schemeCode, metricCode, fromDate, toDate);
        // 收集日期
        List<String> dates = raw.stream()
                .map(r -> String.valueOf(r.get("dataDate")))
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        // 收集关键列（横轴 = 指标×期数 = 5 期 + current 共 6 期，纵轴 = scheme|node|metric|日期）
        // 透视：行 = (schemeCode|nodeCode|nodeName|metricCode|metricLabel|unit)，列 = period(date), period_y1, period_y2, period_y3, period_y4, period_y5
        // 每行的"单元格"是该 (node,metric,date) 的 current / y1 / y2 / y3 / y4 / y5
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Object>> rowMap = new HashMap<>();
        for (Map<String, Object> r : raw) {
            String key = String.format("%s|%s|%s|%s|%s",
                    r.get("schemeCode"), r.get("nodeCode"), r.get("metricCode"),
                    r.get("nodeName"), r.get("metricLabel"));
            Map<String, Object> row = rowMap.computeIfAbsent(key, k -> {
                Map<String, Object> nr = new HashMap<>();
                nr.put("schemeCode", r.get("schemeCode"));
                nr.put("nodeCode", r.get("nodeCode"));
                nr.put("nodeName", r.get("nodeName"));
                nr.put("metricCode", r.get("metricCode"));
                nr.put("metricLabel", r.get("metricLabel"));
                nr.put("unit", r.get("unit"));
                // date0~date11 六个列（current+y1~y5）
                nr.put("cells", new ArrayList<>());
                return nr;
            });
            // 找到 cells 中对应日期的列
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cells = (List<Map<String, Object>>) row.get("cells");
            String dateKey = String.valueOf(r.get("dataDate"));
            // 一次只插入一个日期的所有 6 个值（current+y1..y5）
            Map<String, Object> cell = new HashMap<>();
            cell.put("dataDate", r.get("dataDate"));
            cell.put("currentValue", r.get("currentValue"));
            cell.put("y1Value", r.get("y1Value"));
            cell.put("y2Value", r.get("y2Value"));
            cell.put("y3Value", r.get("y3Value"));
            cell.put("y4Value", r.get("y4Value"));
            cell.put("y5Value", r.get("y5Value"));
            // 同日期仅保留第一个
            boolean exists = cells.stream().anyMatch(c -> String.valueOf(c.get("dataDate")).equals(dateKey));
            if (!exists) cells.add(cell);
        }
        // 排序 cells 按 dataDate
        for (Map<String, Object> row : rowMap.values()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cells = (List<Map<String, Object>>) row.get("cells");
            cells.sort((a, b) -> String.valueOf(a.get("dataDate")).compareTo(String.valueOf(b.get("dataDate"))));
            rows.add(row);
        }
        // 行排序
        rows.sort((a, b) -> {
            int r = String.valueOf(a.get("schemeCode")).compareTo(String.valueOf(b.get("schemeCode")));
            if (r != 0) return r;
            r = String.valueOf(a.get("metricCode")).compareTo(String.valueOf(b.get("metricCode")));
            if (r != 0) return r;
            return String.valueOf(a.get("nodeCode")).compareTo(String.valueOf(b.get("nodeCode")));
        });

        Map<String, Object> out = new HashMap<>();
        out.put("dates", dates);
        out.put("rows", rows);
        out.put("totalRows", rows.size());
        return out;
    }

    public List<String> listSchemes() {
        return mapper.listSchemes();
    }

    public List<Map<String, Object>> listMetrics() {
        return mapper.listMetrics();
    }
}