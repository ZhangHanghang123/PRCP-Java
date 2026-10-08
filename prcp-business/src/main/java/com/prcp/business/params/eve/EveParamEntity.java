package com.prcp.business.params.eve;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * EVE 参数补录
 * 对应表 prcp_eve_param
 *
 * 字段特点（与 CET1/LCR/NIM/NSFR/ROE 不同）：
 *   - asset_type / liability_type 为文本（贷款/债券/同业/...）
 *   - duration 为久期（年），decimal(8,4)
 *   - 主键由 scheme_code + node_code + YYYYMMDD 拼成，非自增
 */
@Data
@TableName("prcp_eve_param")
public class EveParamEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    @TableField("scheme_id")
    private Long schemeId;

    @TableField("scheme_code")
    private String schemeCode;

    @TableField("node_id")
    private Long nodeId;

    @TableField("node_code")
    private String nodeCode;

    @TableField("node_name")
    private String nodeName;

    @TableField("data_date")
    private LocalDate dataDate;

    @TableField("is_asset")
    private Integer isAsset;

    @TableField("asset_type")
    private String assetType;

    @TableField("asset_category")
    private String assetCategory;

    @TableField("asset_operator")
    private String assetOperator;

    @TableField("is_liability")
    private Integer isLiability;

    @TableField("liability_type")
    private String liabilityType;

    @TableField("liability_category")
    private String liabilityCategory;

    @TableField("liability_operator")
    private String liabilityOperator;

    private BigDecimal duration;

    private BigDecimal currentBalance;

    @TableField("rule_note")
    private String ruleNote;

    private String status;

    @TableField("is_deleted")
    private Integer isDeleted;

    @TableField("created_by")
    private String createdBy;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}