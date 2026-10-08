package com.prcp.business.params.eve;

import com.prcp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EveParamService {

    private final EveParamMapper eveParamMapper;

    // ---------- 查询 / 选项 ----------
    public Map<String, Object> query(Long schemeId, Long nodeId, String dataDate, String keyword) {
        // 空串 → null，避免 MySQL DATE 转换报错
        String dd = (dataDate == null || dataDate.isEmpty()) ? null : dataDate;
        String kw = (keyword  == null || keyword.isEmpty())  ? null : keyword;
        List<Map<String, Object>> rows = eveParamMapper.query(schemeId, nodeId, dd, kw);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", rows);
        resp.put("total", rows.size());
        return resp;
    }

    public Map<String, Object> listOptions() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schemes",    eveParamMapper.listSchemes());
        resp.put("nodes",      eveParamMapper.listNodes());
        resp.put("operators",  eveParamMapper.listOperators());
        resp.put("data_dates", eveParamMapper.listDataDates());
        return resp;
    }

    // ---------- 新增 ----------
    public EveParamEntity create(EveParamEntity p) {
        if (p.getSchemeId() == null)   throw new BizException("schemeId 必填");
        if (p.getSchemeCode() == null) throw new BizException("schemeCode 必填");
        if (p.getNodeId() == null)     throw new BizException("nodeId 必填");
        if (p.getNodeCode() == null)   throw new BizException("nodeCode 必填");
        if (p.getDataDate() == null)   throw new BizException("dataDate 必填");

        // 自动补充 node_name（前端未传时）
        if (p.getNodeName() == null || p.getNodeName().isEmpty()) {
            String resolved = resolveNodeName(p.getNodeId());
            if (resolved != null) p.setNodeName(resolved);
        }

        // 归一化 0/1
        p.setIsAsset(norm01(p.getIsAsset()));
        p.setIsLiability(norm01(p.getIsLiability()));

        // ID 生成：{scheme_code}_{node_code}_{YYYYMMDD}
        if (p.getId() == null || p.getId().isEmpty()) {
            p.setId(buildId(p.getSchemeCode(), p.getNodeCode(), p.getDataDate()));
        }

        p.setIsDeleted(0);
        if (p.getStatus() == null || p.getStatus().isEmpty()) p.setStatus("ACTIVE");

        // 主键冲突保护
        if (eveParamMapper.selectById(p.getId()) != null) {
            throw BizException.badRequest("记录已存在：id=" + p.getId());
        }

        eveParamMapper.insert(p);
        return p;
    }

    // ---------- 更新（局部更新，跳过 null） ----------
    public EveParamEntity update(String id, EveParamEntity patch) {
        EveParamEntity exist = eveParamMapper.selectById(id);
        if (exist == null) throw BizException.notFound("记录不存在：" + id);
        if (exist.getIsDeleted() != null && exist.getIsDeleted() == 1) {
            throw BizException.notFound("记录不存在：" + id);
        }

        if (patch.getIsAsset() != null)            exist.setIsAsset(norm01(patch.getIsAsset()));
        if (patch.getAssetType() != null)          exist.setAssetType(patch.getAssetType());
        if (patch.getAssetCategory() != null)      exist.setAssetCategory(patch.getAssetCategory());
        if (patch.getAssetOperator() != null)      exist.setAssetOperator(patch.getAssetOperator());
        if (patch.getIsLiability() != null)        exist.setIsLiability(norm01(patch.getIsLiability()));
        if (patch.getLiabilityType() != null)      exist.setLiabilityType(patch.getLiabilityType());
        if (patch.getLiabilityCategory() != null)  exist.setLiabilityCategory(patch.getLiabilityCategory());
        if (patch.getLiabilityOperator() != null)  exist.setLiabilityOperator(patch.getLiabilityOperator());
        if (patch.getDuration() != null)           exist.setDuration(patch.getDuration());
        if (patch.getCurrentBalance() != null)     exist.setCurrentBalance(patch.getCurrentBalance());
        if (patch.getRuleNote() != null)           exist.setRuleNote(patch.getRuleNote());
        if (patch.getStatus() != null)             exist.setStatus(patch.getStatus());

        eveParamMapper.updateById(exist);
        return exist;
    }

    // ---------- 软删 ----------
    public void delete(String id) {
        EveParamEntity exist = eveParamMapper.selectById(id);
        if (exist == null) throw BizException.notFound("记录不存在：" + id);
        exist.setIsDeleted(1);
        eveParamMapper.updateById(exist);
    }

    // ---------- helpers ----------
    private static String buildId(String schemeCode, String nodeCode, LocalDate dataDate) {
        String dd = dataDate.toString().replace("-", "");
        return schemeCode + "_" + nodeCode + "_" + dd;
    }

    private static Integer norm01(Integer v) {
        if (v == null) return 0;
        return v == 1 ? 1 : 0;
    }

    private String resolveNodeName(Long nodeId) {
        // 复用 mapper.baseMapper.selectById 不可行（node 不在本 mapper），
        // 通过 listNodes 全量回查简单方案：节点数可控
        List<Map<String, Object>> all = eveParamMapper.listNodes();
        for (Map<String, Object> n : all) {
            Object id = n.get("id");
            if (id != null && Long.valueOf(nodeId).equals(((Number) id).longValue())) {
                Object nm = n.get("nodeName");
                return nm == null ? null : nm.toString();
            }
        }
        return null;
    }
}