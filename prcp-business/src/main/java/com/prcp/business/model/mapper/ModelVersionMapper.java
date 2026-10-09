package com.prcp.business.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.model.entity.ModelVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_model_version 表的 SQL 访问层 (模型版本子表)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>listVersions - 版本列表 (JOIN model, LEFT JOIN parent version)</li>
 *   <li>selectDeletedByCode / selectActiveByCode - 按 (model_id, version_code) 查 id (校验唯一性 / 复用)</li>
 *   <li>reactivate - 恢复已软删版本</li>
 *   <li>softDeleteById - 软删版本</li>
 *   <li>updateParamCount - 更新参数计数</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface ModelVersionMapper extends BaseMapper<ModelVersion> {

    /**
     * <p>版本列表 (JOIN prcp_model 取 modelCode/modelName, LEFT JOIN 父版本)</p>
     *
     * @param modelId 模型 ID (可选)
     * @param keyword 模糊搜索 (version_code/version_name)
     * @param kw      同 keyword, 已拼好 %...%
     * @param status  状态 (可选)
     * @return 版本 Map 列表, 按 model_id, id DESC
     */
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

    /**
     * <p>按 (model_id, version_code) 查已软删的版本 ID (用于复活复用)</p>
     *
     * @param mid 模型 ID
     * @param vc  版本编码
     * @return 已软删版本的 ID, 不存在返回 null
     */
    @Select("SELECT id FROM prcp_model_version WHERE model_id=#{mid} AND version_code=#{vc} AND is_deleted=1 LIMIT 1")
    Long selectDeletedByCode(@Param("mid") Long mid, @Param("vc") String vc);

    /**
     * <p>按 (model_id, version_code) 查 active 版本 ID (用于唯一性校验)</p>
     *
     * @param mid 模型 ID
     * @param vc  版本编码
     * @return active 版本 ID, 不存在返回 null
     */
    @Select("SELECT id FROM prcp_model_version WHERE model_id=#{mid} AND version_code=#{vc} AND is_deleted=0 LIMIT 1")
    Long selectActiveByCode(@Param("mid") Long mid, @Param("vc") String vc);

    /**
     * <p>恢复已软删版本 (置 is_deleted=0, 重写 created_at/updated_at)</p>
     *
     * @param id  版本 ID
     * @param vn  新版本名
     * @param d   描述
     * @param s   状态
     * @param uid 操作人 ID (同时作为 created_by/updated_by)
     * @return 受影响行数
     */
    @Update("UPDATE prcp_model_version SET is_deleted=0, version_name=#{vn}, description=#{d}, status=#{s}, created_by=#{uid}, updated_by=#{uid}, updated_at=NOW(), created_at=NOW() WHERE id=#{id}")
    int reactivate(@Param("id") Long id,
                   @Param("vn") String vn,
                   @Param("d") String description,
                   @Param("s") String status,
                   @Param("uid") Long uid);

    /**
     * <p>软删版本</p>
     *
     * @param id  版本 ID
     * @param uid 操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_model_version SET is_deleted=1, updated_by=#{uid}, updated_at=NOW() WHERE id=#{id} AND is_deleted=0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);

    /**
     * <p>更新参数计数 (版本下参数增删时同步)</p>
     *
     * @param id  版本 ID
     * @param n   新参数数
     * @param uid 操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_model_version SET param_count=#{n}, updated_by=#{uid}, updated_at=NOW() WHERE id=#{id}")
    int updateParamCount(@Param("id") Long id, @Param("n") int n, @Param("uid") Long uid);
}