package com.prcp.business.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.model.entity.Model;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface ModelMapper extends BaseMapper<Model> {

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

    @Update("UPDATE prcp_model SET is_deleted=1, updated_by=#{uid}, updated_at=NOW() WHERE id=#{id} AND is_deleted=0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);
}