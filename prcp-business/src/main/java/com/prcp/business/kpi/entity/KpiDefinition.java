package com.prcp.business.kpi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("prcp_kpi_definition")
public class KpiDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long schemeId;
    private Integer indicatorType;  // 1=公式 2=函数
    private String kpiCode;
    private String kpiName;
    private Long rptId;
    private String formula;
    private String scriptPath;
    private String scriptName;
    private String calcUnit;
    private String formulaDesc;
    private BigDecimal thresholdMin;
    private BigDecimal thresholdMax;
    private String status;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}