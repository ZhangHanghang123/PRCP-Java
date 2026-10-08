package com.prcp.business.reverse.dashboard;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 反算 Dashboard API — 4 端点（对位 Python /reverse-dashboard prefix）
 *
 * @author WorkBuddy Agent
 * @date 2026-09-27
 */
@RestController
@RequestMapping("/reverse-dashboard")
@RequiredArgsConstructor
public class ReverseDashboardController {

    private final ReverseDashboardService svc;

    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return svc.options();
    }

    @GetMapping("/runs")
    public R<Map<String, Object>> runs(@RequestParam("scheme_code") String schemeCode) {
        return svc.runs(schemeCode);
    }

    @GetMapping("/dates")
    public R<Map<String, Object>> dates(@RequestParam("scheme_code") String schemeCode,
                                         @RequestParam("run_id") Long runId) {
        return svc.dates(schemeCode, runId);
    }

    @GetMapping("/snapshot")
    public R<Map<String, Object>> snapshot(@RequestParam(value = "scheme_code", required = false) String schemeCode,
                                           @RequestParam(value = "run_id", required = false) Long runId,
                                           @RequestParam(value = "date_offset", required = false) Integer dateOffset) {
        return svc.snapshot(schemeCode, runId, dateOffset);
    }
}