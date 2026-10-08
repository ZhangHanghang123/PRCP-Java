package com.prcp.business.kpi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评分规则区间段（prcp_kpi_score_segment）
 * 一条规则对应多条区间段，按 seg_order 升序匹配第一个落入 [min_value, max_value] 的段
 */
@Data
@TableName("prcp_kpi_score_segment")
public class KpiScoreSegment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ruleId;
    private Integer segOrder;
    private BigDecimal minValue;
    private BigDecimal maxValue;
    private BigDecimal score;
    private String segmentDesc;
    private Integer isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}