# 压测方案（公平实验）

日期基线：2026-09-09。本文件是压测实验的约定，不是容量承诺。

## 为什么要钉死资源

本机 Docker 默认会把 Java 看到的 CPU 数当成宿主机核数，堆也可能随 cgroup 膨胀。两次实验如果一个在空闲电脑上跑满 16 核、另一个和别的容器抢内存，结果不可比。

因此：**被测 Java 实例、基础设施、压测发生器都使用固定 cgroup 配额**；JVM 的 `ActiveProcessorCount` 与 CPU 配额取同一整数。

## 固定预算

| 角色 | CPU 配额 | 内存配额 | 进程内限制 | 目的 |
| --- | --- | --- | --- | --- |
| API | 2.0 | 1Gi | `-Xms512m -Xmx512m`，元空间 128m，`ActiveProcessorCount=2` | 被测入口（Gateway） |
| Admission | 2.0 | 1Gi | 与 API 完全相同 | Dubbo 受理：Lua + `accept()` 写库；**不**消费 Kafka |
| Worker | 2.0 | 1Gi | 与 API 完全相同 | Outbox 发布 + `confirm()` 落库；**不**接 Dubbo |
| MySQL | 4.0 | 2Gi | `innodb-buffer-pool-size=512M`，`max-connections=80`，`innodb-flush-log-at-trx-commit=2` | 数据面；`flush=2` 是本机吞吐档，不是生产双 1 |
| Redis | 1.0 | 256Mi | `maxmemory 128mb`，`noeviction` | 预占与限流 |
| Kafka | 1.0 | 768Mi | `KAFKA_HEAP_OPTS=-Xms256m -Xmx256m` | 异步管道 |
| k6 | 4.0 | 2Gi | 容器内访问 `http://api:18080` | 发生器与被测隔离；breaking 需要更多 VU，配额大于被测 Java |

合计约 **14 CPU / 6.5Gi**（Java 仍是受理+确认各 2 核；MySQL overlay 为 4 核 / 2Gi / `flush=2`）。请保证 Docker Desktop 的 Linux VM **不少于 16 CPU、10Gi**，否则配额会被宿主机再截一次，实验不公平。

本机实测工作点（不是生产口径）：落库跟上大约 **400**；500 能进队；把 confirm 收到 6、落库封在 ~240 时受理能到 **800**。不要把 800 写成完整选课。

1a 实验问的是：500 hold 下落库能否从约 47 回到 100+。多出来的 2 核是确认侧独占，不是给 Kafka/MySQL 加配额。解读时不要把「拆进程」和「给确认加核」完全拆开；要对照的是同压力下 pending 是否还被受理写库挤瘦。

不要只改 `cpus` 或只改 `-Xmx`。必须同时改：

1. `compose.yaml` 里各 Java 服务的 `cpus` / `mem_limit`
2. `JAVA_TOOL_OPTIONS` 里的 `-XX:ActiveProcessorCount` 和 `-Xms/-Xmx`（堆固定 512m，容器 1Gi）

`AlwaysPreTouch` 会在启动时摸完堆，避免第一波请求把缺页算进延迟。

## 压测专用数据（profile `loadtest`）

演示账号 `1001-1005` 和 2 人名额课程不适合吞吐实验。loadtest 使用：

| 项 | 值 |
| --- | --- |
| 学生 | `20001-24000`（4000 人，每人独立学期行） |
| 课程 201 | 100000 名额，测吞吐，避免过早售罄 |
| 课程 202 | 50 名额，测并发超卖边界 |
| 提交限流 | 每学生 100/s，**全局 150/s**（本机 2 核 MySQL 运行点：受理不崩、落库能跟上、终态平均等待按 3s 设计） |
| 查询限流 | 每学生 200/s，全局 100000/s |

容量探针曾把全局提交放到 100000，用来找 500 受理上限；那不是这个配额的运行点。演示默认仍是每学生 5/s、全局 200/s。

Tomcat 最大线程 64、Hikari 16：与 2 核匹配，避免默认 200 工作线程在 2 核上过度切换。

## 三条实验

都先 `down -v` 再启动，保证库存和 Flyway 目录可复现。发生器走 Docker 网络，不走 `localhost`。

1. **submit**：恒定到达率，只打受理接口。预热 15s @ 10 rps，稳态 45s @ 40 rps。每笔独立学生 + 新幂等键，课程 201。看受理延迟和失败率，不把 Worker 确认时间算进去。
2. **e2e**：20 个 VU 提交后轮询终态。看 `time_to_terminal` 的 p95/p99。学生占用会回收，VU 数必须小于学生数。
3. **oversell**：40 VU、200 次迭代打课程 202（50 名额）。HTTP 只允许 202/200/409。结束后应满足 `enrollment <= 50` 且 `course.remaining >= 0`。
4. **breaking**：同一门课 201（50 万名额），学生池 `20001-80000`。从 200 rps 起按 1.35 倍加码，**没有业务上限**；k6 只是必须给一条有限 stage 列表。旁边有进程盯 API/Worker/MySQL，谁退出、OOM 或不健康就杀掉发生器。k6 连续连不上或连续 5xx 也会自己停。全局提交限流在 overlay 里放到 100000，避免 429 先挡住。报告里的 **最高可稳住 QPS** 是失败率仍 < 2% 的最高台阶。这条默认不跟 `all` 一起跑。
5. **ladder**：模拟学生分散选课的受理阶梯。课程 `211-230`（每门 10 万名额），学生 `20001-80000` 按迭代轮转、每人新幂等键。Sentinel 热点仍是每课 50 QPS，20 门课理论上限 1000 QPS，500 全局时每课约 25。台阶：预热 20s@50，之后 100/200/300/400 各 8s 爬升 + 20s hold，最后 10s 爬到 500 并 hold 30s。只打受理接口。**稳住**定义：该 hold 受理成功率 ≥ 98%、实现受理 QPS ≥ 目标的 95%、无连接失败、5xx < 2%。429 记为限流不是崩溃。本机 Docker 2 核配额下 500 是实验目标，不是容量承诺。

```powershell
.\loadtest\run.cmd -Scenario ladder
```

## 怎么跑

```powershell
cd outputs\course-selection
# 本机默认 Restricted 时不要改系统策略，用 Bypass 或 run.cmd
powershell -NoProfile -ExecutionPolicy Bypass -File .\loadtest\run.ps1 -Rebuild
.\loadtest\run.cmd -Rebuild
.\loadtest\run.cmd -Scenario submit
.\loadtest\run.cmd -Scenario e2e
.\loadtest\run.cmd -Scenario oversell
.\loadtest\run.cmd -Scenario breaking   # 加压到实例挂；脚本会一直加码直到挂或连不上
```

跑完看 `docker stats`：API/Worker 的内存上限应停在 1Gi，不应接近宿主机全部内存。

超卖核对：

```powershell
docker compose exec -T mysql mysql -uselection -pselection-dev selection -e "SELECT id,capacity,remaining FROM course WHERE id IN (201,202); SELECT course_id,COUNT(*) n FROM enrollment GROUP BY course_id;"
```

## 读结果时不要做的事

- 不要把 429 当系统崩溃；若在 loadtest 限流内大量 429，才说明入口饱和。
- 不要用演示 5 个学生打高并发：会几乎全是 `STUDENT_BUSY`。
- 不要在 Worker 还在确认时，用同一学生测 submit 吞吐。
- 不要把 k6 和 API 放在同一容器配额里。
- 单机 Docker Desktop 结果不能外推到生产多副本。

## 公平性检查清单

- [ ] Docker VM CPU/内存大于上表合计
- [ ] `docker inspect` 中 API/Worker 的 `NanoCpus=2000000000`、`Memory=1073741824`
- [ ] 日志里 `JAVA_TOOL_OPTIONS` 含 `ActiveProcessorCount=2` 和 `Xmx512m`
- [ ] `SPRING_PROFILES_ACTIVE=loadtest`，课程 201/202 已初始化
- [ ] 每次正式实验 `down -v`
- [ ] 预热阶段不计入对外结论
- [ ] 发生器在 compose 网络内访问 `api:18080`
