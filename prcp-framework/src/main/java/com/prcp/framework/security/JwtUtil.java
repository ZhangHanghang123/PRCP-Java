package com.prcp.framework.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类
 * <p>
 * 与 Python 版兼容：相同的密钥、算法、payload 结构
 * </p>
 */
@Component
public class JwtUtil {

    @Value("${prcp.jwt.secret:prcp-secret-key-2026}")
    private String secret;

    @Value("${prcp.jwt.expiration-hours:24}")
    private long expirationHours;

    private SecretKey getKey() {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        // JJWT 要求 HMAC-SHA 密钥 >= 256 bits (32 bytes)，不够则用 SHA-256 扩展
        if (bytes.length < 32) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                bytes = md.digest(bytes);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
        return new javax.crypto.spec.SecretKeySpec(bytes, "HmacSHA256");
    }

    /** 生成 Token */
    public String createToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("user_id", userId);
        claims.put("sub", username);
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(username)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + expirationHours * 3600 * 1000))
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** 解析 Token */
    public Claims parseToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            return null;
        }
    }

    public Long getUserId(Claims claims) {
        if (claims == null) return null;
        Object v = claims.get("user_id");
        if (v instanceof Number) return ((Number) v).longValue();
        return null;
    }

    public String getUsername(Claims claims) {
        return claims == null ? null : claims.getSubject();
    }
}
