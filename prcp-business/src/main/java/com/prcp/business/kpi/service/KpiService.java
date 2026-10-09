package com.prcp.business.kpi.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.prcp.business.kpi.entity.KpiDefinition;
import com.prcp.business.kpi.entity.KpiScheme;
import com.prcp.business.kpi.entity.KpiScoreRule;
import com.prcp.business.kpi.entity.KpiScoreSegment;
import com.prcp.business.kpi.entity.KpiValue;
import com.prcp.business.kpi.mapper.KpiMapper;
import com.prcp.business.kpi.mapper.KpiScoreRuleMapper;
import com.prcp.business.kpi.mapper.KpiScoreSegmentMapper;
import com.prcp.business.kpi.mapper.KpiValueMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>KPI Service (定义/值/评分规则/区间段/试算评分)</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>KPI 定义 CRUD (含指标方案管理)</li>
 *   <li>KPI 值 CRUD</li>
 *   <li>评分规则 CRUD (含 segments 整段替换)</li>
 *   <li>试算评分 (按指标+日期) + 区间段匹配</li>
 *   <li>报表表项列表</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>软删除: is_deleted=1, 不物理删除</li>
 *   <li>评分规则: status='ACTIVE'/'INACTIVE'</li>
 *   <li>评分算法: 高优型 (higher_is_better=1) 按 [min, max] 线性; 低优型反向</li>
 *   <li>区间段: null = 负无穷/正无穷, 按 seg_order 遍历</li>
 *   <li>字段命名: snake_case 主 + camelCase 别名 (前端两种都用)</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.kpi.mapper.KpiMapper
 * @see com.prcp.business.kpi.entity.KpiDefinition
 */
@Service
@RequiredArgsConstructor
public class KpiService extends ServiceImpl<KpiMapper, KpiDefinition> {

    private final KpiMapper kpiMapper;
    private final KpiValueMapper kpiValueMapper;
    private final KpiScoreRuleMapper kpiScoreRuleMapper;
    private final KpiScoreSegmentMapper kpiScoreSegmentMapper;

    /**
     * <p>查询 KPI 定义列表</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param kpiCode  KPI 编码 (可选)
     * @param keyword  关键字 (可选)
     * @return 行 Map 列表
     */
    public R<List<Map<String, Object>>> listDefs(Long schemeId, String kpiCode, String keyword) {
        return R.ok(kpiMapper.listDefs(schemeId, kpiCode, keyword));
    }

    /**
     * <p>创建 KPI 定义 (校验 kpi_code/name 必填, 默认 status='ACTIVE', indicator_type=1)</p>
     *
     * @param d KPI 定义实体
     * @return R.ok(d) 或 R.fail
     */
    public R<?> createDef(KpiDefinition d) {
        if (d.getKpiCode() == null || d.getKpiCode().isEmpty())
            throw BizException.badRequest("kpi_code 不能为空");
        if (d.getKpiName() == null || d.getKpiName().isEmpty())
            throw BizException.badRequest("kpi_name 不能为空");
        d.setIsDeleted(0);
        d.setStatus(d.getStatus() == null ? "ACTIVE" : d.getStatus());
        if (d.getIndicatorType() == null) d.setIndicatorType(1);
        boolean ok = save(d);
        return ok ? R.ok(d) : R.fail("创建失败");
    }

    /**
     * <p>更新 KPI 定义</p>
     *
     * @param id 指标 ID (必填)
     * @param d  待更新的字段
     * @return R.ok() 或 R.fail; 不存在时抛 notFound
     */
    public R<?> updateDef(Long id, KpiDefinition d) {
        if (kpiMapper.selectById(id) == null) throw BizException.notFound("指标不存在");
        d.setId(id);
        boolean ok = updateById(d);
        return ok ? R.ok() : R.fail("更新失败");
    }

    /**
     * <p>软删除 KPI 定义 (is_deleted=1)</p>
     *
     * @param id 指标 ID (必填)
     * @return R.ok() 或 R.fail; 不存在时抛 notFound
     */
    public R<?> deleteDef(Long id) {
        if (kpiMapper.selectById(id) == null) throw BizException.notFound("指标不存在");
        KpiDefinition upd = new KpiDefinition();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(LocalDateTime.now());
        boolean ok = updateById(upd);
        return ok ? R.ok() : R.fail("删除失败");
    }

    /**
     * <p>查询 KPI 值列表</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param kpiId    指标 ID (可选)
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @return 行 Map 列表
     */
    public R<List<Map<String, Object>>> listValues(Long schemeId, Long kpiId, String dataDate) {
        return R.ok(kpiMapper.listValues(schemeId, kpiId, dataDate));
    }

    /**
     * <p>创建 KPI 值 (默认 data_date=今天, version='V1.0', calc_source='MANUAL')</p>
     *
     * @param v KPI 值实体
     * @return R.ok(v) 或 R.fail
     */
    public R<?> createValue(KpiValue v) {
        if (v.getKpiId() == null) throw BizException.badRequest("kpi_id 不能为空");
        if (v.getDataDate() == null) v.setDataDate(LocalDate.now());
        if (v.getVersion() == null) v.setVersion("V1.0");
        v.setIsDeleted(0);
        if (v.getCalcSource() == null) v.setCalcSource("MANUAL");
        boolean ok = kpiValueMapper.insert(v) > 0;
        return ok ? R.ok(v) : R.fail("创建失败");
    }

    /**
     * <p>软删除 KPI 值 (is_deleted=1)</p>
     *
     * @param id KPI 值主键 ID
     * @return R.ok() 或 R.fail
     */
    public R<?> deleteValue(Long id) {
        KpiValue upd = new KpiValue();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(LocalDateTime.now());
        boolean ok = kpiValueMapper.updateById(upd) > 0;
        return ok ? R.ok() : R.fail("删除失败");
    }

    /**
     * <p>试算评分 (按指标+日期 取最新 value + 最高优先级 ACTIVE 规则 + [min, max] 线性评分)</p>
     *
     * <p>高优型 (higher_is_better=1): cur ≥ max → total; cur ≤ min → 0; 中间按比例</p>
     * <p>低优型: cur ≤ min → total; cur ≥ max → 0; 中间按比例</p>
     *
     * @param kpiId    指标 ID (必填)
     * @param dataDate 数据日期 yyyy-MM-dd (必填)
     * @return R.ok(Map.of("score"/"rule", ruleName)); 缺值或缺规则时抛 badRequest
     */
    public R<?> recalcScore(Long kpiId, String dataDate) {
        // 取最近一条 value
        KpiValue v = kpiValueMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KpiValue>()
                .eq("kpi_id", kpiId)
                .eq("data_date", dataDate)
                .eq("is_deleted", 0)
                .last("LIMIT 1"));
        if (v == null) throw BizException.badRequest("该日期无指标值");

        // 找最高优先级的评分规则
        KpiScoreRule rule = kpiScoreRuleMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<KpiScoreRule>()
                .eq("kpi_id", kpiId)
                .eq("is_deleted", 0)
                .eq("status", "ACTIVE")
                .last("LIMIT 1"));
        if (rule == null) throw BizException.badRequest("该指标未配置评分规则");

        // 简单评分：超过阈值_max 100 分 / 低于阈值_min 0 分 / 线性
        BigDecimal cur = v.getCurrentValue();
        BigDecimal min = null, max = null;
        KpiDefinition def = kpiMapper.selectById(kpiId);
        if (def != null) {
            min = def.getThresholdMin();
            max = def.getThresholdMax();
        }
        BigDecimal score = new BigDecimal("50.00");
        if (cur != null && min != null && max != null && max.compareTo(min) > 0) {
            BigDecimal total = rule.getTotalScore() == null ? new BigDecimal("100") : rule.getTotalScore();
            if (rule.getHigherIsBetter() != null && rule.getHigherIsBetter() == 1) {
                if (cur.compareTo(max) >= 0) score = total;
                else if (cur.compareTo(min) <= 0) score = new BigDecimal("0");
                else {
                    BigDecimal ratio = cur.subtract(min).divide(max.subtract(min), 6, java.math.RoundingMode.HALF_UP);
                    score = ratio.multiply(total).setScale(2, java.math.RoundingMode.HALF_UP);
                }
            } else {
                if (cur.compareTo(min) >= 0) score = total;
                else if (cur.compareTo(max) <= 0) score = new BigDecimal("0");
                else {
                    BigDecimal ratio = max.subtract(cur).divide(max.subtract(min), 6, java.math.RoundingMode.HALF_UP);
                    score = ratio.multiply(total).setScale(2, java.math.RoundingMode.HALF_UP);
                }
            }
        }
        v.setScore(score);
        v.setUpdatedAt(LocalDateTime.now());
        kpiValueMapper.updateById(v);
        return R.ok(java.util.Map.of("score", score, "rule", rule.getRuleName()));
    }

    /**
     * <p>查询评分规则列表</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param kpiId    指标 ID (可选)
     * @return 行 Map 列表
     */
    public R<List<Map<String, Object>>> listScoreRules(Long schemeId, Long kpiId) {
        return R.ok(kpiMapper.listScoreRules(schemeId, kpiId));
    }

    /**
     * <p>创建评分规则 (默认 status='ACTIVE')</p>
     *
     * @param r 评分规则实体 (kpiId/ruleName 必填)
     * @return R.ok(Map.of("id"/"message", "ok")) 或 R.fail
     */
    public R<?> createScoreRule(KpiScoreRule r) {
        if (r.getKpiId() == null) throw BizException.badRequest("kpi_id 不能为空");
        if (r.getRuleName() == null || r.getRuleName().isEmpty())
            throw BizException.badRequest("rule_name 不能为空");
        r.setIsDeleted(0);
        r.setStatus(r.getStatus() == null ? "ACTIVE" : r.getStatus());
        boolean ok = kpiScoreRuleMapper.insert(r) > 0;
        if (!ok) return R.fail("创建失败");
        Long newId = r.getId();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", newId);
        resp.put("message", "ok");
        // segments 暂不在 create body 里（前端通过 update 带 segments），但保留接口位
        return R.ok(resp);
    }

    /**
     * <p>软删除评分规则 (is_deleted=1)</p>
     *
     * @param id 规则 ID (必填)
     * @return R.ok() 或 R.fail
     */
    public R<?> deleteScoreRule(Long id) {
        KpiScoreRule upd = new KpiScoreRule();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(LocalDateTime.now());
        boolean ok = kpiScoreRuleMapper.updateById(upd) > 0;
        return ok ? R.ok() : R.fail("删除失败");
    }

    /**
     * <p>更新评分规则 (含 segments 整段替换)</p>
     *
     * <p>对齐 Python routers/kpi.py update_score_rule: UPDATE 规则 → 软删旧 segments → 插入新 segments</p>
     *
     * @param id   规则 ID (必填)
     * @param r    待更新的规则字段
     * @param body body 含 segments 数组时一并替换; 不含时只更新主表
     * @return R.ok(Map.of("id"/"message"/"segment_count")); 不存在时抛 badRequest
     */
    @Transactional
    public R<?> updateScoreRule(Long id, KpiScoreRule r, Map<String, Object> body) {
        if (id == null) throw BizException.badRequest("id 不能为空");
        KpiScoreRule exist = kpiScoreRuleMapper.selectById(id);
        if (exist == null || Integer.valueOf(1).equals(exist.getIsDeleted())) {
            throw BizException.badRequest("规则不存在");
        }
        r.setId(id);
        r.setIsDeleted(null);
        r.setUpdatedAt(LocalDateTime.now());
        boolean ok = kpiScoreRuleMapper.updateById(r) > 0;
        if (!ok) return R.fail("更新失败");
        int inserted = 0;
        // 解析 body 中的 segments（整段替换）
        Object segsObj = body == null ? null : body.get("segments");
        if (segsObj instanceof List) {
            kpiScoreSegmentMapper.softDeleteByRuleId(id);
            for (Object o : (List<?>) segsObj) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> sm = (Map<?, ?>) o;
                KpiScoreSegment seg = new KpiScoreSegment();
                seg.setRuleId(id);
                seg.setSegOrder(sm.get("seg_order") == null ? 0 : ((Number) sm.get("seg_order")).intValue());
                seg.setMinValue(sm.get("min_value") == null ? null : new java.math.BigDecimal(sm.get("min_value").toString()));
                seg.setMaxValue(sm.get("max_value") == null ? null : new java.math.BigDecimal(sm.get("max_value").toString()));
                seg.setScore(new java.math.BigDecimal(sm.get("score").toString()));
                seg.setSegmentDesc(sm.get("segment_desc") == null ? null : sm.get("segment_desc").toString());
                seg.setIsDeleted(0);
                kpiScoreSegmentMapper.insert(seg);
                inserted++;
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        resp.put("message", "ok");
        resp.put("segment_count", inserted);
        return R.ok(resp);
    }

    /**
     * <p>按规则 + 指标值算分 (PIECEWISE 区间匹配 / LINEAR 线性插值)</p>
     *
     * <p>对齐 Python routers/kpi.py score_calc:
     * <ul>
     *   <li>PIECEWISE - 按 seg_order 遍历 segments, 落入 [min, max] 第一个段</li>
     *   <li>LINEAR - 每个 segment 是一个锚点 (min_value=x, max_value=null), score=该点得分;
     *                  段之间按 x 轴线性插值; 落在两端之外 clamp 到最近锚点分数</li>
     * </ul>
     * </p>
     *
     * @param ruleId 规则 ID (必填)
     * @param value  指标值 (必填)
     * @return R.ok(Map) 含 matched/value/score/matched_range/higher_is_better/calc_method; PIECEWISE 返回原段; LINEAR 返回 [x1,x2,y1,y2] 用于前端可视化插值
     */
    public R<Map<String, Object>> scoreCalc(Long ruleId, BigDecimal value) {
        if (ruleId == null) throw BizException.badRequest("rule_id 不能为空");
        if (value == null) throw BizException.badRequest("value 不能为空");
        KpiScoreRule rule = kpiScoreRuleMapper.selectById(ruleId);
        if (rule == null || Integer.valueOf(1).equals(rule.getIsDeleted())) {
            throw BizException.badRequest("规则不存在");
        }
        List<KpiScoreSegment> segs = kpiScoreSegmentMapper.listByRuleId(ruleId);
        String calcMethod = rule.getCalcMethod() == null ? "PIECEWISE" : rule.getCalcMethod().toUpperCase();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("value", value);
        resp.put("higher_is_better", rule.getHigherIsBetter());
        resp.put("calc_method", calcMethod);

        if ("LINEAR".equals(calcMethod)) {
            return R.ok(scoreCalcLinear(segs, value, rule, resp));
        }
        return R.ok(scoreCalcPiecewise(segs, value, rule, resp));
    }

    /**
     * <p>PIECEWISE 区间段匹配: 遍历 segments, 取第一个落入 [min, max] 的段</p>
     *
     * @param segs 段列表 (按 seg_order 升序)
     * @param value 指标值
     * @param rule 规则
     * @param resp 响应 Map (已含 value/higher_is_better/calc_method)
     * @return 填充完毕的 resp
     */
    private Map<String, Object> scoreCalcPiecewise(List<KpiScoreSegment> segs, BigDecimal value,
                                                   KpiScoreRule rule, Map<String, Object> resp) {
        KpiScoreSegment matched = null;
        for (KpiScoreSegment s : segs) {
            boolean inRange = true;
            if (s.getMinValue() != null && value.compareTo(s.getMinValue()) < 0) inRange = false;
            if (s.getMaxValue() != null && value.compareTo(s.getMaxValue()) > 0) inRange = false;
            if (inRange) { matched = s; break; }
        }
        if (matched == null) {
            resp.put("matched", false);
            resp.put("score", null);
            resp.put("message", "所有区间均不匹配，请检查规则配置");
            return resp;
        }
        Map<String, Object> matchedRange = new LinkedHashMap<>();
        matchedRange.put("min_value", matched.getMinValue());
        matchedRange.put("max_value", matched.getMaxValue());
        matchedRange.put("segment_desc", matched.getSegmentDesc());
        resp.put("matched", true);
        resp.put("score", matched.getScore());
        resp.put("matched_range", matchedRange);
        return resp;
    }

    /**
     * <p>LINEAR 线性插值: 每 segment 是锚点 (min_value=x, max_value=null), score=该点得分</p>
     *
     * <p>算法:
     * <ol>
     *   <li>提取锚点 [(x, score)], x 取 min_value (若 null 则用 max_value)</li>
     *   <li>按 x 升序排序</li>
     *   <li>value ≤ 最小锚点 → clamp 到最小得分</li>
     *   <li>value ≥ 最大锚点 → clamp 到最大得分</li>
     *   <li>落在中间 → 在相邻两锚点间线性插值: y = y1 + (value-x1)/(x2-x1) * (y2-y1)</li>
     * </ol>
     * </p>
     *
     * @param segs 段列表 (作为锚点)
     * @param value 指标值
     * @param rule 规则
     * @param resp 响应 Map (已含 value/higher_is_better/calc_method)
     * @return 填充完毕的 resp, 含 matched_range = [x1, x2, y1, y2] 用于前端可视化
     */
    private Map<String, Object> scoreCalcLinear(List<KpiScoreSegment> segs, BigDecimal value,
                                                KpiScoreRule rule, Map<String, Object> resp) {
        // 1) 提取锚点
        List<Map.Entry<BigDecimal, BigDecimal>> anchors = new ArrayList<>();
        for (KpiScoreSegment s : segs) {
            BigDecimal x = s.getMinValue() != null ? s.getMinValue() : s.getMaxValue();
            if (x == null || s.getScore() == null) continue;
            anchors.add(Map.entry(x, s.getScore()));
        }
        if (anchors.isEmpty()) {
            resp.put("matched", false);
            resp.put("score", null);
            resp.put("message", "LINEAR 规则没有可用锚点，请检查 segments 配置");
            return resp;
        }
        // 2) 按 x 升序
        anchors.sort(Comparator.comparing(Map.Entry::getKey));

        BigDecimal firstX = anchors.get(0).getKey();
        BigDecimal lastX = anchors.get(anchors.size() - 1).getKey();
        BigDecimal score;
        BigDecimal matchedX1 = null, matchedX2 = null, matchedY1 = null, matchedY2 = null;

        // 3) clamp 到最小
        if (value.compareTo(firstX) <= 0) {
            score = anchors.get(0).getValue();
            matchedX1 = firstX;
            matchedY1 = score;
            matchedX2 = firstX;
            matchedY2 = score;
        }
        // 4) clamp 到最大
        else if (value.compareTo(lastX) >= 0) {
            score = anchors.get(anchors.size() - 1).getValue();
            matchedX1 = lastX;
            matchedY1 = score;
            matchedX2 = lastX;
            matchedY2 = score;
        }
        // 5) 线性插值
        else {
            score = null;
            for (int i = 0; i < anchors.size() - 1; i++) {
                BigDecimal x1 = anchors.get(i).getKey();
                BigDecimal y1 = anchors.get(i).getValue();
                BigDecimal x2 = anchors.get(i + 1).getKey();
                BigDecimal y2 = anchors.get(i + 1).getValue();
                if (x1.compareTo(value) <= 0 && value.compareTo(x2) <= 0) {
                    if (x1.compareTo(x2) == 0) {
                        score = y1;
                    } else {
                        // t = (value - x1) / (x2 - x1)
                        BigDecimal t = value.subtract(x1).divide(x2.subtract(x1), 8, RoundingMode.HALF_UP);
                        score = y1.add(t.multiply(y2.subtract(y1))).setScale(4, RoundingMode.HALF_UP);
                    }
                    matchedX1 = x1; matchedY1 = y1;
                    matchedX2 = x2; matchedY2 = y2;
                    break;
                }
            }
        }

        resp.put("matched", score != null);
        resp.put("score", score);
        // 配套可视化字段: 命中的两个锚点
        Map<String, Object> matchedRange = new LinkedHashMap<>();
        matchedRange.put("x1", matchedX1);
        matchedRange.put("y1", matchedY1);
        matchedRange.put("x2", matchedX2);
        matchedRange.put("y2", matchedY2);
        resp.put("matched_range", matchedRange);
        resp.put("anchor_count", anchors.size());
        return resp;
    }

    /**
     * <p>列表 (含 segments 子表数据, 对齐 Python list_score_rules 行为 + 字段 snake_case)</p>
     *
     * <p>字段命名同时输出 snake_case 和 camelCase 别名 (前端两种命名都能用)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param kpiId    指标 ID (可选)
     * @return R.ok(List) 每条规则 + 嵌套 segments 数组
     */
    public R<List<Map<String, Object>>> listScoreRulesWithSegments(Long schemeId, Long kpiId) {
        List<Map<String, Object>> rules = kpiMapper.listScoreRules(schemeId, kpiId);
        if (rules == null || rules.isEmpty()) return R.ok(new ArrayList<>());
        List<Long> ruleIds = new ArrayList<>();
        for (Map<String, Object> r : rules) {
            Object rid = r.get("id");
            if (rid != null) ruleIds.add(((Number) rid).longValue());
        }
        Map<Long, List<Map<String, Object>>> segMap = new LinkedHashMap<>();
        for (Long rid : ruleIds) {
            List<KpiScoreSegment> segs = kpiScoreSegmentMapper.listByRuleId(rid);
            List<Map<String, Object>> segList = new ArrayList<>();
            for (KpiScoreSegment s : segs) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("id", s.getId());
                sm.put("rule_id", s.getRuleId());
                sm.put("seg_order", s.getSegOrder());
                sm.put("min_value", s.getMinValue());
                sm.put("max_value", s.getMaxValue());
                sm.put("score", s.getScore());
                sm.put("segment_desc", s.getSegmentDesc());
                segList.add(sm);
            }
            segMap.put(rid, segList);
        }
        // 字段命名对齐 Python（snake_case）+ 同时输出 camelCase 别名（前端两种命名都能用）
        List<Map<String, Object>> renamed = new ArrayList<>();
        for (Map<String, Object> r : rules) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : r.entrySet()) m.put(e.getKey(), e.getValue());
            // 兼容字段名（前端 camelCase 也直接可用）
            if (m.containsKey("scheme_id"))   m.put("schemeId",       m.get("scheme_id"));
            if (m.containsKey("scheme_code")) m.put("schemeCode",     m.get("scheme_code"));
            if (m.containsKey("scheme_name")) m.put("schemeName",     m.get("scheme_name"));
            if (m.containsKey("kpi_id"))      m.put("kpiId",          m.get("kpi_id"));
            if (m.containsKey("kpi_code"))    m.put("kpiCode",        m.get("kpi_code"));
            if (m.containsKey("kpi_name"))    m.put("kpiName",        m.get("kpi_name"));
            if (m.containsKey("rule_name"))   m.put("ruleName",       m.get("rule_name"));
            if (m.containsKey("calc_method")) m.put("calcMethod",     m.get("calc_method"));
            if (m.containsKey("total_score")) m.put("totalScore",     m.get("total_score"));
            if (m.containsKey("higher_is_better")) m.put("higherIsBetter", m.get("higher_is_better"));
            // segments 子表也兼容
            Object rid = m.get("id");
            if (rid != null) {
                List<Map<String, Object>> segs = segMap.get(((Number) rid).longValue());
                if (segs != null) {
                    List<Map<String, Object>> segsWithAlias = new ArrayList<>();
                    for (Map<String, Object> s : segs) {
                        Map<String, Object> sm = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> e : s.entrySet()) sm.put(e.getKey(), e.getValue());
                        if (sm.containsKey("rule_id"))      sm.put("ruleId",      sm.get("rule_id"));
                        if (sm.containsKey("seg_order"))    sm.put("segOrder",    sm.get("seg_order"));
                        if (sm.containsKey("min_value"))    sm.put("minValue",    sm.get("min_value"));
                        if (sm.containsKey("max_value"))    sm.put("maxValue",    sm.get("max_value"));
                        if (sm.containsKey("segment_desc")) sm.put("segmentDesc", sm.get("segment_desc"));
                        segsWithAlias.add(sm);
                    }
                    m.put("segments", segsWithAlias);
                }
            }
            renamed.add(m);
        }
        return R.ok(renamed);
    }

    /**
     * <p>查询 ACTIVE 的 KPI 方案列表 (供下拉选项)</p>
     *
     * @return R.ok(List)
     */
    public R<List<Map<String, Object>>> listKpiSchemes() { return R.ok(kpiMapper.listKpiSchemes()); }

    /**
     * <p>查询所有未删除的 KPI 方案 (含 INACTIVE, 方案维护 Modal 用)</p>
     *
     * @return R.ok(List) 字段为 camelCase
     */
    public R<List<Map<String, Object>>> listAllKpiSchemes() {
        QueryWrapper<KpiScheme> qw = new QueryWrapper<>();
        qw.eq("is_deleted", 0).orderByDesc("id");
        List<KpiScheme> list = kpiMapper.selectKpiSchemeList(qw);
        return R.ok(list.stream().map(this::schemeToMap).collect(java.util.stream.Collectors.toList()));
    }

    /**
     * <p>创建 KPI 方案 (默认 status='ACTIVE', kpi_count=0)</p>
     *
     * @param s KPI 方案实体 (schemeCode/schemeName 必填)
     * @return R.ok(Map) 字段为 camelCase 或 R.fail
     */
    public R<?> createScheme(KpiScheme s) {
        if (s.getSchemeCode() == null || s.getSchemeCode().isEmpty())
            throw BizException.badRequest("scheme_code 不能为空");
        if (s.getSchemeName() == null || s.getSchemeName().isEmpty())
            throw BizException.badRequest("scheme_name 不能为空");
        s.setIsDeleted(0);
        s.setStatus(s.getStatus() == null ? "ACTIVE" : s.getStatus());
        if (s.getKpiCount() == null) s.setKpiCount(0);
        boolean ok = kpiMapper.insertScheme(s) > 0;
        return ok ? R.ok(schemeToMap(s)) : R.fail("创建失败");
    }

    /**
     * <p>更新 KPI 方案</p>
     *
     * @param id 方案 ID (必填)
     * @param s  待更新的字段
     * @return R.ok() 或 R.fail; 不存在时抛 notFound
     */
    public R<?> updateScheme(Long id, KpiScheme s) {
        if (kpiMapper.selectKpiSchemeById(id) == null) throw BizException.notFound("方案不存在");
        s.setId(id);
        boolean ok = kpiMapper.updateKpiSchemeById(s) > 0;
        return ok ? R.ok() : R.fail("更新失败");
    }

    /**
     * <p>软删除 KPI 方案 (is_deleted=1)</p>
     *
     * @param id 方案 ID (必填)
     * @return R.ok() 或 R.fail
     */
    public R<?> deleteScheme(Long id) {
        KpiScheme upd = new KpiScheme();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(LocalDateTime.now());
        boolean ok = kpiMapper.updateKpiSchemeById(upd) > 0;
        return ok ? R.ok() : R.fail("删除失败");
    }

    /** Entity → Map（前端 camelCase） */
    private java.util.Map<String, Object> schemeToMap(KpiScheme s) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", s.getId());
        m.put("schemeCode", s.getSchemeCode());
        m.put("schemeName", s.getSchemeName());
        m.put("description", s.getDescription());
        m.put("kpiCount", s.getKpiCount());
        m.put("status", s.getStatus());
        m.put("createdAt", s.getCreatedAt());
        return m;
    }

    /**
     * <p>查询报表表项列表</p>
     *
     * @param rptId 报表 ID (必填)
     * @return R.ok(List)
     */
    public R<List<Map<String, Object>>> listRptItems(Long rptId) {
        if (rptId == null) throw BizException.badRequest("rpt_id 不能为空");
        return R.ok(kpiMapper.listRptItems(rptId));
    }
}
