package com.prcp.business.sim.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("prcp_sim_scheme")
public class SimScheme {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String schemeCode;
    private String schemeName;
    private Long coaSchemeId;
    private LocalDate dataDate;
    private String description;
    private Integer configNodeCount;
    private String status;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}