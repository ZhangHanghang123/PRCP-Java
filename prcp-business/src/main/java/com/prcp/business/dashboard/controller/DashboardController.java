package com.prcp.business.dashboard.controller;

import com.prcp.business.dashboard.service.DashboardService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/overview")
    public R<Map<String, Object>> overview() { return dashboardService.overview(); }

    @GetMapping("/kpi-trend")
    public R<Map<String, Object>> kpiTrend(@RequestParam(defaultValue = "14") int days) {
        return dashboardService.kpiTrend(days);
    }

    @GetMapping("/scheme-distribution")
    public R<Map<String, Object>> schemeDistribution() { return dashboardService.schemeDistribution(); }

    @GetMapping("/top-kpis")
    public R<Map<String, Object>> topKpis() { return dashboardService.topKpis(); }
}