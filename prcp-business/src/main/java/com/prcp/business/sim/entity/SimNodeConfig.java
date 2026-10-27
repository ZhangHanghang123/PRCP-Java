package com.prcp.business.sim.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("prcp_sim_node_config")
public class SimNodeConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long schemeId;
    private Long coaNodeId;
    private String coaNodeCode;
    private BigDecimal annualGrowthRate;
    private String termUnit;
    private Integer termCount;
    /** 🆕 v2.1: 是否模型测算 (1=是 走模型测算分支, 2=否 存量保持) */
    private Integer isModelCalc;
    /** 🆕 v2.1: 节奏配置类型 (1=线性 12 月均匀, 2=自定义) */
    private Integer rhythmType;
    private String remark;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}