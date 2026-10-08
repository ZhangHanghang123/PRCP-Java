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
    private String remark;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}