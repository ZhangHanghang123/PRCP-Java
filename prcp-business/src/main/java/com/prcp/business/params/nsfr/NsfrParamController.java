package com.prcp.business.params.nsfr;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * NSFR 参数补录 Controller
 * <p>对齐 Python routers/nsfr_param.py：</p>
 * <ul>
 *   <li>GET    /nsfr-param           列表（带筛选）</li>
 *   <li>GET    /nsfr-param/options   下拉选项</li>
 *   <li>POST   /nsfr-param           新增</li>
 *   <li>PUT    /nsfr-param/{id}      更新</li>
 *   <li>DELETE /nsfr-param/{id}      软删</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/nsfr-param")
@RequiredArgsConstructor
public class NsfrParamController {

    private final NsfrParamService nsfrParamService;

    /**
     * 列表（GET /nsfr-param）
     */
    @GetMapping("")
    public R<Map<String, Object>> list(@RequestParam(required = false) Long schemeId,
                                        @RequestParam(required = false) Long nodeId,
                                        @RequestParam(required = false) String dataDate,
                                        @RequestParam(required = false) String keyword) {
        return R.ok(nsfrParamService.query(schemeId, nodeId, dataDate, keyword));
    }

    /**
     * 下拉选项（GET /nsfr-param/options）
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return R.ok(nsfrParamService.listOptions());
    }

    /**
     * 新增（POST /nsfr-param）
     */
    @PostMapping("")
    public R<NsfrParamEntity> create(@RequestBody NsfrParamEntity entity) {
        return R.ok(nsfrParamService.create(entity));
    }

    /**
     * 更新（PUT /nsfr-param/{id}）
     */
    @PutMapping("/{id}")
    public R<NsfrParamEntity> update(@PathVariable String id, @RequestBody NsfrParamEntity entity) {
        entity.setId(id);
        return R.ok(nsfrParamService.update(entity));
    }

    /**
     * 软删（DELETE /nsfr-param/{id}）
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        nsfrParamService.delete(id);
        return R.ok();
    }
}