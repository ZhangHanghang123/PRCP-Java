package com.prcp.business.reverse.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 反算方案实体（prcp_reverse_scheme）
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Data
@TableName("prcp_reverse_scheme")
public class ReverseScheme {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String schemeCode;
    private String schemeName;
    private String schemeType;
    private Long coaSchemeId;
    private LocalDate dataDate;
    private Integer horizonMonths;
    private String algorithm;
    private Long modelId;
    private String description;
    private String status;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
