package com.prcp.business.coa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.coa.entity.CoaScheme;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: prcp_coa_scheme 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD</li>
 * </ul>
 * </p>
 *
 * <p>说明: 自定义列表查询由 DashboardMapper / RptMapper 等通过 JOIN 间接访问。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface CoaSchemeMapper extends BaseMapper<CoaScheme> {
}
