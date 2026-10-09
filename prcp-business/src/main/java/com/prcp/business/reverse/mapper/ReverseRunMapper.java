package com.prcp.business.reverse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.reverse.entity.ReverseRun;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: prcp_reverse_run 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD</li>
 * </ul>
 * </p>
 *
 * <p>说明: 每次反算任务的运行记录 (status/progress/duration), 由引擎调用 insert。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface ReverseRunMapper extends BaseMapper<ReverseRun> {
}
