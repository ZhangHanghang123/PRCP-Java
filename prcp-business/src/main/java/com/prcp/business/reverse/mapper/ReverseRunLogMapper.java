package com.prcp.business.reverse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.reverse.entity.ReverseRunLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: prcp_reverse_run_log 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD</li>
 * </ul>
 * </p>
 *
 * <p>说明: 反算引擎运行期间的关键步骤日志 (如对账差异、回滚步骤), 由引擎直接 insert。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface ReverseRunLogMapper extends BaseMapper<ReverseRunLog> {
}
