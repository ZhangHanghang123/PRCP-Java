package com.prcp.business.esg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.esg.entity.EsgRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_esg_run 表的 SQL 访问层 (ESG 引擎运行历史)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>insertRun - 插入运行历史 (返回自增 ID, useGeneratedKeys)</li>
 *   <li>selectById - 按 id 查</li>
 *   <li>listByScheme - 方案级 run 历史 (按 run_type/status 过滤)</li>
 *   <li>listAll / countAll - 全局 run 历史 (含分页)</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface EsgRunMapper extends BaseMapper<EsgRun> {

    /**
     * <p>插入运行历史 (返回自增 ID, useGeneratedKeys)</p>
     *
     * @param r 包含 schemeId/schemeCode/runType/status/paramsJson/outputJson/filePath/durationMs/errorMessage/createdBy 的实体
     * @return 受影响行数 (通常 1)
     */
    @Insert({
        "INSERT INTO prcp_esg_run",
        "  (scheme_id, scheme_code, run_type, status, params_json, output_json,",
        "   file_path, duration_ms, error_message, created_by)",
        "VALUES",
        "  (#{schemeId}, #{schemeCode}, #{runType}, #{status}, #{paramsJson}, #{outputJson},",
        "   #{filePath}, #{durationMs}, #{errorMessage}, #{createdBy})"
    })
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertRun(EsgRun r);

    /**
     * <p>按 id 查运行记录 (含全部字段, 软删也返回)</p>
     *
     * @param id 运行记录 ID
     * @return EsgRun 实体, 不存在返回 null
     */
    @Select("SELECT * FROM prcp_esg_run WHERE id = #{id}")
    EsgRun selectById(@Param("id") Long id);

    /**
     * <p>方案级 run 历史 (按 run_type/status 过滤, 取最近 N 条)</p>
     *
     * @param schemeId 方案 ID
     * @param runType  运行类型 (可选, 例 "FIT" / "PREDICT")
     * @param status   运行状态 (可选, 例 "SUCCESS" / "FAILED" / "RUNNING")
     * @param limit    取最近 N 条
     * @return 运行记录 Map 列表, 按 ID DESC
     */
    @Select({
        "<script>",
        "SELECT id, scheme_id AS schemeId, scheme_code AS schemeCode, run_type AS runType, status,",
        "       params_json AS paramsJson, output_json AS outputJson,",
        "       file_path AS filePath, duration_ms AS durationMs,",
        "       error_message AS errorMessage, created_at AS createdAt",
        "  FROM prcp_esg_run",
        " WHERE scheme_id = #{schemeId}",
        "   <if test='runType != null'> AND run_type = #{runType} </if>",
        "   <if test='status != null'> AND status = #{status} </if>",
        " ORDER BY id DESC",
        " LIMIT #{limit}",
        "</script>"
    })
    List<Map<String, Object>> listByScheme(@Param("schemeId") Long schemeId,
                                            @Param("runType") String runType,
                                            @Param("status") String status,
                                            @Param("limit") int limit);

    /**
     * <p>全局 run 历史 (含分页)</p>
     *
     * @param schemeId 方案 ID (可选, null 查全部方案)
     * @param runType  运行类型 (可选)
     * @param status   运行状态 (可选)
     * @param pageSize 每页条数
     * @param offset   偏移量
     * @return 运行记录 Map 列表, 按 ID DESC
     */
    @Select({
        "<script>",
        "SELECT id, scheme_id AS schemeId, scheme_code AS schemeCode, run_type AS runType, status,",
        "       params_json AS paramsJson, output_json AS outputJson,",
        "       file_path AS filePath, duration_ms AS durationMs,",
        "       error_message AS errorMessage, created_at AS createdAt",
        "  FROM prcp_esg_run",
        " WHERE 1=1",
        "   <if test='schemeId != null'> AND scheme_id = #{schemeId} </if>",
        "   <if test='runType != null'> AND run_type = #{runType} </if>",
        "   <if test='status != null'> AND status = #{status} </if>",
        " ORDER BY id DESC",
        " LIMIT #{pageSize} OFFSET #{offset}",
        "</script>"
    })
    List<Map<String, Object>> listAll(@Param("schemeId") Long schemeId,
                                       @Param("runType") String runType,
                                       @Param("status") String status,
                                       @Param("pageSize") int pageSize,
                                       @Param("offset") int offset);

    /**
     * <p>全局 run 历史总数 (配合 listAll 分页)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param runType  运行类型 (可选)
     * @param status   运行状态 (可选)
     * @return 满足条件的总行数
     */
    @Select({
        "<script>",
        "SELECT COUNT(*) FROM prcp_esg_run WHERE 1=1",
        "   <if test='schemeId != null'> AND scheme_id = #{schemeId} </if>",
        "   <if test='runType != null'> AND run_type = #{runType} </if>",
        "   <if test='status != null'> AND status = #{status} </if>",
        "</script>"
    })
    int countAll(@Param("schemeId") Long schemeId,
                 @Param("runType") String runType,
                 @Param("status") String status);
}