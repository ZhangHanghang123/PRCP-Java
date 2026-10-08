package com.prcp.business.esg.controller;

import com.prcp.business.esg.service.EsgCurveService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ESG Svensson 曲线 6 端点
 * 对齐 Python routers/esg.py curves 相关端点
 */
@RestController
@RequestMapping("/esg/curves")
@RequiredArgsConstructor
public class EsgCurveController {

    private final EsgCurveService curveService;

    @GetMapping
    public R<Map<String, Object>> listCurves(@RequestParam(required = false) String source,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "50") int pageSize) {
        return curveService.listCurves(source, startDate, endDate, page, Math.min(pageSize, 200));
    }

    @GetMapping("/sources")
    public R<Map<String, Object>> curveSources() {
        return curveService.curveSources();
    }

    /** 单日曲线：GET /curves/{curve_date}?source=ECB */
    @GetMapping("/{curveDate}")
    public R<Map<String, Object>> getCurve(@PathVariable("curveDate") String curveDate,
                                             @RequestParam(required = false) String source) {
        return curveService.getCurve(curveDate, source);
    }

    /** upsert 单条：POST /curves */
    @PostMapping
    public R<Map<String, Object>> upsertCurve(@RequestBody Map<String, Object> body) {
        return curveService.upsertCurve(body);
    }

    /** 批量 upsert：POST /curves/bulk */
    @PostMapping("/bulk")
    public R<Map<String, Object>> bulkUpsert(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) body.get("points");
        return curveService.bulkUpsert(points);
    }

    /** 单日还原利率：POST /curves/{curve_date}/rates */
    @PostMapping("/{curveDate}/rates")
    public R<Map<String, Object>> svenssonRates(@PathVariable("curveDate") String curveDate,
                                                  @RequestBody Map<String, Object> body) {
        return curveService.svenssonRates(curveDate, body);
    }
}