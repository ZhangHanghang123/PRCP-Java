package com.prcp.business.params.nsfr;

import com.prcp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>NSFR (净稳定资金比例) 参数补录 Service</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>列表查询 (4 个过滤条件)</li>
 *   <li>下拉选项 (4 类: schemes/nodes/operators/data_dates)</li>
 *   <li>新增 (自动生成主键 + 自动补 node_name + 默认值)</li>
 *   <li>部分更新 (skip null)</li>
 *   <li>软删除</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>复合主键: {scheme_code}_{node_code}_{YYYYMMDD}</li>
 *   <li>软删除: is_deleted=1, 不物理删除</li>
 *   <li>默认值: status='ACTIVE', is_asf=0, is_rsf=0, operator='+', factor=0</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.params.nsfr.NsfrParamMapper
 * @see com.prcp.business.params.nsfr.NsfrParamEntity
 */
@Service
@RequiredArgsConstructor
public class NsfrParamService {

    private final NsfrParamMapper nsfrParamMapper;

    /**
     * <p>列表查询 (4 个过滤条件, 空串视为无过滤)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param nodeId   节点 ID (可选)
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @param keyword  关键字 (可选)
     * @return Map.of("items", list, "total", list.size())
     */
    public Map<String, Object> query(Long schemeId, Long nodeId, String dataDate, String keyword) {
        String dd = (dataDate == null || dataDate.isEmpty()) ? null : dataDate;
        String kw = (keyword == null || keyword.isEmpty()) ? null : keyword;
        List<Map<String, Object>> rows = nsfrParamMapper.query(schemeId, nodeId, dd, kw);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", rows);
        resp.put("total", rows.size());
        return resp;
    }

    /**
     * <p>下拉选项 (4 类: schemes/nodes/operators/data_dates)</p>
     *
     * @return Map 含 schemes/nodes/operators/data_dates 四个列表
     */
    public Map<String, Object> listOptions() {
        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("schemes", nsfrParamMapper.listSchemes());
        opt.put("nodes", nsfrParamMapper.listNodes());
        opt.put("operators", nsfrParamMapper.listOperators());
        opt.put("data_dates", nsfrParamMapper.listDataDates());
        return opt;
    }

    /**
     * <p>新增 (ID 规则: {scheme_code}_{node_code}_{YYYYMMDD}, 自动补 node_name + 默认值填充)</p>
     *
     * @param e NSFR 参数实体 (schemeId/schemeCode/nodeId/nodeCode/dataDate 必填)
     * @return 创建后的实体 (含 ID); 异常时抛 BizException
     */
    public NsfrParamEntity create(NsfrParamEntity e) {
        if (e.getSchemeId() == null) throw new BizException("schemeId 必填");
        if (e.getSchemeCode() == null || e.getSchemeCode().isEmpty()) throw new BizException("schemeCode 必填");
        if (e.getNodeId() == null) throw new BizException("nodeId 必填");
        if (e.getNodeCode() == null || e.getNodeCode().isEmpty()) throw new BizException("nodeCode 必填");
        if (e.getDataDate() == null) throw new BizException("dataDate 必填");

        // 自动补充 node_name（前端未传时，从节点表查）
        if (e.getNodeName() == null || e.getNodeName().isEmpty()) {
            e.setNodeName(nsfrParamMapper.selectNodeName(e.getNodeId()));
        }

        // 生成主键 ID
        e.setId(buildId(e.getSchemeCode(), e.getNodeCode(), e.getDataDate()));

        // 默认值
        e.setIsAsf(e.getIsAsf() == null ? 0 : e.getIsAsf());
        e.setAsfFactor(e.getAsfFactor() == null ? BigDecimal.ZERO : e.getAsfFactor());
        e.setAsfOperator(e.getAsfOperator() == null || e.getAsfOperator().isEmpty() ? "+" : e.getAsfOperator());
        e.setIsRsf(e.getIsRsf() == null ? 0 : e.getIsRsf());
        e.setRsfFactor(e.getRsfFactor() == null ? BigDecimal.ZERO : e.getRsfFactor());
        e.setRsfOperator(e.getRsfOperator() == null || e.getRsfOperator().isEmpty() ? "+" : e.getRsfOperator());
        e.setStatus(e.getStatus() == null || e.getStatus().isEmpty() ? "ACTIVE" : e.getStatus());
        e.setIsDeleted(0);

        try {
            nsfrParamMapper.insert(e);
        } catch (Exception ex) {
            throw new BizException("新增失败：" + ex.getMessage());
        }
        return e;
    }

    /**
     * <p>部分更新 (skip null)</p>
     *
     * @param e NSFR 参数实体 (id 必填)
     * @return 更新后的实体; 不存在时抛 notFound
     */
    public NsfrParamEntity update(NsfrParamEntity e) {
        if (e.getId() == null || e.getId().isEmpty()) throw new BizException("id 不能为空");
        NsfrParamEntity exist = nsfrParamMapper.selectById(e.getId());
        if (exist == null || Integer.valueOf(1).equals(exist.getIsDeleted())) {
            throw BizException.notFound("记录不存在");
        }
        if (e.getIsAsf() != null) exist.setIsAsf(e.getIsAsf());
        if (e.getAsfFactor() != null) exist.setAsfFactor(e.getAsfFactor());
        if (e.getAsfOperator() != null) exist.setAsfOperator(e.getAsfOperator());
        if (e.getIsRsf() != null) exist.setIsRsf(e.getIsRsf());
        if (e.getRsfFactor() != null) exist.setRsfFactor(e.getRsfFactor());
        if (e.getRsfOperator() != null) exist.setRsfOperator(e.getRsfOperator());
        if (e.getRuleNote() != null) exist.setRuleNote(e.getRuleNote());
        if (e.getStatus() != null) exist.setStatus(e.getStatus());
        nsfrParamMapper.updateById(exist);
        return exist;
    }

    /**
     * <p>软删除 (is_deleted=1)</p>
     *
     * @param id 主键 ID (必填)
     */
    public void delete(String id) {
        NsfrParamEntity exist = nsfrParamMapper.selectById(id);
        if (exist == null || Integer.valueOf(1).equals(exist.getIsDeleted())) {
            throw BizException.notFound("记录不存在");
        }
        exist.setIsDeleted(1);
        nsfrParamMapper.updateById(exist);
    }

    /**
     * ID 生成：{scheme_code}_{node_code}_{YYYYMMDD}
     */
    private String buildId(String schemeCode, String nodeCode, LocalDate dataDate) {
        String s = dataDate.toString().replace("-", "");
        return schemeCode + "_" + nodeCode + "_" + s;
    }
}