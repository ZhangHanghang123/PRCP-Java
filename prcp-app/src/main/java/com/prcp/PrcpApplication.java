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
    "com.prcp.business.*.mapper",
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
