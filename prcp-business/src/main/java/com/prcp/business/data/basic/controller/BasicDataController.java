package com.prcp.business.data.basic.controller;

import com.prcp.business.data.basic.service.BasicDataService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 基础数据维护
 * - /basic-data/list：列表（scheme/coaNode/start/end/date/category/nodeKw + 全 64 桶 + 7 度量）
 * - /basic-data/matrix：节点 × 桶 透视（8 代表桶 + 7 度量）
 * - /basic-data/dates：可用数据日期列表
 * - /basic-data/upsert：保存（4 元组 + 64 桶 + 7 度量 + 自动补节点元数据）
 * - /basic-data/{id}：逻辑删除（is_deleted=1）
 * - /basic-data/delete-batch：批量逻辑删除
 * - /basic-data/export-xlsx：导出 145 列宽表（4 行表头 + 冻结 + 着色）
 * - /basic-data/import-xlsx：导入 xlsx（multipart + 4 元组 upsert + 错误清单）
 * - /basic-data/preview-xlsx：导入预览（校验不入库）
 */
@RestController
@RequestMapping("/basic-data")
@RequiredArgsConstructor
public class BasicDataController {

    private final BasicDataService basicDataService;

    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long schemeId,
                                              @RequestParam(required = false) Long coaNodeId,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(required = false) String dataDate,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String nodeKw) {
        return basicDataService.list(schemeId, coaNodeId, startDate, endDate, dataDate, category, nodeKw);
    }

    @GetMapping("/matrix")
    public R<Map<String, Object>> matrix(@RequestParam(required = false) Long schemeId,
                                          @RequestParam(required = false) String dataDate) {
        return basicDataService.matrix(schemeId, dataDate);
    }

    /**
     * 按方案二维矩阵（账户册节点 × 期限桶 + 大类汇总）
     * GET /basic-data/by-scheme-matrix?schemeId=1&dataDate=2025-12-31&dateOffset=0&offsetUnit=D
     * 对齐 Python basic.py by-scheme-matrix：返回 {schemeId, dataDate, dateOffset, offsetUnit, nodes, matrix, categories, buckets}
     */
    @GetMapping("/by-scheme-matrix")
    public R<Map<String, Object>> bySchemeMatrix(@RequestParam Long schemeId,
                                                  @RequestParam String dataDate,
                                                  @RequestParam(required = false, defaultValue = "0") Integer dateOffset,
                                                  @RequestParam(required = false, defaultValue = "D") String offsetUnit) {
        return basicDataService.bySchemeMatrix(schemeId, dataDate, dateOffset, offsetUnit);
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

    @DeleteMapping("/delete-batch")
    public R<Map<String, Object>> deleteBatch(@RequestBody Map<String, Object> body) {
        return basicDataService.deleteBatch(body);
    }

    @GetMapping("/export-xlsx")
    public void exportXlsx(@RequestParam(required = false) Long schemeId,
                           @RequestParam(required = false) String dataDate,
                           @RequestParam(required = false) Integer dateOffset,
                           @RequestParam(required = false) String offsetUnit,
                           HttpServletResponse response) throws IOException {
        basicDataService.exportXlsx(schemeId, dataDate, dateOffset, offsetUnit, response);
    }

    @PostMapping("/import-xlsx")
    public R<Map<String, Object>> importXlsx(@RequestParam("file") MultipartFile file,
                                              @RequestParam(required = false) Long schemeId,
                                              @RequestParam(required = false) Integer dateOffset,
                                              @RequestParam(required = false) String offsetUnit,
                                              @RequestParam(required = false) Boolean dryRun) {
        return basicDataService.importXlsx(file, schemeId, dateOffset, offsetUnit, dryRun);
    }

    /** 兼容旧接口：preview = dryRun=true 的 import */
    @PostMapping("/preview-xlsx")
    public R<Map<String, Object>> previewXlsx(@RequestParam("file") MultipartFile file,
                                               @RequestParam(required = false) Long schemeId,
                                               @RequestParam(required = false) Integer dateOffset,
                                               @RequestParam(required = false) String offsetUnit) {
        return basicDataService.importXlsx(file, schemeId, dateOffset, offsetUnit, Boolean.TRUE);
    }
}
