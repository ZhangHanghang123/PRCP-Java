package com.prcp.business.kpi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("prcp_kpi_score_rule")
public class KpiScoreRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long schemeId;
    private Long kpiId;
    private String ruleName;
    private String calcMethod;
    private BigDecimal totalScore;
    private Integer higherIsBetter;
    private String description;
    private String status;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}