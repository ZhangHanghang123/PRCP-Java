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
 * <p>实体类: 映射数据库表 prcp_eve_param</p>
 *
 * <p>字段说明:
 * <ul>
 *   <li>id - 复合主键 {scheme_code}_{node_code}_{YYYYMMDD}</li>
 *   <li>schemeId / schemeCode - 方案 ID 和编码</li>
 *   <li>nodeId / nodeCode / nodeName - 科目节点三件套</li>
 *   <li>dataDate - 数据日期</li>
 *   <li>isAsset + assetType + assetCategory + assetOperator - 资产端 (利率敏感性资产)</li>
 *   <li>isLiability + liabilityType + liabilityCategory + liabilityOperator - 负债端 (利率敏感性负债)</li>
 *   <li>duration - 久期 (年, decimal(8,4))</li>
 *   <li>currentBalance - 期末余额</li>
 *   <li>isDeleted - 软删除标记</li>
 * </ul>
 * </p>
 *
 * <p>特点: asset_type / liability_type 为文本 (贷款/债券/同业/...); 与 CET1/LCR/NIM/NSFR/ROE 字段结构不同。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
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