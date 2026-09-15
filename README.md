# 最小异步选课系统

Java 21 / Spring Boot 3.5.16 / Spring Cloud Alibaba 2025.0.0.0 / Apache Dubbo 3.3.4 / Nacos 2.3.2 / Sentinel 1.8.8 / MySQL 8.4 / Redis 7.4 / Apache Kafka 4.0.2。

这是可本地部署的教学最小版。不做登录和鉴权；学生身份用请求头 `X-Student-Id`。`selection-gateway` 只路由；`selection-web` 和 `selection-domain` 注册到 Nacos。RPC 是 Dubbo。

本次实际构建、测试及环境限制见 [VERIFICATION.md](VERIFICATION.md)。

## 启动

需要 Docker Desktop 的 Linux 容器环境，首次构建需要联网。

```powershell
docker compose up --build -d
docker compose ps -a
docker compose logs -f api admission worker
```

`init` 执行 Flyway 建表和首次 Redis 库存初始化，正常结束为 `Exited (0)`；之后 Admission、Worker、Web、API 启动。基础设施使用持久化卷，端口仅绑定本机。

| 服务 | 地址 |
| --- | --- |
| Gateway | http://localhost:18080 ，转发到 Web |
| Web | http://localhost:18082 ，仅内部调试 |
| Admission 健康检查 | http://localhost:18083/actuator/health （Dubbo 受理） |
| Worker 健康检查 | http://localhost:18081/actuator/health （Kafka 确认） |
| MySQL | localhost:13306，库 `selection`，用户 `selection`，密码 `selection-dev` |
| Redis | localhost:16379 |
| Kafka | localhost:19092，容器内部 `kafka:9092` |
| Nacos | http://localhost:18848/nacos ，容器内部 `nacos:8848` |

停止但保留数据：`docker compose down`。重新运行不会把已有名额重置为初始容量。

演示学生 `1001` 到 `1005`，用请求头 `X-Student-Id` 标明身份，没有登录。状态查询仍校验申请是否属于该学生。

## 实际操作

```powershell
# 提交并指数退避轮询，默认最多等待 30 秒。
.\scripts\select.ps1 -Student 1001 -Course 101

# 恢复查看已有申请，不重新提交。
.\scripts\select.ps1 -Student 1001 -QueryOnly -RequestId '替换为已返回的UUID'
```

脚本每次发请求前打印 RequestID。网络超时后不会生成新 ID。轮询间隔为 0.5、1、2、4、5 秒，之后保持 5 秒，每次加入正负 20% 随机抖动；上一轮完成才安排下一轮。前端停止等待不取消选课。

也可以直接调用：

```http
POST /api/terms/202601/selections
X-Student-Id: 1001
Idempotency-Key: <客户端为本次业务申请生成的UUID，重试时不变>
Content-Type: application/json

{"courseId":101}
```

返回 `202` 和 `requestId/state/statusUrl`，随后请求 `GET /api/terms/202601/selections/{requestId}`。终态为 `SUCCESS`、`REJECTED`、`CANCELLED`；等待态为 `PROCESSING` 或 `CONFIRMING`。同一幂等键更换课程返回 409，学生处理中或无名额也返回 409；限流返回 429。基础设施错误返回 503，并要求使用原 ID 查询或重试，不能据此判断业务失败。

当前固定演示学期 `202601`，课程如下，时间区间为左闭右开：

| ID | 课程 | 名额 | 学分 | 时间 | 规则 |
| --- | --- | --- | --- | --- | --- |
| 101 | Java Foundations | 2 | 3 | 周一 [1,3) | 与 104 互斥 |
| 102 | Database Systems | 2 | 3 | 周一 [2,4) | 与 101 时间冲突 |
| 103 | Distributed Systems | 1 | 4 | 周三 [3,5) | 需要通过 9001，仅 1001、1002 满足 |
| 104 | Python Foundations | 2 | 3 | 周四 [1,3) | 与 101 互斥 |
| 105 | Systems Lab | 2 | 6 | 周五 [1,5) | 每位学生学分上限为 8 |

先让 1001 选 101，再选 102、104、105，可以分别观察时间冲突、互斥、学分超限。让 1003 选 103，可以观察先修课失败及名额回补。演示数据会保留，每笔操作会改变后续结果。

## 一条申请怎样走完

1. 请求先到 Gateway，再到 Web（`X-Student-Id`），再经 Dubbo 调领域服务。未知课程先被内存布隆过滤器挡住；热点课程走 Sentinel 参数限流。Lua 原子检查学生占用、课程名额、幂等，预扣库存；状态缓存 TTL 带随机抖动。
2. `SelectionService.accept` 在 MySQL 一个事务中写申请与 COMMAND Outbox，提交后返回处理中。完整规则不在 HTTP 入口执行。
3. Worker 以带租约的抢占方式取得 Outbox，事务外发送 Kafka；收到确认才标记 SENT。发送失败指数退避，进程崩溃后租约到期可接管。
4. Kafka 按 `学生:学期` 分区。消费者先锁申请行，再锁学生学期行，以 READ_COMMITTED 读取最新已选课程并校验。
5. 数据库条件扣减名额、写选课记录、记录申请终态和 RESULT Outbox，在同一事务提交。之后才 ACK Kafka。重复消息看到终态直接 ACK。
6. RESULT 经 Kafka 投递到 Redis 投影消费者。Lua 只回补一次失败预占，并且只删除仍属于原 requestId 的学生占用。成功不回补。重复或迟到的事件不能回退最终状态。
7. 查询接口只读 Redis。状态缓存缺失时优先读预占账本；两者都没有时放入有容量上限的修复队列，Worker 限批次回源数据库，API 不直接回源。
8. 对账任务扫描过期申请和 Redis 到期索引。对于没有数据库申请的预占，先创建数据库取消记录，再回补；同一主键与迟到受理互斥，取消与确认共用申请行锁。

## 目录

```text
selection-rpc/     Dubbo 接口与 DTO
selection-gateway/ Nacos 发现 Web，只路由不鉴权
selection-web/     HTTP、注册到 Nacos
selection-domain/
  api/             领域进程 Actuator 鉴权
  rpc/             Dubbo 实现：预占 + accept + 状态查询
  application/     受理、确认、取消的事务边界
  domain/          申请与课程模型、纯业务规则
  infrastructure/  MySQL、Redis Lua、Outbox 存储、初始化
  worker/          Kafka 收发、对账、状态修复、积压指标
scripts/select.ps1 提交与指数退避轮询客户端
```

## 超时、幂等与恢复

短期幂等 Hash TTL 为 30 秒，状态缓存保留 24 小时。预占账本是补偿凭据，不使用短 TTL；最小版保留到学期归档，未实现自动归档。数据库申请及其主键是长期幂等依据。这里的业务 RequestID 不等于每次 HTTP 调用重新生成的 traceId。

学生 Redis 占用和处理期限均为 120 秒。它是入口的租约，不是跨线程 Redisson RLock，不依赖跨服务 watchdog 交接。即使租约失效后出现两笔申请，数据库学生行锁仍串行执行最终校验。

对账每 2 秒处理最多 50 条，状态回源每个 Worker 每 2 秒最多 50 条，修复队列最多 1000 条。提交默认每学生 5 次/秒、全局 200 次/秒；查询默认每学生 10 次/秒、全局 1000 次/秒。这些是演示保护值，不是性能承诺。

Kafka 消费错误重试 4 次，再可靠投递至同名 `.DLT` 主题，确认 DLT 写入后才提交原偏移；DLT 发送失败不确认原消息。未完成申请最终由对账取消，结果投影丢失则由对账修复。DLT 不自动重新投递，需排查后使用原 requestId 重放；取消的申请不会复活。

Redis 预占失败不会发送消息；数据库提交结果不明时不会立即回补。Kafka 宕机时 Outbox 保留并重试；超过申请处理期限将取消，即使 Kafka 之后恢复，迟到命令也不会继续选课。

## 观测与测试

`/actuator/health` 无需认证，`/actuator/metrics` 需要 ops 账号。指标包括 Outbox 待发送数量、最老消息年龄、活跃申请数、发送失败、对账失败和消费结果。日志包含失败申请或 Outbox ID。

```powershell
# HTTP/E2E：按 tests/case.yaml 跑多场景（需 API 已启动，或加 -StartStack）。
.\tests\run-cases.ps1 -StartStack
# 单条：.\tests\run-cases.ps1 -Id FLOW-001
# 故障演练 CHAOS-001：.\tests\run-cases.ps1 -Slow -Id CHAOS-001

# 本机 Maven 需要 JAVA_HOME 指向 JDK 21。
mvn test

# 必须有可用 Docker，启动独立 MySQL / Redis / Kafka 容器做集成测试。
# Docker 不可用时会失败，不会把跳过测试伪装成通过。
mvn verify
```

如果受限 Windows 环境中的 JDK 编译器报 `ZipFileSystem / AccessDeniedException`，可用 `mvn -Peclipse-compiler test` 或 `mvn -Peclipse-compiler verify`，通过可选 Eclipse 编译器验证同一份 Java 21 源码；默认构建仍使用 javac。

单元测试覆盖冲突、互斥、学分、先修条件、取消后迟到受理和 ACK 顺序。集成测试验证 Lua 幂等、数据库并发互斥、防超卖、孤儿预占取消、重复回补防护、旧占用防误删、结果缓存修复及 Kafka 完整往返。

故障演练：`docker compose stop worker` 后提交申请，等待超过 120 秒再 `docker compose start worker`，申请应最终取消且名额恢复。停止 Kafka 可以观察 Outbox 积压及恢复；单节点开发环境中 Kafka 停机期间请求也可能按期限被取消，这是预期行为。

压测实验的资源配额和场景见 [loadtest/LOADTEST.md](loadtest/LOADTEST.md)，本机配额结果见 [loadtest/REPORT-2026-09-15.md](loadtest/REPORT-2026-09-15.md)。API、Admission、Worker 各 **2 CPU / 1Gi**，堆 **512m**，`ActiveProcessorCount=2`。Nacos 运行点是全局提交 1000 QPS、每课热点 80 QPS。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\loadtest\run.ps1 -Rebuild -Scenario submit
.\loadtest\run.cmd -Rebuild -Scenario submit
.\loadtest\run.cmd -Rebuild -Scenario breaking
```

## 最小版边界

- 单节点 MySQL、Redis、Kafka，仅用于本机演示；未提供高可用、TLS、Kafka ACL、真实身份系统和压测容量结论。
- Redis 启用 AOF everysec 和 noeviction。AOF 仍有故障丢失窗口；数据库条件扣减保证最终不超卖，但 Redis 不是无损事务日志。
- Redis 整体丢失时不自动从数据库剩余名额直接重置库存。已存在申请时初始化拒绝执行；结果投影发现预占账本缺失或终态冲突时暂停该学期新受理，需要停入口后人工核查与重建。此版实现请求级对账，未实现灾难恢复级全量重建。
- 一个学期的 Lua Key 采用相同 hash tag，兼容同槽要求，但一个学期集中在一个 Redis 分片。它不是无限水平扩展的入口方案。
- 每门课只建模一个每周时间区间，课程配置在选课期间视为固定；未实现退课、管理员代选、复杂周次和远程资格服务。新增修改入口必须复用学生学期事务锁。
- 本版不做登录鉴权。Nacos 单机，配置启动时拉取。Sentinel 无控制台。单用户串行是 Kafka 分区 + MySQL `FOR UPDATE`。

参考：[Spring Boot 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[Kafka 官方容器](https://kafka.apache.org/40/getting-started/docker/)、[Spring Kafka ACK 语义](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html)。
