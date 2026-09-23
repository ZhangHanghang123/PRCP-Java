package com.prcp.business.coa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账户册方案
 * 对应表 prcp_coa_scheme
 */
@Data
@TableName("prcp_coa_scheme")
public class CoaScheme {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String schemeCode;
    private String schemeName;
    private String description;
    private String status;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
