package com.prcp.business.params.nim;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * NIM 参数补录 Controller
 * <p>对齐 Python routers/nim_param.py，端点前缀 /nim-param</p>
 */
@Slf4j
@RestController
@RequestMapping("/nim-param")
@RequiredArgsConstructor
public class NimParamController {

    private final NimParamService nimParamService;

    /** GET /nim-param — 列表（带筛选） */
    @GetMapping("")
    public R<Map<String, Object>> list(@RequestParam(required = false) Integer schemeId,
                                       @RequestParam(required = false) Integer nodeId,
                                       @RequestParam(required = false) String dataDate,
                                       @RequestParam(required = false) String keyword) {
        return nimParamService.query(schemeId, nodeId, dataDate, keyword);
    }

    /** GET /nim-param/options — 下拉选项（账户册方案 + 节点 + 运算符字典 + 历史日期） */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return nimParamService.listOptions();
    }

    /** POST /nim-param — 新增 */
    @PostMapping("")
    public R<Map<String, Object>> create(@RequestBody NimParamEntity payload) {
        return nimParamService.create(payload);
    }

    /** PUT /nim-param/{id} — 局部更新 */
    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable("id") String id, @RequestBody NimParamEntity payload) {
        return nimParamService.update(id, payload);
    }

    /** DELETE /nim-param/{id} — 软删 */
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable("id") String id) {
        return nimParamService.delete(id);
    }
}
