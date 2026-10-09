package com.prcp.business.reverse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.reverse.entity.ReverseResult;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: prcp_reverse_result 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD</li>
 * </ul>
 * </p>
 *
 * <p>说明: 反算预测结果由反算引擎 (ReverseEngine) 写入, 查询走 BaseMapper 标准接口。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface ReverseResultMapper extends BaseMapper<ReverseResult> {
}
