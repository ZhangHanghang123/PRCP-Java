package com.prcp.framework.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.prcp.framework.security.JwtAuthenticationFilter;

/**
 * Web 配置：注册 JWT 过滤器
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilter() {
        FilterRegistrationBean<JwtAuthenticationFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(jwtAuthenticationFilter);
        reg.addUrlPatterns("/*");
        reg.setOrder(1);
        return reg;
    }
}
