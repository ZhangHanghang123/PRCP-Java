package com.prcp.business.params.cet1;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CET1 参数补录 REST 接口
 *
 * 端点：
 *  GET    /cet1-param            列表（带筛选）
 *  GET    /cet1-param/options    下拉选项（账户册方案 + 节点 + 运算符字典 + 最近 60 个数据日期）
 *  POST   /cet1-param            新增（自动生成 id）
 *  PUT    /cet1-param/{id}       更新（只更新分子/RWA/规则/状态）
 *  DELETE /cet1-param/{id}       软删
 */
@RestController
@RequestMapping("/cet1-param")
@RequiredArgsConstructor
public class Cet1ParamController {

    private final Cet1ParamService service;

    @GetMapping("")
    public R<Map<String, Object>> list(@RequestParam(required = false) Long schemeId,
                                       @RequestParam(required = false) Long nodeId,
                                       @RequestParam(required = false) String dataDate,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(service.query(schemeId, nodeId, dataDate, keyword));
    }

    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return R.ok(service.listOptions());
    }

    @PostMapping("")
    public R<Cet1ParamEntity> create(@RequestBody Cet1ParamEntity e) {
        return R.ok(service.create(e));
    }

    @PutMapping("/{id}")
    public R<Cet1ParamEntity> update(@PathVariable String id, @RequestBody Cet1ParamEntity e) {
        return R.ok(service.update(id, e));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        service.delete(id);
        return R.ok();
    }
}