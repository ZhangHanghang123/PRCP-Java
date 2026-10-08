package com.prcp.business.model.controller;

import com.prcp.business.model.service.ModelService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 模型管理 API — 对齐 Python routers/model.py
 * 模块前缀：/prcp-java/api/model
 *
 * 本次实现核心 CRUD（13 端点）：
 *  - 算法元数据：algorithms + scheme-options
 *  - 模型定义：models CRUD
 *  - 模型版本：versions CRUD + copy
 *  - 模型参数：params CRUD
 *
 * 训练 + 模板 + 日志（13 端点）下一批实现。
 */
@RestController
@RequestMapping("/model")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    // ============ 1. algorithms ============
    @GetMapping("/algorithms")
    public R<Map<String, Object>> listAlgorithms() {
        return modelService.listAlgorithms();
    }

    // ============ 2. scheme-options ============
    @GetMapping("/scheme-options")
    public R<Map<String, Object>> schemeOptions() {
        return modelService.schemeOptions();
    }

    // ============ 3. models list ============
    @GetMapping("/models")
    public R<Map<String, Object>> listModels(@RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status) {
        return modelService.listModels(keyword, status);
    }

    // ============ 4. create model ============
    @PostMapping("/models")
    public R<Map<String, Object>> createModel(@RequestBody Map<String, Object> body) {
        return modelService.createModel(body);
    }

    // ============ 5. update model ============
    @PutMapping("/models/{mid}")
    public R<Map<String, Object>> updateModel(@PathVariable Long mid, @RequestBody Map<String, Object> body) {
        return modelService.updateModel(mid, body);
    }

    // ============ 6. delete model ============
    @DeleteMapping("/models/{mid}")
    public R<Map<String, Object>> deleteModel(@PathVariable Long mid) {
        return modelService.deleteModel(mid);
    }

    // ============ 7. versions list ============
    @GetMapping("/versions")
    public R<Map<String, Object>> listVersions(@RequestParam(required = false) Long modelId,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status) {
        return modelService.listVersions(modelId, keyword, status);
    }

    // ============ 8. create version ============
    @PostMapping("/versions")
    public R<Map<String, Object>> createVersion(@RequestBody Map<String, Object> body) {
        return modelService.createVersion(body);
    }

    // ============ 9. update version ============
    @PutMapping("/versions/{vid}")
    public R<Map<String, Object>> updateVersion(@PathVariable Long vid, @RequestBody Map<String, Object> body) {
        return modelService.updateVersion(vid, body);
    }

    // ============ 10. delete version ============
    @DeleteMapping("/versions/{vid}")
    public R<Map<String, Object>> deleteVersion(@PathVariable Long vid) {
        return modelService.deleteVersion(vid);
    }

    // ============ 11. copy version ============
    @PostMapping("/versions/{vid}/copy")
    public R<Map<String, Object>> copyVersion(@PathVariable Long vid, @RequestBody Map<String, Object> body) {
        return modelService.copyVersion(vid, body);
    }

    // ============ 12. params list ============
    @GetMapping("/params")
    public R<Map<String, Object>> listParams(@RequestParam(required = false) Long versionId) {
        return modelService.listParams(versionId);
    }

    // ============ 13. create param ============
    @PostMapping("/params")
    public R<Map<String, Object>> saveParam(@RequestBody Map<String, Object> body) {
        return modelService.saveParam(body);
    }

    // ============ 14. update param ============
    @PutMapping("/params/{pid}")
    public R<Map<String, Object>> updateParam(@PathVariable Long pid, @RequestBody Map<String, Object> body) {
        return modelService.updateParam(pid, body);
    }

    // ============ 15. delete param ============
    @DeleteMapping("/params/{pid}")
    public R<Map<String, Object>> deleteParam(@PathVariable Long pid) {
        return modelService.deleteParam(pid);
    }

    // ============ 16. POST /model/{mid}/train — 启动训练 ============
    @PostMapping("/{mid}/train")
    public R<Map<String, Object>> startTrain(@PathVariable Long mid, @RequestBody Map<String, Object> body) {
        return modelService.startTrain(mid, body);
    }

    // ============ 17. POST /model/train/cancel — 取消训练 ============
    @PostMapping("/train/cancel")
    public R<Map<String, Object>> cancelTrain(@RequestBody Map<String, Object> body) {
        return modelService.cancelTrain(body);
    }

    // ============ 18. GET /model/{mid}/logs — 训练日志（轮询） ============
    @GetMapping("/{mid}/logs")
    public R<Map<String, Object>> trainLogs(@PathVariable Long mid,
                                            @RequestParam(required = false) Long trainId) {
        return modelService.trainLogs(mid, trainId);
    }

    // ============ 19. GET /model/results — 训练结果列表 ============
    @GetMapping("/results")
    public R<Map<String, Object>> trainResults(@RequestParam(required = false) Long modelId,
                                                @RequestParam(required = false) Long trainId) {
        return modelService.trainResults(modelId, trainId);
    }
}