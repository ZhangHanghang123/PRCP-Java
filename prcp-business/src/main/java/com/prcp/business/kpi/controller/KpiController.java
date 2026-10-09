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

/**
 * <p>KPI 管理 Controller (方案 + 定义 + 值 + 评分规则)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 银行 KPI 指标的完整生命周期管理: 方案 → 定义 → 取值 → 评分规则 → 实际算分</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>Scheme CRUD: GET/POST/PUT/DELETE /kpi/schemes</li>
 *       <li>Definition CRUD: GET/POST/PUT/DELETE /kpi/definitions</li>
 *       <li>Value CRUD: GET/POST/DELETE /kpi/values</li>
 *       <li>ScoreRule CRUD: GET/POST/PUT/DELETE /kpi/score-rules (含 segments 子表)</li>
 *       <li>POST /kpi/score-calc — 按规则算分</li>
 *       <li>POST /kpi/recalc — 重算分数</li>
 *       <li>GET /kpi/rpt-items — 报表表项 (供公式引用)</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: KpiService、KpiScheme/KpiDefinition/KpiValue/KpiScoreRule 实体</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /kpi}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.kpi.service.KpiService
 */
@RestController
@RequestMapping("/kpi")
@RequiredArgsConstructor
public class KpiController {

    private final KpiService kpiService;

    // ============ 方案 ============
    /**
     * <p>KPI 方案列表 (仅 ACTIVE)</p>
     *
     * <pre>
     * GET /kpi/schemes
     * Response: R.ok(List&lt;Map&gt;) KPI 方案列表
     * </pre>
     *
     * @return R.ok(KPI 方案列表)
     */
    @GetMapping("/schemes")
    public R<List<Map<String, Object>>> schemes() {
        return kpiService.listKpiSchemes();
    }

    /**
     * <p>KPI 方案列表 (含 INACTIVE, 供维护)</p>
     *
     * <pre>
     * GET /kpi/schemes/all
     * Response: R.ok(List&lt;Map&gt;) 全部 KPI 方案
     * </pre>
     *
     * @return R.ok(全部 KPI 方案)
     */
    @GetMapping("/schemes/all")
    public R<List<Map<String, Object>>> allSchemes() {
        return kpiService.listAllKpiSchemes();
    }

    /**
     * <p>新建 KPI 方案</p>
     *
     * <pre>
     * POST /kpi/schemes
     * Body: KpiScheme (scheme_code, scheme_name, status, ...)
     *
     * Response: R.ok(新建方案)
     * </pre>
     *
     * @param s KPI 方案实体
     * @return R.ok(新建方案)
     */
    @PostMapping("/schemes")
    public R<?> createScheme(@Valid @RequestBody KpiScheme s) { return kpiService.createScheme(s); }

    /**
     * <p>更新 KPI 方案</p>
     *
     * <pre>
     * PUT /kpi/schemes/{id}
     * Path: id (Long, required) - 方案 ID
     * Body: KpiScheme (更新字段)
     *
     * Response: R.ok(更新后方案)
     * </pre>
     *
     * @param id 方案 ID
     * @param s  KPI 方案实体
     * @return R.ok(更新后方案)
     */
    @PutMapping("/schemes/{id}")
    public R<?> updateScheme(@PathVariable Long id, @Valid @RequestBody KpiScheme s) {
        return kpiService.updateScheme(id, s);
    }

    /**
     * <p>软删 KPI 方案</p>
     *
     * <pre>
     * DELETE /kpi/schemes/{id}
     * Path: id (Long, required) - 方案 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 方案 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/schemes/{id}")
    public R<?> deleteScheme(@PathVariable Long id) { return kpiService.deleteScheme(id); }

    // ============ KPI 定义 ============
    /**
     * <p>KPI 定义列表 (按方案/编码/关键字过滤)</p>
     *
     * <pre>
     * GET /kpi/definitions
     * Query: scheme_id (Long, optional) - 方案 ID
     *        kpi_code  (String, optional) - KPI 编码
     *        keyword   (String, optional) - 模糊搜索
     *
     * Response: R.ok(List&lt;Map&gt;) KPI 定义列表
     * </pre>
     *
     * @param scheme_id 方案 ID (可选)
     * @param kpi_code  KPI 编码 (可选)
     * @param keyword   模糊搜索关键字 (可选)
     * @return R.ok(KPI 定义列表)
     */
    @GetMapping("/definitions")
    public R<List<Map<String, Object>>> listDefs(@RequestParam(required = false) Long scheme_id,
                                                  @RequestParam(required = false) String kpi_code,
                                                  @RequestParam(required = false) String keyword) {
        return kpiService.listDefs(scheme_id, kpi_code, keyword);
    }

    /**
     * <p>新建 KPI 定义</p>
     *
     * <pre>
     * POST /kpi/definitions
     * Body: KpiDefinition (kpi_code, kpi_name, unit, formula, ...)
     *
     * Response: R.ok(新建定义)
     * </pre>
     *
     * @param d KPI 定义实体
     * @return R.ok(新建定义)
     */
    @PostMapping("/definitions")
    public R<?> createDef(@Valid @RequestBody KpiDefinition d) { return kpiService.createDef(d); }

    /**
     * <p>更新 KPI 定义</p>
     *
     * <pre>
     * PUT /kpi/definitions/{id}
     * Path: id (Long, required) - 定义 ID
     * Body: KpiDefinition (更新字段)
     *
     * Response: R.ok(更新后定义)
     * </pre>
     *
     * @param id 定义 ID
     * @param d  KPI 定义实体
     * @return R.ok(更新后定义)
     */
    @PutMapping("/definitions/{id}")
    public R<?> updateDef(@PathVariable Long id, @Valid @RequestBody KpiDefinition d) {
        return kpiService.updateDef(id, d);
    }

    /**
     * <p>软删 KPI 定义</p>
     *
     * <pre>
     * DELETE /kpi/definitions/{id}
     * Path: id (Long, required) - 定义 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 定义 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/definitions/{id}")
    public R<?> deleteDef(@PathVariable Long id) { return kpiService.deleteDef(id); }

    // ============ KPI 值 ============
    /**
     * <p>KPI 值列表</p>
     *
     * <pre>
     * GET /kpi/values
     * Query: scheme_id (Long, optional) - 方案 ID
     *        kpi_id    (Long, optional) - KPI 定义 ID
     *        data_date (yyyy-MM-dd, optional) - 数据日期
     *
     * Response: R.ok(List&lt;Map&gt;) KPI 值列表
     * </pre>
     *
     * @param scheme_id 方案 ID (可选)
     * @param kpi_id    KPI 定义 ID (可选)
     * @param data_date 数据日期 yyyy-MM-dd (可选)
     * @return R.ok(KPI 值列表)
     */
    @GetMapping("/values")
    public R<List<Map<String, Object>>> listValues(@RequestParam(required = false) Long scheme_id,
                                                     @RequestParam(required = false) Long kpi_id,
                                                     @RequestParam(required = false) String data_date) {
        return kpiService.listValues(scheme_id, kpi_id, data_date);
    }

    /**
     * <p>新增 KPI 值</p>
     *
     * <pre>
     * POST /kpi/values
     * Body: KpiValue (scheme_id, kpi_id, data_date, value, score)
     *
     * Response: R.ok(新建值)
     * </pre>
     *
     * @param v KPI 值实体
     * @return R.ok(新建值)
     */
    @PostMapping("/values")
    public R<?> createValue(@Valid @RequestBody KpiValue v) { return kpiService.createValue(v); }

    /**
     * <p>软删 KPI 值</p>
     *
     * <pre>
     * DELETE /kpi/values/{id}
     * Path: id (Long, required) - 值 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 值 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/values/{id}")
    public R<?> deleteValue(@PathVariable Long id) { return kpiService.deleteValue(id); }

    /**
     * <p>重算 KPI 分数 (基于已有值 + 评分规则)</p>
     *
     * <pre>
     * POST /kpi/recalc
     * Query: kpi_id    (Long, required) - KPI 定义 ID
     *        data_date (yyyy-MM-dd, required) - 数据日期
     *
     * Response: R.ok({recalculated: N})
     * </pre>
     *
     * @param kpi_id    KPI 定义 ID
     * @param data_date 数据日期 yyyy-MM-dd
     * @return R.ok({recalculated: N})
     */
    @PostMapping("/recalc")
    public R<?> recalc(@RequestParam Long kpi_id, @RequestParam String data_date) {
        return kpiService.recalcScore(kpi_id, data_date);
    }

    // ============ 评分规则 ============
    /**
     * <p>评分规则列表 (含 segments 子表)</p>
     *
     * <pre>
     * GET /kpi/score-rules
     * Query: scheme_id (Long, optional) - 方案 ID
     *        kpi_id    (Long, optional) - KPI 定义 ID
     *
     * Response: R.ok(List&lt;Map&gt;) 评分规则列表 (含 segments 区间段)
     * </pre>
     *
     * @param scheme_id 方案 ID (可选)
     * @param kpi_id    KPI 定义 ID (可选)
     * @return R.ok(评分规则列表)
     */
    @GetMapping("/score-rules")
    public R<List<Map<String, Object>>> listScoreRules(@RequestParam(required = false) Long scheme_id,
                                                         @RequestParam(required = false) Long kpi_id) {
        // 含 segments 子表数据（对齐 Python list_score_rules）
        return kpiService.listScoreRulesWithSegments(scheme_id, kpi_id);
    }

    /**
     * <p>新建评分规则</p>
     *
     * <pre>
     * POST /kpi/score-rules
     * Body: {scheme_id, kpi_id, rule_name, calc_method, total_score, segments: [{min, max, score}, ...]}
     *
     * Response: R.ok(新建规则)
     * </pre>
     *
     * @param body 含 segments 子表的规则数据
     * @return R.ok(新建规则)
     */
    @PostMapping("/score-rules")
    public R<?> createScoreRule(@Valid @RequestBody Map<String, Object> body) {
        return kpiService.createScoreRule(parseRuleBody(body));
    }

    /**
     * <p>更新评分规则 (含 segments 子表)</p>
     *
     * <pre>
     * PUT /kpi/score-rules/{id}
     * Path: id (Long, required) - 规则 ID
     * Body: {rule_name, calc_method, total_score, segments}
     *
     * Response: R.ok(更新后规则)
     * </pre>
     *
     * @param id   规则 ID
     * @param body 更新内容 (含 segments)
     * @return R.ok(更新后规则)
     */
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

    /**
     * <p>软删评分规则</p>
     *
     * <pre>
     * DELETE /kpi/score-rules/{id}
     * Path: id (Long, required) - 规则 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 规则 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/score-rules/{id}")
    public R<?> deleteScoreRule(@PathVariable Long id) { return kpiService.deleteScoreRule(id); }

    /**
     * <p>按规则 + 指标值算分 (区间段匹配)</p>
     *
     * <pre>
     * POST /kpi/score-calc
     * Body: {rule_id: Long, value: BigDecimal}
     *
     * Response: R.ok({matched, value, score, matched_range, higher_is_better})
     * </pre>
     *
     * @param payload 含 rule_id 和 value
     * @return R.ok({matched, value, score, matched_range, higher_is_better})
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
    /**
     * <p>报表表项列表 (供 KPI 公式引用, 形如 {@code =报表项目.资产总计})</p>
     *
     * <pre>
     * GET /kpi/rpt-items
     * Query: rpt_id (Long, required) - 报表 ID
     *
     * Response: R.ok(List&lt;Map&gt;) 报表表项列表 (item_code, item_name, value)
     * </pre>
     *
     * @param rptId 报表 ID
     * @return R.ok(报表表项列表)
     */
    @GetMapping("/rpt-items")
    public R<List<Map<String, Object>>> listRptItems(@RequestParam("rpt_id") Long rptId) {
        return kpiService.listRptItems(rptId);
    }
}
