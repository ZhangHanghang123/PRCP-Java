package com.prcp.business.params.cet1;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CET1 参数补录 实体
 * 表：prcp_cet1_param
 * 主键规则：{scheme_code}_{node_code}_{YYYYMMDD}（手工指定 INPUT）
 *
 * 字段说明：
 *  - 分子参数（is_numerator / numerator_factor / numerator_operator）：CET1 核心一级资本折算
 *  - RWA  参数（is_rwa         / rwa_weight         / rwa_operator        ）：风险加权资产权重
 */
@Data
@TableName("prcp_cet1_param")
public class Cet1ParamEntity {

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

    @TableField("is_numerator")
    private Integer isNumerator;

    @TableField("numerator_factor")
    private BigDecimal numeratorFactor;

    @TableField("numerator_operator")
    private String numeratorOperator;

    @TableField("is_rwa")
    private Integer isRwa;

    @TableField("rwa_weight")
    private BigDecimal rwaWeight;

    @TableField("rwa_operator")
    private String rwaOperator;

    @TableField("numerator_category")
    private String numeratorCategory;

    @TableField("rwa_category")
    private String rwaCategory;

    @TableField("current_balance")
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