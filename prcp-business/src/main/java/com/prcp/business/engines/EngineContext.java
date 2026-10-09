package com.prcp.business.engines;

import lombok.Getter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * <p>引擎执行上下文 (对位 Python EngineBase.run 中的 db 参数)</p>
 *
 * <p>Java 端用 JdbcTemplate 而非 SQLAlchemy Session, 所以封装一个轻量级上下文:
 * <ul>
 *   <li>dataSource — 用于编程式 SQL</li>
 *   <li>jdbcTemplate — 简化 SQL 执行</li>
 *   <li>namedJdbcTemplate — 命名参数 (与 NewBusinessEngine 配合)</li>
 * </ul>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Getter
public class EngineContext {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    /**
     * <p>构造: 由 DataSource 派生 jdbcTemplate 和 namedJdbcTemplate</p>
     *
     * @param dataSource Spring 注入的数据源
     */
    public EngineContext(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * <p>便于子类获取当前时间戳 (run_started_at 等字段用)</p>
     *
     * @return 当前时间戳
     */
    public java.sql.Timestamp now() {
        return new java.sql.Timestamp(System.currentTimeMillis());
    }
}
