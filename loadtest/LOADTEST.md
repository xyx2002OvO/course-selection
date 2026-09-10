# 压测方案（公平实验）

日期基线：2026-09-09。本文件是压测实验的约定，不是容量承诺。

## 为什么要钉死资源

本机 Docker 默认会把 Java 看到的 CPU 数当成宿主机核数，堆也可能随 cgroup 膨胀。两次实验如果一个在空闲电脑上跑满 16 核、另一个和别的容器抢内存，结果不可比。

因此：**被测 Java 实例、基础设施、压测发生器都使用固定 cgroup 配额**；JVM 的 `ActiveProcessorCount` 与 CPU 配额取同一整数。

## 固定预算

| 角色 | CPU 配额 | 内存配额 | 进程内限制 | 目的 |
| --- | --- | --- | --- | --- |
| API | 2.0 | 1Gi | `-Xms512m -Xmx512m`，元空间 128m，`ActiveProcessorCount=2` | 被测入口 |
| Worker | 2.0 | 1Gi | 与 API 完全相同 | 避免 API/Worker 因配额不对称造成假瓶颈 |
| MySQL | 2.0 | 1Gi | `innodb-buffer-pool-size=256M`，`max-connections=80` | 数据面，单独配额，不从 Java 偷核 |
| Redis | 1.0 | 256Mi | `maxmemory 128mb`，`noeviction` | 预占与限流 |
| Kafka | 1.0 | 768Mi | `KAFKA_HEAP_OPTS=-Xms256m -Xmx256m` | 异步管道 |
| k6 | 4.0 | 2Gi | 容器内访问 `http://api:18080` | 发生器与被测隔离；breaking 需要更多 VU，配额大于被测 Java |

合计约 **10 CPU / 4.5Gi**。请保证 Docker Desktop 的 Linux VM **不少于 12 CPU、8Gi**，否则配额会被宿主机再截一次，实验不公平。

不要只改 `cpus` 或只改 `-Xmx`。必须同时改：

1. `compose.yaml` 里 API/Worker 的 `cpus` / `mem_limit`
2. `JAVA_TOOL_OPTIONS` 里的 `-XX:ActiveProcessorCount` 和 `-Xms/-Xmx`（堆固定 512m，容器 1Gi）

`AlwaysPreTouch` 会在启动时摸完堆，避免第一波请求把缺页算进延迟。

## 压测专用数据（profile `loadtest`）

演示账号 `1001-1005` 和 2 人名额课程不适合吞吐实验。loadtest 使用：

| 项 | 值 |
| --- | --- |
| 学生 | `20001-24000`（4000 人，每人独立学期行） |
| 课程 201 | 100000 名额，测吞吐，避免过早售罄 |
| 课程 202 | 50 名额，测并发超卖边界 |
| 提交限流 | 每学生 100/s，全局 5000/s |
| 查询限流 | 每学生 200/s，全局 5000/s |

演示默认仍是每学生 5/s、全局 200/s。压测必须用 overlay，否则测到的是保护阀而不是系统。

Tomcat 最大线程 64、Hikari 16：与 2 核匹配，避免默认 200 工作线程在 2 核上过度切换。

## 三条实验

都先 `down -v` 再启动，保证库存和 Flyway 目录可复现。发生器走 Docker 网络，不走 `localhost`。

1. **submit**：恒定到达率，只打受理接口。预热 15s @ 10 rps，稳态 45s @ 40 rps。每笔独立学生 + 新幂等键，课程 201。看受理延迟和失败率，不把 Worker 确认时间算进去。
2. **e2e**：20 个 VU 提交后轮询终态。看 `time_to_terminal` 的 p95/p99。学生占用会回收，VU 数必须小于学生数。
3. **oversell**：40 VU、200 次迭代打课程 202（50 名额）。HTTP 只允许 202/200/409。结束后应满足 `enrollment <= 50` 且 `course.remaining >= 0`。
4. **breaking**：同一门课 201（50 万名额），学生池 `20001-80000`。从 200 rps 起按 1.35 倍加码，**没有业务上限**；k6 只是必须给一条有限 stage 列表。旁边有进程盯 API/Worker/MySQL，谁退出、OOM 或不健康就杀掉发生器。k6 连续连不上或连续 5xx 也会自己停。全局提交限流在 overlay 里放到 100000，避免 429 先挡住。报告里的 **最高可稳住 QPS** 是失败率仍 < 2% 的最高台阶。这条默认不跟 `all` 一起跑。

## 怎么跑

```powershell
cd outputs\course-selection
.\loadtest\run.ps1 -Rebuild          # 首次或改过 Java 代码
.\loadtest\run.ps1 -Scenario submit
.\loadtest\run.ps1 -Scenario e2e
.\loadtest\run.ps1 -Scenario oversell
.\loadtest\run.ps1 -Scenario breaking   # 加压到实例挂；脚本会一直加码直到挂或连不上
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
