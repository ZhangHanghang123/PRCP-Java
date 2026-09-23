package com.prcp.business.metric.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("prcp_metric_coefficient")
public class MetricCoefficient {

    @TableId(type = IdType.INPUT)
    private String id;

    @TableField("scheme_id")
    private Long schemeId;

    @TableField("scheme_code")
    private String schemeCode;

    @TableField("node_id")
    private Long nodeId;

    @TableField("node_code")
    private String nodeCode;

    @TableField("metric_type")
    private String metricType;

    @TableField("metric_code")
    private String metricCode;

    @TableField("data_date")
    private LocalDate dataDate;

    @TableField("current_value")
    private BigDecimal currentValue;

    @TableField("y1_value")
    private BigDecimal y1Value;

    @TableField("y2_value")
    private BigDecimal y2Value;

    @TableField("y3_value")
    private BigDecimal y3Value;

    @TableField("y4_value")
    private BigDecimal y4Value;

    @TableField("y5_value")
    private BigDecimal y5Value;

    private String unit;

    private String description;

    private String status;

    @TableField("is_deleted")
    private Integer isDeleted;

    @TableField("created_by")
    private Long createdBy;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}