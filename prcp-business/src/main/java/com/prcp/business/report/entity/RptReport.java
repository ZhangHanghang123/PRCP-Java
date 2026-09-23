package com.prcp.business.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表定义
 * 对应表 prcp_rpt_report
 */
@Data
@TableName("prcp_rpt_report")
public class RptReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String reportCode;
    private String reportName;
    private String reportType;     // BALANCE/INCOME/CASHFLOW/INDICATOR/RISK/LIQUIDITY
    private Long schemeId;
    private String description;
    private Integer itemCount;
    private String status;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}