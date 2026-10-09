package com.prcp.business.esg.controller;

import com.prcp.business.esg.service.EsgSchemeService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>ESG 方案 CRUD + Clone Controller (7 端点)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: ESG 方案的 CRUD, 支持克隆 (基于已有方案复制为新方案), 含缓存诊断</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>GET /esg/schemes — 列表 (分页 + keyword/status 过滤)</li>
 *       <li>POST /esg/schemes — 新建</li>
 *       <li>GET /esg/schemes/{id} — 详情</li>
 *       <li>PUT /esg/schemes/{id} — 更新</li>
 *       <li>DELETE /esg/schemes/{id} — 软删</li>
 *       <li>POST /esg/schemes/{id}/clone — 克隆</li>
 *       <li>GET /esg/cache-info — 缓存诊断 (运维用)</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: EsgSchemeService、EsgGeneratorStore (缓存)</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /esg} (与 EsgRunController / EsgExecutionController 共用)</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.esg.service.EsgSchemeService
 */
@RestController
@RequestMapping("/esg")
@RequiredArgsConstructor
public class EsgSchemeController {

    private final EsgSchemeService schemeService;
    private final com.prcp.business.esg.util.EsgGeneratorStore generatorStore;

    /**
     * <p>ESG 方案列表 (分页 + keyword/status 过滤)</p>
     *
     * <pre>
     * GET /esg/schemes
     * Query: keyword  (String, optional) - 模糊搜索关键字
     *        status   (String, optional) - 状态 (ACTIVE/INACTIVE)
     *        page     (int, default 1) - 页码
     *        pageSize (int, default 20, max 100) - 页大小
     *
     * Response: R.ok({items: [{id, scheme_code, scheme_name, status, created_at}], total, page, pageSize})
     * </pre>
     *
     * @param keyword  模糊搜索关键字 (可选)
     * @param status   状态 (可选)
     * @param page     页码 (默认 1)
     * @param pageSize 页大小 (默认 20, 最大 100)
     * @return R.ok(方案列表)
     */
    @GetMapping("/schemes")
    public R<Map<String, Object>> listSchemes(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "20") int pageSize) {
        return schemeService.listSchemes(keyword, status, page, Math.min(pageSize, 100));
    }

    /**
     * <p>新建 ESG 方案</p>
     *
     * <pre>
     * POST /esg/schemes
     * Body: {scheme_code, scheme_name, status, params, ...}
     *
     * Response: R.ok(新建方案实体)
     * </pre>
     *
     * @param body 方案数据
     * @return R.ok(新建方案)
     */
    @PostMapping("/schemes")
    public R<Map<String, Object>> createScheme(@RequestBody Map<String, Object> body) {
        return schemeService.createScheme(body);
    }

    /**
     * <p>ESG 方案详情</p>
     *
     * <pre>
     * GET /esg/schemes/{id}
     * Path: id (Long, required) - 方案 ID
     *
     * Response: R.ok({id, scheme_code, scheme_name, status, params, factors, ...})
     * </pre>
     *
     * @param id 方案 ID
     * @return R.ok(方案详情)
     */
    @GetMapping("/schemes/{id}")
    public R<Map<String, Object>> getScheme(@PathVariable("id") Long id) {
        return schemeService.getScheme(id);
    }

    /**
     * <p>更新 ESG 方案</p>
     *
     * <pre>
     * PUT /esg/schemes/{id}
     * Path: id (Long, required) - 方案 ID
     * Body: 要更新的字段
     *
     * Response: R.ok(更新后方案)
     * </pre>
     *
     * @param id   方案 ID
     * @param body 更新内容
     * @return R.ok(更新后方案)
     */
    @PutMapping("/schemes/{id}")
    public R<Map<String, Object>> updateScheme(@PathVariable("id") Long id,
                                                 @RequestBody Map<String, Object> body) {
        return schemeService.updateScheme(id, body);
    }

    /**
     * <p>软删 ESG 方案</p>
     *
     * <pre>
     * DELETE /esg/schemes/{id}
     * Path: id (Long, required) - 方案 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 方案 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/schemes/{id}")
    public R<Map<String, Object>> deleteScheme(@PathVariable("id") Long id) {
        return schemeService.deleteScheme(id);
        // 软删方案时清缓存
        // generatorStore.evict(id);
    }

    /**
     * <p>克隆 ESG 方案 (复制已有方案为新方案)</p>
     *
     * <pre>
     * POST /esg/schemes/{id}/clone
     * Path: id (Long, required) - 源方案 ID
     * Body: {new_scheme_code, new_scheme_name, ...}
     *
     * Response: R.ok(新方案实体)
     * </pre>
     *
     * @param id   源方案 ID
     * @param body 克隆参数 (新方案编码/名称)
     * @return R.ok(新方案)
     */
    @PostMapping("/schemes/{id}/clone")
    public R<Map<String, Object>> cloneScheme(@PathVariable("id") Long id,
                                                @RequestBody Map<String, Object> body) {
        return schemeService.cloneScheme(id, body);
    }

    /**
     * <p>缓存诊断 (运维用, 查 Generator 内存缓存状态)</p>
     *
     * <pre>
     * GET /esg/cache-info
     * Response: R.ok({size, entries: [{key, scheme_id, last_access_ts}]})
     * </pre>
     *
     * @return R.ok(缓存诊断信息)
     */
    @GetMapping("/cache-info")
    public R<Map<String, Object>> cacheInfo() {
        return R.ok(generatorStore.diagnostics());
    }
}