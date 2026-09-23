package com.prcp.business.metric.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("prcp_kpi_definition")
public class MetricOption {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String kpiCode;
    private String kpiName;
    private String kpiType;
    private String unit;
    private String description;
    private Integer status;
    private LocalDateTime createdAt;
}