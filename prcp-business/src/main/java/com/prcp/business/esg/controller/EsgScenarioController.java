package com.prcp.business.esg.controller;

import com.prcp.business.esg.service.EsgScenarioService;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ESG 情景集 4 端点
 */
@RestController
@RequestMapping("/esg/scenarios")
@RequiredArgsConstructor
public class EsgScenarioController {

    private final EsgScenarioService scenarioService;

    @GetMapping
    public R<Map<String, Object>> listScenarios(@RequestParam(required = false) Long schemeId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        return scenarioService.listScenarios(schemeId, page, pageSize);
    }

    @GetMapping("/{scenarioCode}")
    public R<Map<String, Object>> getScenario(@PathVariable("scenarioCode") String scenarioCode) {
        return scenarioService.getScenario(scenarioCode);
    }

    @GetMapping("/{scenarioCode}/stats")
    public R<Map<String, Object>> getStats(@PathVariable("scenarioCode") String scenarioCode) {
        return scenarioService.getStats(scenarioCode);
    }

    @GetMapping("/{scenarioCode}/download")
    public ResponseEntity<byte[]> download(@PathVariable("scenarioCode") String scenarioCode) {
        byte[] data = scenarioService.downloadNumpy(scenarioCode);
        if (data == null || data.length == 0) {
            throw BizException.badRequest("scenario " + scenarioCode + " 数据为空");
        }
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        h.setContentDispositionFormData("attachment", "scenario_" + scenarioCode + ".npz");
        h.setContentLength(data.length);
        return ResponseEntity.ok().headers(h).body(data);
    }
}