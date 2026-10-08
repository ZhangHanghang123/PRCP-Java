package com.prcp.business.params.eve;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/eve-param")
@RequiredArgsConstructor
public class EveParamController {

    private final EveParamService eveParamService;

    /**
     * 列表查询：schemeId / nodeId / dataDate / keyword
     */
    @GetMapping("")
    public R<Map<String, Object>> list(@RequestParam(required = false) Long schemeId,
                                        @RequestParam(required = false) Long nodeId,
                                        @RequestParam(required = false) String dataDate,
                                        @RequestParam(required = false) String keyword) {
        return R.ok(eveParamService.query(schemeId, nodeId, dataDate, keyword));
    }

    /**
     * 下拉选项：账户册方案 + 节点 + EVE_OPERATOR 字典 + 已用数据日期
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return R.ok(eveParamService.listOptions());
    }

    /**
     * 新增：ID = {scheme_code}_{node_code}_{YYYYMMDD}
     */
    @PostMapping("")
    public R<EveParamEntity> create(@RequestBody EveParamEntity payload) {
        return R.ok(eveParamService.create(payload));
    }

    /**
     * 局部更新（仅数值/运算符/久期/余额/规则说明/状态可改）
     */
    @PutMapping("/{id}")
    public R<EveParamEntity> update(@PathVariable String id, @RequestBody EveParamEntity patch) {
        return R.ok(eveParamService.update(id, patch));
    }

    /**
     * 软删
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        eveParamService.delete(id);
        return R.ok();
    }
}