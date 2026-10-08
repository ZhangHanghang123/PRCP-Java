package com.prcp.business.esg.controller;

import com.prcp.business.esg.service.EsgRunService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ESG 运行历史 3 端点
 */
@RestController
@RequestMapping("/esg")
@RequiredArgsConstructor
public class EsgRunController {

    private final EsgRunService runService;

    /** 方案级 run 历史 */
    @GetMapping("/schemes/{schemeId}/runs")
    public R<Map<String, Object>> listByScheme(@PathVariable("schemeId") Long schemeId,
                                                 @RequestParam(required = false) String runType,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "50") int limit) {
        return runService.listByScheme(schemeId, runType, status, limit);
    }

    /** 全局 run 历史（含分页） */
    @GetMapping("/runs")
    public R<Map<String, Object>> listAll(@RequestParam(required = false) Long schemeId,
                                           @RequestParam(required = false) String runType,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "50") int pageSize) {
        return runService.listAll(schemeId, runType, status, page, Math.min(pageSize, 200));
    }

    /** 单 run 详情 */
    @GetMapping("/runs/{id}")
    public R<Map<String, Object>> getRun(@PathVariable("id") Long id) {
        return runService.getRun(id);
    }
}