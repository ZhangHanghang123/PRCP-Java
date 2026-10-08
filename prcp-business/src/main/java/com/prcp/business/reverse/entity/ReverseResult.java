package com.prcp.business.reverse.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 反算预测结果（prcp_reverse_result）
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Data
@TableName("prcp_reverse_result")
public class ReverseResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private Integer predictMonth;
    private LocalDate predictDate;
    private Long rptItemId;
    private String rptItemCode;
    private BigDecimal currentValue;
    private BigDecimal adjustedValue;
    private BigDecimal deltaValue;
    private Integer isDeleted;
    private LocalDateTime createdAt;
}
