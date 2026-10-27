package com.prcp.business.sim.service;

import com.prcp.business.coa.entity.CoaScheme;
import com.prcp.business.coa.service.CoaNodeService;
import com.prcp.business.coa.service.CoaSchemeService;
import com.prcp.business.sim.entity.SimNodeConfig;
import com.prcp.business.sim.entity.SimScheme;
import com.prcp.business.sim.entity.SimTermRatio;
import com.prcp.business.sim.mapper.SimGrowthConfigMapper;
import com.prcp.business.sim.mapper.SimNodeConfigMapper;
import com.prcp.business.sim.mapper.SimRhythmMapper;
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

/**
 * <p>模拟方案 Service (Sim Scheme + Node Config + Term Ratio)</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>账户册方案下拉 + 节点树</li>
 *   <li>节点信息 (含最新余额)</li>
 *   <li>模拟方案 CRUD (含 toggle status + 级联软删)</li>
 *   <li>节点配置 (含期限占比整段替换 + 自动校验 100%)</li>
 *   <li>校验全方案 term_ratios 比例合计</li>
 *   <li>单条 term_ratio 增删改</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>复合主键: (scheme_id, coa_node_id) — 已删可复活</li>
 *   <li>软删除: is_deleted=1, 不物理删除 (含级联 node_config + term_ratio)</li>
 *   <li>业务占比合计必须 = 100% (容差 0.01%)</li>
 *   <li>期限单位: 当前仅支持 MONTH (月)</li>
 *   <li>config_node_count: 方案下有效节点配置数 (自动同步)</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.sim.mapper.SimSchemeMapper
 * @see com.prcp.business.sim.entity.SimScheme
 */
@Service
@RequiredArgsConstructor
public class SimService {

    private final SimSchemeMapper schemeMapper;
    private final SimNodeConfigMapper nodeConfigMapper;
    private final SimTermRatioMapper termRatioMapper;
    /** 🆕 v2.1: 12 个月投放节奏权重 */
    private final SimRhythmMapper rhythmMapper;
    /** 🆕 v2.1: 新业务增长配置 (增长方式 + 5 年参数) */
    private final SimGrowthConfigMapper growthConfigMapper;
    private final CoaSchemeService coaSchemeService;
    private final CoaNodeService coaNodeService;

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Long uid() { return 1L; }

    /**
     * <p>查询 ACTIVE 账户册方案 (snake_case 字段)</p>
     *
     * @return R.ok(Map.of("items", list))
     */
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

    /**
     * <p>查询节点树 (后端组装嵌套树, 含 isLeaf 标记)</p>
     *
     * @param coaSchemeId 账户册方案 ID (必填)
     * @return R.ok(Map.of("scheme_id"/"items"/"total", ...))
     */
    /**
     * <p>账户册节点树 (含「已配置」标记)</p>
     *
     * <p><b>v2.4 修正</b>: 新增 {@code sim_scheme_id} 参数 ——
     * 传了则每个节点带 {@code has_config} 标记 (该节点在该方案下是否已有配置),
     * 前端据此把「已配置」的末级节点标绿 (对齐 Python routers/sim.py coa_tree)。</p>
     *
     * @param coaSchemeId 账户册方案 ID
     * @param simSchemeId 模拟方案 ID (可选, 用于标记已配置)
     * @return R.ok({scheme_id, items, total})
     */
    public R<Map<String, Object>> coaTree(Long coaSchemeId, Long simSchemeId) {
        if (coaSchemeId == null) throw BizException.badRequest("coa_scheme_id 必填");

        // 🆕 v2.4 修正: coaNodeService.listTree() 返回的已经是**嵌套树** (3 个根 + children),
        //   旧代码把它当扁平列表再按 path 组装一次 → 子节点全丢, 树只剩 3 个顶层节点。
        //   现在直接复用嵌套结构, 只做字段改名 + has_config 标记。
        List<Map<String, Object>> roots = coaNodeService.listTree(coaSchemeId).getData();

        // 已配置节点集合 (该 sim 方案下存在未删除的 node_config)
        Set<Long> configured = new HashSet<>();
        if (simSchemeId != null) {
            List<Long> ids = nodeConfigMapper.listConfiguredNodeIds(simSchemeId);
            if (ids != null) configured.addAll(ids);
        }

        // 递归改造节点结构 (字段改名 + has_config + isLeaf)
        int[] total = {0};
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : roots) {
            items.add(mapTreeNode(r, configured, total));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scheme_id", coaSchemeId);
        resp.put("items", items);
        resp.put("total", total[0]);
        return R.ok(resp);
    }

    /**
     * <p>递归改造账户册树节点 (字段改名 + has_config + isLeaf)</p>
     *
     * <p>源结构来自 {@code CoaNodeService.listTree()}: 字段是 camelCase 的
     * {@code nodeCode/nodeName/nodeLevel/nodeType/sortOrder}, 子节点在 {@code children} 里。</p>
     *
     * @param src        源节点
     * @param configured 已配置的节点 ID 集合
     * @param total      计数器 (统计总节点数)
     * @return 改造后的节点 (字段名对齐 Python routers/sim.py coa_tree)
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mapTreeNode(Map<String, Object> src, Set<Long> configured, int[] total) {
        total[0]++;
        Object idObj = src.get("id");
        Long id = idObj == null ? null : ((Number) idObj).longValue();

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("key", String.valueOf(idObj));
        node.put("title", src.get("nodeName") + " (" + src.get("nodeCode") + ")");
        node.put("code", src.get("nodeCode"));
        node.put("name", src.get("nodeName"));
        node.put("id", idObj);
        node.put("level", src.get("nodeLevel"));
        node.put("type", src.get("nodeType"));
        node.put("path", src.get("path"));
        node.put("sort_order", src.get("sortOrder"));
        node.put("status", src.get("status"));
        node.put("has_config", id != null && configured.contains(id));

        List<Map<String, Object>> srcChildren = (List<Map<String, Object>>) src.get("children");
        List<Map<String, Object>> children = new ArrayList<>();
        if (srcChildren != null) {
            for (Map<String, Object> c : srcChildren) {
                children.add(mapTreeNode(c, configured, total));
            }
        }
        node.put("children", children);
        node.put("isLeaf", children.isEmpty());
        return node;
    }

    /**
     * <p>节点信息 (含节点元数据 + 最新余额)</p>
     *
     * @param coaNodeId 节点 ID (必填)
     * @return R.ok(Map); 不存在时抛 notFound
     */
    public R<Map<String, Object>> nodeInfo(Long coaNodeId) {
        if (coaNodeId == null) throw BizException.badRequest("coa_node_id 必填");
        Map<String, Object> node = coaNodeService.nodeWithLatestBalance(coaNodeId);
        if (node == null) throw BizException.notFound("节点不存在");
        return R.ok(node);
    }

    /**
     * <p>查询模拟方案 (按 keyword/status/coaSchemeId 过滤)</p>
     *
     * @param keyword    关键字 (可选)
     * @param status     状态 (可选)
     * @param coaSchemeId 账户册方案 ID (可选)
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> listSchemes(String keyword, String status, Long coaSchemeId) {
        String kw = (keyword == null || keyword.isEmpty()) ? null : "%" + keyword + "%";
        List<Map<String, Object>> rows = schemeMapper.listSchemes(keyword, kw, status, coaSchemeId);
        return R.ok(Collections.singletonMap("items", rows));
    }

    /**
     * <p>创建模拟方案 (校验 coa_scheme ACTIVE, 默认 status='ACTIVE', config_node_count=0)</p>
     *
     * @param in 方案实体 (schemeCode/schemeName/coaSchemeId/dataDate 必填)
     * @return R.ok(Map.of("id"/"scheme_code"/"scheme_name"/"data_date")); scheme_code 重复时抛 badRequest
     */
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

    /**
     * <p>更新模拟方案 (锁定 coa_scheme_id 和 data_date 不允许改动)</p>
     *
     * @param sid 方案 ID (必填)
     * @param in  待更新字段
     * @return R.ok(Map.of("ok"/"coa_scheme_id_locked"/"data_date_locked")); 不存在时抛 notFound
     */
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

    /**
     * <p>切换方案状态 (ACTIVE/INACTIVE)</p>
     *
     * @param sid    方案 ID (必填)
     * @param status 新状态 (ACTIVE/INACTIVE, 必填)
     * @return R.ok(Map.of("ok", true)); 状态值非法或方案不存在时抛错
     */
    public R<Map<String, Object>> toggleStatus(Long sid, String status) {
        if (sid == null) throw BizException.badRequest("id 必填");
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status))
            throw BizException.badRequest("status 必须为 ACTIVE/INACTIVE");
        int n = schemeMapper.updateStatus(sid, status, uid());
        if (n == 0) throw BizException.notFound("方案不存在");
        return R.ok(Collections.singletonMap("ok", true));
    }

    /**
     * <p>软删除模拟方案 (级联软删 node_config + term_ratio)</p>
     *
     * @param sid 方案 ID (必填)
     * @return R.ok(Map.of("ok"/"deleted_configs"/"deleted_ratios")); 不存在时抛 notFound
     */
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

    /**
     * <p>查询节点配置 (含 term_ratios 子表)</p>
     *
     * @param schemeId 方案 ID (必填)
     * @param coaNodeId 节点 ID (必填)
     * @return R.ok(Map.of("exists"/"config"/"ratios"/"scheme_data_date", ...))
     */
    /**
     * <p>取节点配置 (含期限占比 + 12 月节奏 + 增长配置)</p>
     *
     * <p><b>v2.4 修正</b>: 响应结构对齐 Python routers/sim.py get_node_config ——
     * 新增 {@code rhythm[]} (12 月权重) 和 {@code growth{}} (增长方式 + 5 年参数),
     * 且 {@code config} 里带 {@code is_model_calc} / {@code rhythm_type}。</p>
     *
     * @param schemeId  方案 ID
     * @param coaNodeId 节点 ID
     * @return R.ok({exists, config, ratios, rhythm, growth, scheme_data_date})
     */
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
            resp.put("rhythm", defaultRhythm());
            resp.put("growth", defaultGrowth());
            resp.put("scheme_data_date", schemeDataDate);
            return R.ok(resp);
        }
        Long cfgId = ((Number) cfg.get("id")).longValue();
        List<Map<String, Object>> ratios = termRatioMapper.listByConfigId(cfgId);

        // 节奏配置 (12 个月权重), 无数据 → 默认线性
        List<Map<String, Object>> rhythm = rhythmMapper.listByConfigId(cfgId);
        if (rhythm == null || rhythm.isEmpty()) rhythm = defaultRhythm();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("exists", true);
        resp.put("config", cfg);
        resp.put("ratios", ratios);
        resp.put("rhythm", rhythm);
        resp.put("growth", readGrowth(cfgId));
        resp.put("scheme_data_date", schemeDataDate);
        return R.ok(resp);
    }

    /** 默认线性节奏 (12 个月均匀 100/12 = 8.3333%) */
    private List<Map<String, Object>> defaultRhythm() {
        List<Map<String, Object>> list = new ArrayList<>();
        BigDecimal w = new BigDecimal("100").divide(new BigDecimal("12"), 4, java.math.RoundingMode.HALF_UP);
        for (int m = 1; m <= 12; m++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("month_no", m);
            r.put("month_label", m + "月");
            r.put("weight", w);
            r.put("remark", "默认线性（12 个月均匀）");
            list.add(r);
        }
        return list;
    }

    /** 读增长配置 (无数据 → 默认: 目标增长百分比 + 5 年全 0) */
    private Map<String, Object> readGrowth(Long cfgId) {
        Map<String, Object> g = growthConfigMapper.findByConfigId(cfgId);
        Map<String, Object> resp = new LinkedHashMap<>();
        if (g == null) {
            resp.put("exists", false);
            resp.put("growth_type", 1);
            resp.put("year1_value", BigDecimal.ZERO);
            resp.put("year2_value", BigDecimal.ZERO);
            resp.put("year3_value", BigDecimal.ZERO);
            resp.put("year4_value", BigDecimal.ZERO);
            resp.put("year5_value", BigDecimal.ZERO);
            resp.put("unit", "%");
            resp.put("amount_unit", null);
            resp.put("remark", null);
            return resp;
        }
        resp.put("exists", true);
        resp.put("id", g.get("id"));
        resp.put("growth_type", intOf(g.get("growthType"), 1));
        resp.put("year1_value", g.get("year1Value"));
        resp.put("year2_value", g.get("year2Value"));
        resp.put("year3_value", g.get("year3Value"));
        resp.put("year4_value", g.get("year4Value"));
        resp.put("year5_value", g.get("year5Value"));
        resp.put("unit", g.getOrDefault("unit", "%"));
        resp.put("amount_unit", g.get("amountUnit"));
        resp.put("remark", g.get("remark"));
        return resp;
    }

    /** 默认增长配置 */
    private Map<String, Object> defaultGrowth() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("exists", false);
        resp.put("growth_type", 1);
        for (int i = 1; i <= 5; i++) resp.put("year" + i + "_value", BigDecimal.ZERO);
        resp.put("unit", "%");
        resp.put("amount_unit", null);
        resp.put("remark", null);
        return resp;
    }

    /** Object → int (兼容 Boolean / Number / String, tinyint(1) 会被驱动映射成 Boolean) */
    private static int intOf(Object o, int defaultVal) {
        if (o == null) return defaultVal;
        if (o instanceof Boolean) return ((Boolean) o) ? 1 : 0;
        if (o instanceof Number) return ((Number) o).intValue();
        try {
            return new BigDecimal(o.toString().trim()).intValue();
        } catch (Exception e) {
            return defaultVal;
        }
    }

    /**
     * <p>保存节点配置 (UPSERT + term_ratios 整段替换 + 校验 100% 占比)</p>
     *
     * @param schemeId 方案 ID (必填)
     * @param body     含 coa_node_id/annual_growth_rate/term_unit/remark + ratios 数组
     * @return R.ok(Map.of("ok"/"config_id"/"term_count"/"total_ratio")); 校验失败时抛 badRequest
     */
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
        // 🆕 v2.1: 是否模型测算 (1=是 2=否) + 节奏配置类型 (1=线性 2=自定义)
        int isModelCalc = intOf(body.get("is_model_calc"), 2);
        int rhythmType = intOf(body.get("rhythm_type"), 1);

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
            upd.setIsModelCalc(isModelCalc);
            upd.setRhythmType(rhythmType);
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
            ins.setIsModelCalc(isModelCalc);
            ins.setRhythmType(rhythmType);
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

    // ========================================================================
    // v2.1: 12 个月节奏配置 (prcp_sim_rhythm)
    // ========================================================================

    /**
     * <p>取 12 个月节奏权重 (无数据 → 默认线性 8.3333%)</p>
     *
     * @param cfgId 节点配置 ID
     * @return R.ok({config_id, weights:[{month_no, month_label, weight, remark}], total})
     */
    public R<Map<String, Object>> getRhythm(Long cfgId) {
        if (cfgId == null) throw BizException.badRequest("cfg_id 必填");
        List<Map<String, Object>> rows = rhythmMapper.listByConfigId(cfgId);
        if (rows == null || rows.isEmpty()) rows = defaultRhythm();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            Object w = r.get("weight");
            if (w != null) total = total.add(new BigDecimal(w.toString()));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("config_id", cfgId);
        resp.put("weights", rows);
        resp.put("total", total.setScale(4, java.math.RoundingMode.HALF_UP));
        return R.ok(resp);
    }

    /**
     * <p>保存 12 个月节奏权重 (整段替换)</p>
     *
     * <p>校验: 必须恰好 12 个月 (1~12 不重复), 权重和偏差 ≤ 0.1%</p>
     *
     * @param cfgId 节点配置 ID
     * @param body  {weights:[{month_no, weight, remark}]}
     * @return R.ok({ok, config_id, count, total})
     */
    @Transactional
    public R<Map<String, Object>> saveRhythm(Long cfgId, Map<String, Object> body) {
        if (cfgId == null) throw BizException.badRequest("cfg_id 必填");
        if (body == null) throw BizException.badRequest("请求体不能为空");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> weights = (List<Map<String, Object>>) body.get("weights");
        if (weights == null || weights.isEmpty())
            throw BizException.badRequest("weights 不能为空");

        Set<Integer> months = new TreeSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> w : weights) {
            int m = intOf(w.get("month_no"), 0);
            if (m < 1 || m > 12) throw BizException.badRequest("month_no 必须 1~12");
            months.add(m);
            Object v = w.get("weight");
            if (v != null) total = total.add(new BigDecimal(v.toString()));
        }
        if (months.size() != 12)
            throw BizException.badRequest("必须配置满 12 个月, 当前 " + months.size() + " 个月");
        if (total.subtract(new BigDecimal("100")).abs().compareTo(new BigDecimal("0.1")) > 0)
            throw BizException.badRequest("12 个月权重之和必须为 100% (±0.1%), 当前 = "
                    + total.setScale(4, java.math.RoundingMode.HALF_UP) + "%");

        // 取所属 scheme / node 信息
        SimNodeConfig cfg = nodeConfigMapper.selectById(cfgId);
        if (cfg == null) throw BizException.notFound("节点配置不存在");

        rhythmMapper.softDeleteByConfigId(cfgId);
        for (Map<String, Object> w : weights) {
            int m = intOf(w.get("month_no"), 1);
            BigDecimal weight = w.get("weight") == null ? BigDecimal.ZERO
                    : new BigDecimal(w.get("weight").toString());
            String remark = w.get("remark") == null ? null : w.get("remark").toString();
            // ⚠️ 唯一键 uk_config_month = (config_id, month_no) 不含 is_deleted
            //   → 必须「软删复活优先」, 否则 Duplicate entry
            Map<String, Object> ex = rhythmMapper.findByConfigAndMonth(cfgId, m);
            if (ex != null) {
                rhythmMapper.reviveById(((Number) ex.get("id")).longValue(), m, m + "月", weight, remark, uid());
            } else {
                rhythmMapper.insertOne(cfgId, cfg.getSchemeId(), cfg.getCoaNodeId(),
                        m, m + "月", weight, remark, uid());
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("config_id", cfgId);
        resp.put("count", weights.size());
        resp.put("total", total.setScale(4, java.math.RoundingMode.HALF_UP));
        return R.ok(resp);
    }

    /**
     * <p>重置 12 个月节奏为线性 (每��� 100/12 = 8.3333%)</p>
     *
     * @param cfgId 节点配置 ID
     * @return R.ok({ok, config_id, count, total})
     */
    @Transactional
    public R<Map<String, Object>> resetRhythmLinear(Long cfgId) {
        if (cfgId == null) throw BizException.badRequest("cfg_id 必填");
        SimNodeConfig cfg = nodeConfigMapper.selectById(cfgId);
        if (cfg == null) throw BizException.notFound("节点配置不存在");
        rhythmMapper.softDeleteByConfigId(cfgId);
        BigDecimal w = new BigDecimal("100").divide(new BigDecimal("12"), 4, java.math.RoundingMode.HALF_UP);
        for (int m = 1; m <= 12; m++) {
            // ⚠️ 唯一键 uk_config_month 不含 is_deleted → 复活优先
            Map<String, Object> ex = rhythmMapper.findByConfigAndMonth(cfgId, m);
            if (ex != null) {
                rhythmMapper.reviveById(((Number) ex.get("id")).longValue(), m, m + "月", w, "12 月线性分配", uid());
            } else {
                rhythmMapper.insertOne(cfgId, cfg.getSchemeId(), cfg.getCoaNodeId(),
                        m, m + "月", w, "12 月线性分配", uid());
            }
        }
        // 对齐 Python: 重置线性时同步把 rhythm_type 改成 1
        nodeConfigMapper.updateRhythmType(cfgId, 1, uid());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("config_id", cfgId);
        resp.put("count", 12);
        resp.put("total", new BigDecimal("100"));
        // 返回重置后的权重列表 (前端可直接刷新表格, 不用再查一次)
        resp.put("weights", rhythmMapper.listByConfigId(cfgId));
        return R.ok(resp);
    }

    // ========================================================================
    // v2.1: 新业务增长配置 (prcp_sim_growth_config)
    // ========================================================================

    /**
     * <p>取增长配置 (无数据 → 默认: 目标增长百分比 + 5 年全 0)</p>
     *
     * @param cfgId 节点配置 ID
     * @return R.ok(growth Map)
     */
    public R<Map<String, Object>> getGrowth(Long cfgId) {
        if (cfgId == null) throw BizException.badRequest("cfg_id 必填");
        return R.ok(readGrowth(cfgId));
    }

    /**
     * <p>保存增长配置 (UPSERT: 有则更新, 无则新增)</p>
     *
     * @param cfgId 节点配置 ID
     * @param body  {growth_type, year1_value..year5_value, amount_unit, remark}
     * @return R.ok({ok, config_id, growth_id, growth_type})
     */
    @Transactional
    public R<Map<String, Object>> saveGrowth(Long cfgId, Map<String, Object> body) {
        if (cfgId == null) throw BizException.badRequest("cfg_id 必填");
        if (body == null) throw BizException.badRequest("请求体不能为空");
        int growthType = intOf(body.get("growth_type"), 1);
        if (growthType < 1 || growthType > 4)
            throw BizException.badRequest("growth_type 必须 1~4");
        BigDecimal[] y = new BigDecimal[5];
        for (int i = 0; i < 5; i++) {
            Object v = body.get("year" + (i + 1) + "_value");
            y[i] = v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
        }
        String unit = body.get("unit") == null ? "%" : body.get("unit").toString();
        String amountUnit = body.get("amount_unit") == null ? null : body.get("amount_unit").toString();
        String remark = body.get("remark") == null ? null : body.get("remark").toString();

        SimNodeConfig cfg = nodeConfigMapper.selectById(cfgId);
        if (cfg == null) throw BizException.notFound("节点配置不存在");

        Map<String, Object> existing = growthConfigMapper.findIncludingDeleted(cfgId);
        Long growthId;
        if (existing != null) {
            growthId = ((Number) existing.get("id")).longValue();
            growthConfigMapper.updateOne(growthId, growthType, y[0], y[1], y[2], y[3], y[4],
                    unit, amountUnit, remark, uid());
        } else {
            SimScheme scheme = schemeMapper.selectById(cfg.getSchemeId());
            growthConfigMapper.insertOne(cfgId, cfg.getSchemeId(),
                    scheme == null ? null : scheme.getSchemeCode(),
                    scheme == null ? null : scheme.getSchemeName(),
                    cfg.getCoaNodeId(), cfg.getCoaNodeCode(), null,
                    growthType, y[0], y[1], y[2], y[3], y[4], unit, amountUnit, remark, uid());
            Map<String, Object> after = growthConfigMapper.findByConfigId(cfgId);
            growthId = after == null ? null : ((Number) after.get("id")).longValue();
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("config_id", cfgId);
        resp.put("growth_id", growthId);
        resp.put("growth_type", growthType);
        return R.ok(resp);
    }

    /**
     * <p>删除增长配置 (软删)</p>
     *
     * @param cfgId 节点配置 ID
     * @return R.ok({ok, config_id})
     */
    @Transactional
    public R<Map<String, Object>> deleteGrowth(Long cfgId) {
        if (cfgId == null) throw BizException.badRequest("cfg_id 必填");
        int n = growthConfigMapper.softDeleteByConfigId(cfgId, uid());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("config_id", cfgId);
        resp.put("deleted", n);
        return R.ok(resp);
    }

    /**
     * <p>软删除节点配置 (级联软删 term_ratio)</p>
     *
     * @param cfgId 配置 ID (必填)
     * @return R.ok(Map.of("ok", true)); 不存在时抛 notFound
     */
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

    /**
     * <p>校验全方案的 term_ratios 比例合计 = 100%</p>
     *
     * @param schemeId 方案 ID (必填)
     * @param body     预留 (当前未用)
     * @return R.ok(Map.of("ok"/"checked"/"issues", ...))
     */
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

    /**
     * <p>改单条 term_ratio (业务占比/利率/期限/排序)</p>
     *
     * @param rid  term_ratio ID (必填)
     * @param body camelCase/snake_case 双兼容; business_ratio 必须在 0~100 之间
     * @return R.ok(Map.of("ok", true)); 不存在或参数非法时抛错
     */
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

    /**
     * <p>软删单条 term_ratio</p>
     *
     * @param rid term_ratio ID (必填)
     * @return R.ok(Map.of("ok", true)); 不存在时抛 notFound
     */
    public R<Map<String, Object>> deleteTermRatio(Long rid) {
        if (rid == null) throw BizException.badRequest("rid 必填");
        int n = termRatioMapper.softDeleteById(rid, uid());
        if (n == 0) throw BizException.notFound("term_ratio 不存在");
        return R.ok(Collections.singletonMap("ok", true));
    }
}