package com.prcp.business.sim.service;

import com.prcp.business.coa.entity.CoaScheme;
import com.prcp.business.coa.service.CoaNodeService;
import com.prcp.business.coa.service.CoaSchemeService;
import com.prcp.business.sim.entity.SimNodeConfig;
import com.prcp.business.sim.entity.SimScheme;
import com.prcp.business.sim.entity.SimTermRatio;
import com.prcp.business.sim.mapper.SimNodeConfigMapper;
import com.prcp.business.sim.mapper.SimSchemeMapper;
import com.prcp.business.sim.mapper.SimTermRatioMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SimService {

    private final SimSchemeMapper schemeMapper;
    private final SimNodeConfigMapper nodeConfigMapper;
    private final SimTermRatioMapper termRatioMapper;
    private final CoaSchemeService coaSchemeService;
    private final CoaNodeService coaNodeService;

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Long uid() { return 1L; }

    // ============ 1. coa-schemes ============
    public R<Map<String, Object>> listCoaSchemes() {
        List<CoaScheme> schemes = coaSchemeService.listActive().getData();
        List<Map<String, Object>> items = new ArrayList<>();
        if (schemes != null) {
            for (CoaScheme s : schemes) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("scheme_code", s.getSchemeCode());
                m.put("scheme_name", s.getSchemeName());
                m.put("status", s.getStatus());
                m.put("node_count", s.getNodeCount());
                items.add(m);
            }
        }
        return R.ok(Collections.singletonMap("items", items));
    }

    // ============ 2. coa-tree ============
    public R<Map<String, Object>> coaTree(Long coaSchemeId) {
        if (coaSchemeId == null) throw BizException.badRequest("coa_scheme_id 必填");
        List<Map<String, Object>> rows = coaNodeService.listTree(coaSchemeId).getData();
        // 后端组装嵌套树（与 Python routers/sim.py coa_tree 一致）
        Map<String, Map<String, Object>> byPath = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String path = (String) r.get("path");
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("key", String.valueOf(r.get("id")));
            node.put("title", r.get("nodeName") + " (" + r.get("nodeCode") + ")");
            node.put("code", r.get("nodeCode"));
            node.put("name", r.get("nodeName"));
            node.put("id", r.get("id"));
            node.put("level", r.get("nodeLevel"));
            node.put("type", r.get("nodeType"));
            node.put("path", path);
            node.put("sort_order", r.get("sortOrder"));
            node.put("status", r.get("status"));
            node.put("isLeaf", false);
            node.put("children", new ArrayList<>());
            byPath.put(path, node);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> node : byPath.values()) {
            String path = (String) node.get("path");
            int lastSlash = path.length() > 1 ? path.substring(0, path.length() - 1).lastIndexOf("/") + 1 : 0;
            String parentPath = path.substring(0, lastSlash);
            Map<String, Object> parent = byPath.get(parentPath);
            if (parent != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> pc = (List<Map<String, Object>>) parent.get("children");
                pc.add(node);
            } else {
                roots.add(node);
            }
        }
        // 标记叶子
        markLeaves(roots);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scheme_id", coaSchemeId);
        resp.put("items", roots);
        resp.put("total", byPath.size());
        return R.ok(resp);
    }

    private void markLeaves(List<Map<String, Object>> nodes) {
        for (Map<String, Object> n : nodes) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) n.get("children");
            if (children == null || children.isEmpty()) {
                n.put("isLeaf", true);
            } else {
                markLeaves(children);
            }
        }
    }

    // ============ 3. node-info ============
    public R<Map<String, Object>> nodeInfo(Long coaNodeId) {
        if (coaNodeId == null) throw BizException.badRequest("coa_node_id 必填");
        Map<String, Object> node = coaNodeService.nodeWithLatestBalance(coaNodeId);
        if (node == null) throw BizException.notFound("节点不存在");
        return R.ok(node);
    }

    // ============ 4. schemes list ============
    public R<Map<String, Object>> listSchemes(String keyword, String status, Long coaSchemeId) {
        String kw = (keyword == null || keyword.isEmpty()) ? null : "%" + keyword + "%";
        List<Map<String, Object>> rows = schemeMapper.listSchemes(keyword, kw, status, coaSchemeId);
        return R.ok(Collections.singletonMap("items", rows));
    }

    // ============ 5. create scheme ============
    @Transactional
    public R<Map<String, Object>> createScheme(SimScheme in) {
        if (in.getSchemeCode() == null || in.getSchemeCode().isEmpty())
            throw BizException.badRequest("scheme_code 必填");
        if (in.getSchemeName() == null || in.getSchemeName().isEmpty())
            throw BizException.badRequest("scheme_name 必填");
        if (in.getCoaSchemeId() == null)
            throw BizException.badRequest("coa_scheme_id 必填");
        if (in.getDataDate() == null)
            throw BizException.badRequest("data_date 必填");
        // 校验 coa_scheme 存在 + ACTIVE
        if (!coaSchemeService.isActive(in.getCoaSchemeId()))
            throw BizException.badRequest("关联账户册方案不存在或已停用");
        SimScheme p = new SimScheme();
        p.setSchemeCode(in.getSchemeCode());
        p.setSchemeName(in.getSchemeName());
        p.setCoaSchemeId(in.getCoaSchemeId());
        p.setDataDate(in.getDataDate());
        p.setDescription(in.getDescription());
        p.setStatus(in.getStatus() == null ? "ACTIVE" : in.getStatus());
        p.setConfigNodeCount(0);
        p.setIsDeleted(0);
        p.setCreatedBy(uid());
        p.setUpdatedBy(uid());
        try {
            schemeMapper.insert(p);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Duplicate") || msg.contains("uk_scheme_code")))
                throw BizException.badRequest("方案编码已存在：" + p.getSchemeCode());
            throw BizException.badRequest("创建失败：" + msg);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", p.getId());
        resp.put("scheme_code", p.getSchemeCode());
        resp.put("scheme_name", p.getSchemeName());
        resp.put("data_date", p.getDataDate() != null ? p.getDataDate().format(DF) : null);
        return R.ok(resp);
    }

    // ============ 6. update scheme ============
    @Transactional
    public R<Map<String, Object>> updateScheme(Long sid, SimScheme in) {
        if (sid == null) throw BizException.badRequest("id 必填");
        SimScheme cur = schemeMapper.selectById(sid);
        if (cur == null || Integer.valueOf(1).equals(cur.getIsDeleted()))
            throw BizException.notFound("方案不存在");
        SimScheme upd = new SimScheme();
        upd.setId(sid);
        upd.setSchemeCode(in.getSchemeCode());
        upd.setSchemeName(in.getSchemeName());
        upd.setDescription(in.getDescription());
        upd.setStatus(in.getStatus());
        upd.setUpdatedBy(uid());
        upd.setUpdatedAt(java.time.LocalDateTime.now());
        try {
            int n = schemeMapper.updateById(upd);
            if (n == 0) throw BizException.notFound("方案不存在");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Duplicate") || msg.contains("uk_scheme_code")))
                throw BizException.badRequest("方案编码已存在：" + in.getSchemeCode());
            throw BizException.badRequest("更新失败：" + msg);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("coa_scheme_id_locked", cur.getCoaSchemeId());
        resp.put("data_date_locked", cur.getDataDate() != null ? cur.getDataDate().format(DF) : null);
        return R.ok(resp);
    }

    // ============ 7. toggle status ============
    public R<Map<String, Object>> toggleStatus(Long sid, String status) {
        if (sid == null) throw BizException.badRequest("id 必填");
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status))
            throw BizException.badRequest("status 必须为 ACTIVE/INACTIVE");
        int n = schemeMapper.updateStatus(sid, status, uid());
        if (n == 0) throw BizException.notFound("方案不存在");
        return R.ok(Collections.singletonMap("ok", true));
    }

    // ============ 8. delete scheme ============
    @Transactional
    public R<Map<String, Object>> deleteScheme(Long sid) {
        if (sid == null) throw BizException.badRequest("id 必填");
        int n = schemeMapper.softDeleteById(sid, uid());
        if (n == 0) throw BizException.notFound("方案不存在");
        int cn = nodeConfigMapper.softDeleteByScheme(sid, uid());
        int tr = termRatioMapper.softDeleteByScheme(sid, uid());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("deleted_configs", cn);
        resp.put("deleted_ratios", tr);
        return R.ok(resp);
    }

    // ============ 9. get node-config ============
    public R<Map<String, Object>> getNodeConfig(Long schemeId, Long coaNodeId) {
        if (schemeId == null) throw BizException.badRequest("scheme_id 必填");
        if (coaNodeId == null) throw BizException.badRequest("coa_node_id 必填");
        String dd = schemeMapper.selectDataDate(schemeId);
        String schemeDataDate = dd;
        Map<String, Object> cfg = nodeConfigMapper.findBySchemeAndNode(schemeId, coaNodeId);
        if (cfg == null) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("exists", false);
            resp.put("config", null);
            resp.put("ratios", Collections.emptyList());
            resp.put("scheme_data_date", schemeDataDate);
            return R.ok(resp);
        }
        Long cfgId = ((Number) cfg.get("id")).longValue();
        List<Map<String, Object>> ratios = termRatioMapper.listByConfigId(cfgId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("exists", true);
        resp.put("config", cfg);
        resp.put("ratios", ratios);
        resp.put("scheme_data_date", schemeDataDate);
        return R.ok(resp);
    }

    // ============ 10. save node-config ============
    @Transactional
    public R<Map<String, Object>> saveNodeConfig(Long schemeId, Map<String, Object> body) {
        if (schemeId == null) throw BizException.badRequest("scheme_id 必填");
        if (body == null) throw BizException.badRequest("请求体不能为空");

        Object coaNodeIdObj = body.get("coa_node_id");
        if (coaNodeIdObj == null) throw BizException.badRequest("coa_node_id 必填");
        Long coaNodeId = ((Number) coaNodeIdObj).longValue();
        BigDecimal annualGrowth = body.get("annual_growth_rate") == null
            ? BigDecimal.ZERO : new BigDecimal(body.get("annual_growth_rate").toString());
        String termUnit = body.get("term_unit") == null ? "MONTH" : body.get("term_unit").toString();
        String remark = body.get("remark") == null ? null : body.get("remark").toString();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ratios = (List<Map<String, Object>>) body.get("ratios");
        if (ratios == null || ratios.isEmpty())
            throw BizException.badRequest("期限占比子表不能为空，至少 1 行");

        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : ratios) {
            Object br = r.get("business_ratio");
            if (br != null) total = total.add(new BigDecimal(br.toString()));
        }
        if (total.subtract(new BigDecimal("100")).abs().compareTo(new BigDecimal("0.01")) > 0)
            throw BizException.badRequest("业务占比之和必须为 100%，当前 = " + total.setScale(4, java.math.RoundingMode.HALF_UP) + "%");

        // 校验节点存在
        String nodeCode = coaNodeService.codeById(coaNodeId);
        if (nodeCode == null) throw BizException.notFound("节点不存在");
        // 校验方案存在
        if (schemeMapper.selectDataDate(schemeId) == null)
            throw BizException.notFound("方案不存在");

        // UPSERT 节点配置
        Map<String, Object> existing = nodeConfigMapper.findIncludingDeleted(schemeId, coaNodeId);
        Long cfgId;
        if (existing != null) {
            cfgId = ((Number) existing.get("id")).longValue();
            Integer isDel = ((Number) existing.get("is_deleted")).intValue();
            SimNodeConfig upd = new SimNodeConfig();
            upd.setId(cfgId);
            upd.setAnnualGrowthRate(annualGrowth);
            upd.setTermUnit(termUnit);
            upd.setRemark(remark);
            upd.setUpdatedBy(uid());
            upd.setUpdatedAt(java.time.LocalDateTime.now());
            if (isDel == 1) {
                upd.setIsDeleted(0);
            }
            nodeConfigMapper.updateById(upd);
        } else {
            SimNodeConfig ins = new SimNodeConfig();
            ins.setSchemeId(schemeId);
            ins.setCoaNodeId(coaNodeId);
            ins.setCoaNodeCode(nodeCode);
            ins.setAnnualGrowthRate(annualGrowth);
            ins.setTermUnit(termUnit);
            ins.setTermCount(0);
            ins.setRemark(remark);
            ins.setIsDeleted(0);
            ins.setCreatedBy(uid());
            ins.setUpdatedBy(uid());
            nodeConfigMapper.insert(ins);
            cfgId = ins.getId();
        }

        // 软删旧占比 + 插入新占比
        termRatioMapper.softDeleteByConfigId(cfgId, uid());
        int idx = 1;
        for (Map<String, Object> r : ratios) {
            Integer tv = ((Number) r.get("term_value")).intValue();
            String tu = r.get("term_unit") == null ? "MONTH" : r.get("term_unit").toString();
            if (!"MONTH".equals(tu))
                throw BizException.badRequest("期限单位当前仅支持 MONTH（月）");
            BigDecimal br = new BigDecimal(r.get("business_ratio").toString());
            BigDecimal ir = r.get("interest_rate") == null ? BigDecimal.ZERO
                : new BigDecimal(r.get("interest_rate").toString());
            Integer so = r.get("sort_order") == null ? idx : ((Number) r.get("sort_order")).intValue();
            SimTermRatio ins = new SimTermRatio();
            ins.setConfigId(cfgId);
            ins.setTermValue(tv);
            ins.setTermUnit(tu);
            ins.setBusinessRatio(br);
            ins.setInterestRate(ir);
            ins.setSortOrder(so);
            ins.setIsDeleted(0);
            ins.setCreatedBy(uid());
            ins.setUpdatedBy(uid());
            termRatioMapper.insert(ins);
            idx++;
        }
        // 更新 term_count
        SimNodeConfig updTc = new SimNodeConfig();
        updTc.setId(cfgId);
        updTc.setTermCount(ratios.size());
        updTc.setUpdatedBy(uid());
        updTc.setUpdatedAt(java.time.LocalDateTime.now());
        nodeConfigMapper.updateById(updTc);
        // 更新方案 config_node_count
        schemeMapper.refreshConfigNodeCount(schemeId, uid());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("config_id", cfgId);
        resp.put("term_count", ratios.size());
        resp.put("total_ratio", total.setScale(4, java.math.RoundingMode.HALF_UP));
        return R.ok(resp);
    }

    // ============ 11. delete node-config ============
    @Transactional
    public R<Map<String, Object>> deleteNodeConfig(Long cfgId) {
        if (cfgId == null) throw BizException.badRequest("cfg_id 必填");
        SimNodeConfig cfg = nodeConfigMapper.selectById(cfgId);
        if (cfg == null || Integer.valueOf(1).equals(cfg.getIsDeleted()))
            throw BizException.notFound("节点配置不存在");
        Long schemeId = cfg.getSchemeId();
        int n = nodeConfigMapper.softDeleteById(cfgId, uid());
        if (n == 0) throw BizException.notFound("节点配置不存在");
        termRatioMapper.softDeleteByConfigId(cfgId, uid());
        schemeMapper.refreshConfigNodeCount(schemeId, uid());
        return R.ok(Collections.singletonMap("ok", true));
    }

    // ============ 12. validateScheme 校验全方案的 term_ratios 比例合计 = 100% ============
    public R<Map<String, Object>> validateScheme(Long schemeId, Map<String, Object> body) {
        if (schemeId == null) throw BizException.badRequest("scheme_id 必填");
        // 查所有 active node_config 的 term_ratios
        List<Map<String, Object>> cfgs = nodeConfigMapper.listByScheme(schemeId);
        java.util.Map<String, Object> resp = new LinkedHashMap<>();
        java.util.List<Map<String, Object>> issues = new java.util.ArrayList<>();
        int total = 0;
        for (Map<String, Object> cfg : cfgs) {
            Long cfgId = ((Number) cfg.get("id")).longValue();
            List<Map<String, Object>> ratios = termRatioMapper.listByConfigId(cfgId);
            if (ratios.isEmpty()) continue;
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> r : ratios) {
                Object br = r.get("businessRatio");
                if (br != null) sum = sum.add(new BigDecimal(br.toString()));
            }
            total++;
            if (sum.subtract(new BigDecimal("100")).abs().compareTo(new BigDecimal("0.01")) > 0) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("cfg_id", cfgId);
                issue.put("node_code", cfg.get("coaNodeCode"));
                issue.put("node_name", cfg.get("coaNodeName"));
                issue.put("business_ratio_sum", sum);
                issue.put("expected", 100);
                issues.add(issue);
            }
        }
        resp.put("ok", issues.isEmpty());
        resp.put("checked", total);
        resp.put("issues", issues);
        return R.ok(resp);
    }

    // ============ 13. updateTermRatio 改单条 term_ratio（业务占比/利率/期限/排序） ============
    public R<Map<String, Object>> updateTermRatio(Long rid, Map<String, Object> body) {
        if (rid == null) throw BizException.badRequest("rid 必填");
        if (body == null) throw BizException.badRequest("请求体不能为空");
        // 校验业务占比 0~100
        Object brObj = body.get("business_ratio");
        if (brObj == null) brObj = body.get("businessRatio");
        if (brObj != null) {
            BigDecimal br = new BigDecimal(brObj.toString());
            if (br.compareTo(BigDecimal.ZERO) < 0 || br.compareTo(new BigDecimal("100")) > 0)
                throw BizException.badRequest("business_ratio 必须在 0~100 之间");
        }
        SimTermRatio tr = new SimTermRatio();
        tr.setId(rid);
        if (brObj != null) tr.setBusinessRatio(new BigDecimal(brObj.toString()));
        Object irObj = body.get("interest_rate");
        if (irObj == null) irObj = body.get("interestRate");
        if (irObj != null) tr.setInterestRate(new BigDecimal(irObj.toString()));
        Object tvObj = body.get("term_value");
        if (tvObj == null) tvObj = body.get("termValue");
        if (tvObj != null) tr.setTermValue(((Number) tvObj).intValue());
        Object soObj = body.get("sort_order");
        if (soObj == null) soObj = body.get("sortOrder");
        if (soObj != null) tr.setSortOrder(((Number) soObj).intValue());
        if (body.get("remark") != null) tr.setRemark(body.get("remark").toString());
        int n = termRatioMapper.updateById(tr);
        if (n == 0) throw BizException.notFound("term_ratio 不存在");
        return R.ok(Collections.singletonMap("ok", true));
    }

    // ============ 14. deleteTermRatio 软删单条 term_ratio ============
    public R<Map<String, Object>> deleteTermRatio(Long rid) {
        if (rid == null) throw BizException.badRequest("rid 必填");
        int n = termRatioMapper.softDeleteById(rid, uid());
        if (n == 0) throw BizException.notFound("term_ratio 不存在");
        return R.ok(Collections.singletonMap("ok", true));
    }
}