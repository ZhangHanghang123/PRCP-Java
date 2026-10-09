package com.prcp.business.params.cet1;

import com.prcp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>CET1 (核心一级资本) 参数补录 Service</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>列表查询 (4 个过滤条件)</li>
 *   <li>下拉选项 (方案/节点/操作符/数据日期)</li>
 *   <li>新增 (自动生成主键 + 自动补 node_name)</li>
 *   <li>部分更新 (scheme/node/date 不允许改动)</li>
 *   <li>软删除</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>复合主键: {scheme_code}_{node_code}_{YYYYMMDD} (手工生成, 覆盖前端传入的 id)</li>
 *   <li>软删除: is_deleted=1, 不物理删除</li>
 *   <li>默认值: is_deleted=0, status='ACTIVE', is_numerator=0, operator='+', is_rwa=0</li>
 *   <li>node_name 缺失时自动从 prcp_coa_node 补</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.params.cet1.Cet1ParamMapper
 * @see com.prcp.business.params.cet1.Cet1ParamEntity
 */
@Service
@RequiredArgsConstructor
public class Cet1ParamService {

    private final Cet1ParamMapper mapper;

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * <p>列表查询 (返回 {items, total} 结构与 Python 保持一致)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param nodeId   节点 ID (可选)
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @param keyword  关键字 (匹配 节点编码/名称/规则说明, 可选)
     * @return Map.of("items", list, "total", list.size())
     */
    public Map<String, Object> query(Long schemeId, Long nodeId, String dataDate, String keyword) {
        // 空串视为无过滤 → 传 null（避免 MySQL 报 Incorrect DATE value: ''）
        String dd = (dataDate == null || dataDate.isEmpty()) ? null : dataDate;
        String kw = (keyword  == null || keyword.isEmpty())  ? null : keyword;
        List<Map<String, Object>> rows = mapper.query(schemeId, nodeId, dd, kw);
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
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("schemes",    mapper.listSchemes());
        opts.put("nodes",      mapper.listNodes());
        opts.put("operators",  mapper.listOperators());
        opts.put("data_dates", mapper.listDataDates());
        return opts;
    }

    /**
     * <p>新增: 自动生成主键 {scheme_code}_{node_code}_{YYYYMMDD} + 自动补 node_name + 默认值填充</p>
     *
     * @param e CET1 参数实体 (schemeId/schemeCode/nodeId/nodeCode/dataDate 必填)
     * @return 创建后的实体 (含 ID)
     */
    public Cet1ParamEntity create(Cet1ParamEntity e) {
        if (e.getSchemeId() == null)                       throw new BizException("schemeId 必填");
        if (e.getSchemeCode() == null || e.getSchemeCode().isEmpty()) throw new BizException("schemeCode 必填");
        if (e.getNodeId() == null)                         throw new BizException("nodeId 必填");
        if (e.getNodeCode() == null || e.getNodeCode().isEmpty())     throw new BizException("nodeCode 必填");
        if (e.getDataDate() == null)                       throw new BizException("dataDate 必填");

        // 自动补 node_name
        if (e.getNodeName() == null || e.getNodeName().isEmpty()) {
            String nn = mapper.getNodeNameById(e.getNodeId());
            e.setNodeName(nn == null ? "" : nn);
        }

        // 主键 = {scheme_code}_{node_code}_{YYYYMMDD}
        String dateStr = e.getDataDate().format(YYYYMMDD);
        e.setId(e.getSchemeCode() + "_" + e.getNodeCode() + "_" + dateStr);

        // 默认值
        if (e.getIsDeleted()          == null) e.setIsDeleted(0);
        if (e.getStatus()             == null || e.getStatus().isEmpty()) e.setStatus("ACTIVE");
        if (e.getIsNumerator()        == null) e.setIsNumerator(0);
        if (e.getNumeratorFactor()    == null) e.setNumeratorFactor(BigDecimal.ZERO);
        if (e.getNumeratorOperator()  == null || e.getNumeratorOperator().isEmpty()) e.setNumeratorOperator("+");
        if (e.getIsRwa()              == null) e.setIsRwa(0);
        if (e.getRwaWeight()          == null) e.setRwaWeight(BigDecimal.ZERO);
        if (e.getRwaOperator()        == null || e.getRwaOperator().isEmpty()) e.setRwaOperator("+");
        if (e.getCurrentBalance()     == null) e.setCurrentBalance(BigDecimal.ZERO);

        mapper.insert(e);
        return e;
    }

    /**
     * <p>更新: 只更新非空字段 (scheme/node/date 不允许改动)</p>
     *
     * @param id 主键 ID (必填, 由 create 时生成)
     * @param e  待更新字段 (分子/RWA/规则/状态等)
     * @return 更新后的实体
     */
    public Cet1ParamEntity update(String id, Cet1ParamEntity e) {
        if (id == null || id.isEmpty()) throw new BizException("id 不能为空");

        Cet1ParamEntity exist = mapper.selectById(id);
        if (exist == null) throw new BizException("记录不存在");

        if (e.getIsNumerator()        != null) exist.setIsNumerator(e.getIsNumerator());
        if (e.getNumeratorFactor()    != null) exist.setNumeratorFactor(e.getNumeratorFactor());
        if (e.getNumeratorOperator()  != null) exist.setNumeratorOperator(e.getNumeratorOperator());
        if (e.getIsRwa()              != null) exist.setIsRwa(e.getIsRwa());
        if (e.getRwaWeight()          != null) exist.setRwaWeight(e.getRwaWeight());
        if (e.getRwaOperator()        != null) exist.setRwaOperator(e.getRwaOperator());
        if (e.getCurrentBalance()     != null) exist.setCurrentBalance(e.getCurrentBalance());
        if (e.getRuleNote()           != null) exist.setRuleNote(e.getRuleNote());
        if (e.getStatus()             != null) exist.setStatus(e.getStatus());

        mapper.updateById(exist);
        return exist;
    }

    /**
     * <p>软删除 (is_deleted=1)</p>
     *
     * @param id 主键 ID (必填)
     */
    public void delete(String id) {
        Cet1ParamEntity exist = mapper.selectById(id);
        if (exist == null) throw new BizException("记录不存在");
        exist.setIsDeleted(1);
        mapper.updateById(exist);
    }
}