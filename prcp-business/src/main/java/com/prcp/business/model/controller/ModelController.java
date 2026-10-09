package com.prcp.business.model.controller;

import com.prcp.business.model.service.ModelService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>模型管理 Controller (对位 Python routers/model.py, 模块前缀 /prcp-java/api/model)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 反算/预测模型的完整生命周期管理: 算法元数据 → 模型定义 → 版本 → 参数 → 训练</li>
 *   <li>核心端点 (19 端点):
 *     <ul>
 *       <li>算法元数据: GET /model/algorithms、GET /model/scheme-options</li>
 *       <li>模型定义 CRUD: GET/POST/PUT/DELETE /model/models</li>
 *       <li>模型版本 CRUD + copy: GET/POST/PUT/DELETE/POST-copy /model/versions</li>
 *       <li>模型参数 CRUD: GET/POST/PUT/DELETE /model/params</li>
 *       <li>训练: POST /model/{mid}/train、POST /model/train/cancel、GET /model/{mid}/logs、GET /model/results</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: ModelService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /model}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.model.service.ModelService
 */
@RestController
@RequestMapping("/model")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    // ============ 1. algorithms ============
    /**
     * <p>算法元数据列表 (下拉选项, 用于训练选算法)</p>
     *
     * <pre>
     * GET /model/algorithms
     * Response: R.ok({items: [{code, name, description, default_params}, ...]})
     * </pre>
     *
     * @return R.ok(算法元数据)
     */
    @GetMapping("/algorithms")
    public R<Map<String, Object>> listAlgorithms() {
        return modelService.listAlgorithms();
    }

    // ============ 2. scheme-options ============
    /**
     * <p>方案下拉选项 (model 维度)</p>
     *
     * <pre>
     * GET /model/scheme-options
     * Response: R.ok({items: [{id, scheme_code, scheme_name}, ...]})
     * </pre>
     *
     * @return R.ok(方案选项)
     */
    @GetMapping("/scheme-options")
    public R<Map<String, Object>> schemeOptions() {
        return modelService.schemeOptions();
    }

    // ============ 3. models list ============
    /**
     * <p>模型列表 (按关键字/状态过滤)</p>
     *
     * <pre>
     * GET /model/models
     * Query: keyword (String, optional) - 模糊搜索
     *        status  (String, optional) - 状态 (DRAFT/ACTIVE/ARCHIVED)
     *
     * Response: R.ok({items: [{id, model_code, model_name, status, scheme_id}]})
     * </pre>
     *
     * @param keyword 模糊搜索关键字 (可选)
     * @param status  状态 (可选)
     * @return R.ok(模型列表)
     */
    @GetMapping("/models")
    public R<Map<String, Object>> listModels(@RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status) {
        return modelService.listModels(keyword, status);
    }

    // ============ 4. create model ============
    /**
     * <p>新建模型</p>
     *
     * <pre>
     * POST /model/models
     * Body: {model_code, model_name, scheme_id, description, ...}
     *
     * Response: R.ok(新建模型)
     * </pre>
     *
     * @param body 模型数据
     * @return R.ok(新建模型)
     */
    @PostMapping("/models")
    public R<Map<String, Object>> createModel(@RequestBody Map<String, Object> body) {
        return modelService.createModel(body);
    }

    // ============ 5. update model ============
    /**
     * <p>更新模型</p>
     *
     * <pre>
     * PUT /model/models/{mid}
     * Path: mid (Long, required) - 模型 ID
     * Body: 更新字段
     *
     * Response: R.ok(更新后模型)
     * </pre>
     *
     * @param mid  模型 ID
     * @param body 更新内容
     * @return R.ok(更新后模型)
     */
    @PutMapping("/models/{mid}")
    public R<Map<String, Object>> updateModel(@PathVariable Long mid, @RequestBody Map<String, Object> body) {
        return modelService.updateModel(mid, body);
    }

    // ============ 6. delete model ============
    /**
     * <p>软删模型</p>
     *
     * <pre>
     * DELETE /model/models/{mid}
     * Path: mid (Long, required) - 模型 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param mid 模型 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/models/{mid}")
    public R<Map<String, Object>> deleteModel(@PathVariable Long mid) {
        return modelService.deleteModel(mid);
    }

    // ============ 7. versions list ============
    /**
     * <p>模型版本列表</p>
     *
     * <pre>
     * GET /model/versions
     * Query: modelId (Long, optional) - 模型 ID
     *        keyword (String, optional) - 模糊搜索
     *        status  (String, optional) - 状态
     *
     * Response: R.ok({items: [{id, version_code, version_name, model_id, status}]})
     * </pre>
     *
     * @param modelId 模型 ID (可选)
     * @param keyword 模糊搜索关键字 (可选)
     * @param status  状态 (可选)
     * @return R.ok(版本列表)
     */
    @GetMapping("/versions")
    public R<Map<String, Object>> listVersions(@RequestParam(required = false) Long modelId,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status) {
        return modelService.listVersions(modelId, keyword, status);
    }

    // ============ 8. create version ============
    /**
     * <p>新建模型版本</p>
     *
     * <pre>
     * POST /model/versions
     * Body: {model_id, version_code, version_name, algorithm_code, params, ...}
     *
     * Response: R.ok(新建版本)
     * </pre>
     *
     * @param body 版本数据
     * @return R.ok(新建版本)
     */
    @PostMapping("/versions")
    public R<Map<String, Object>> createVersion(@RequestBody Map<String, Object> body) {
        return modelService.createVersion(body);
    }

    // ============ 9. update version ============
    /**
     * <p>更新模型版本</p>
     *
     * <pre>
     * PUT /model/versions/{vid}
     * Path: vid (Long, required) - 版本 ID
     * Body: 更新字段
     *
     * Response: R.ok(更新后版本)
     * </pre>
     *
     * @param vid  版本 ID
     * @param body 更新内容
     * @return R.ok(更新后版本)
     */
    @PutMapping("/versions/{vid}")
    public R<Map<String, Object>> updateVersion(@PathVariable Long vid, @RequestBody Map<String, Object> body) {
        return modelService.updateVersion(vid, body);
    }

    // ============ 10. delete version ============
    /**
     * <p>软删模型版本</p>
     *
     * <pre>
     * DELETE /model/versions/{vid}
     * Path: vid (Long, required) - 版本 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param vid 版本 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/versions/{vid}")
    public R<Map<String, Object>> deleteVersion(@PathVariable Long vid) {
        return modelService.deleteVersion(vid);
    }

    // ============ 11. copy version ============
    /**
     * <p>复制模型版本 (基于已有版本创建新版本)</p>
     *
     * <pre>
     * POST /model/versions/{vid}/copy
     * Path: vid (Long, required) - 源版本 ID
     * Body: {new_version_code, new_version_name, ...}
     *
     * Response: R.ok(新版本)
     * </pre>
     *
     * @param vid  源版本 ID
     * @param body 新版本参数
     * @return R.ok(新版本)
     */
    @PostMapping("/versions/{vid}/copy")
    public R<Map<String, Object>> copyVersion(@PathVariable Long vid, @RequestBody Map<String, Object> body) {
        return modelService.copyVersion(vid, body);
    }

    // ============ 12. params list ============
    /**
     * <p>模型参数列表 (按版本过滤)</p>
     *
     * <pre>
     * GET /model/params
     * Query: versionId (Long, optional) - 版本 ID
     *
     * Response: R.ok({items: [{id, param_code, param_name, value, version_id}]})
     * </pre>
     *
     * @param versionId 版本 ID (可选)
     * @return R.ok(参数列表)
     */
    @GetMapping("/params")
    public R<Map<String, Object>> listParams(@RequestParam(required = false) Long versionId) {
        return modelService.listParams(versionId);
    }

    // ============ 13. create param ============
    /**
     * <p>新建模型参数</p>
     *
     * <pre>
     * POST /model/params
     * Body: {version_id, param_code, param_name, value, type, ...}
     *
     * Response: R.ok(新建参数)
     * </pre>
     *
     * @param body 参数数据
     * @return R.ok(新建参数)
     */
    @PostMapping("/params")
    public R<Map<String, Object>> saveParam(@RequestBody Map<String, Object> body) {
        return modelService.saveParam(body);
    }

    // ============ 14. update param ============
    /**
     * <p>更新模型参数</p>
     *
     * <pre>
     * PUT /model/params/{pid}
     * Path: pid (Long, required) - 参数 ID
     * Body: 更新字段
     *
     * Response: R.ok(更新后参数)
     * </pre>
     *
     * @param pid  参数 ID
     * @param body 更新内容
     * @return R.ok(更新后参数)
     */
    @PutMapping("/params/{pid}")
    public R<Map<String, Object>> updateParam(@PathVariable Long pid, @RequestBody Map<String, Object> body) {
        return modelService.updateParam(pid, body);
    }

    // ============ 15. delete param ============
    /**
     * <p>软删模型参数</p>
     *
     * <pre>
     * DELETE /model/params/{pid}
     * Path: pid (Long, required) - 参数 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param pid 参数 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/params/{pid}")
    public R<Map<String, Object>> deleteParam(@PathVariable Long pid) {
        return modelService.deleteParam(pid);
    }

    // ============ 16. POST /model/{mid}/train — 启动训练 ============
    /**
     * <p>启动模型训练</p>
     *
     * <pre>
     * POST /model/{mid}/train
     * Path: mid (Long, required) - 模型 ID
     * Body: {version_id, data_date, hyperparams, ...}
     *
     * Response: R.ok({train_id, status: "RUNNING"})
     * </pre>
     *
     * @param mid  模型 ID
     * @param body 训练参数
     * @return R.ok({train_id, status})
     */
    @PostMapping("/{mid}/train")
    public R<Map<String, Object>> startTrain(@PathVariable Long mid, @RequestBody Map<String, Object> body) {
        return modelService.startTrain(mid, body);
    }

    // ============ 17. POST /model/train/cancel — 取消训练 ============
    /**
     * <p>取消训练任务</p>
     *
     * <pre>
     * POST /model/train/cancel
     * Body: {train_id}
     *
     * Response: R.ok({cancelled: true})
     * </pre>
     *
     * @param body 含 train_id
     * @return R.ok({cancelled: true})
     */
    @PostMapping("/train/cancel")
    public R<Map<String, Object>> cancelTrain(@RequestBody Map<String, Object> body) {
        return modelService.cancelTrain(body);
    }

    // ============ 18. GET /model/{mid}/logs — 训练日志（轮询） ============
    /**
     * <p>训练日志 (轮询拉取)</p>
     *
     * <pre>
     * GET /model/{mid}/logs?trainId=123
     * Path: mid (Long, required) - 模型 ID
     * Query: trainId (Long, optional) - 训练任务 ID
     *
     * Response: R.ok({lines: [{ts, level, msg}, ...], cursor})
     * </pre>
     *
     * @param mid     模型 ID
     * @param trainId 训练任务 ID (可选)
     * @return R.ok({lines, cursor})
     */
    @GetMapping("/{mid}/logs")
    public R<Map<String, Object>> trainLogs(@PathVariable Long mid,
                                            @RequestParam(required = false) Long trainId) {
        return modelService.trainLogs(mid, trainId);
    }

    // ============ 19. GET /model/results — 训练结果列表 ============
    /**
     * <p>训练结果列表</p>
     *
     * <pre>
     * GET /model/results
     * Query: modelId (Long, optional) - 模型 ID
     *        trainId (Long, optional) - 训练任务 ID
     *
     * Response: R.ok({items: [{train_id, model_id, metrics: {...}, finished_at}]})
     * </pre>
     *
     * @param modelId 模型 ID (可选)
     * @param trainId 训练任务 ID (可选)
     * @return R.ok(训练结果列表)
     */
    @GetMapping("/results")
    public R<Map<String, Object>> trainResults(@RequestParam(required = false) Long modelId,
                                                @RequestParam(required = false) Long trainId) {
        return modelService.trainResults(modelId, trainId);
    }
}