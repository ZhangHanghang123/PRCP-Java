package com.prcp.business.dashboard.service;

import com.prcp.business.dashboard.mapper.DashboardMapper;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardMapper dashboardMapper;

    /** 总览 KPI 卡片 */
    public R<Map<String, Object>> overview() {
        Map<String, Object> raw = dashboardMapper.overview();
        List<Map<String, Object>> kpi = new ArrayList<>();
        kpi.add(card("账户册方案", raw.get("schemes"), "个", "#C7000B"));
        kpi.add(card("账户册节点", raw.get("coa_nodes"), "个", "#A31A1F"));
        kpi.add(card("报表模板", raw.get("reports"), "个", "#E84E4E"));
        kpi.add(card("报表表项", raw.get("rpt_items"), "项", "#D71B1B"));
        kpi.add(card("指标方案", raw.get("kpi_schemes"), "个", "#C8102E"));
        kpi.add(card("指标定义", raw.get("kpi_defs"), "个", "#DC2626"));
        kpi.add(card("指标值", raw.get("kpi_values"), "条", "#991B1B"));
        kpi.add(card("评分规则", raw.get("score_rules"), "个", "#7F1D1D"));

        Object latest = raw.get("latest_date");
        String latestStr = null;
        if (latest instanceof java.sql.Date d) latestStr = d.toLocalDate().toString();
        else if (latest instanceof LocalDate d) latestStr = d.toString();

        Map<String, Object> out = new HashMap<>();
        out.put("kpi", kpi);
        out.put("latest_data_date", latestStr);
        return R.ok(out);
    }

    private Map<String, Object> card(String label, Object value, String unit, String color) {
        Map<String, Object> m = new HashMap<>();
        m.put("label", label);
        m.put("value", value == null ? 0 : value);
        m.put("unit", unit);
        m.put("color", color);
        return m;
    }

    public R<Map<String, Object>> kpiTrend(int days) {
        Map<String, Object> out = new HashMap<>();
        out.put("items", dashboardMapper.kpiTrend(days));
        return R.ok(out);
    }

    public R<Map<String, Object>> schemeDistribution() {
        Map<String, Object> out = new HashMap<>();
        out.put("items", dashboardMapper.schemeDistribution());
        return R.ok(out);
    }

    public R<Map<String, Object>> topKpis() {
        Map<String, Object> out = new HashMap<>();
        out.put("items", dashboardMapper.topKpis());
        return R.ok(out);
    }
}