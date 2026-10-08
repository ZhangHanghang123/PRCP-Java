package com.prcp.business.esg.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Svensson 6 参数曲线（曲线日 + 数据源维度）
 * 对齐 Python prcp_esg_curve_point 表
 */
@Data
@TableName("prcp_esg_curve_point")
public class EsgCurvePoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonAlias({"curve_date"})
    private LocalDate curveDate;

    private String source;       // ECB / FRB / CUSTOM / BANK

    private BigDecimal theta0;
    private BigDecimal theta1;
    private BigDecimal theta2;
    private BigDecimal theta3;

    private BigDecimal lambda1;
    private BigDecimal lambda2;

    @JsonAlias({"raw_data_json"})
    private String rawDataJson;

    private String description;

    @JsonAlias({"is_deleted"})
    private Integer isDeleted;

    @JsonAlias({"created_by"})
    private Long createdBy;

    @JsonAlias({"updated_by"})
    private Long updatedBy;

    @JsonAlias({"created_at"})
    private LocalDateTime createdAt;

    @JsonAlias({"updated_at"})
    private LocalDateTime updatedAt;
}