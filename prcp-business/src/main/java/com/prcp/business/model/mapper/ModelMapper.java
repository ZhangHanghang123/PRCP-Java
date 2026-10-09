package com.prcp.business.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.model.entity.Model;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_model 表的 SQL 访问层 (模型主表)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>listModels - 模型列表 (含 versionCount 子查询, LEFT JOIN kpi_scheme)</li>
 *   <li>softDeleteById - 软删模型</li>
 * </ul>
 * </p>
 *
 * <p>子表: {@link ModelVersionMapper} / {@link ModelParamMapper} / {@link ModelTrainMapper}。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface ModelMapper extends BaseMapper<Model> {

    /**
     * <p>模型列表 (含 versionCount 子查询, LEFT JOIN prcp_kpi_scheme 取方案名)</p>
     *
     * @param keyword 模糊搜索关键字 (model_code/model_name LIKE, 需自己加 %)
     * @param kw      同 keyword, 已拼好 %...% (用于兼容两种调用)
     * @param status  状态 (可选)
     * @return 模型 Map 列表, 按 ID DESC
     */
    @Select({
        "SELECT m.id, m.model_code AS modelCode, m.model_name AS modelName,",
        "       m.model_type AS modelType, m.biz_domain AS bizDomain,",
        "       m.kpi_scheme_id AS kpiSchemeId, m.description, m.algo_config AS algoConfig,",
        "       m.status, m.created_at AS createdAt, m.updated_at AS updatedAt,",
        "       (SELECT COUNT(*) FROM prcp_model_version v",
        "          WHERE v.model_id=m.id AND v.is_deleted=0) AS versionCount,",
        "       ks.scheme_code AS kpiSchemeCode, ks.scheme_name AS kpiSchemeName",
        "  FROM prcp_model m",
        "  LEFT JOIN prcp_kpi_scheme ks ON ks.id=m.kpi_scheme_id AND ks.is_deleted=0",
        " WHERE m.is_deleted=0",
        "   AND (#{keyword} IS NULL OR m.model_code LIKE #{kw} OR m.model_name LIKE #{kw})",
        "   AND (#{status} IS NULL OR m.status = #{status})",
        " ORDER BY m.id DESC"
    })
    List<Map<String, Object>> listModels(@Param("keyword") String keyword,
                                        @Param("kw") String kw,
                                        @Param("status") String status);

    /**
     * <p>软删模型 (置 is_deleted=1)</p>
     *
     * @param id  模型 ID
     * @param uid 操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_model SET is_deleted=1, updated_by=#{uid}, updated_at=NOW() WHERE id=#{id} AND is_deleted=0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);
}