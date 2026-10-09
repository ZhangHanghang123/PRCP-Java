package com.prcp.business.sim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sim.entity.SimScheme;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_sim_scheme 表的 SQL 访问层 (新业务模拟方案)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>listSchemes - 方案列表 (LEFT JOIN coa_scheme)</li>
 *   <li>selectDataDate - 查方案的数据日期</li>
 *   <li>refreshConfigNodeCount - 刷新配置节点计数 (子查询 COUNT)</li>
 *   <li>updateStatus - 更新状态</li>
 *   <li>softDeleteById - 软删方案</li>
 * </ul>
 * </p>
 *
 * <p>配套表: {@link SimNodeConfigMapper} / {@link SimTermRatioMapper} / {@link SimRunMapper} / {@link SimResultMapper}。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface SimSchemeMapper extends BaseMapper<SimScheme> {

    /**
     * <p>方案列表 (LEFT JOIN prcp_coa_scheme 取 coaSchemeCode/coaSchemeName)</p>
     *
     * @param keyword     模糊搜索 (scheme_code/scheme_name)
     * @param kw          同 keyword, 已拼好 %...%
     * @param status      状态 (可选)
     * @param coaSchemeId 账户册方案 ID (可选)
     * @return 方案 Map 列表, 按 ID DESC
     */
    @Select({
        "SELECT s.id, s.scheme_code AS schemeCode, s.scheme_name AS schemeName,",
        "       s.coa_scheme_id AS coaSchemeId, s.data_date AS dataDate,",
        "       s.description, s.config_node_count AS configNodeCount,",
        "       s.status, s.created_at AS createdAt, s.updated_at AS updatedAt,",
        "       cs.scheme_code AS coaSchemeCode, cs.scheme_name AS coaSchemeName",
        "  FROM prcp_sim_scheme s",
        "  LEFT JOIN prcp_coa_scheme cs ON cs.id = s.coa_scheme_id",
        " WHERE s.is_deleted = 0",
        "   AND (#{keyword} IS NULL OR s.scheme_code LIKE #{kw} OR s.scheme_name LIKE #{kw})",
        "   AND (#{status} IS NULL OR s.status = #{status})",
        "   AND (#{coaSchemeId} IS NULL OR s.coa_scheme_id = #{coaSchemeId})",
        " ORDER BY s.id DESC"
    })
    List<Map<String, Object>> listSchemes(@Param("keyword") String keyword,
                                          @Param("kw") String kw,
                                          @Param("status") String status,
                                          @Param("coaSchemeId") Long coaSchemeId);

    /**
     * <p>查方案的数据日期</p>
     *
     * @param id 方案 ID
     * @return data_date 字符串 (yyyy-MM-dd), 不存在返回 null
     */
    @Select("SELECT data_date FROM prcp_sim_scheme WHERE id = #{id} AND is_deleted = 0")
    String selectDataDate(@Param("id") Long id);

    /**
     * <p>刷新配置节点计数 (子查询 COUNT sim_node_config)</p>
     *
     * @param id  方案 ID
     * @param uid 操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_sim_scheme SET config_node_count = ("
        + " SELECT COUNT(*) FROM prcp_sim_node_config c"
        + "  WHERE c.scheme_id = #{id} AND c.is_deleted = 0"
        + "), updated_by = #{uid}, updated_at = NOW()"
        + " WHERE id = #{id}")
    int refreshConfigNodeCount(@Param("id") Long id, @Param("uid") Long uid);

    /**
     * <p>更新方案状态</p>
     *
     * @param id     方案 ID
     * @param status 新状态 (ACTIVE/DRAFT/ARCHIVED)
     * @param uid    操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_sim_scheme SET status = #{status}, updated_by = #{uid}, updated_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("uid") Long uid);

    /**
     * <p>软删方案</p>
     *
     * @param id  方案 ID
     * @param uid 操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_sim_scheme SET is_deleted = 1, updated_by = #{uid}, updated_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);
}