package com.prcp.business.sim.controller;

import com.prcp.business.sim.entity.SimScheme;
import com.prcp.business.sim.service.SimService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 新业务模拟方案管理 API — 对齐 Python routers/sim.py
 * 模块前缀：/prcp-java/api/sim
 *
 * 引擎相关端点（run/runs/results）由 SimEngineController 提供。
 */
@RestController
@RequestMapping("/sim")
@RequiredArgsConstructor
public class SimController {

    private final SimService simService;

    // ============ 1. coa-schemes（账户册方案下拉） ============
    @GetMapping("/coa-schemes")
    public R<Map<String, Object>> listCoaSchemes() {
        return simService.listCoaSchemes();
    }

    // ============ 2. coa-tree（节点树） ============
    @GetMapping("/coa-tree")
    public R<Map<String, Object>> coaTree(@RequestParam("coa_scheme_id") Long coaSchemeId) {
        return simService.coaTree(coaSchemeId);
    }

    // ============ 3. node-info（节点基础信息 + 当前余额） ============
    @GetMapping("/node-info/{coaNodeId}")
    public R<Map<String, Object>> nodeInfo(@PathVariable Long coaNodeId) {
        return simService.nodeInfo(coaNodeId);
    }

    // ============ 4. schemes list ============
    @GetMapping("/schemes")
    public R<Map<String, Object>> listSchemes(@RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) Long coaSchemeId) {
        return simService.listSchemes(keyword, status, coaSchemeId);
    }

    // ============ 5. create scheme ============
    @PostMapping("/schemes")
    public R<Map<String, Object>> createScheme(@RequestBody Map<String, Object> body) {
        return simService.createScheme(parseScheme(body));
    }

    // ============ 6. update scheme ============
    @PutMapping("/schemes/{sid}")
    public R<Map<String, Object>> updateScheme(@PathVariable Long sid, @RequestBody Map<String, Object> body) {
        return simService.updateScheme(sid, parseScheme(body));
    }

    private SimScheme parseScheme(Map<String, Object> body) {
        SimScheme s = new SimScheme();
        s.setSchemeCode(str(body.get("scheme_code")));
        s.setSchemeName(str(body.get("scheme_name")));
        Object coaId = body.get("coa_scheme_id");
        if (coaId != null) s.setCoaSchemeId(((Number) coaId).longValue());
        Object dd = body.get("data_date");
        if (dd != null) s.setDataDate(LocalDate.parse(dd.toString(), DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        s.setDescription(str(body.get("description")));
        s.setStatus(str(body.get("status")));
        return s;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    // ============ 7. toggle status ============
    @PatchMapping("/schemes/{sid}/status")
    public R<Map<String, Object>> toggleStatus(@PathVariable Long sid, @RequestBody Map<String, String> body) {
        return simService.toggleStatus(sid, body == null ? null : body.get("status"));
    }

    // ============ 8. delete scheme ============
    @DeleteMapping("/schemes/{sid}")
    public R<Map<String, Object>> deleteScheme(@PathVariable Long sid) {
        return simService.deleteScheme(sid);
    }

    // ============ 9. get node-config ============
    @GetMapping("/node-config")
    public R<Map<String, Object>> getNodeConfig(@RequestParam("scheme_id") Long schemeId,
                                                @RequestParam("coa_node_id") Long coaNodeId) {
        return simService.getNodeConfig(schemeId, coaNodeId);
    }

    // ============ 10. save node-config ============
    @PostMapping("/node-config")
    public R<Map<String, Object>> saveNodeConfig(@RequestParam("scheme_id") Long schemeId,
                                                 @RequestBody Map<String, Object> body) {
        return simService.saveNodeConfig(schemeId, body);
    }

    // ============ 11. delete node-config ============
    @DeleteMapping("/node-config/{cfgId}")
    public R<Map<String, Object>> deleteNodeConfig(@PathVariable Long cfgId) {
        return simService.deleteNodeConfig(cfgId);
    }

    // ============ 12. /sim/tree（对齐 Python：账户册树别名） ============
    @GetMapping("/tree")
    public R<Map<String, Object>> simTree(@RequestParam("scheme_id") Long coaSchemeId) {
        return simService.coaTree(coaSchemeId);
    }

    // ============ 13. /sim/schemes/{id}/nodes/{nid}（节点配置详情） ============
    @GetMapping("/schemes/{sid}/nodes/{nid}")
    public R<Map<String, Object>> getSchemeNode(@PathVariable("sid") Long sid,
                                                 @PathVariable("nid") Long nid) {
        return simService.getNodeConfig(sid, nid);
    }

    // ============ 14. /sim/schemes/{id}/save（批量保存配置） ============
    @PostMapping("/schemes/{sid}/save")
    public R<Map<String, Object>> saveSchemeAll(@PathVariable("sid") Long sid,
                                                 @RequestBody Map<String, Object> body) {
        return simService.saveNodeConfig(sid, body);
    }

    // ============ 15. /sim/schemes/{id}/validate（校验期限占比=100%） ============
    @PostMapping("/schemes/{sid}/validate")
    public R<Map<String, Object>> validateScheme(@PathVariable("sid") Long sid,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        return simService.validateScheme(sid, body);
    }

    // ============ 16. /sim/term-ratios（按 scheme+node 查 term_ratios） ============
    @GetMapping("/term-ratios")
    public R<List<Map<String, Object>>> listTermRatios(@RequestParam("scheme_id") Long schemeId,
                                                       @RequestParam("coa_node_id") Long coaNodeId) {
        R<Map<String, Object>> resp = simService.getNodeConfig(schemeId, coaNodeId);
        Map<String, Object> data = resp.getData();
        List<Map<String, Object>> ratios = (List<Map<String, Object>>) data.getOrDefault("ratios", Collections.emptyList());
        return R.ok(ratios);
    }

    // ============ 17. /sim/term-ratios/{id}（修改单条 term_ratio） ============
    @PutMapping("/term-ratios/{rid}")
    public R<Map<String, Object>> updateTermRatio(@PathVariable("rid") Long rid,
                                                   @RequestBody Map<String, Object> body) {
        return simService.updateTermRatio(rid, body);
    }

    // ============ 18. /sim/term-ratios/{id}（删除单条 term_ratio） ============
    @DeleteMapping("/term-ratios/{rid}")
    public R<Map<String, Object>> deleteTermRatio(@PathVariable("rid") Long rid) {
        return simService.deleteTermRatio(rid);
    }
}