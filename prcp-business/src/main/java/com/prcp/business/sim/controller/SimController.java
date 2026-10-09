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
 * <p>新业务模拟方案管理 Controller (对位 Python routers/sim.py, 模块前缀 /prcp-java/api/sim)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 新业务模拟 (New Business Simulation) 方案管理, 包含方案 CRUD、节点配置 (term_ratios)、校验</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>账户册下拉: GET /sim/coa-schemes、GET /sim/coa-tree、GET /sim/node-info/{id}</li>
 *       <li>方案 CRUD: GET/POST/PUT/DELETE /sim/schemes</li>
 *       <li>节点配置: GET/POST/DELETE /sim/node-config</li>
 *       <li>期限占比: GET/PUT/DELETE /sim/term-ratios</li>
 *       <li>校验: POST /sim/schemes/{sid}/validate</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: SimService、SimScheme 实体</li>
 *   <li>引擎相关端点 (run/runs/results) 由 EngineController 提供</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /sim} (与 EngineController 共用)</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.sim.service.SimService
 */
@RestController
@RequestMapping("/sim")
@RequiredArgsConstructor
public class SimController {

    private final SimService simService;

    // ============ 1. coa-schemes（账户册方案下拉） ============
    /**
     * <p>账户册方案下拉</p>
     *
     * <pre>
     * GET /sim/coa-schemes
     * Response: R.ok({items: [{id, scheme_code, scheme_name}]})
     * </pre>
     *
     * @return R.ok(账户册方案列表)
     */
    @GetMapping("/coa-schemes")
    public R<Map<String, Object>> listCoaSchemes() {
        return simService.listCoaSchemes();
    }

    // ============ 2. coa-tree（节点树） ============
    /**
     * <p>节点树 (按账户册方案)</p>
     *
     * <pre>
     * GET /sim/coa-tree
     * Query: coa_scheme_id (Long, required) - 账户册方案 ID
     *
     * Response: R.ok({nodes: [{id, node_code, node_name, children}]})
     * </pre>
     *
     * @param coaSchemeId 账户册方案 ID
     * @return R.ok(节点树)
     */
    @GetMapping("/coa-tree")
    public R<Map<String, Object>> coaTree(@RequestParam("coa_scheme_id") Long coaSchemeId) {
        return simService.coaTree(coaSchemeId);
    }

    // ============ 3. node-info（节点基础信息 + 当前余额） ============
    /**
     * <p>节点基础信息 + 当前余额</p>
     *
     * <pre>
     * GET /sim/node-info/{coaNodeId}
     * Path: coaNodeId (Long, required) - 节点 ID
     *
     * Response: R.ok({id, node_code, node_name, balance, ...})
     * </pre>
     *
     * @param coaNodeId 节点 ID
     * @return R.ok(节点信息)
     */
    @GetMapping("/node-info/{coaNodeId}")
    public R<Map<String, Object>> nodeInfo(@PathVariable Long coaNodeId) {
        return simService.nodeInfo(coaNodeId);
    }

    // ============ 4. schemes list ============
    /**
     * <p>模拟方案列表</p>
     *
     * <pre>
     * GET /sim/schemes
     * Query: keyword      (String, optional) - 模糊搜索
     *        status       (String, optional) - 状态
     *        coaSchemeId  (Long, optional) - 账户册方案 ID
     *
     * Response: R.ok({items: [{id, scheme_code, scheme_name, status}]})
     * </pre>
     *
     * @param keyword     模糊搜索关键字 (可选)
     * @param status      状态 (可选)
     * @param coaSchemeId 账户册方案 ID (可选)
     * @return R.ok(方案列表)
     */
    @GetMapping("/schemes")
    public R<Map<String, Object>> listSchemes(@RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) Long coaSchemeId) {
        return simService.listSchemes(keyword, status, coaSchemeId);
    }

    // ============ 5. create scheme ============
    /**
     * <p>新建模拟方案</p>
     *
     * <pre>
     * POST /sim/schemes
     * Body: {scheme_code, scheme_name, coa_scheme_id, data_date, status, description}
     *
     * Response: R.ok(新建方案)
     * </pre>
     *
     * @param body 方案数据
     * @return R.ok(新建方案)
     */
    @PostMapping("/schemes")
    public R<Map<String, Object>> createScheme(@RequestBody Map<String, Object> body) {
        return simService.createScheme(parseScheme(body));
    }

    // ============ 6. update scheme ============
    /**
     * <p>更新模拟方案</p>
     *
     * <pre>
     * PUT /sim/schemes/{sid}
     * Path: sid (Long, required) - 方案 ID
     * Body: 更新字段
     *
     * Response: R.ok(更新后方案)
     * </pre>
     *
     * @param sid  方案 ID
     * @param body 更新内容
     * @return R.ok(更新后方案)
     */
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
    /**
     * <p>切换方案状态 (ACTIVE/INACTIVE)</p>
     *
     * <pre>
     * PATCH /sim/schemes/{sid}/status
     * Path: sid (Long, required) - 方案 ID
     * Body: {status: "ACTIVE"/"INACTIVE"}
     *
     * Response: R.ok(更新后方案)
     * </pre>
     *
     * @param sid  方案 ID
     * @param body 含 status
     * @return R.ok(更新后方案)
     */
    @PatchMapping("/schemes/{sid}/status")
    public R<Map<String, Object>> toggleStatus(@PathVariable Long sid, @RequestBody Map<String, String> body) {
        return simService.toggleStatus(sid, body == null ? null : body.get("status"));
    }

    // ============ 8. delete scheme ============
    /**
     * <p>软删模拟方案</p>
     *
     * <pre>
     * DELETE /sim/schemes/{sid}
     * Path: sid (Long, required) - 方案 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param sid 方案 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/schemes/{sid}")
    public R<Map<String, Object>> deleteScheme(@PathVariable Long sid) {
        return simService.deleteScheme(sid);
    }

    // ============ 9. get node-config ============
    /**
     * <p>获取节点配置 (含 term_ratios)</p>
     *
     * <pre>
     * GET /sim/node-config
     * Query: scheme_id    (Long, required) - 方案 ID
     *        coa_node_id  (Long, required) - 节点 ID
     *
     * Response: R.ok({node_id, ratios: [{term_bucket, ratio, ...}, ...]})
     * </pre>
     *
     * @param schemeId   方案 ID
     * @param coaNodeId  节点 ID
     * @return R.ok(节点配置)
     */
    @GetMapping("/node-config")
    public R<Map<String, Object>> getNodeConfig(@RequestParam("scheme_id") Long schemeId,
                                                @RequestParam("coa_node_id") Long coaNodeId) {
        return simService.getNodeConfig(schemeId, coaNodeId);
    }

    // ============ 10. save node-config ============
    /**
     * <p>保存节点配置</p>
     *
     * <pre>
     * POST /sim/node-config
     * Query: scheme_id (Long, required) - 方案 ID
     * Body: {coa_node_id, ratios: [{term_bucket, ratio, ...}, ...]}
     *
     * Response: R.ok(保存结果)
     * </pre>
     *
     * @param schemeId 方案 ID
     * @param body     节点配置
     * @return R.ok(保存结果)
     */
    @PostMapping("/node-config")
    public R<Map<String, Object>> saveNodeConfig(@RequestParam("scheme_id") Long schemeId,
                                                 @RequestBody Map<String, Object> body) {
        return simService.saveNodeConfig(schemeId, body);
    }

    // ============ 11. delete node-config ============
    /**
     * <p>删除节点配置</p>
     *
     * <pre>
     * DELETE /sim/node-config/{cfgId}
     * Path: cfgId (Long, required) - 配置 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param cfgId 配置 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/node-config/{cfgId}")
    public R<Map<String, Object>> deleteNodeConfig(@PathVariable Long cfgId) {
        return simService.deleteNodeConfig(cfgId);
    }

    // ============ 12. /sim/tree（对齐 Python：账户册树别名） ============
    /**
     * <p>账户册树别名 (与 /sim/coa-tree 一致)</p>
     *
     * <pre>
     * GET /sim/tree
     * Query: scheme_id (Long, required) - 账户册方案 ID
     *
     * Response: R.ok({nodes})
     * </pre>
     *
     * @param coaSchemeId 账户册方案 ID
     * @return R.ok(节点树)
     */
    @GetMapping("/tree")
    public R<Map<String, Object>> simTree(@RequestParam("scheme_id") Long coaSchemeId) {
        return simService.coaTree(coaSchemeId);
    }

    // ============ 13. /sim/schemes/{id}/nodes/{nid}（节点配置详情） ============
    /**
     * <p>方案下节点配置详情 (RESTful 风格)</p>
     *
     * <pre>
     * GET /sim/schemes/{sid}/nodes/{nid}
     * Path: sid (Long, required) - 方案 ID
     *        nid (Long, required) - 节点 ID
     *
     * Response: R.ok(节点配置)
     * </pre>
     *
     * @param sid 方案 ID
     * @param nid 节点 ID
     * @return R.ok(节点配置)
     */
    @GetMapping("/schemes/{sid}/nodes/{nid}")
    public R<Map<String, Object>> getSchemeNode(@PathVariable("sid") Long sid,
                                                 @PathVariable("nid") Long nid) {
        return simService.getNodeConfig(sid, nid);
    }

    // ============ 14. /sim/schemes/{id}/save（批量保存配置） ============
    /**
     * <p>批量保存方案节点配置</p>
     *
     * <pre>
     * POST /sim/schemes/{sid}/save
     * Path: sid (Long, required) - 方案 ID
     * Body: {nodes: [{coa_node_id, ratios, ...}, ...]}
     *
     * Response: R.ok({saved: N})
     * </pre>
     *
     * @param sid  方案 ID
     * @param body 批量配置
     * @return R.ok({saved: N})
     */
    @PostMapping("/schemes/{sid}/save")
    public R<Map<String, Object>> saveSchemeAll(@PathVariable("sid") Long sid,
                                                 @RequestBody Map<String, Object> body) {
        return simService.saveNodeConfig(sid, body);
    }

    // ============ 15. /sim/schemes/{id}/validate（校验期限占比=100%） ============
    /**
     * <p>校验方案所有节点的期限占比之和 = 100%</p>
     *
     * <pre>
     * POST /sim/schemes/{sid}/validate
     * Path: sid (Long, required) - 方案 ID
     * Body: 可选 {strict: true/false}
     *
     * Response: R.ok({valid: bool, errors: [{node_id, msg}, ...]})
     * </pre>
     *
     * @param sid  方案 ID
     * @param body 可选 strict 等参数
     * @return R.ok({valid, errors})
     */
    @PostMapping("/schemes/{sid}/validate")
    public R<Map<String, Object>> validateScheme(@PathVariable("sid") Long sid,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        return simService.validateScheme(sid, body);
    }

    // ============ 16. /sim/term-ratios（按 scheme+node 查 term_ratios） ============
    /**
     * <p>查询某方案下某节点的期限占比列表</p>
     *
     * <pre>
     * GET /sim/term-ratios
     * Query: scheme_id   (Long, required) - 方案 ID
     *        coa_node_id (Long, required) - 节点 ID
     *
     * Response: R.ok(List&lt;Map&gt;) 期限占比列表 [{term_bucket, ratio}, ...]
     * </pre>
     *
     * @param schemeId  方案 ID
     * @param coaNodeId 节点 ID
     * @return R.ok(期限占比列表)
     */
    @GetMapping("/term-ratios")
    public R<List<Map<String, Object>>> listTermRatios(@RequestParam("scheme_id") Long schemeId,
                                                       @RequestParam("coa_node_id") Long coaNodeId) {
        R<Map<String, Object>> resp = simService.getNodeConfig(schemeId, coaNodeId);
        Map<String, Object> data = resp.getData();
        List<Map<String, Object>> ratios = (List<Map<String, Object>>) data.getOrDefault("ratios", Collections.emptyList());
        return R.ok(ratios);
    }

    // ============ 17. /sim/term-ratios/{id}（修改单条 term_ratio） ============
    /**
     * <p>修改单条期限占比</p>
     *
     * <pre>
     * PUT /sim/term-ratios/{rid}
     * Path: rid (Long, required) - term_ratio ID
     * Body: {ratio, term_bucket, ...}
     *
     * Response: R.ok(更新后 term_ratio)
     * </pre>
     *
     * @param rid  term_ratio ID
     * @param body 更新内容
     * @return R.ok(更新后 term_ratio)
     */
    @PutMapping("/term-ratios/{rid}")
    public R<Map<String, Object>> updateTermRatio(@PathVariable("rid") Long rid,
                                                   @RequestBody Map<String, Object> body) {
        return simService.updateTermRatio(rid, body);
    }

    // ============ 18. /sim/term-ratios/{id}（删除单条 term_ratio） ============
    /**
     * <p>删除单条期限占比</p>
     *
     * <pre>
     * DELETE /sim/term-ratios/{rid}
     * Path: rid (Long, required) - term_ratio ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param rid term_ratio ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/term-ratios/{rid}")
    public R<Map<String, Object>> deleteTermRatio(@PathVariable("rid") Long rid) {
        return simService.deleteTermRatio(rid);
    }
}