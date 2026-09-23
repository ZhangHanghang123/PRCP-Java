package com.prcp.business.metric.controller;

import com.prcp.business.metric.entity.MetricCoefficient;
import com.prcp.business.metric.service.MetricService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/metric-coefficient")
@RequiredArgsConstructor
public class MetricController {

    private final MetricService metricService;

    @GetMapping("")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long schemeId,
                                              @RequestParam(required = false) String nodeCode,
                                              @RequestParam(required = false) String metricCode,
                                              @RequestParam(required = false) String dataDate) {
        return R.ok(metricService.query(schemeId, nodeCode, metricCode, dataDate));
    }

    @GetMapping("/options")
    public R<Map<String, Object>> options(@RequestParam(required = false) String schemeId) {
        // 兼容 axios 把 null 序列化成 "null" 的情况
        Long sid = null;
        if (schemeId != null && !"null".equalsIgnoreCase(schemeId) && !schemeId.isEmpty()) {
            try { sid = Long.parseLong(schemeId); } catch (NumberFormatException ignore) {}
        }
        return R.ok(Map.of(
                "schemes", metricService.listSchemeOptions(),
                "nodes", metricService.listNodeOptions(sid),
                "metrics", metricService.listMetricOptions()
        ));
    }

    @PostMapping("")
    public R<MetricCoefficient> create(@RequestBody MetricCoefficient mc) {
        return R.ok(metricService.create(mc));
    }

    @PutMapping("/{id}")
    public R<MetricCoefficient> update(@PathVariable String id, @RequestBody MetricCoefficient mc) {
        mc.setId(id);
        return R.ok(metricService.update(mc));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        metricService.delete(id);
        return R.ok();
    }
}
