package com.prcp.business.kpi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指标方案
 */
@Data
@TableName("prcp_kpi_scheme")
public class KpiScheme {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String schemeCode;
    private String schemeName;
    private String description;
    private Integer kpiCount;
    private String status;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
