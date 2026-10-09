package com.prcp.business.params.nsfr;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>NSFR 参数补录 Controller (NSFR = 净稳定资金比例, Net Stable Funding Ratio)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: NSFR 计量所需的可用稳定资金 (ASF) 和所需稳定资金 (RSF) 的手工补录参数</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>GET /nsfr-param — 列表 (带筛选)</li>
 *       <li>GET /nsfr-param/options — 下拉选项</li>
 *       <li>POST /nsfr-param — 新增</li>
 *       <li>PUT /nsfr-param/{id} — 更新</li>
 *       <li>DELETE /nsfr-param/{id} — 软删</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: NsfrParamService、NsfrParamEntity</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /nsfr-param} (对齐 Python routers/nsfr_param.py)</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.params.nsfr.NsfrParamService
 */
@Slf4j
@RestController
@RequestMapping("/nsfr-param")
@RequiredArgsConstructor
public class NsfrParamController {

    private final NsfrParamService nsfrParamService;

    /**
     * <p>列表查询</p>
     *
     * <pre>
     * GET /nsfr-param
     * Query: schemeId (Long, optional) - 方案 ID
     *        nodeId   (Long, optional) - 节点 ID
     *        dataDate (yyyy-MM-dd, optional) - 数据日期
     *        keyword  (String, optional) - 模糊搜索
     *
     * Response: R.ok({items: [...], total})
     * </pre>
     *
     * @param schemeId 方案 ID (可选)
     * @param nodeId   节点 ID (可选)
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @param keyword  模糊搜索关键字 (可选)
     * @return R.ok({items, total})
     */
    @GetMapping("")
    public R<Map<String, Object>> list(@RequestParam(required = false) Long schemeId,
                                        @RequestParam(required = false) Long nodeId,
                                        @RequestParam(required = false) String dataDate,
                                        @RequestParam(required = false) String keyword) {
        return R.ok(nsfrParamService.query(schemeId, nodeId, dataDate, keyword));
    }

    /**
     * <p>下拉选项</p>
     *
     * <pre>
     * GET /nsfr-param/options
     * Response: R.ok({schemes, nodes, operators: NSFR_OPERATOR, data_dates})
     * </pre>
     *
     * @return R.ok(下拉选项)
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return R.ok(nsfrParamService.listOptions());
    }

    /**
     * <p>新增 NSFR 参数</p>
     *
     * <pre>
     * POST /nsfr-param
     * Body: NsfrParamEntity (schemeId, nodeId, dataDate, asfFactor, rsfFactor, ...)
     *
     * Response: R.ok(NsfrParamEntity) 新建参数
     * </pre>
     *
     * @param entity NSFR 参数实体
     * @return R.ok(新建参数)
     */
    @PostMapping("")
    public R<NsfrParamEntity> create(@RequestBody NsfrParamEntity entity) {
        return R.ok(nsfrParamService.create(entity));
    }

    /**
     * <p>更新 NSFR 参数</p>
     *
     * <pre>
     * PUT /nsfr-param/{id}
     * Path: id (String, required) - 复合主键
     * Body: NsfrParamEntity (更新字段)
     *
     * Response: R.ok(NsfrParamEntity) 更新后参数
     * </pre>
     *
     * @param id     复合主键
     * @param entity 更新内容
     * @return R.ok(更新后参数)
     */
    @PutMapping("/{id}")
    public R<NsfrParamEntity> update(@PathVariable String id, @RequestBody NsfrParamEntity entity) {
        entity.setId(id);
        return R.ok(nsfrParamService.update(entity));
    }

    /**
     * <p>软删 NSFR 参数</p>
     *
     * <pre>
     * DELETE /nsfr-param/{id}
     * Path: id (String, required) - 复合主键
     *
     * Response: R.ok()
     * </pre>
     *
     * @param id 复合主键
     * @return R.ok()
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        nsfrParamService.delete(id);
        return R.ok();
    }
}