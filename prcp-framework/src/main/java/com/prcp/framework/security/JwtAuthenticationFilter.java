package com.prcp.framework.security;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * JWT 鉴权过滤器
 * <p>从请求头解析 token，把 user_id / username 放到 request attribute 中</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            Claims claims = jwtUtil.parseToken(token);
            if (claims != null) {
                Long uid = jwtUtil.getUserId(claims);
                String username = jwtUtil.getUsername(claims);
                req.setAttribute("user_id", uid);
                req.setAttribute("username", username);
                log.debug("[JWT] ok: user_id={}, username={}", uid, username);
            } else {
                log.debug("[JWT] invalid token");
            }
        }
        chain.doFilter(req, res);
    }
}
