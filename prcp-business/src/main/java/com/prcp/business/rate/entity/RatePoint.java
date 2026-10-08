package com.prcp.business.rate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("prcp_rate_point")
public class RatePoint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long curveId;
    private String curveCode;
    private LocalDate dataDate;
    private String ccy;

    // 13 个期限利率
    private BigDecimal rateD1;
    private BigDecimal rateD7;
    private BigDecimal rateM1;
    private BigDecimal rateM3;
    private BigDecimal rateM6;
    private BigDecimal rateY1;
    private BigDecimal rateY2;
    private BigDecimal rateY3;
    private BigDecimal rateY5;
    private BigDecimal rateY10;
    private BigDecimal rateY15;
    private BigDecimal rateY20;
    private BigDecimal rateY30;

    private BigDecimal curveShiftBps;
    private BigDecimal curveSlope;

    private LocalDate sourceDate;
    private String remark;

    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}