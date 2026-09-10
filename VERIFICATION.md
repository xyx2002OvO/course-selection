# 本次验证结果

日期：2026-09-09。

## 已完成

- 启动本机 Docker Desktop（此前 daemon 未运行，无法连接 `dockerDesktopLinuxEngine`）。
- 拉取集成测试镜像：`mysql:8.4`、`redis:7.4-alpine`、`apache/kafka:4.0.2`。
- `JAVA_HOME` 使用 JDK 21，默认 javac 编译成功。
- 13 个单元测试通过，0 失败、0 错误、0 跳过。
- 11 个集成测试通过：`SelectionFlowIT`（Testcontainers MySQL / Redis / Kafka），覆盖重复预占、并发冲突、防超卖、孤儿预占取消、重复结果、旧占用防误删、结果修复和 Kafka 往返。
- `mvn verify`：BUILD SUCCESS。Spring Boot 可执行包：`target/course-selection-0.1.0.jar`。

## 代码修复

- 集成测试 JDBC URL：Testcontainers 的 `getJdbcUrl()` 没有查询串时，不能直接拼接 `&...`。否则库名会变成 `test&connectionTimeZone=...`，Flyway 报标识符过长。已按是否已有 `?` 选择分隔符。

## 本机注意

- 若本机 `JAVA_HOME` 不是 JDK 21，跑 Maven 前请改到 21。
- 项目路径含非 ASCII 时，建议给 Testcontainers 使用纯 ASCII 的 `PATH` / `TMP`，Windows 上可设 `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`。
