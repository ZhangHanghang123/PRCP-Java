package com.prcp.business.reversemt.controller;

import com.prcp.business.reversemt.service.ReverseMetricTableService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/reverse-metric-table")
@RequiredArgsConstructor
public class ReverseMetricTableController {

    private final ReverseMetricTableService service;

    @GetMapping("")
    public R<Map<String, Object>> query(@RequestParam(required = false, defaultValue = "2026") String schemeCode,
                                        @RequestParam(required = false) String metricCode,
                                        @RequestParam(required = false) String fromDate,
                                        @RequestParam(required = false) String toDate) {
        return R.ok(service.query(schemeCode, metricCode, fromDate, toDate));
    }

    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return R.ok(Map.of(
                "schemes", service.listSchemes(),
                "metrics", service.listMetrics()
        ));
    }
}