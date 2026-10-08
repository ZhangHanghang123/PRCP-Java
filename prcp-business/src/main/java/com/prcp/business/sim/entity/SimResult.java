package com.prcp.business.sim.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 引擎结果快照（prcp_sim_result）— 对位 Python prcp_sim_result 表
 *
 * <p>字段包含元数据 18 个 + 128 桶列（orig_m1..orig_y30, rem_m1..rem_y30）+ 主指标 4 个。
 * 桶列不映射为字段，运行时通过编程式 SQL 直接读写。
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Data
@TableName("prcp_sim_result")
public class SimResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String simSchemeCode;
    private Long simSchemeId;
    private Long runId;
    private LocalDate dataDate;
    private Integer dateOffset;
    private Long coaSchemeId;
    private Long coaNodeId;
    private String nodeCode;
    private String nodeName;
    private Integer nodeLevel;
    private String category;
    private Integer isConfigured;
    private Integer isAggregated;
    private BigDecimal currentBalance;
    private BigDecimal avgBalance;
    private BigDecimal weightedRate;
    private BigDecimal interestAmount;
    private String calcNote;
}
