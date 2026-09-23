package com.prcp.business.kpi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("prcp_kpi_value")
public class KpiValue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long kpiId;
    private LocalDate dataDate;
    private String version;
    private BigDecimal currentValue;
    private BigDecimal prevValue;
    private BigDecimal prevYearValue;
    private String calcSource;
    private String calcLog;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private BigDecimal score;
}