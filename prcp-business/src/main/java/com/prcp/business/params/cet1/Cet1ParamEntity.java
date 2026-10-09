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
 * <p>实体类: 映射数据库表 prcp_cet1_param</p>
 *
 * <p>字段说明:
 * <ul>
 *   <li>id - 复合主键 {scheme_code}_{node_code}_{YYYYMMDD} (手工指定 INPUT)</li>
 *   <li>schemeId / schemeCode - 方案 ID 和编码</li>
 *   <li>nodeId / nodeCode / nodeName - 科目节点三件套</li>
 *   <li>dataDate - 数据日期</li>
 *   <li>isNumerator + numeratorFactor + numeratorOperator - 分子参数三件套 (CET1 核心一级资本)</li>
 *   <li>isRwa + rwaWeight + rwaOperator - 风险加权资产三件套 (RWA)</li>
 *   <li>numeratorCategory / rwaCategory - 分子/RWA 分类</li>
 *   <li>currentBalance - 期末余额</li>
 *   <li>isDeleted - 软删除标记 (0 有效, 1 删除)</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
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