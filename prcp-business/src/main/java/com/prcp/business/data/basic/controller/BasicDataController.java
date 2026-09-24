package com.prcp.business.data.basic.controller;

import com.prcp.business.data.basic.service.BasicDataService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 基础数据维护
 * - /basic-data/list：按方案+日期 查节点（orig/rem 64+64 桶）
 * - /basic-data/matrix：透视（节点 × 期限桶）
 * - /basic-data/dates：可用数据日期列表
 * - /basic-data/upsert：保存一条节点 × 日期 的桶数据
 * - /basic-data/{id}：删除
 */
@RestController
@RequestMapping("/basic-data")
@RequiredArgsConstructor
public class BasicDataController {

    private final BasicDataService basicDataService;

    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long schemeId,
                                              @RequestParam(required = false) String dataDate,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String nodeKw) {
        return basicDataService.list(schemeId, dataDate, category, nodeKw);
    }

    @GetMapping("/matrix")
    public R<Map<String, Object>> matrix(@RequestParam(required = false) Long schemeId,
                                          @RequestParam(required = false) String dataDate) {
        return basicDataService.matrix(schemeId, dataDate);
    }

    @GetMapping("/dates")
    public R<List<String>> dates(@RequestParam(required = false) Long schemeId) {
        return basicDataService.dates(schemeId);
    }

    @PostMapping("/upsert")
    public R<?> upsert(@RequestBody Map<String, Object> body) {
        return basicDataService.upsert(body);
    }

    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        return basicDataService.delete(id);
    }
}