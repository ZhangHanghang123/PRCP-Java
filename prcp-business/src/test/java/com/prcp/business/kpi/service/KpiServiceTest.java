package com.prcp.business.kpi.service;

import com.prcp.business.kpi.entity.KpiScoreRule;
import com.prcp.business.kpi.entity.KpiScoreSegment;
import com.prcp.business.kpi.mapper.KpiMapper;
import com.prcp.business.kpi.mapper.KpiScoreRuleMapper;
import com.prcp.business.kpi.mapper.KpiScoreSegmentMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * KpiService 单元测试
 *
 * 覆盖：
 * - scoreCalc：区间段匹配（4 个场景）
 * - listScoreRulesWithSegments：snake_case 字段命名 + segments 嵌套
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KpiService 评分规则 & 区间匹配")
class KpiServiceTest {

    @Mock private KpiMapper kpiMapper;
    @Mock private KpiScoreRuleMapper kpiScoreRuleMapper;
    @Mock private KpiScoreSegmentMapper kpiScoreSegmentMapper;

    @InjectMocks private KpiService kpiService;

    private KpiScoreRule rule;

    @BeforeEach
    void setUp() {
        rule = new KpiScoreRule();
        rule.setId(7L);
        rule.setRuleName("利率风险评分");
        rule.setHigherIsBetter(0);
        rule.setTotalScore(new BigDecimal("100"));
        rule.setIsDeleted(0);
    }

    /** 构造 segment 工具方法 */
    private KpiScoreSegment seg(int order, BigDecimal min, BigDecimal max, BigDecimal score, String desc) {
        KpiScoreSegment s = new KpiScoreSegment();
        s.setRuleId(7L);
        s.setSegOrder(order);
        s.setMinValue(min);
        s.setMaxValue(max);
        s.setScore(score);
        s.setSegmentDesc(desc);
        return s;
    }

    @Test
    @DisplayName("scoreCalc：value 落入第一个匹配段（[-50000,-20000]）")
    void testScoreCalc_MatchFirstSegment() {
        when(kpiScoreRuleMapper.selectById(7L)).thenReturn(rule);
        when(kpiScoreSegmentMapper.listByRuleId(7L)).thenReturn(Arrays.asList(
            seg(1, new BigDecimal("-50000"), new BigDecimal("-20000"), new BigDecimal("70"), "轻度亏损"),
            seg(2, new BigDecimal("-20000"), new BigDecimal("0"),      new BigDecimal("90"), "微亏"),
            seg(3, new BigDecimal("0"),     null,                       new BigDecimal("100"), "盈利")
        ));

        R<Map<String, Object>> resp = kpiService.scoreCalc(7L, new BigDecimal("-30000"));
        assertNotNull(resp);
        assertEquals(0, resp.getCode());
        Map<String, Object> data = resp.getData();
        assertNotNull(data);
        assertEquals(true, data.get("matched"));
        assertEquals(new BigDecimal("70"), data.get("score"));
        assertEquals(0, data.get("higher_is_better"));

        @SuppressWarnings("unchecked")
        Map<String, Object> range = (Map<String, Object>) data.get("matched_range");
        assertEquals("轻度亏损", range.get("segment_desc"));
        assertEquals(new BigDecimal("-50000"), range.get("min_value"));
        assertEquals(new BigDecimal("-20000"), range.get("max_value"));
    }

    @Test
    @DisplayName("scoreCalc：value 落入最后一段（min=0,max=null 表示正无穷）")
    void testScoreCalc_MatchLastSegment_NoUpperBound() {
        when(kpiScoreRuleMapper.selectById(7L)).thenReturn(rule);
        when(kpiScoreSegmentMapper.listByRuleId(7L)).thenReturn(Arrays.asList(
            seg(1, null,                          new BigDecimal("0"),    new BigDecimal("0"),   "≤0"),
            seg(2, new BigDecimal("0"),            new BigDecimal("100"),  new BigDecimal("50"),  "(0,100]"),
            seg(3, new BigDecimal("100"),          null,                   new BigDecimal("100"), ">100")
        ));

        R<Map<String, Object>> resp = kpiService.scoreCalc(7L, new BigDecimal("150"));
        assertEquals(true, resp.getData().get("matched"));
        assertEquals(new BigDecimal("100"), resp.getData().get("score"));
    }

    @Test
    @DisplayName("scoreCalc：value 不在任何段 → matched=false")
    void testScoreCalc_NoMatch() {
        when(kpiScoreRuleMapper.selectById(7L)).thenReturn(rule);
        when(kpiScoreSegmentMapper.listByRuleId(7L)).thenReturn(Arrays.asList(
            seg(1, new BigDecimal("0"),   new BigDecimal("100"),  new BigDecimal("50"),  "[0,100]")
        ));

        R<Map<String, Object>> resp = kpiService.scoreCalc(7L, new BigDecimal("200"));
        assertEquals(false, resp.getData().get("matched"));
        assertNull(resp.getData().get("score"));
        assertNotNull(resp.getData().get("message"));
    }

    @Test
    @DisplayName("scoreCalc：规则不存在 → 抛 BizException")
    void testScoreCalc_RuleNotFound() {
        when(kpiScoreRuleMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
            () -> kpiService.scoreCalc(99L, new BigDecimal("0")));
        assertTrue(ex.getMessage().contains("规则不存在"));
    }

    @Test
    @DisplayName("scoreCalc：null ruleId → 抛 BizException")
    void testScoreCalc_NullRuleId() {
        assertThrows(BizException.class,
            () -> kpiService.scoreCalc(null, new BigDecimal("0")));
    }

    @Test
    @DisplayName("listScoreRulesWithSegments：返回 snake_case 字段 + segments 嵌套")
    void testListScoreRulesWithSegments() {
        // mock kpiMapper.listScoreRules 返回 snake_case 行
        java.util.Map<String, Object> rawRule = new java.util.LinkedHashMap<>();
        rawRule.put("id", 7L);
        rawRule.put("scheme_id", 1L);
        rawRule.put("kpi_id", 1L);
        rawRule.put("rule_name", "测试规则");
        rawRule.put("calc_method", "LINEAR");
        rawRule.put("total_score", new BigDecimal("100"));
        rawRule.put("higher_is_better", 0);

        when(kpiMapper.listScoreRules(1L, 1L)).thenReturn(new ArrayList<>(java.util.Collections.singletonList(rawRule)));
        when(kpiScoreSegmentMapper.listByRuleId(7L)).thenReturn(Arrays.asList(
            seg(1, new BigDecimal("-50000"), new BigDecimal("0"), new BigDecimal("70"), "亏损区间"),
            seg(2, new BigDecimal("0"),      null,               new BigDecimal("100"), "盈利区间")
        ));

        R<List<Map<String, Object>>> resp = kpiService.listScoreRulesWithSegments(1L, 1L);
        assertEquals(0, resp.getCode());
        List<Map<String, Object>> rules = resp.getData();
        assertEquals(1, rules.size());
        Map<String, Object> r = rules.get(0);
        // 验证 snake_case 字段保留
        assertEquals(1L, r.get("scheme_id"));
        assertEquals("LINEAR", r.get("calc_method"));
        assertEquals(0, r.get("higher_is_better"));
        // segments 嵌套（Service 顺序：id, rule_id, seg_order, min_value, max_value, score, segment_desc）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segs = (List<Map<String, Object>>) r.get("segments");
        assertEquals(2, segs.size());
        assertEquals("亏损区间", segs.get(0).get("segment_desc"));
        // 验证 seg_order = 1（第一段）
        assertEquals(1, segs.get(0).get("seg_order"));
        // 验证 min_value/max_value 是 BigDecimal
        assertEquals(new BigDecimal("-50000"), segs.get(0).get("min_value"));
        assertEquals(new BigDecimal("0"), segs.get(0).get("max_value"));
    }
}