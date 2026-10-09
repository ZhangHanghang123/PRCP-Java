package com.prcp.business.data.basic.controller;

import com.prcp.business.data.basic.service.BasicDataService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * <p>基础数据维护 Controller (Basic Data 145 列宽表)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 银行经营分析的核心数据源, 每行 = 1 个 (方案/节点/日期/类别) 4 元组, 含 64 个期限桶 + 7 个度量</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>list — 列表 (scheme/coaNode/start/end/date/category/nodeKw 过滤)</li>
 *       <li>matrix / by-scheme-matrix — 节点 × 桶 透视 (8 代表桶 + 7 度量)</li>
 *       <li>dates — 可用数据日期列表</li>
 *       <li>upsert — 保存 (4 元组 + 64 桶 + 7 度量 + 自动补节点元数据)</li>
 *       <li>delete / delete-batch — 逻辑删除 (is_deleted=1)</li>
 *       <li>export-xlsx — 导出 145 列宽表 (4 行表头 + 冻结 + 着色)</li>
 *       <li>import-xlsx / preview-xlsx — 导入 xlsx (multipart + 错误清单 / dryRun)</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: BasicDataService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /basic-data}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.data.basic.service.BasicDataService
 */
@RestController
@RequestMapping("/basic-data")
@RequiredArgsConstructor
public class BasicDataController {

    private final BasicDataService basicDataService;

    /**
     * <p>列表查询 (多条件过滤)</p>
     *
     * <pre>
     * GET /basic-data/list
     * Query: schemeId  (Long, optional) - 方案 ID
     *        coaNodeId (Long, optional) - 账户册节点 ID
     *        startDate (yyyy-MM-dd, optional) - 起期
     *        endDate   (yyyy-MM-dd, optional) - 止期
     *        dataDate  (yyyy-MM-dd, optional) - 数据日期
     *        category  (String, optional) - 类别
     *        nodeKw    (String, optional) - 节点编码/名称模糊搜索
     *
     * Response: R.ok(List&lt;Map&gt;) 含 4 元组 + 64 桶 + 7 度量
     * </pre>
     *
     * @param schemeId  方案 ID (可选)
     * @param coaNodeId 账户册节点 ID (可选)
     * @param startDate 起期 yyyy-MM-dd (可选)
     * @param endDate   止期 yyyy-MM-dd (可选)
     * @param dataDate  数据日期 yyyy-MM-dd (可选)
     * @param category  类别 (可选)
     * @param nodeKw    节点模糊搜索关键字 (可选)
     * @return R.ok(List&lt;Map&gt;) 基础数据列表
     */
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long schemeId,
                                              @RequestParam(required = false) Long coaNodeId,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(required = false) String dataDate,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String nodeKw) {
        return basicDataService.list(schemeId, coaNodeId, startDate, endDate, dataDate, category, nodeKw);
    }

    /**
     * <p>节点 × 期限桶 透视 (8 代表桶 + 7 度量)</p>
     *
     * <pre>
     * GET /basic-data/matrix
     * Query: schemeId (Long, optional) - 方案 ID
     *        dataDate (yyyy-MM-dd, optional) - 数据日期
     *
     * Response: R.ok({nodes: [...], buckets: [...], matrix: [[...]], metrics: {...}})
     * </pre>
     *
     * @param schemeId 方案 ID (可选)
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @return R.ok(节点 × 桶 矩阵)
     */
    @GetMapping("/matrix")
    public R<Map<String, Object>> matrix(@RequestParam(required = false) Long schemeId,
                                          @RequestParam(required = false) String dataDate) {
        return basicDataService.matrix(schemeId, dataDate);
    }

    /**
     * <p>按方案二维矩阵 (账户册节点 × 期限桶 + 大类汇总)</p>
     *
     * <pre>
     * GET /basic-data/by-scheme-matrix
     * Query: schemeId    (Long, required) - 方案 ID
     *        dataDate    (yyyy-MM-dd, required) - 数据日期
     *        dateOffset  (Integer, optional, default 0) - 日期偏移量
     *        offsetUnit  (String, optional, default "D") - 偏移单位 (D/M/Y)
     *
     * Response: R.ok({schemeId, dataDate, dateOffset, offsetUnit, nodes, matrix, categories, buckets})
     * </pre>
     *
     * @param schemeId   方案 ID
     * @param dataDate   数据日期 yyyy-MM-dd
     * @param dateOffset 日期偏移量 (默认 0)
     * @param offsetUnit 偏移单位 (D=日 / M=月 / Y=年, 默认 D)
     * @return R.ok(节点 × 桶 矩阵 + 大类汇总)
     */
    @GetMapping("/by-scheme-matrix")
    public R<Map<String, Object>> bySchemeMatrix(@RequestParam Long schemeId,
                                                  @RequestParam String dataDate,
                                                  @RequestParam(required = false, defaultValue = "0") Integer dateOffset,
                                                  @RequestParam(required = false, defaultValue = "D") String offsetUnit) {
        return basicDataService.bySchemeMatrix(schemeId, dataDate, dateOffset, offsetUnit);
    }

    /**
     * <p>可用数据日期列表</p>
     *
     * <pre>
     * GET /basic-data/dates
     * Query: schemeId (Long, optional) - 方案 ID (过滤)
     *
     * Response: R.ok(List&lt;String&gt;) yyyy-MM-dd 列表
     * </pre>
     *
     * @param schemeId 方案 ID (可选)
     * @return R.ok(List&lt;String&gt;) 可用日期
     */
    @GetMapping("/dates")
    public R<List<String>> dates(@RequestParam(required = false) Long schemeId) {
        return basicDataService.dates(schemeId);
    }

    /**
     * <p>保存基础数据 (upsert, 4 元组 + 64 桶 + 7 度量, 自动补节点元数据)</p>
     *
     * <pre>
     * POST /basic-data/upsert
     * Body: {schemeId, coaNodeId, dataDate, category, m1~m60, y10, y15, y20, y30, ...}
     *
     * Response: R.ok(持久化结果)
     * </pre>
     *
     * @param body 请求体 (含 4 元组 + 64 桶 + 7 度量)
     * @return R.ok(upsert 结果)
     */
    @PostMapping("/upsert")
    public R<?> upsert(@RequestBody Map<String, Object> body) {
        return basicDataService.upsert(body);
    }

    /**
     * <p>逻辑删除单条 (is_deleted=1)</p>
     *
     * <pre>
     * DELETE /basic-data/{id}
     * Path: id (Long, required) - 主键 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 主键 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        return basicDataService.delete(id);
    }

    /**
     * <p>批量逻辑删除</p>
     *
     * <pre>
     * DELETE /basic-data/delete-batch
     * Body: {ids: [Long, ...]}  或  {schemeId, coaNodeId, dataDate, ...}
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param body 请求体 (含 ids 或筛选条件)
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/delete-batch")
    public R<Map<String, Object>> deleteBatch(@RequestBody Map<String, Object> body) {
        return basicDataService.deleteBatch(body);
    }

    /**
     * <p>导出基础数据到 xlsx (145 列宽表, 4 行表头 + 冻结 + 着色)</p>
     *
     * <pre>
     * GET /basic-data/export-xlsx
     * Query: schemeId   (Long, optional) - 方案 ID
     *        dataDate   (yyyy-MM-dd, optional) - 数据日期
     *        dateOffset (Integer, optional) - 日期偏移量
     *        offsetUnit (String, optional) - 偏移单位
     *
     * Response: 二进制流 (xlsx)
     * </pre>
     *
     * @param schemeId   方案 ID (可选)
     * @param dataDate   数据日期 yyyy-MM-dd (可选)
     * @param dateOffset 日期偏移量 (可选)
     * @param offsetUnit 偏移单位 (可选)
     * @param response   HTTP 响应对象 (用于写入 xlsx 流)
     * @throws IOException IO 异常
     */
    @GetMapping("/export-xlsx")
    public void exportXlsx(@RequestParam(required = false) Long schemeId,
                           @RequestParam(required = false) String dataDate,
                           @RequestParam(required = false) Integer dateOffset,
                           @RequestParam(required = false) String offsetUnit,
                           HttpServletResponse response) throws IOException {
        basicDataService.exportXlsx(schemeId, dataDate, dateOffset, offsetUnit, response);
    }

    /**
     * <p>从 xlsx 导入基础数据 (multipart + 4 元组 upsert + 错误清单)</p>
     *
     * <pre>
     * POST /basic-data/import-xlsx
     * Form: file      (MultipartFile, required) - xlsx 文件
     *       schemeId  (Long, optional) - 默认方案 ID
     *       dateOffset(Integer, optional) - 默认偏移量
     *       offsetUnit(String, optional) - 默认偏移单位
     *       dryRun    (Boolean, optional) - true 仅校验不入库
     *
     * Response: R.ok({imported, skipped, errors: [{row, message}]})
     * </pre>
     *
     * @param file       xlsx 文件
     * @param schemeId   默认方案 ID (可选)
     * @param dateOffset 默认偏移量 (可选)
     * @param offsetUnit 默认偏移单位 (可选)
     * @param dryRun     仅校验不入库 (可选, 默认 false)
     * @return R.ok({imported, skipped, errors})
     */
    @PostMapping("/import-xlsx")
    public R<Map<String, Object>> importXlsx(@RequestParam("file") MultipartFile file,
                                              @RequestParam(required = false) Long schemeId,
                                              @RequestParam(required = false) Integer dateOffset,
                                              @RequestParam(required = false) String offsetUnit,
                                              @RequestParam(required = false) Boolean dryRun) {
        return basicDataService.importXlsx(file, schemeId, dateOffset, offsetUnit, dryRun);
    }

    /**
     * <p>导入预览 (兼容旧接口: 等同 dryRun=true 的 import)</p>
     *
     * <pre>
     * POST /basic-data/preview-xlsx
     * Form: file      (MultipartFile, required) - xlsx 文件
     *       schemeId  (Long, optional) - 默认方案 ID
     *       dateOffset(Integer, optional) - 默认偏移量
     *       offsetUnit(String, optional) - 默认偏移单位
     *
     * Response: R.ok({imported, skipped, errors}) — 校验结果, 不入库
     * </pre>
     *
     * @param file       xlsx 文件
     * @param schemeId   默认方案 ID (可选)
     * @param dateOffset 默认偏移量 (可选)
     * @param offsetUnit 默认偏移单位 (可选)
     * @return R.ok(校验结果)
     */
    @PostMapping("/preview-xlsx")
    public R<Map<String, Object>> previewXlsx(@RequestParam("file") MultipartFile file,
                                               @RequestParam(required = false) Long schemeId,
                                               @RequestParam(required = false) Integer dateOffset,
                                               @RequestParam(required = false) String offsetUnit) {
        return basicDataService.importXlsx(file, schemeId, dateOffset, offsetUnit, Boolean.TRUE);
    }
}
