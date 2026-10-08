package com.prcp.business.esg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.esg.entity.EsgRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

import java.util.List;
import java.util.Map;

@Mapper
public interface EsgRunMapper extends BaseMapper<EsgRun> {

    /** 插入运行历史（返回 last_insert_id） */
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

    /** 按 id 查 */
    @Select("SELECT * FROM prcp_esg_run WHERE id = #{id}")
    EsgRun selectById(@Param("id") Long id);

    /** 方案级 run 历史（按 run_type/status 过滤） */
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

    /** 全局 run 历史（含分页） */
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