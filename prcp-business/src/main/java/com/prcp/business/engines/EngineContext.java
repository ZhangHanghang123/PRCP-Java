package com.prcp.business.engines;

import lombok.Getter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * 引擎执行上下文（对位 Python EngineBase.run 中的 db 参数）
 *
 * <p>Java 端用 JdbcTemplate 而非 SQLAlchemy Session，所以封装一个轻量级上下文：
 * <ul>
 *   <li>dataSource — 用于编程式 SQL</li>
 *   <li>jdbcTemplate — 简化 SQL 执行</li>
 *   <li>namedJdbcTemplate — 命名参数</li>
 * </ul>
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Getter
public class EngineContext {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public EngineContext(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    /** 便于子类获取当前时间戳 */
    public java.sql.Timestamp now() {
        return new java.sql.Timestamp(System.currentTimeMillis());
    }
}
