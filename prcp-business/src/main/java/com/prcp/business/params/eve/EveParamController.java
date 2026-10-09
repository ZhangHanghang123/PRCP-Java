package com.prcp.business.params.eve;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>EVE 参数补录 Controller (EVE = 经济价值变动, Economic Value of Equity)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 利率风险计量 (IRRBB) 中经济价值变动所需的久期/重定价参数, 用于压力测试和监管报送</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>GET /eve-param — 列表</li>
 *       <li>GET /eve-param/options — 下拉选项</li>
 *       <li>POST /eve-param — 新增</li>
 *       <li>PUT /eve-param/{id} — 局部更新</li>
 *       <li>DELETE /eve-param/{id} — 软删</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: EveParamService、EveParamEntity</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /eve-param}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.params.eve.EveParamService
 */
@RestController
@RequestMapping("/eve-param")
@RequiredArgsConstructor
public class EveParamController {

    private final EveParamService eveParamService;

    /**
     * <p>列表查询 (schemeId / nodeId / dataDate / keyword 过滤)</p>
     *
     * <pre>
     * GET /eve-param
     * Query: schemeId (Long, optional) - 方案 ID
     *        nodeId   (Long, optional) - 节点 ID
     *        dataDate (yyyy-MM-dd, optional) - 数据日期
     *        keyword  (String, optional) - 模糊搜索关键字
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
        return R.ok(eveParamService.query(schemeId, nodeId, dataDate, keyword));
    }

    /**
     * <p>下拉选项 (账户册方案 + 节点 + EVE_OPERATOR 字典 + 已用数据日期)</p>
     *
     * <pre>
     * GET /eve-param/options
     * Response: R.ok({schemes, nodes, operators: EVE_OPERATOR, data_dates})
     * </pre>
     *
     * @return R.ok(下拉选项)
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return R.ok(eveParamService.listOptions());
    }

    /**
     * <p>新增 EVE 参数 (ID = {scheme_code}_{node_code}_{YYYYMMDD})</p>
     *
     * <pre>
     * POST /eve-param
     * Body: EveParamEntity (schemeId, nodeId, dataDate, duration, balance, ...)
     *
     * Response: R.ok(EveParamEntity) 新建参数
     * </pre>
     *
     * @param payload EVE 参数实体
     * @return R.ok(新建参数)
     */
    @PostMapping("")
    public R<EveParamEntity> create(@RequestBody EveParamEntity payload) {
        return R.ok(eveParamService.create(payload));
    }

    /**
     * <p>局部更新 (仅数值/运算符/久期/余额/规则说明/状态可改)</p>
     *
     * <pre>
     * PUT /eve-param/{id}
     * Path: id (String, required) - 复合主键 {scheme_code}_{node_code}_{YYYYMMDD}
     * Body: EveParamEntity (更新字段)
     *
     * Response: R.ok(EveParamEntity) 更新后参数
     * </pre>
     *
     * @param id    复合主键
     * @param patch 更新内容
     * @return R.ok(更新后参数)
     */
    @PutMapping("/{id}")
    public R<EveParamEntity> update(@PathVariable String id, @RequestBody EveParamEntity patch) {
        return R.ok(eveParamService.update(id, patch));
    }

    /**
     * <p>软删 EVE 参数</p>
     *
     * <pre>
     * DELETE /eve-param/{id}
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
        eveParamService.delete(id);
        return R.ok();
    }
}