package com.prcp.business.sim.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("prcp_sim_term_ratio")
public class SimTermRatio {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long configId;
    private Integer termValue;
    private String termUnit;
    private BigDecimal businessRatio;
    private BigDecimal interestRate;
    private Integer sortOrder;
    private String remark;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}