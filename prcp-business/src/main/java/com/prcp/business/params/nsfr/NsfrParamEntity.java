package com.prcp.business.params.nsfr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>实体类: 映射数据库表 prcp_nsfr_param</p>
 *
 * <p>字段说明:
 * <ul>
 *   <li>id - 复合主键 {scheme_code}_{node_code}_{YYYYMMDD}</li>
 *   <li>schemeId / schemeCode - 方案 ID 和编码</li>
 *   <li>nodeId / nodeCode / nodeName - 科目节点三件套</li>
 *   <li>dataDate - 数据日期</li>
 *   <li>isAsf + asfFactor + asfOperator - 可用稳定资金 (Available Stable Funding) 三件套</li>
 *   <li>isRsf + rsfFactor + rsfOperator - 所需稳定资金 (Required Stable Funding) 三件套</li>
 *   <li>currentBalance - 期末余额</li>
 *   <li>isDeleted - 软删除标记</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Data
@TableName("prcp_nsfr_param")
public class NsfrParamEntity {

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

    @TableField("is_asf")
    private Integer isAsf;

    @TableField("asf_factor")
    private BigDecimal asfFactor;

    @TableField("asf_operator")
    private String asfOperator;

    @TableField("is_rsf")
    private Integer isRsf;

    @TableField("rsf_factor")
    private BigDecimal rsfFactor;

    @TableField("rsf_operator")
    private String rsfOperator;

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