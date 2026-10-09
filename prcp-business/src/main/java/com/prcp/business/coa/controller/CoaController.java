package com.prcp.business.coa.controller;

import com.prcp.business.coa.entity.CoaNode;
import com.prcp.business.coa.entity.CoaScheme;
import com.prcp.business.coa.service.CoaNodeService;
import com.prcp.business.coa.service.CoaSchemeService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * <p>账户册 Controller (Chart of Accounts, 方案 + 节点)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 银行账户册是所有数据/参数/报表的基础维度, 一个方案 (Scheme) 下挂多棵节点 (Node) 树</li>
 *   <li>核心端点: Scheme CRUD (listActive/listAll/get/create/update/softDelete) + Node CRUD (listByScheme/listTree/create/update/softDelete)</li>
 *   <li>关联模块: CoaSchemeService (方案服务)、CoaNodeService (节点服务)、CoaScheme + CoaNode 实体</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /coa} (路径前缀 /prcp-java/api/coa, 与 Python 版 /prcp/api/coa 对齐但前缀不同以避免冲突)</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.coa.service.CoaSchemeService
 * @see com.prcp.business.coa.service.CoaNodeService
 */
@Slf4j
@RestController
@RequestMapping("/coa")
@RequiredArgsConstructor
public class CoaController {

    private final CoaSchemeService coaSchemeService;
    private final CoaNodeService coaNodeService;

    // ============ 方案（Scheme）=============

    /**
     * <p>列出所有 ACTIVE 状态的账户册方案</p>
     *
     * <pre>
     * GET /coa/schemes
     * Response: R.ok(List&lt;CoaScheme&gt;)
     *   CoaScheme 字段: id / schemeCode / schemeName / status / description / isDeleted
     * </pre>
     *
     * @return R.ok(List&lt;CoaScheme&gt;) 所有 ACTIVE 方案
     */
    @GetMapping("/schemes")
    public R<List<CoaScheme>> schemes() {
        return coaSchemeService.listActive();
    }

    /**
     * <p>列出所有账户册方案 (含 INACTIVE, 供方案维护使用)</p>
     *
     * <pre>
     * GET /coa/schemes/all
     * Response: R.ok(List&lt;CoaScheme&gt;)
     * </pre>
     *
     * @return R.ok(List&lt;CoaScheme&gt;) 所有方案
     */
    @GetMapping("/schemes/all")
    public R<List<CoaScheme>> allSchemes() {
        return coaSchemeService.listAll();
    }

    /**
     * <p>查询单个账户册方案 (含全部字段)</p>
     *
     * <pre>
     * GET /coa/schemes/{id}
     * Path: id (Long, required) - 方案 ID
     *
     * Response: R.ok(CoaScheme) or R.error("方案不存在")
     * </pre>
     *
     * @param id 方案 ID
     * @return R.ok(CoaScheme)
     */
    @GetMapping("/schemes/{id}")
    public R<?> getScheme(@PathVariable Long id) {
        return coaSchemeService.getById(id);
    }

    /**
     * <p>新建账户册方案</p>
     *
     * <pre>
     * POST /coa/schemes
     * Body: CoaScheme (schemeCode, schemeName, status, description)
     *
     * Response: R.ok(新方案实体)
     * </pre>
     *
     * @param scheme 方案实体
     * @return R.ok(新建的 CoaScheme)
     */
    @PostMapping("/schemes")
    public R<?> createScheme(@Valid @RequestBody CoaScheme scheme) {
        return coaSchemeService.create(scheme);
    }

    /**
     * <p>更新账户册方案</p>
     *
     * <pre>
     * PUT /coa/schemes/{id}
     * Path: id (Long, required) - 方案 ID
     * Body: CoaScheme (要更新的字段)
     *
     * Response: R.ok(更新后的方案)
     * </pre>
     *
     * @param id     方案 ID
     * @param scheme 方案实体
     * @return R.ok(更新后的 CoaScheme)
     */
    @PutMapping("/schemes/{id}")
    public R<?> updateScheme(@PathVariable Long id, @Valid @RequestBody CoaScheme scheme) {
        return coaSchemeService.update(id, scheme);
    }

    /**
     * <p>软删账户册方案 (is_deleted=1)</p>
     *
     * <pre>
     * DELETE /coa/schemes/{id}
     * Path: id (Long, required) - 方案 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 方案 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/schemes/{id}")
    public R<?> deleteScheme(@PathVariable Long id) {
        return coaSchemeService.softDelete(id);
    }

    // ============ 节点（Node）=============

    /**
     * <p>按方案平铺列出所有节点 (无父子层级)</p>
     *
     * <pre>
     * GET /coa/nodes?scheme_id=1
     * Query: scheme_id (Long, required) - 方案 ID
     *
     * Response: R.ok(List&lt;Map&gt;) 节点列表 (含 id/nodeCode/nodeName/parentId/level)
     * </pre>
     *
     * @param schemeId 方案 ID
     * @return R.ok(List&lt;Map&gt;) 节点列表
     */
    @GetMapping("/nodes")
    public R<List<Map<String, Object>>> nodes(@RequestParam("scheme_id") Long schemeId) {
        return coaNodeService.listByScheme(schemeId);
    }

    /**
     * <p>按方案列出节点 (树形结构, 父子嵌套)</p>
     *
     * <pre>
     * GET /coa/nodes/tree?scheme_id=1
     * Query: scheme_id (Long, required) - 方案 ID
     *
     * Response: R.ok(List&lt;Map&gt;) 树形节点 (children 嵌套)
     * </pre>
     *
     * @param schemeId 方案 ID
     * @return R.ok(List&lt;Map&gt;) 树形节点
     */
    @GetMapping("/nodes/tree")
    public R<List<Map<String, Object>>> nodesTree(@RequestParam("scheme_id") Long schemeId) {
        return coaNodeService.listTree(schemeId);
    }

    /**
     * <p>新建账户册节点</p>
     *
     * <pre>
     * POST /coa/nodes
     * Body: CoaNode (schemeId, nodeCode, nodeName, parentId, level, ...)
     *
     * Response: R.ok(新建的节点)
     * </pre>
     *
     * @param node 节点实体
     * @return R.ok(新建的 CoaNode)
     */
    @PostMapping("/nodes")
    public R<?> createNode(@Valid @RequestBody CoaNode node) {
        log.info("[createNode] {}", node);
        return coaNodeService.create(node);
    }

    /**
     * <p>更新账户册节点</p>
     *
     * <pre>
     * PUT /coa/nodes/{id}
     * Path: id (Long, required) - 节点 ID
     * Body: CoaNode (要更新的字段)
     *
     * Response: R.ok(更新后的节点)
     * </pre>
     *
     * @param id   节点 ID
     * @param node 节点实体
     * @return R.ok(更新后的 CoaNode)
     */
    @PutMapping("/nodes/{id}")
    public R<?> updateNode(@PathVariable Long id, @Valid @RequestBody CoaNode node) {
        return coaNodeService.update(id, node);
    }

    /**
     * <p>软删账户册节点 (is_deleted=1)</p>
     *
     * <pre>
     * DELETE /coa/nodes/{id}
     * Path: id (Long, required) - 节点 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 节点 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/nodes/{id}")
    public R<?> deleteNode(@PathVariable Long id) {
        return coaNodeService.softDelete(id);
    }
}

