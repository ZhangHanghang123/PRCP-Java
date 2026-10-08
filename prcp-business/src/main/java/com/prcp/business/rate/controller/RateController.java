package com.prcp.business.rate.controller;

import com.prcp.business.rate.entity.RateScheme;
import com.prcp.business.rate.service.RateService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/rate")
@RequiredArgsConstructor
public class RateController {

    private final RateService rateService;

    // ============ 曲线方案 ============
    @GetMapping("/schemes")
    public R<Map<String, Object>> listSchemes(@RequestParam(required = false) String curveType,
                                               @RequestParam(required = false) String status) {
        return rateService.listSchemes(curveType, status);
    }

    @PostMapping("/schemes")
    public R<Map<String, Object>> createScheme(@RequestBody RateScheme s) {
        return rateService.createScheme(s);
    }

    @PutMapping("/schemes/{id}")
    public R<?> updateScheme(@PathVariable Long id, @RequestBody RateScheme s) {
        return rateService.updateScheme(id, s);
    }

    @DeleteMapping("/schemes/{id}")
    public R<?> deleteScheme(@PathVariable Long id) {
        return rateService.deleteScheme(id);
    }

    // ============ 利率点 ============
    @GetMapping("/points")
    public R<Map<String, Object>> listPoints(@RequestParam(required = false) String curveCode,
                                              @RequestParam(required = false) String dataDate) {
        return rateService.listPoints(curveCode, dataDate);
    }

    @PostMapping("/points")
    public R<Map<String, Object>> upsertPoint(@RequestBody Map<String, Object> body) {
        return rateService.upsertPoint(body);
    }

    @DeleteMapping("/points/{id}")
    public R<?> deletePoint(@PathVariable Long id) {
        return rateService.deletePoint(id);
    }

    // ============ 历史曲线对比 ============
    @GetMapping("/compare")
    public R<Map<String, Object>> compareCurves(@RequestParam("curve_code") String curveCode,
                                                 @RequestParam(required = false) String startDate,
                                                 @RequestParam(required = false) String endDate) {
        return rateService.compareCurves(curveCode, startDate, endDate);
    }

    // ============ 按期限点查单一利率 ============
    @GetMapping("/lookup")
    public R<Map<String, Object>> lookupRate(@RequestParam("curve_code") String curveCode,
                                              @RequestParam("data_date") String dataDate,
                                              @RequestParam String term) {
        return rateService.lookupRate(curveCode, dataDate, term);
    }

    // ============ Svensson 拟合（6 参数：beta0~3, tau1, tau2） ============
    @PostMapping("/svensson")
    public R<Map<String, Object>> fitSvensson(@RequestBody Map<String, Object> body) {
        return rateService.fitSvensson(body);
    }

    // ============ Nelson-Siegel 拟合（4 参数：beta0~3, tau） ============
    @PostMapping("/ns")
    public R<Map<String, Object>> fitNS(@RequestBody Map<String, Object> body) {
        return rateService.fitNS(body);
    }

    // ============ Nelson-Siegel-Svensson 拟合（6 参数，svensson 别名） ============
    @PostMapping("/nss")
    public R<Map<String, Object>> fitNSS(@RequestBody Map<String, Object> body) {
        return rateService.fitSvensson(body);
    }

    // ============ CSV 导出 ============
    @GetMapping("/export")
    public void exportRates(@RequestParam("curve_code") String curveCode,
                             @RequestParam(required = false) String startDate,
                             @RequestParam(required = false) String endDate,
                             javax.servlet.http.HttpServletResponse resp) throws java.io.IOException {
        rateService.exportRates(curveCode, startDate, endDate, resp);
    }
}