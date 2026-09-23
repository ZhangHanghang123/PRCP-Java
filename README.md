# PRCP Java 版 — SpringBoot 多模块工程

> 项目代号: prcp-java
> 启动日期: 2026-09-23
> 技术栈: SpringBoot 2.7.18 + MyBatis-Plus 3.5.5 + MySQL 8 + JDK 17

## 项目结构

```
prcp-java/                          # 父工程（聚合）
├── pom.xml                         # 父 POM（统一版本管理）
├── prcp-common/                    # 通用模块（统一响应、异常、工具类）
│   └── src/main/java/com/prcp/common/
│       ├── result/R.java
│       ├── exception/BizException.java
│       ├── exception/GlobalExceptionHandler.java
│       ├── page/PageQuery.java
│       └── page/PageResult.java
├── prcp-framework/                 # 框架模块（JWT、配置、安全）
│   └── src/main/java/com/prcp/framework/
│       ├── security/JwtUtil.java
│       ├── security/JwtAuthenticationFilter.java
│       └── config/
│           ├── MybatisPlusConfig.java
│           ├── CorsConfig.java
│           └── WebConfig.java
├── prcp-business/                  # 业务模块（账户册、认证）
│   └── src/main/java/com/prcp/business/
│       ├── auth/
│       │   ├── entity/SysUser.java
│       │   ├── mapper/SysUserMapper.java
│       │   └── controller/AuthController.java
│       └── coa/
│           ├── entity/CoaScheme.java
│           ├── entity/CoaNode.java
│           ├── mapper/CoaSchemeMapper.java
│           ├── mapper/CoaNodeMapper.java
│           ├── service/CoaSchemeService.java
│           ├── service/CoaNodeService.java
│           └── controller/CoaController.java
└── prcp-app/                       # 启动模块
    ├── src/main/java/com/prcp/PrcpApplication.java
    └── src/main/resources/
        ├── application.yml
        └── logback-spring.xml
```

## 已实现功能（PoC v1.0）

| 模块 | 接口 | 状态 |
|------|------|------|
| 认证 | `POST /prcp-java/api/auth/login` | ✅ |
| 健康检查 | `POST /prcp-java/api/auth/health` | ✅ |
| 账户册方案 | `GET/POST/PUT/DELETE /coa/scheme` | ✅ |
| 账户册节点 | `GET /coa/nodes?scheme_id=` | ✅ |
| 账户册节点树 | `GET /coa/nodes/tree?scheme_id=` | ✅ |
| 节点 CRUD | `POST/PUT/DELETE /coa/node` | ✅ |

## 环境要求

| 工具 | 版本 |
|------|------|
| JDK | 17 (LTS) |
| Maven | 3.8+ |
| MySQL | 8.0+ |
| Redis | 6.0+（可选） |

## 本地构建

```bash
# 1. 进入项目目录
cd C:\银行经营\prcp-java

# 2. 编译所有模块
mvn clean compile

# 3. 打包可执行 jar（仅 prcp-app）
mvn clean package -DskipTests

# 产物位置：prcp-app/target/prcp-app-1.0.0-SNAPSHOT.jar
```

## 本地运行

```bash
# 方式 1：Maven 直接启动
cd prcp-app
mvn spring-boot:run

# 方式 2：java -jar 启动
java -jar prcp-app/target/prcp-app-1.0.0-SNAPSHOT.jar

# 启动后访问
# http://localhost:8008/prcp-java/api/auth/health
# http://localhost:8008/prcp-java/api/coa/schemes
# Swagger UI: http://localhost:8008/prcp-java/api/swagger-ui.html
```

## 数据库配置

**复用 Python 版的 `prcp_db` 数据库**（DDL 不变）。

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/prcp_db
    username: prcp
    password: Prcp@2026
```

**注意**：Java 版和 Python 版共享同一数据库，**两端不要同时写入相同数据**。生产部署建议：
- 双版本并行期间，Java 版只读 / 灰度部分写入
- 切流期间锁 Python 版 5 分钟，让 Java 版接管

## API 路径前缀

| 路径 | 用途 |
|------|------|
| `/prcp-java/api/*` | **Java 版所有接口**（8008 端口） |
| `/prcp/api/*` | Python 版接口（8006 端口，**保留不动**） |

## 与 Python 版兼容

### JWT Token 兼容

- 签名密钥：`prcp-secret-key-2026`（与 Python 版完全一致）
- 算法：HS256
- Payload：`{sub: username, user_id: Long}`
- 有效期：24 小时

### 数据库兼容

**所有 30+ 张表 DDL 完全不动**，Java 版用 MyBatis-Plus 直接读写。

### 响应格式对齐

Java 版 `R<T>` 与 Python 版 `{"code": 0, "msg": "OK", "data": ...}` 完全对齐。

## 服务部署（生产环境）

```bash
# 1. 打包
mvn clean package -DskipTests

# 2. 上传 jar 到服务器
scp prcp-app/target/prcp-app-1.0.0-SNAPSHOT.jar almd@43.143.253.186:/home/almd/prcp-java/

# 3. 服务器 systemd 服务
sudo tee /etc/systemd/system/prcp-java.service << 'EOF'
[Unit]
Description=PRCP Java Backend
After=network.target mysql.service

[Service]
Type=simple
User=almd
WorkingDirectory=/home/almd/prcp-java
ExecStart=/usr/bin/java -Xms1g -Xmx2g -jar /home/almd/prcp-java/prcp-app-1.0.0-SNAPSHOT.jar --spring.profiles.active=prod
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# 4. 启动
sudo systemctl daemon-reload
sudo systemctl enable prcp-java
sudo systemctl start prcp-java

# 5. nginx 配置（新增 location）
location /prcp-java/api/ {
    proxy_pass http://127.0.0.1:8008/prcp-java/api/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
location /prcp-java/ {
    alias /var/www/prcp-java/;
    try_files $uri $uri/ /prcp-java/index.html;
}
```

## 测试账号

| 账号 | 密码 | 权限 |
|------|------|------|
| admin | admin123 | 全部模块 |
| user | user123 | 只读 |

## 已知问题（PoC 阶段）

1. **数据库共享风险**：Java/Python 同时写入可能导致数据竞争，**生产部署前必须错开写入时间窗**
2. **JWT 刷新**：当前未实现 refresh token 机制，24 小时后需重新登录
3. **权限控制**：当前所有接口只需登录即可访问，未做 RBAC 细粒度权限
4. **审计日志**：未实现操作日志切面
5. **单元测试**：PoC 阶段未编写，待 Phase 2 补齐

## 下一步规划

| 阶段 | 内容 | 估时 |
|------|------|------|
| Phase 1（当前） | 基础 + 账户册 PoC | 12 人天 ✅ |
| Phase 2 | 报表 + 指标 + 字典 + 驾驶舱 | 18 人天 |
| Phase 3 | 数据维护 + 模型 + 反算 | 18 人天 |
| Phase 4 | 利率 + 模拟 + ESG 算法包 | 22 人天 |
| Phase 5 | E2E + 部署 + 切流 | 5 人天 |

## 维护者

- 主开发：PRCP WorkBuddy Agent
- 启动日期：2026-09-23
- 仓库：`github.com:ZhangHanghang123/PRCP-Java.git`（待创建）