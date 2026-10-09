package com.prcp.business.esg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.esg.entity.EsgScenario;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_esg_scenario 表的 SQL 访问层 (ESG 情景集)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>listScenarios / countScenarios - 情景集列表 (含 has_blob / n_zeros / n_negatives)</li>
 *   <li>selectByCode - 按 scenario_code 查 (含 paths_blob 字节流 + 9 个派生 JSON)</li>
 *   <li>insertScenario - 插入情景集 (B+D 双写: blob + 9 JSON)</li>
 *   <li>updateLastRunId - 更新 last_run_id</li>
 * </ul>
 * </p>
 *
 * <p>关键派生 JSON: p10/p50/p90 (百分位) + final_mean/std/min/max (终值统计) + vol_per_maturity (波动率)。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface EsgScenarioMapper extends BaseMapper<EsgScenario> {

    /**
     * <p>情景集列表 (含 has_blob / n_zeros / n_negatives)</p>
     *
     * @param schemeId 方案 ID (可选, null 查全部)
     * @param pageSize 每页条数
     * @param offset   偏移量
     * @return 情景集 Map 列表, 按 ID DESC
     */
    @Select({
        "<script>",
        "SELECT s.id, s.scheme_id AS schemeId, s.scenario_code AS scenarioCode,",
        "       s.last_run_id AS lastRunId, s.scenario_type AS scenarioType,",
        "       s.file_path AS filePath,",
        "       s.n_scenarios AS nScenarios, s.n_steps AS nSteps, s.n_maturities AS nMaturities,",
        "       s.seed, s.maturities_json AS maturitiesJson,",
        "       s.file_size_bytes AS fileSizeBytes, s.description, s.created_at AS createdAt,",
        "       (s.paths_blob IS NOT NULL AND LENGTH(s.paths_blob) > 0) AS hasBlob,",
        "       COALESCE(s.n_zeros, 0) AS nZeros,",
        "       COALESCE(s.n_negatives, 0) AS nNegatives",
        "  FROM prcp_esg_scenario s",
        " WHERE s.is_deleted = 0",
        "   <if test='schemeId != null'> AND s.scheme_id = #{schemeId} </if>",
        " ORDER BY s.id DESC",
        " LIMIT #{pageSize} OFFSET #{offset}",
        "</script>"
    })
    List<Map<String, Object>> listScenarios(@Param("schemeId") Long schemeId,
                                             @Param("pageSize") int pageSize,
                                             @Param("offset") int offset);

    /**
     * <p>情景集总数 (配合 listScenarios 分页)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @return 满足条件的总行数
     */
    @Select({
        "<script>",
        "SELECT COUNT(*) FROM prcp_esg_scenario WHERE is_deleted = 0",
        "   <if test='schemeId != null'> AND scheme_id = #{schemeId} </if>",
        "</script>"
    })
    int countScenarios(@Param("schemeId") Long schemeId);

    /**
     * <p>按 scenario_code 查 (含 paths_blob 字节流 + 9 个派生 JSON)</p>
     *
     * @param code 情景集编码 (唯一)
     * @return 单行 Map 含 pathsBlob + percentile10Json/percentile50Json/percentile90Json + finalMeanJson/Std/Min/MaxJson + volPerMaturityJson
     */
    @Select("SELECT s.id, s.scheme_id AS schemeId, s.scenario_code AS scenarioCode," +
            "       s.last_run_id AS lastRunId, s.scenario_type AS scenarioType," +
            "       s.file_path AS filePath," +
            "       s.n_scenarios AS nScenarios, s.n_steps AS nSteps, s.n_maturities AS nMaturities," +
            "       s.seed, s.maturities_json AS maturitiesJson," +
            "       s.file_size_bytes AS fileSizeBytes, s.description, s.created_at AS createdAt," +
            "       (s.paths_blob IS NOT NULL AND LENGTH(s.paths_blob) > 0) AS hasBlob," +
            "       COALESCE(s.n_zeros, 0) AS nZeros," +
            "       COALESCE(s.n_negatives, 0) AS nNegatives," +
            "       s.paths_blob AS pathsBlob," +
            "       s.p10_json AS percentile10Json, s.p50_json AS percentile50Json, s.p90_json AS percentile90Json," +
            "       s.final_mean_json AS finalMeanJson, s.final_std_json AS finalStdJson," +
            "       s.final_min_json AS finalMinJson, s.final_max_json AS finalMaxJson," +
            "       s.vol_per_maturity_json AS volPerMaturityJson" +
            "  FROM prcp_esg_scenario s WHERE s.scenario_code = #{code} AND s.is_deleted = 0")
    Map<String, Object> selectByCode(@Param("code") String code);

    /**
     * <p>插入情景集 (B+D 双写: blob + 9 JSON 字段, useGeneratedKeys 返回自增 ID)</p>
     *
     * @param s 情景集实体 (含 pathsBlob 字节流 + 9 个派生 JSON)
     * @return 受影响行数 (通常 1)
     */
    @Insert({
        "<script>",
        "INSERT INTO prcp_esg_scenario",
        "  (scheme_id, scenario_code, scenario_type, file_path,",
        "   n_scenarios, n_steps, n_maturities, seed, maturities_json,",
        "   file_size_bytes, description, created_by,",
        "   paths_blob,",
        "   p10_json, p50_json, p90_json,",
        "   final_mean_json, final_std_json, final_min_json, final_max_json,",
        "   vol_per_maturity_json, n_zeros, n_negatives)",
        "VALUES",
        "  (#{schemeId}, #{scenarioCode}, #{scenarioType}, #{filePath},",
        "   #{nScenarios}, #{nSteps}, #{nMaturities}, #{seed}, #{maturitiesJson},",
        "   #{fileSizeBytes}, #{description}, #{createdBy},",
        "   #{pathsBlob},",
        "   #{percentile10Json}, #{percentile50Json}, #{percentile90Json},",
        "   #{finalMeanJson}, #{finalStdJson}, #{finalMinJson}, #{finalMaxJson},",
        "   #{volPerMaturityJson}, #{nZeros}, #{nNegatives})",
        "</script>"
    })
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertScenario(EsgScenario s);

    /**
     * <p>更新 last_run_id (运行后回写)</p>
     *
     * @param id   情景集 ID
     * @param runId 最新运行 ID
     * @return 受影响行数 (1 成功 / 0 未找到)
     */
    @Update("UPDATE prcp_esg_scenario SET last_run_id = #{runId} WHERE id = #{id}")
    int updateLastRunId(@Param("id") Long id, @Param("runId") Long runId);
}