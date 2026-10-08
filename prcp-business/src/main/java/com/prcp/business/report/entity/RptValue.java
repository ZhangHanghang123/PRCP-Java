package com.prcp.business.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表表项值（按月试算结果）
 * 对应表 prcp_rpt_value
 */
@Data
@TableName("prcp_rpt_value")
public class RptValue {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long itemId;
    private String dataDate;
    private java.math.BigDecimal value;
    private String source;     // MANUAL / CALC / IMPORT
    private String calcLog;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}