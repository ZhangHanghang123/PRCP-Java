package com.prcp.business.params.roe;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ROE 参数补录 API（5 端点，对位 Python /roe-param prefix）
 *
 * 端点：
 *   GET    ""            列表（带筛选：schemeId/nodeId/dataDate/keyword）
 *   GET    "/options"    下拉选项（方案 + 节点 + ROE_OPERATOR 字典 + 可用日期）
 *   POST   ""            新增（ID = {scheme_code}_{node_code}_{YYYYMMDD}）
 *   PUT    "/{id}"       部分更新（数值/运算符/余额/规则说明/状态）
 *   DELETE "/{id}"       软删
 *
 * @author PRCP WorkBuddy Agent
 * @date 2026-10-08
 */
@RestController
@RequestMapping("/roe-param")
@RequiredArgsConstructor
public class RoeParamController {

    private final RoeParamService svc;

    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) Long schemeId,
                                        @RequestParam(required = false) Long nodeId,
                                        @RequestParam(required = false) String dataDate,
                                        @RequestParam(required = false) String keyword) {
        return svc.query(schemeId, nodeId, dataDate, keyword);
    }

    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return svc.listOptions();
    }

    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return svc.create(body);
    }

    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable("id") String id,
                                          @RequestBody Map<String, Object> body) {
        return svc.update(id, body);
    }

    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable("id") String id) {
        return svc.delete(id);
    }
}