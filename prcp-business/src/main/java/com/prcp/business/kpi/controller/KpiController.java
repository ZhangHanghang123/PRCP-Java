package com.prcp.business.kpi.controller;

import com.prcp.business.kpi.entity.KpiDefinition;
import com.prcp.business.kpi.entity.KpiScoreRule;
import com.prcp.business.kpi.entity.KpiValue;
import com.prcp.business.kpi.service.KpiService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/kpi")
@RequiredArgsConstructor
public class KpiController {

    private final KpiService kpiService;

    // ============ 方案 ============
    @GetMapping("/schemes")
    public R<List<Map<String, Object>>> schemes() {
        return kpiService.listKpiSchemes();
    }

    // ============ KPI 定义 ============
    @GetMapping("/definitions")
    public R<List<Map<String, Object>>> listDefs(@RequestParam(required = false) Long scheme_id,
                                                  @RequestParam(required = false) String kpi_code,
                                                  @RequestParam(required = false) String keyword) {
        return kpiService.listDefs(scheme_id, kpi_code, keyword);
    }

    @PostMapping("/definitions")
    public R<?> createDef(@Valid @RequestBody KpiDefinition d) { return kpiService.createDef(d); }

    @PutMapping("/definitions/{id}")
    public R<?> updateDef(@PathVariable Long id, @Valid @RequestBody KpiDefinition d) {
        return kpiService.updateDef(id, d);
    }

    @DeleteMapping("/definitions/{id}")
    public R<?> deleteDef(@PathVariable Long id) { return kpiService.deleteDef(id); }

    // ============ KPI 值 ============
    @GetMapping("/values")
    public R<List<Map<String, Object>>> listValues(@RequestParam(required = false) Long kpi_id,
                                                     @RequestParam(required = false) String data_date) {
        return kpiService.listValues(kpi_id, data_date);
    }

    @PostMapping("/values")
    public R<?> createValue(@Valid @RequestBody KpiValue v) { return kpiService.createValue(v); }

    @DeleteMapping("/values/{id}")
    public R<?> deleteValue(@PathVariable Long id) { return kpiService.deleteValue(id); }

    @PostMapping("/recalc")
    public R<?> recalc(@RequestParam Long kpi_id, @RequestParam String data_date) {
        return kpiService.recalcScore(kpi_id, data_date);
    }

    // ============ 评分规则 ============
    @GetMapping("/score-rules")
    public R<List<Map<String, Object>>> listScoreRules(@RequestParam(required = false) Long scheme_id,
                                                         @RequestParam(required = false) Long kpi_id) {
        return kpiService.listScoreRules(scheme_id, kpi_id);
    }

    @PostMapping("/score-rules")
    public R<?> createScoreRule(@Valid @RequestBody KpiScoreRule r) { return kpiService.createScoreRule(r); }

    @DeleteMapping("/score-rules/{id}")
    public R<?> deleteScoreRule(@PathVariable Long id) { return kpiService.deleteScoreRule(id); }

    // ============ 辅助：报表表项（供公式引用） ============
    @GetMapping("/rpt-items")
    public R<List<Map<String, Object>>> listRptItems(@RequestParam("rpt_id") Long rptId) {
        return kpiService.listRptItems(rptId);
    }
}