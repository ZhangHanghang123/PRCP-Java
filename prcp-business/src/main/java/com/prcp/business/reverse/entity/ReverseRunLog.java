package com.prcp.business.reverse.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 反算运行日志（prcp_reverse_run_log）
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Data
@TableName("prcp_reverse_run_log")
public class ReverseRunLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private String logLevel;
    private String logMessage;
    private BigDecimal progress;
    private LocalDateTime createdAt;
}
