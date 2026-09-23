package com.prcp.business.kpi.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.prcp.business.kpi.entity.KpiDefinition;
import com.prcp.business.kpi.entity.KpiScheme;
import com.prcp.business.kpi.entity.KpiScoreRule;
import com.prcp.business.kpi.entity.KpiValue;
import com.prcp.business.kpi.mapper.KpiMapper;
import com.prcp.business.kpi.mapper.KpiScoreRuleMapper;
import com.prcp.business.kpi.mapper.KpiValueMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KpiService extends ServiceImpl<KpiMapper, KpiDefinition> {

    private final KpiMapper kpiMapper;
    private final KpiValueMapper kpiValueMapper;
    private final KpiScoreRuleMapper kpiScoreRuleMapper;

    // ============ KPI 定义 ============
    public R<List<Map<String, Object>>> listDefs(Long schemeId, String kpiCode, String keyword) {
        return R.ok(kpiMapper.listDefs(schemeId, kpiCode, keyword));
    }

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

    public R<?> updateDef(Long id, KpiDefinition d) {
        if (kpiMapper.selectById(id) == null) throw BizException.notFound("指标不存在");
        d.setId(id);
        boolean ok = updateById(d);
        return ok ? R.ok() : R.fail("更新失败");
    }

    public R<?> deleteDef(Long id) {
        if (kpiMapper.selectById(id) == null) throw BizException.notFound("指标不存在");
        KpiDefinition upd = new KpiDefinition();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(LocalDateTime.now());
        boolean ok = updateById(upd);
        return ok ? R.ok() : R.fail("删除失败");
    }

    // ============ KPI 值 ============
    public R<List<Map<String, Object>>> listValues(Long kpiId, String dataDate) {
        return R.ok(kpiMapper.listValues(kpiId, dataDate));
    }

    public R<?> createValue(KpiValue v) {
        if (v.getKpiId() == null) throw BizException.badRequest("kpi_id 不能为空");
        if (v.getDataDate() == null) v.setDataDate(LocalDate.now());
        if (v.getVersion() == null) v.setVersion("V1.0");
        v.setIsDeleted(0);
        if (v.getCalcSource() == null) v.setCalcSource("MANUAL");
        boolean ok = kpiValueMapper.insert(v) > 0;
        return ok ? R.ok(v) : R.fail("创建失败");
    }

    public R<?> deleteValue(Long id) {
        KpiValue upd = new KpiValue();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(LocalDateTime.now());
        boolean ok = kpiValueMapper.updateById(upd) > 0;
        return ok ? R.ok() : R.fail("删除失败");
    }

    // ============ 试算评分（简易：与 last_value 比较） ============
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

    // ============ 评分规则 ============
    public R<List<Map<String, Object>>> listScoreRules(Long schemeId, Long kpiId) {
        return R.ok(kpiMapper.listScoreRules(schemeId, kpiId));
    }

    public R<?> createScoreRule(KpiScoreRule r) {
        if (r.getKpiId() == null) throw BizException.badRequest("kpi_id 不能为空");
        if (r.getRuleName() == null || r.getRuleName().isEmpty())
            throw BizException.badRequest("rule_name 不能为空");
        r.setIsDeleted(0);
        r.setStatus(r.getStatus() == null ? "ACTIVE" : r.getStatus());
        boolean ok = kpiScoreRuleMapper.insert(r) > 0;
        return ok ? R.ok(r) : R.fail("创建失败");
    }

    public R<?> deleteScoreRule(Long id) {
        KpiScoreRule upd = new KpiScoreRule();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(LocalDateTime.now());
        boolean ok = kpiScoreRuleMapper.updateById(upd) > 0;
        return ok ? R.ok() : R.fail("删除失败");
    }

    // ============ 辅助接口 ============
    public R<List<Map<String, Object>>> listKpiSchemes() { return R.ok(kpiMapper.listKpiSchemes()); }

    public R<List<Map<String, Object>>> listAllKpiSchemes() {
        QueryWrapper<KpiScheme> qw = new QueryWrapper<>();
        qw.eq("is_deleted", 0).orderByDesc("id");
        List<KpiScheme> list = kpiMapper.selectKpiSchemeList(qw);
        return R.ok(list.stream().map(this::schemeToMap).collect(java.util.stream.Collectors.toList()));
    }

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

    public R<?> updateScheme(Long id, KpiScheme s) {
        if (kpiMapper.selectKpiSchemeById(id) == null) throw BizException.notFound("方案不存在");
        s.setId(id);
        boolean ok = kpiMapper.updateKpiSchemeById(s) > 0;
        return ok ? R.ok() : R.fail("更新失败");
    }

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

    public R<List<Map<String, Object>>> listRptItems(Long rptId) {
        if (rptId == null) throw BizException.badRequest("rpt_id 不能为空");
        return R.ok(kpiMapper.listRptItems(rptId));
    }
}
