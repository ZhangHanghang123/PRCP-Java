package com.prcp.business.params.lcr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * LCR 参数补录 实体
 * <p>对应表 prcp_lcr_param</p>
 *
 * <p>ID 规则：{scheme_code}_{node_code}_{YYYYMMDD}
 * 例如：ZX_COA_S010102010101_20251231</p>
 *
 * <p>字段分为两组：
 * <ul>
 *   <li>分子（HQLA）：isNumerator + numFactor + numOperator</li>
 *   <li>分母（30 天净流出）：isDenominator + denFactor + denOperator</li>
 * </ul>
 * </p>
 */
@Data
@TableName("prcp_lcr_param")
public class LcrParamEntity {

    /** 主键：{scheme_code}_{node_code}_{YYYYMMDD} */
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

    // ============ 分子（HQLA）============
    @TableField("is_numerator")
    private Integer isNumerator;

    @TableField("num_factor")
    private BigDecimal numFactor;

    @TableField("num_operator")
    private String numOperator;

    // ============ 分母（30 天净流出）============
    @TableField("is_denominator")
    private Integer isDenominator;

    @TableField("den_factor")
    private BigDecimal denFactor;

    @TableField("den_operator")
    private String denOperator;

    // ============ 其他 ============
    @TableField("current_balance")
    private BigDecimal currentBalance;

    @TableField("rule_note")
    private String ruleNote;

    private String status;

    @TableField("is_deleted")
    private Integer isDeleted;

    @TableField("created_by")
    private Long createdBy;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
