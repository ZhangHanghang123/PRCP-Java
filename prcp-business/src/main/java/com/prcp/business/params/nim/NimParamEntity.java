package com.prcp.business.params.nim;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>实体类: 映射数据库表 prcp_nim_param</p>
 *
 * <p>字段说明:
 * <ul>
 *   <li>id - 复合主键 {scheme_code}_{node_code}_{YYYYMMDD} (业务生成, 非自增)</li>
 *   <li>schemeId / schemeCode - 方案 ID 和编码</li>
 *   <li>nodeId / nodeCode / nodeName - 科目节点三件套</li>
 *   <li>dataDate - 数据日期</li>
 *   <li>isInterestAsset + assetRate + assetOperator + assetCategory - 生息资产四件套 (分子)</li>
 *   <li>isInterestLiability + liabilityRate + liabilityOperator + liabilityCategory - 计息负债四件套 (分母)</li>
 *   <li>currentBalance - 期末余额</li>
 *   <li>isDeleted - 软删除标记</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
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
