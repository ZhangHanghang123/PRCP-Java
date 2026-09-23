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
 * 账户册 Controller
 * <p>路径前缀 /prcp-java/api/coa（与 Python 版 /prcp/api/coa 对齐，但前缀不同以避免冲突）</p>
 */
@Slf4j
@RestController
@RequestMapping("/coa")
@RequiredArgsConstructor
public class CoaController {

    private final CoaSchemeService coaSchemeService;
    private final CoaNodeService coaNodeService;

    // ============ 方案（Scheme）=============

    /** GET /coa/schemes — 列出所有 ACTIVE 方案 */
    @GetMapping("/schemes")
    public R<List<CoaScheme>> schemes() {
        return coaSchemeService.listActive();
    }

    /** GET /coa/scheme/{id} */
    @GetMapping("/scheme/{id}")
    public R<?> getScheme(@PathVariable Long id) {
        return coaSchemeService.getById(id);
    }

    /** POST /coa/scheme */
    @PostMapping("/scheme")
    public R<?> createScheme(@Valid @RequestBody CoaScheme scheme) {
        return coaSchemeService.create(scheme);
    }

    /** PUT /coa/scheme/{id} */
    @PutMapping("/scheme/{id}")
    public R<?> updateScheme(@PathVariable Long id, @Valid @RequestBody CoaScheme scheme) {
        return coaSchemeService.update(id, scheme);
    }

    /** DELETE /coa/scheme/{id} */
    @DeleteMapping("/scheme/{id}")
    public R<?> deleteScheme(@PathVariable Long id) {
        return coaSchemeService.softDelete(id);
    }

    // ============ 节点（Node）=============

    /** GET /coa/nodes?scheme_id=1 — 平铺 */
    @GetMapping("/nodes")
    public R<List<Map<String, Object>>> nodes(@RequestParam("scheme_id") Long schemeId) {
        return coaNodeService.listByScheme(schemeId);
    }

    /** GET /coa/nodes/tree?scheme_id=1 — 树形 */
    @GetMapping("/nodes/tree")
    public R<List<Map<String, Object>>> nodesTree(@RequestParam("scheme_id") Long schemeId) {
        return coaNodeService.listTree(schemeId);
    }

    /** POST /coa/node */
    @PostMapping("/node")
    public R<?> createNode(@Valid @RequestBody CoaNode node) {
        log.info("[createNode] {}", node);
        return coaNodeService.create(node);
    }

    /** PUT /coa/node/{id} */
    @PutMapping("/node/{id}")
    public R<?> updateNode(@PathVariable Long id, @Valid @RequestBody CoaNode node) {
        return coaNodeService.update(id, node);
    }

    /** DELETE /coa/node/{id} */
    @DeleteMapping("/node/{id}")
    public R<?> deleteNode(@PathVariable Long id) {
        return coaNodeService.softDelete(id);
    }
}
