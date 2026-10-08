package com.prcp.business.kpi.controller;

import com.prcp.business.kpi.entity.KpiDefinition;
import com.prcp.business.kpi.entity.KpiScheme;
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

    @GetMapping("/schemes/all")
    public R<List<Map<String, Object>>> allSchemes() {
        return kpiService.listAllKpiSchemes();
    }

    @PostMapping("/schemes")
    public R<?> createScheme(@Valid @RequestBody KpiScheme s) { return kpiService.createScheme(s); }

    @PutMapping("/schemes/{id}")
    public R<?> updateScheme(@PathVariable Long id, @Valid @RequestBody KpiScheme s) {
        return kpiService.updateScheme(id, s);
    }

    @DeleteMapping("/schemes/{id}")
    public R<?> deleteScheme(@PathVariable Long id) { return kpiService.deleteScheme(id); }

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
    public R<List<Map<String, Object>>> listValues(@RequestParam(required = false) Long scheme_id,
                                                     @RequestParam(required = false) Long kpi_id,
                                                     @RequestParam(required = false) String data_date) {
        return kpiService.listValues(scheme_id, kpi_id, data_date);
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
        // 含 segments 子表数据（对齐 Python list_score_rules）
        return kpiService.listScoreRulesWithSegments(scheme_id, kpi_id);
    }

    @PostMapping("/score-rules")
    public R<?> createScoreRule(@Valid @RequestBody Map<String, Object> body) {
        return kpiService.createScoreRule(parseRuleBody(body));
    }

    @PutMapping("/score-rules/{id}")
    public R<?> updateScoreRule(@PathVariable Long id, @Valid @RequestBody Map<String, Object> body) {
        return kpiService.updateScoreRule(id, parseRuleBody(body), body);
    }

    /** 把 body 解析成 KpiScoreRule + 额外 segments 列表（保持实体干净） */
    private static KpiScoreRule parseRuleBody(Map<String, Object> body) {
        KpiScoreRule r = new KpiScoreRule();
        r.setSchemeId(toLong(body.get("scheme_id")));
        r.setKpiId(toLong(body.get("kpi_id")));
        r.setRuleName(toStr(body.get("rule_name")));
        r.setCalcMethod(toStr(body.get("calc_method")));
        r.setTotalScore(toBigDecimal(body.get("total_score")));
        r.setHigherIsBetter(toInt(body.get("higher_is_better")));
        r.setDescription(toStr(body.get("description")));
        r.setStatus(toStr(body.get("status")));
        return r;
    }
    private static Long toLong(Object o) { return o == null ? null : (o instanceof Number ? ((Number) o).longValue() : Long.parseLong(o.toString())); }
    private static Integer toInt(Object o) { return o == null ? null : (o instanceof Number ? ((Number) o).intValue() : Integer.parseInt(o.toString())); }
    private static String toStr(Object o) { return o == null ? null : o.toString(); }
    private static java.math.BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof java.math.BigDecimal) return (java.math.BigDecimal) o;
        if (o instanceof Number) return new java.math.BigDecimal(o.toString());
        return new java.math.BigDecimal(o.toString().trim());
    }

    @DeleteMapping("/score-rules/{id}")
    public R<?> deleteScoreRule(@PathVariable Long id) { return kpiService.deleteScoreRule(id); }

    /**
     * 按规则 + 指标值算分（区间段匹配）
     * 对齐 Python routers/kpi.py score_calc
     * 请求：{rule_id, value} → {matched, value, score, matched_range, higher_is_better}
     */
    @PostMapping("/score-calc")
    public R<Map<String, Object>> scoreCalc(@RequestBody Map<String, Object> payload) {
        Object rid = payload.get("rule_id");
        Object val = payload.get("value");
        if (rid == null) throw com.prcp.common.exception.BizException.badRequest("缺少 rule_id");
        if (val == null) throw com.prcp.common.exception.BizException.badRequest("缺少 value");
        Long ruleId = rid instanceof Number ? ((Number) rid).longValue() : Long.parseLong(rid.toString());
        java.math.BigDecimal value = new java.math.BigDecimal(val.toString());
        return kpiService.scoreCalc(ruleId, value);
    }

    // ============ 辅助：报表表项（供公式引用） ============
    @GetMapping("/rpt-items")
    public R<List<Map<String, Object>>> listRptItems(@RequestParam("rpt_id") Long rptId) {
        return kpiService.listRptItems(rptId);
    }
}
