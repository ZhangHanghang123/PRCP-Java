package com.prcp.business.params.roe;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ROE 参数补录
 * <p>对应表 prcp_roe_param</p>
 * <p>字段命名：net_profit_*（净利润 · 分子）+ net_asset_*（净资产 · 分母）</p>
 * <p>ID 规则：{scheme_code}_{node_code}_{YYYYMMDD}</p>
 *
 * @author PRCP WorkBuddy Agent
 * @date 2026-10-08
 */
@Data
@TableName("prcp_roe_param")
public class RoeParamEntity {

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

    @TableField("is_net_profit")
    private Integer isNetProfit;

    @TableField("net_profit_symbol")
    private String netProfitSymbol;

    @TableField("net_profit_factor")
    private BigDecimal netProfitFactor;

    @TableField("net_profit_category")
    private String netProfitCategory;

    @TableField("is_net_asset")
    private Integer isNetAsset;

    @TableField("net_asset_symbol")
    private String netAssetSymbol;

    @TableField("net_asset_factor")
    private BigDecimal netAssetFactor;

    @TableField("net_asset_category")
    private String netAssetCategory;

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