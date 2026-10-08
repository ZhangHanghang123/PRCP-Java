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
 * CET1 参数补录 业务逻辑
 *
 * 关键点：
 *  1. 主键 = {scheme_code}_{node_code}_{YYYYMMDD}  （手工生成，覆盖前端传入的 id）
 *  2. 新增时 node_name 为空则从 prcp_coa_node 自动补
 *  3. 更新走 partial update：scheme/node/date 不允许改动，只更新分子/RWA/规则/状态
 *  4. 软删：is_deleted = 1
 */
@Service
@RequiredArgsConstructor
public class Cet1ParamService {

    private final Cet1ParamMapper mapper;

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 列表查询 — 返回 {items, total} 结构与 Python 保持一致 */
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

    /** 下拉选项：合并 schemes / nodes / operators / dataDates */
    public Map<String, Object> listOptions() {
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("schemes",    mapper.listSchemes());
        opts.put("nodes",      mapper.listNodes());
        opts.put("operators",  mapper.listOperators());
        opts.put("data_dates", mapper.listDataDates());
        return opts;
    }

    /** 新增：自动生成 id、自动补 node_name */
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

    /** 更新：只更新非空字段（不允许改 scheme/node/date） */
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

    /** 软删 */
    public void delete(String id) {
        Cet1ParamEntity exist = mapper.selectById(id);
        if (exist == null) throw new BizException("记录不存在");
        exist.setIsDeleted(1);
        mapper.updateById(exist);
    }
}