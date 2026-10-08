package com.prcp.business.reverse.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 反算目标约束（prcp_reverse_target）
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Data
@TableName("prcp_reverse_target")
public class ReverseTarget {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long schemeId;
    private Long kpiId;
    private String kpiCode;
    private String targetName;
    private BigDecimal targetValue;
    private String constraintType;
    private BigDecimal weight;
    private Integer horizonMonth;
    private Integer sortOrder;
    private String description;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
