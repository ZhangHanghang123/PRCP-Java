package com.prcp.business.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.model.entity.ModelParam;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_model_param 表的 SQL 访问层 (模型参数子表)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>listParams - 参数列表 (LEFT JOIN kpi_definition 取 refKpiName, 按 param_category FIELD 排序)</li>
 *   <li>listForCopy - 复制参数用 (扁平字段)</li>
 *   <li>softDeleteByVersion - 按版本软删 (版本删除时级联)</li>
 *   <li>softDeleteById - 按 ID 软删</li>
 * </ul>
 * </p>
 *
 * <p>param_category 取值: DATA_DATE / DATA_ESG / NEURAL_NETWORK / LOSS_FUNCTION / TRAINING / OPTIMIZER / KPI_DRIVEN。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface ModelParamMapper extends BaseMapper<ModelParam> {

    /**
     * <p>参数列表 (LEFT JOIN kpi_definition 取 refKpiName/refFormula, 按 param_category FIELD 排序)</p>
     *
     * @param versionId 模型版本 ID (可选, null 取全部)
     * @return 参数 Map 列表, 排序: version_id, param_category (FIELD), sort_order, id
     */
    @Select({
        "SELECT p.id, p.version_id AS versionId, p.kpi_id AS kpiId, p.kpi_code AS kpiCode,",
        "       p.param_code AS paramCode, p.param_name AS paramName,",
        "       p.param_type AS paramType, p.param_category AS paramCategory,",
        "       p.param_value AS paramValue, p.param_value_str AS paramValueStr,",
        "       p.unit, p.formula, p.formula_desc AS formulaDesc,",
        "       p.sort_order AS sortOrder, p.description,",
        "       p.created_at AS createdAt, p.updated_at AS updatedAt,",
        "       k.kpi_name AS refKpiName, k.formula AS refFormula",
        "  FROM prcp_model_param p",
        "  LEFT JOIN prcp_kpi_definition k ON k.id=p.kpi_id AND k.is_deleted=0",
        " WHERE p.is_deleted=0",
        "   AND (#{versionId} IS NULL OR p.version_id = #{versionId})",
        " ORDER BY p.version_id,",
        "          FIELD(p.param_category, 'DATA_DATE','DATA_ESG','NEURAL_NETWORK','LOSS_FUNCTION','TRAINING','OPTIMIZER','KPI_DRIVEN'),",
        "          p.sort_order, p.id"
    })
    List<Map<String, Object>> listParams(@Param("versionId") Long versionId);

    /**
     * <p>复制参数用 (扁平字段, 便于直接 insert 到新版本)</p>
     *
     * @param vid 源版本 ID
     * @return 参数 Map 列表 (kpi_id/kpi_code/param_code/param_name/param_type/param_value/unit/formula/...)
     */
    @Select("SELECT kpi_id, kpi_code, param_code, param_name, param_type, param_value, unit, formula, formula_desc, sort_order, description FROM prcp_model_param WHERE version_id=#{vid} AND is_deleted=0")
    List<Map<String, Object>> listForCopy(@Param("vid") Long vid);

    /**
     * <p>按版本软删所有参数 (版本删除时级联)</p>
     *
     * @param vid 版本 ID
     * @param uid 操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_model_param SET is_deleted=1, updated_by=#{uid}, updated_at=NOW() WHERE version_id=#{vid} AND is_deleted=0")
    int softDeleteByVersion(@Param("vid") Long vid, @Param("uid") Long uid);

    /**
     * <p>按 ID 软删单条参数</p>
     *
     * @param id  参数 ID
     * @param uid 操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_model_param SET is_deleted=1, updated_by=#{uid}, updated_at=NOW() WHERE id=#{id} AND is_deleted=0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);
}