package com.prcp.business.esg.controller;

import com.prcp.business.esg.service.EsgExecutionService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ESG 执行 5 端点
 * 对齐 Python routers/esg.py:
 *   POST /schemes/{id}/fit-pca
 *   POST /schemes/{id}/generate-hjm
 *   POST /schemes/{id}/generate
 *   POST /schemes/{id}/run-all
 *   POST /case/run
 */
@RestController
@RequestMapping("/esg")
@RequiredArgsConstructor
public class EsgExecutionController {

    private final EsgExecutionService executionService;

    @PostMapping("/schemes/{id}/fit-pca")
    public R<Map<String, Object>> fitPca(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        return executionService.fitPca(id, body != null ? body : Map.of());
    }

    @PostMapping("/schemes/{id}/generate-hjm")
    public R<Map<String, Object>> generateHjm(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        return executionService.generateHjm(id, body != null ? body : Map.of());
    }

    @PostMapping("/schemes/{id}/generate")
    public R<Map<String, Object>> generateScenarios(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        return executionService.generateScenarios(id, body != null ? body : Map.of());
    }

    @PostMapping("/schemes/{id}/run-all")
    public R<Map<String, Object>> runAll(@PathVariable("id") Long id) {
        return executionService.runAll(id);
    }

    @PostMapping("/case/run")
    public R<Map<String, Object>> caseRun(@RequestBody(required = false) Map<String, Object> body) {
        return executionService.caseRun(body != null ? body : Map.of());
    }
}