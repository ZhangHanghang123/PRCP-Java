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
 * <p>实体类: 映射数据库表 prcp_lcr_param</p>
 *
 * <p>字段说明:
 * <ul>
 *   <li>id - 复合主键 {scheme_code}_{node_code}_{YYYYMMDD}, 例 ZX_COA_S010102010101_20251231</li>
 *   <li>schemeId / schemeCode - 方案 ID 和编码</li>
 *   <li>nodeId / nodeCode / nodeName - 科目节点三件套</li>
 *   <li>dataDate - 数据日期</li>
 *   <li>isNumerator + numFactor + numOperator - 分子三件套 (合格优质流动性资产 HQLA)</li>
 *   <li>isDenominator + denFactor + denOperator - 分母三件套 (30 天净现金流出)</li>
 *   <li>currentBalance - 期末余额</li>
 *   <li>isDeleted - 软删除标记</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
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
