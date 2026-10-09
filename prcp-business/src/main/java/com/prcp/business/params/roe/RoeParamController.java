package com.prcp.business.params.roe;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>ROE 参数补录 Controller (ROE = 净资产收益率, Return on Equity)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: ROE 试算所需的净利润/平均股东权益相关补录参数, 用于月度 ROE 走势预测和同业对标</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>GET /roe-param — 列表 (schemeId/nodeId/dataDate/keyword 过滤)</li>
 *       <li>GET /roe-param/options — 下拉选项 (方案 + 节点 + ROE_OPERATOR 字典 + 可用日期)</li>
 *       <li>POST /roe-param — 新增 (ID = {scheme_code}_{node_code}_{YYYYMMDD})</li>
 *       <li>PUT /roe-param/{id} — 部分更新 (数值/运算符/余额/规则说明/状态)</li>
 *       <li>DELETE /roe-param/{id} — 软删</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: RoeParamService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /roe-param} (对位 Python /roe-param prefix)</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.params.roe.RoeParamService
 */
@RestController
@RequestMapping("/roe-param")
@RequiredArgsConstructor
public class RoeParamController {

    private final RoeParamService svc;

    /**
     * <p>列表查询 (schemeId/nodeId/dataDate/keyword 过滤)</p>
     *
     * <pre>
     * GET /roe-param
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
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) Long schemeId,
                                        @RequestParam(required = false) Long nodeId,
                                        @RequestParam(required = false) String dataDate,
                                        @RequestParam(required = false) String keyword) {
        return svc.query(schemeId, nodeId, dataDate, keyword);
    }

    /**
     * <p>下拉选项 (方案 + 节点 + ROE_OPERATOR 字典 + 可用日期)</p>
     *
     * <pre>
     * GET /roe-param/options
     * Response: R.ok({schemes, nodes, operators: ROE_OPERATOR, data_dates})
     * </pre>
     *
     * @return R.ok(下拉选项)
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return svc.listOptions();
    }

    /**
     * <p>新增 ROE 参数 (ID = {scheme_code}_{node_code}_{YYYYMMDD})</p>
     *
     * <pre>
     * POST /roe-param
     * Body: {schemeId, nodeId, dataDate, numeratorFactor, balance, ...}
     *
     * Response: R.ok(新建参数)
     * </pre>
     *
     * @param body 请求体 (含方案/节点/日期 + 数值字段)
     * @return R.ok(新建参数)
     */
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return svc.create(body);
    }

    /**
     * <p>部分更新 (数值/运算符/余额/规则说明/状态)</p>
     *
     * <pre>
     * PUT /roe-param/{id}
     * Path: id (String, required) - 复合主键
     * Body: 更新字段
     *
     * Response: R.ok(更新后参数)
     * </pre>
     *
     * @param id   复合主键
     * @param body 更新内容
     * @return R.ok(更新后参数)
     */
    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable("id") String id,
                                          @RequestBody Map<String, Object> body) {
        return svc.update(id, body);
    }

    /**
     * <p>软删 ROE 参数</p>
     *
     * <pre>
     * DELETE /roe-param/{id}
     * Path: id (String, required) - 复合主键
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 复合主键
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable("id") String id) {
        return svc.delete(id);
    }
}