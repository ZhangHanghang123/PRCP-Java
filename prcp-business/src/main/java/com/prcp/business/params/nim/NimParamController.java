package com.prcp.business.params.nim;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>NIM 参数补录 Controller (NIM = 净息差, Net Interest Margin)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: NIM 试算所需的利率/余额/重定价参数, 用于月度 NIM 走势预测</li>
 *   <li>核心端点: GET/POST/PUT/DELETE /nim-param 及 GET /nim-param/options</li>
 *   <li>关联模块: NimParamService、NimParamEntity</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /nim-param} (对齐 Python routers/nim_param.py)</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.params.nim.NimParamService
 */
@Slf4j
@RestController
@RequestMapping("/nim-param")
@RequiredArgsConstructor
public class NimParamController {

    private final NimParamService nimParamService;

    /**
     * <p>列表 (带筛选)</p>
     *
     * <pre>
     * GET /nim-param
     * Query: schemeId (Integer, optional) - 方案 ID
     *        nodeId   (Integer, optional) - 节点 ID
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
    public R<Map<String, Object>> list(@RequestParam(required = false) Integer schemeId,
                                       @RequestParam(required = false) Integer nodeId,
                                       @RequestParam(required = false) String dataDate,
                                       @RequestParam(required = false) String keyword) {
        return nimParamService.query(schemeId, nodeId, dataDate, keyword);
    }

    /**
     * <p>下拉选项 (账户册方案 + 节点 + 运算符字典 + 历史日期)</p>
     *
     * <pre>
     * GET /nim-param/options
     * Response: R.ok({schemes, nodes, operators, data_dates})
     * </pre>
     *
     * @return R.ok(下拉选项)
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return nimParamService.listOptions();
    }

    /**
     * <p>新增 NIM 参数</p>
     *
     * <pre>
     * POST /nim-param
     * Body: NimParamEntity (schemeId, nodeId, dataDate, rate, balance, ...)
     *
     * Response: R.ok(NimParamEntity) 新建参数
     * </pre>
     *
     * @param payload NIM 参数实体
     * @return R.ok(新建参数)
     */
    @PostMapping("")
    public R<Map<String, Object>> create(@RequestBody NimParamEntity payload) {
        return nimParamService.create(payload);
    }

    /**
     * <p>局部更新 NIM 参数</p>
     *
     * <pre>
     * PUT /nim-param/{id}
     * Path: id (String, required) - 复合主键
     * Body: NimParamEntity (更新字段)
     *
     * Response: R.ok(NimParamEntity) 更新后参数
     * </pre>
     *
     * @param id      复合主键
     * @param payload 更新内容
     * @return R.ok(更新后参数)
     */
    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable("id") String id, @RequestBody NimParamEntity payload) {
        return nimParamService.update(id, payload);
    }

    /**
     * <p>软删 NIM 参数</p>
     *
     * <pre>
     * DELETE /nim-param/{id}
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
        return nimParamService.delete(id);
    }
}
