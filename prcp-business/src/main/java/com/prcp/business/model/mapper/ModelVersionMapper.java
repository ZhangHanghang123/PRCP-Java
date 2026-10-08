package com.prcp.business.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.model.entity.ModelVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface ModelVersionMapper extends BaseMapper<ModelVersion> {

    @Select({
        "SELECT v.id, v.model_id AS modelId, v.version_code AS versionCode,",
        "       v.version_name AS versionName, v.parent_version_id AS parentVersionId,",
        "       v.param_count AS paramCount, v.description, v.status,",
        "       v.created_at AS createdAt, v.updated_at AS updatedAt,",
        "       m.model_code AS modelCode, m.model_name AS modelName,",
        "       pv.version_code AS parentVersionCode",
        "  FROM prcp_model_version v",
        "  JOIN prcp_model m ON m.id=v.model_id AND m.is_deleted=0",
        "  LEFT JOIN prcp_model_version pv ON pv.id=v.parent_version_id AND pv.is_deleted=0",
        " WHERE v.is_deleted=0",
        "   AND (#{modelId} IS NULL OR v.model_id = #{modelId})",
        "   AND (#{keyword} IS NULL OR v.version_code LIKE #{kw} OR v.version_name LIKE #{kw})",
        "   AND (#{status} IS NULL OR v.status = #{status})",
        " ORDER BY v.model_id, v.id DESC"
    })
    List<Map<String, Object>> listVersions(@Param("modelId") Long modelId,
                                           @Param("keyword") String keyword,
                                           @Param("kw") String kw,
                                           @Param("status") String status);

    @Select("SELECT id FROM prcp_model_version WHERE model_id=#{mid} AND version_code=#{vc} AND is_deleted=1 LIMIT 1")
    Long selectDeletedByCode(@Param("mid") Long mid, @Param("vc") String vc);

    @Select("SELECT id FROM prcp_model_version WHERE model_id=#{mid} AND version_code=#{vc} AND is_deleted=0 LIMIT 1")
    Long selectActiveByCode(@Param("mid") Long mid, @Param("vc") String vc);

    @Update("UPDATE prcp_model_version SET is_deleted=0, version_name=#{vn}, description=#{d}, status=#{s}, created_by=#{uid}, updated_by=#{uid}, updated_at=NOW(), created_at=NOW() WHERE id=#{id}")
    int reactivate(@Param("id") Long id,
                   @Param("vn") String vn,
                   @Param("d") String description,
                   @Param("s") String status,
                   @Param("uid") Long uid);

    @Update("UPDATE prcp_model_version SET is_deleted=1, updated_by=#{uid}, updated_at=NOW() WHERE id=#{id} AND is_deleted=0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);

    @Update("UPDATE prcp_model_version SET param_count=#{n}, updated_by=#{uid}, updated_at=NOW() WHERE id=#{id}")
    int updateParamCount(@Param("id") Long id, @Param("n") int n, @Param("uid") Long uid);
}