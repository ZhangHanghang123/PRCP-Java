package com.prcp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * PRCP SpringBoot 启动类
 *
 * @author PRCP WorkBuddy Agent
 * @date 2026-09-23
 */
@SpringBootApplication(scanBasePackages = "com.prcp")
@MapperScan({
    "com.prcp.business.auth.mapper",
    "com.prcp.business.coa.mapper",
    "com.prcp.business.report.mapper",
    "com.prcp.business.kpi.mapper",
    "com.prcp.business.dashboard.mapper",
    "com.prcp.business.reversemt.mapper",
    "com.prcp.business.reverse.mapper",
    "com.prcp.business.sys.mapper",
    "com.prcp.business.metric.mapper",
    "com.prcp.business.data.basic.mapper",
    "com.prcp.business.data.reverse.mapper",
    "com.prcp.business.rate.mapper",
    "com.prcp.business.sim.mapper",
    "com.prcp.business.model.mapper",
    "com.prcp.business.esg.mapper",
    "com.prcp.business.balance.mapper",
    "com.prcp.business.params.cet1",
    "com.prcp.business.params.lcr",
    "com.prcp.business.params.nim",
    "com.prcp.business.params.nsfr",
    "com.prcp.business.params.roe",
    "com.prcp.business.params.eve",
    "com.prcp.framework.**.mapper"
})
public class PrcpApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(PrcpApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(PrcpApplication.class);
    }
}

