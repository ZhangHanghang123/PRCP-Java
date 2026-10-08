package com.prcp.business.params.lcr;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * LCR 参数补录 Controller
 * <p>路径前缀 /lcr-param（对位 Python app/routers/lcr_param.py）</p>
 *
 * <p>ID 规则：{scheme_code}_{node_code}_{YYYYMMDD}</p>
 *
 * @author WorkBuddy Agent
 * @date 2026-10-08
 */
@Slf4j
@RestController
@RequestMapping("/lcr-param")
@RequiredArgsConstructor
public class LcrParamController {

    private final LcrParamService svc;

    /**
     * GET /lcr-param — 列表查询（默认过滤 is_deleted=0）
     */
    @GetMapping
    public R<Map<String, Object>> list(
            @RequestParam(required = false) Long schemeId,
            @RequestParam(required = false) Long nodeId,
            @RequestParam(required = false) String dataDate,
            @RequestParam(required = false) String keyword) {
        return svc.query(schemeId, nodeId, dataDate, keyword);
    }

    /**
     * GET /lcr-param/options — 下拉选项（方案 + 节点 + LCR_OPERATOR 字典 + 已有数据日期）
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return svc.listOptions();
    }

    /**
     * POST /lcr-param — 新增
     * <p>主键 = {scheme_code}_{node_code}_{YYYYMMDD}</p>
     */
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody LcrParamEntity body) {
        log.info("[lcr-param.create] scheme={}, node={}, date={}",
                body.getSchemeCode(), body.getNodeCode(), body.getDataDate());
        return svc.create(body);
    }

    /**
     * PUT /lcr-param/{id} — 部分更新（null 字段跳过）
     */
    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable String id,
                                         @RequestBody LcrParamEntity body) {
        return svc.update(id, body);
    }

    /**
     * DELETE /lcr-param/{id} — 软删
     */
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable String id) {
        return svc.softDelete(id);
    }
}
