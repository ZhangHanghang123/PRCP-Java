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
 * NSFR 参数补录 Service
 * <p>对齐 Python routers/nsfr_param.py：</p>
 * <ul>
 *   <li>ID = {scheme_code}_{node_code}_{YYYYMMDD}</li>
 *   <li>新增时自动补充 node_name（前端未传）</li>
 *   <li>更新采用「skip null」增量更新</li>
 *   <li>软删 is_deleted=1</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class NsfrParamService {

    private final NsfrParamMapper nsfrParamMapper;

    public Map<String, Object> query(Long schemeId, Long nodeId, String dataDate, String keyword) {
        String dd = (dataDate == null || dataDate.isEmpty()) ? null : dataDate;
        String kw = (keyword == null || keyword.isEmpty()) ? null : keyword;
        List<Map<String, Object>> rows = nsfrParamMapper.query(schemeId, nodeId, dd, kw);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", rows);
        resp.put("total", rows.size());
        return resp;
    }

    public Map<String, Object> listOptions() {
        Map<String, Object> opt = new LinkedHashMap<>();
        opt.put("schemes", nsfrParamMapper.listSchemes());
        opt.put("nodes", nsfrParamMapper.listNodes());
        opt.put("operators", nsfrParamMapper.listOperators());
        opt.put("data_dates", nsfrParamMapper.listDataDates());
        return opt;
    }

    /**
     * 新增
     * <p>ID 规则：{scheme_code}_{node_code}_{YYYYMMDD}</p>
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
     * 部分更新（skip null）
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
     * 软删
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