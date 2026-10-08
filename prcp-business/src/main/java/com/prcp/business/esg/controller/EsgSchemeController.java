package com.prcp.business.esg.controller;

import com.prcp.business.esg.service.EsgSchemeService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ESG 方案 CRUD + Clone（7 端点）
 * 对齐 Python routers/esg.py: list/create/get/update/delete/clone
 *   GET    /schemes              列表（分页 + keyword/status 过滤）
 *   POST   /schemes              新建
 *   GET    /schemes/{id}         详情
 *   PUT    /schemes/{id}         更新
 *   DELETE /schemes/{id}         软删
 *   POST   /schemes/{id}/clone   克隆
 *   GET    /cache-info           缓存诊断（运维用）
 */
@RestController
@RequestMapping("/esg")
@RequiredArgsConstructor
public class EsgSchemeController {

    private final EsgSchemeService schemeService;
    private final com.prcp.business.esg.util.EsgGeneratorStore generatorStore;

    @GetMapping("/schemes")
    public R<Map<String, Object>> listSchemes(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int pageSize) {
        return schemeService.listSchemes(keyword, status, page, Math.min(pageSize, 100));
    }

    @PostMapping("/schemes")
    public R<Map<String, Object>> createScheme(@RequestBody Map<String, Object> body) {
        return schemeService.createScheme(body);
    }

    @GetMapping("/schemes/{id}")
    public R<Map<String, Object>> getScheme(@PathVariable("id") Long id) {
        return schemeService.getScheme(id);
    }

    @PutMapping("/schemes/{id}")
    public R<Map<String, Object>> updateScheme(@PathVariable("id") Long id,
                                                 @RequestBody Map<String, Object> body) {
        return schemeService.updateScheme(id, body);
    }

    @DeleteMapping("/schemes/{id}")
    public R<Map<String, Object>> deleteScheme(@PathVariable("id") Long id) {
        return schemeService.deleteScheme(id);
        // 软删方案时清缓存
        // generatorStore.evict(id);
    }

    @PostMapping("/schemes/{id}/clone")
    public R<Map<String, Object>> cloneScheme(@PathVariable("id") Long id,
                                                @RequestBody Map<String, Object> body) {
        return schemeService.cloneScheme(id, body);
    }

    /** 缓存诊断（运维用） */
    @GetMapping("/cache-info")
    public R<Map<String, Object>> cacheInfo() {
        return R.ok(generatorStore.diagnostics());
    }
}