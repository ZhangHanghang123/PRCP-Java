package com.prcp.business.params.eve;

import com.prcp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>△EVE (利率风险经济价值变动) 参数补录 Service</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>列表查询 (4 个过滤条件)</li>
 *   <li>下拉选项 (方案/节点/操作符/数据日期)</li>
 *   <li>新增 (自动生成主键 + 自动补 node_name + 0/1 归一化)</li>
 *   <li>部分更新 (scheme/node/date 不允许改动)</li>
 *   <li>软删除</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>复合主键: {scheme_code}_{node_code}_{YYYYMMDD}</li>
 *   <li>软删除: is_deleted=1, 不物理删除</li>
 *   <li>默认值: status='ACTIVE', is_asset=0, is_liability=0</li>
 *   <li>主键冲突保护: 已存在时抛 badRequest</li>
 *   <li>0/1 归一化: 非 1 强制为 0</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.params.eve.EveParamMapper
 * @see com.prcp.business.params.eve.EveParamEntity
 */
@Service
@RequiredArgsConstructor
public class EveParamService {

    private final EveParamMapper eveParamMapper;

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
        // 空串 → null，避免 MySQL DATE 转换报错
        String dd = (dataDate == null || dataDate.isEmpty()) ? null : dataDate;
        String kw = (keyword  == null || keyword.isEmpty())  ? null : keyword;
        List<Map<String, Object>> rows = eveParamMapper.query(schemeId, nodeId, dd, kw);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", rows);
        resp.put("total", rows.size());
        return resp;
    }

    /**
     * <p>下拉选项 (合并 schemes / nodes / operators / dataDates)</p>
     *
     * @return Map 含 schemes/nodes/operators/data_dates 四个列表
     */
    public Map<String, Object> listOptions() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schemes",    eveParamMapper.listSchemes());
        resp.put("nodes",      eveParamMapper.listNodes());
        resp.put("operators",  eveParamMapper.listOperators());
        resp.put("data_dates", eveParamMapper.listDataDates());
        return resp;
    }

    /**
     * <p>新增: 自动生成主键 + 自动补 node_name + 0/1 归一化 + 主键冲突保护</p>
     *
     * @param p EVE 参数实体 (schemeId/schemeCode/nodeId/nodeCode/dataDate 必填)
     * @return 创建后的实体
     */
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

    /**
     * <p>更新 (局部更新, 跳过 null)</p>
     *
     * @param id    主键 ID (必填)
     * @param patch 待更新字段
     * @return 更新后的实体; 不存在或已删除时抛 notFound
     */
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

    /**
     * <p>软删除 (is_deleted=1)</p>
     *
     * @param id 主键 ID (必填)
     */
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