package com.prcp.business.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.prcp.business.auth.entity.SysUser;
import com.prcp.business.auth.mapper.SysUserMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import com.prcp.framework.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>认证 Controller (登录鉴权)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 提供用户名 + 密码登录, 返回 JWT token, 兼容 Python 版 /prcp/api/auth/login 接口</li>
 *   <li>核心端点: POST /auth/login (登录)、POST /auth/health (健康检查)</li>
 *   <li>关联模块: SysUserMapper、SysUser 实体、JwtUtil (生成/解析 token)</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /auth}</p>
 * <p>权限要求: 公开端点 (登录前可调用)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.framework.security.JwtUtil
 * @see com.prcp.business.auth.entity.SysUser
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserMapper sysUserMapper;
    private final JwtUtil jwtUtil;

    /**
     * <p>用户登录 (验证用户名密码, 返回 JWT token)</p>
     *
     * <pre>
     * POST /auth/login
     * Body (JSON): {username: String, password: String}
     *       或 application/x-www-form-urlencoded
     *
     * Response: R.ok({access_token, token_type, user_id, username, display_name, role})
     *   access_token - JWT token
     *   token_type   - "bearer"
     *   user_id      - 用户 ID
     *   role         - 角色编码 (ADMIN/USER/...)
     * </pre>
     *
     * @param body 请求体 (可为 null, null 时尝试 form-data)
     * @param req  HTTP 请求对象 (备用)
     * @return R.ok({access_token, token_type, user_id, username, display_name, role})
     * @throws com.prcp.common.exception.BizException 用户名/密码为空、用户不存在、密码错误、用户停用
     */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody(required = false) Map<String, String> body,
                                       HttpServletRequest req) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");

        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            throw BizException.badRequest("用户名和密码不能为空");
        }

        QueryWrapper<SysUser> qw = new QueryWrapper<>();
        qw.eq("username", username).eq("is_deleted", 0);
        SysUser user = sysUserMapper.selectOne(qw);
        if (user == null) throw BizException.unauthorized("用户不存在");

        // 简化版：SHA256 哈希比对（与 Python 版一致）
        String inputHash = sha256(password);
        if (!inputHash.equalsIgnoreCase(user.getPasswordHash())) {
            throw BizException.unauthorized("密码错误");
        }
        // sys_user.status 是 tinyint: 1=ACTIVE / 0=DISABLED
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw BizException.forbidden("用户已停用");
        }

        // 生成 token
        String token = jwtUtil.createToken(user.getId(), user.getUsername());

        Map<String, Object> resp = new HashMap<>();
        resp.put("access_token", token);
        resp.put("token_type", "bearer");
        resp.put("user_id", user.getId());
        resp.put("username", user.getUsername());
        resp.put("display_name", user.getDisplayName());
        resp.put("role", user.getRole());

        log.info("[login] user={} ok", username);
        return R.ok(resp);
    }

    /**
     * <p>健康检查 (返回服务运行状态、版本号、时间戳)</p>
     *
     * <pre>
     * POST /auth/health
     * Response: R.ok({status, module, version, timestamp})
     *   status    - "UP"
     *   module    - "prcp-java"
     *   version   - "1.0.0"
     *   timestamp - 毫秒级时间戳
     * </pre>
     *
     * @return R.ok({status, module, version, timestamp})
     */
    @PostMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> r = new HashMap<>();
        r.put("status", "UP");
        r.put("module", "prcp-java");
        r.put("version", "1.0.0");
        r.put("timestamp", System.currentTimeMillis());
        return R.ok(r);
    }

    /**
     * SHA-256 哈希（与 Python hashlib.sha256 一致）
     */
    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BizException("SHA256 计算失败");
        }
    }
}
