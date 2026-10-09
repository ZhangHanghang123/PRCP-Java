package com.prcp.business.reverse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.reverse.entity.ReverseScheme;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: prcp_reverse_scheme 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD</li>
 * </ul>
 * </p>
 *
 * <p>说明: 反算方案的 CRUD, 含目标约束列表由 {@link com.prcp.business.reversemt.mapper.ReverseMetricTableMapper} 提供。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface ReverseSchemeMapper extends BaseMapper<ReverseScheme> {
}
