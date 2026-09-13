# 选课系统优化过程

只记录怎么走到当前方案。对外数字以 `REPORT-2026-09-10.md` 的 confirm=12 / hold-500 为准，不要把中间态写进报告。

生产 MySQL 也是 `innodb_flush_log_at_trx_commit=2`。本机与生产刷盘策略一致；本机 QPS 仍不能外推成集群容量。

## 链路（未变）

```text
HTTP → Gateway → Web → Dubbo
  → Redis Lua 预占（学生租约 + 库存 DECR）
  → accept() 写请求 + COMMAND outbox → 202
  → Kafka → confirm() 规则 / 扣 MySQL 库存 / 写选课 + RESULT
  → Redis 投影终态
```

Redis 管受理，MySQL 管权威库存。失败或超时回补 Redis。租约 120s。

## 过程

### 1. 同进程 + 2 核 MySQL + flush=1

API 与 Worker 抢同一批核和同一台小 MySQL。hold-500 受理约 492，落库约 **47**，pending 约 2.8 万。停压后排水能到约 230，说明确认侧不是废的，是被受理写库和 `flush=1` 每笔 fsync 按住。

### 2. 拆 Admission / Worker（1a）

各 2 核 / 1Gi。MySQL 仍 2 核 flush=1。hold-500 落库从 47 回到约 **151**。HTTP p95 从约 715ms 降到约 177ms。天花板仍在共享 InnoDB，没有把排水的 230 搬进 500 压力。

### 3. 合并规则查询（1b，已回滚）

把课程 / 已选 / 已修合成一条 UNION。hold-500 落库到约 193，但超时从 415 升到 **1528**，202 率掉到 96.7%。后台变快、入口超时，算负收益。回滚到 `13a8410`。

更早的 confirm 查询合并、盲目加消费并发，同样出现过「SQL 少了、端到端没变好」。有限配额下，新增执行和排队会抵消往返收益。

### 4. Catalog freeze

选课窗口目录冻结：L1 Bloom + L2 Redis，`confirm()` 不再为目录打 MySQL。本身没有把落库从 151 抬上去。它去掉的是确认路径上的目录读，不是 redo。

### 5. MySQL 加核，仍 flush=1

4 核 / 2Gi / buffer 512M，`flush=1`。hold-500 落库仍约 **125**。多出来的核吃不满，fsync 才是限制。HTTP p95 有改善，5xx 为 0。

### 6. 改为 flush=2（与生产一致）

同一 4 核配额。confirm=6 时 hold-500 落库约 **281**。限制从「每笔 COMMIT fsync」变成「Worker 并发和共享 redo」。

### 7. confirm=12

当前方案。hold-500 受理 **500**，落库 **453**，oldest 4s，全程 p95 **79ms**，停压 ACCEPTED 17。hold-400 落库 **369**、oldest 1s。正确性：课程 202 恰好 50 人、drift=0，开放课 drift=0。

加长阶梯继续往 600–1000 爬时，hold-500 窗口落库会掉到约 368。短 hold-500 的 453 才是方案口径。

## 没有写进方案的对照

- confirm=6 的 281：过程数字，不是当前对外口径。
- HTTP 同步对照（`151341`）：为了比 202 和请求内落库。异步 hold-500 落库被挤到 141–159，不能用来否定 453。
- 同步请求内落库在同 500 上可以受理=落库 501。那是另一条架构，说明「500 完整选课」在同步路径上做得到；异步卖的是收据和削峰。

## 结论

真正抬起落库的是：**拆受理/确认进程**、**flush=2**、**把 confirm 并发加到 12**。加核但不改刷盘、合并 SQL、目录缓存，都没有单独把 500 下落库打上去。

当前锁死：Admission / Worker 分进程，MySQL 4 核 `flush=2`，`CONFIRM_CONCURRENCY=12`，catalog freeze。报告只报这一版。
