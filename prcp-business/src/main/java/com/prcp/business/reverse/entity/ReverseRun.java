package com.prcp.business.reverse.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 反算执行记录（prcp_reverse_run）
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Data
@TableName("prcp_reverse_run")
public class ReverseRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runCode;
    private Long schemeId;
    private String status;
    private BigDecimal progress;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer durationSec;
    private BigDecimal optimalValue;
    private String metrics;
    private String errorMessage;
    private String description;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
