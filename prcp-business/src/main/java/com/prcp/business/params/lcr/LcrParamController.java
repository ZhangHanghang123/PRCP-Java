package com.prcp.business.params.lcr;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>LCR 参数补录 Controller (LCR = 流动性覆盖率)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: LCR 计量所需的分子 (合格优质流动性资产) 和分母 (现金净流出) 的手工补录参数</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>GET /lcr-param — 列表</li>
 *       <li>GET /lcr-param/options — 下拉选项</li>
 *       <li>POST /lcr-param — 新增</li>
 *       <li>PUT /lcr-param/{id} — 部分更新</li>
 *       <li>DELETE /lcr-param/{id} — 软删</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: LcrParamService、LcrParamEntity</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /lcr-param} (对位 Python app/routers/lcr_param.py)</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.params.lcr.LcrParamService
 */
@Slf4j
@RestController
@RequestMapping("/lcr-param")
@RequiredArgsConstructor
public class LcrParamController {

    private final LcrParamService svc;

    /**
     * <p>列表查询 (默认过滤 is_deleted=0)</p>
     *
     * <pre>
     * GET /lcr-param
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
    @GetMapping
    public R<Map<String, Object>> list(
            @RequestParam(required = false) Long schemeId,
            @RequestParam(required = false) Long nodeId,
            @RequestParam(required = false) String dataDate,
            @RequestParam(required = false) String keyword) {
        return svc.query(schemeId, nodeId, dataDate, keyword);
    }

    /**
     * <p>下拉选项 (方案 + 节点 + LCR_OPERATOR 字典 + 已有数据日期)</p>
     *
     * <pre>
     * GET /lcr-param/options
     * Response: R.ok({schemes, nodes, operators: LCR_OPERATOR, data_dates})
     * </pre>
     *
     * @return R.ok(下拉选项)
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return svc.listOptions();
    }

    /**
     * <p>新增 LCR 参数 (主键 = {scheme_code}_{node_code}_{YYYYMMDD})</p>
     *
     * <pre>
     * POST /lcr-param
     * Body: LcrParamEntity (schemeId, nodeId, dataDate, hqlaFactor, outflowFactor, ...)
     *
     * Response: R.ok(LcrParamEntity) 新建参数
     * </pre>
     *
     * @param body LCR 参数实体
     * @return R.ok(新建参数)
     */
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody LcrParamEntity body) {
        log.info("[lcr-param.create] scheme={}, node={}, date={}",
                body.getSchemeCode(), body.getNodeCode(), body.getDataDate());
        return svc.create(body);
    }

    /**
     * <p>部分更新 (null 字段跳过)</p>
     *
     * <pre>
     * PUT /lcr-param/{id}
     * Path: id (String, required) - 复合主键
     * Body: LcrParamEntity (更新字段, null 不变)
     *
     * Response: R.ok(LcrParamEntity) 更新后参数
     * </pre>
     *
     * @param id   复合主键
     * @param body 更新内容
     * @return R.ok(更新后参数)
     */
    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable String id,
                                         @RequestBody LcrParamEntity body) {
        return svc.update(id, body);
    }

    /**
     * <p>软删 LCR 参数</p>
     *
     * <pre>
     * DELETE /lcr-param/{id}
     * Path: id (String, required) - 复合主键
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 复合主键
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable String id) {
        return svc.softDelete(id);
    }
}
