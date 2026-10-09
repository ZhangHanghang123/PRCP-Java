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
 * <p>CET1 参数补录 Controller (CET1 = 一级资本充足率)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: CET1 资本计量所需的手工补录参数 (分子因子/RWA 系数/规则说明), 用于月度 CET1 试算与监管报送</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>GET /cet1-param — 列表 (schemeId/nodeId/dataDate/keyword 过滤)</li>
 *       <li>GET /cet1-param/options — 下拉选项 (账户册方案 + 节点 + 运算符字典 + 最近 60 个数据日期)</li>
 *       <li>POST /cet1-param — 新增 (ID = {scheme_code}_{node_code}_{YYYYMMDD})</li>
 *       <li>PUT /cet1-param/{id} — 更新 (仅分子/RWA/规则/状态)</li>
 *       <li>DELETE /cet1-param/{id} — 软删</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: Cet1ParamService、Cet1ParamEntity</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /cet1-param}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.params.cet1.Cet1ParamService
 */
@RestController
@RequestMapping("/cet1-param")
@RequiredArgsConstructor
public class Cet1ParamController {

    private final Cet1ParamService service;

    /**
     * <p>列表查询 (按方案/节点/日期/关键字过滤)</p>
     *
     * <pre>
     * GET /cet1-param
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
        return R.ok(service.query(schemeId, nodeId, dataDate, keyword));
    }

    /**
     * <p>下拉选项 (账户册方案 + 节点 + 运算符字典 + 最近 60 个数据日期)</p>
     *
     * <pre>
     * GET /cet1-param/options
     * Response: R.ok({schemes: [...], nodes: [...], operators: [...], data_dates: [...]})
     * </pre>
     *
     * @return R.ok(下拉选项)
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return R.ok(service.listOptions());
    }

    /**
     * <p>新增 CET1 参数 (ID = {scheme_code}_{node_code}_{YYYYMMDD})</p>
     *
     * <pre>
     * POST /cet1-param
     * Body: Cet1ParamEntity (schemeId, nodeId, dataDate, numeratorFactor, rwaFactor, ...)
     *
     * Response: R.ok(Cet1ParamEntity) 新建参数
     * </pre>
     *
     * @param e CET1 参数实体
     * @return R.ok(新建参数)
     */
    @PostMapping("")
    public R<Cet1ParamEntity> create(@RequestBody Cet1ParamEntity e) {
        return R.ok(service.create(e));
    }

    /**
     * <p>更新 CET1 参数 (仅分子/RWA/规则/状态可改)</p>
     *
     * <pre>
     * PUT /cet1-param/{id}
     * Path: id (String, required) - 复合主键 {scheme_code}_{node_code}_{YYYYMMDD}
     * Body: Cet1ParamEntity (更新字段)
     *
     * Response: R.ok(Cet1ParamEntity) 更新后参数
     * </pre>
     *
     * @param id 复合主键
     * @param e  CET1 参数实体
     * @return R.ok(更新后参数)
     */
    @PutMapping("/{id}")
    public R<Cet1ParamEntity> update(@PathVariable String id, @RequestBody Cet1ParamEntity e) {
        return R.ok(service.update(id, e));
    }

    /**
     * <p>软删 CET1 参数</p>
     *
     * <pre>
     * DELETE /cet1-param/{id}
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
        service.delete(id);
        return R.ok();
    }
}