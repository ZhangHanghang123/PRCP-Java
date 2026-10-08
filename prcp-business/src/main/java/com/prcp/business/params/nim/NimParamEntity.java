package com.prcp.business.params.nim;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * NIM 参数补录（净息差计量参数）
 * <p>对应表 prcp_nim_param</p>
 * <p>主键由业务生成（复合字符串 ID）：{scheme_code}_{node_code}_{YYYYMMDD}</p>
 */
@Data
@TableName("prcp_nim_param")
public class NimParamEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private Integer schemeId;
    private String schemeCode;
    private Integer nodeId;
    private String nodeCode;
    private String nodeName;
    private LocalDate dataDate;

    /** 是否生息资产（0/1） */
    private Integer isInterestAsset;
    private BigDecimal assetRate;
    private String assetOperator;
    private String assetCategory;

    /** 是否计息负债（0/1） */
    private Integer isInterestLiability;
    private BigDecimal liabilityRate;
    private String liabilityOperator;
    private String liabilityCategory;

    private BigDecimal currentBalance;
    private String ruleNote;
    private String status;

    private Integer isDeleted;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
