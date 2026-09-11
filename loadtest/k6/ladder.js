import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { env, uuid, studentForIteration, courseForIteration } from './helpers.js';

const term = 202601;
const acceptOk = new Counter('accept_ok');
const http429 = new Counter('http_429');
const http409 = new Counter('http_409');
const http5xx = new Counter('http_5xx');
const httpDown = new Counter('http_down');
const other = new Counter('http_other');
const acceptDuration = new Trend('accept_duration', true);

// Staircase for dispersed admission. Warmup and ramps are not scored.
// Each hold is scored on 202/200 rate and realized accept QPS.
const pieces = [
  { dur: 20, rate: 50, name: 'warmup', score: false },
  { dur: 8, rate: 100, name: 'ramp-100', score: false },
  { dur: 20, rate: 100, name: 'hold-100', score: true },
  { dur: 8, rate: 200, name: 'ramp-200', score: false },
  { dur: 20, rate: 200, name: 'hold-200', score: true },
  { dur: 8, rate: 300, name: 'ramp-300', score: false },
  { dur: 20, rate: 300, name: 'hold-300', score: true },
  { dur: 8, rate: 400, name: 'ramp-400', score: false },
  { dur: 20, rate: 400, name: 'hold-400', score: true },
  { dur: 10, rate: 500, name: 'ramp-500', score: false },
  { dur: 30, rate: 500, name: 'hold-500', score: true },
  { dur: 8, rate: 600, name: 'ramp-600', score: false },
  { dur: 20, rate: 600, name: 'hold-600', score: true },
  { dur: 8, rate: 700, name: 'ramp-700', score: false },
  { dur: 20, rate: 700, name: 'hold-700', score: true },
  { dur: 8, rate: 800, name: 'ramp-800', score: false },
  { dur: 20, rate: 800, name: 'hold-800', score: true },
  { dur: 8, rate: 900, name: 'ramp-900', score: false },
  { dur: 20, rate: 900, name: 'hold-900', score: true },
  { dur: 8, rate: 1000, name: 'ramp-1000', score: false },
  { dur: 20, rate: 1000, name: 'hold-1000', score: true },
];

export const options = {
  discardResponseBodies: true,
  scenarios: {
    ladder: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 400,
      maxVUs: 1500,
      gracefulStop: '5s',
      stages: pieces.map((p) => ({ duration: `${p.dur}s`, target: p.rate })),
    },
  },
};

function stageAt(elapsedSec) {
  let t = 0;
  for (let i = 0; i < pieces.length; i += 1) {
    t += pieces[i].dur;
    if (elapsedSec <= t) return pieces[i];
  }
  return pieces[pieces.length - 1];
}

export function setup() {
  return { start: Date.now(), pieces };
}

export default function (data) {
  const elapsed = (Date.now() - data.start) / 1000;
  const stage = stageAt(elapsed);
  const tags = { stage: stage.name, target_rps: String(stage.rate) };
  const iteration = exec.scenario.iterationInTest;
  const student = studentForIteration(env.studentFrom, env.studentTo, iteration);
  const courseId = courseForIteration(env.courseFrom, env.courseTo, iteration);
  const headers = {
    'X-Student-Id': String(student),
    'Idempotency-Key': uuid(),
    'Content-Type': 'application/json',
  };
  const res = http.post(
    `${env.base}/api/terms/${term}/selections`,
    JSON.stringify({ courseId }),
    { headers, tags: { name: 'submit', ...tags }, timeout: '2s' },
  );

  if (res.status === 202 || res.status === 200) {
    acceptOk.add(1, tags);
    acceptDuration.add(res.timings.duration, tags);
    check(res, { accepted: () => true });
    return;
  }
  if (res.status === 0) {
    httpDown.add(1, tags);
    check(res, { accepted: () => false });
    return;
  }
  if (res.status >= 500) {
    http5xx.add(1, tags);
    check(res, { accepted: () => false });
    return;
  }
  if (res.status === 429) {
    http429.add(1, tags);
    check(res, { accepted: () => false });
    return;
  }
  if (res.status === 409) {
    http409.add(1, tags);
    check(res, { accepted: () => false });
    return;
  }
  other.add(1, tags);
  check(res, { accepted: () => false });
}

function metricCount(metric) {
  if (!metric || !metric.values) return 0;
  return Number(metric.values.count || 0);
}

function taggedCount(data, prefix, stageName) {
  let n = 0;
  for (const [name, metric] of Object.entries(data.metrics || {})) {
    if (name !== prefix && name.indexOf(`${prefix}{`) !== 0) continue;
    if (name.indexOf(`stage:${stageName}`) >= 0 || name.indexOf(`stage":"${stageName}`) >= 0) {
      n += metricCount(metric);
    }
  }
  return n;
}

function taggedDuration(data, stageName) {
  for (const [name, metric] of Object.entries(data.metrics || {})) {
    if (name.indexOf('accept_duration') !== 0) continue;
    if (name.indexOf(`stage:${stageName}`) < 0 && name.indexOf(`stage":"${stageName}`) < 0) continue;
    const v = metric.values || {};
    return {
      p95: v['p(95)'] != null ? Number(v['p(95)']) : null,
      p99: v['p(99)'] != null ? Number(v['p(99)']) : null,
      avg: v.avg != null ? Number(v.avg) : null,
    };
  }
  return { p95: null, p99: null, avg: null };
}

export function handleSummary(data) {
  const holds = pieces.filter((p) => p.score);
  const stages = holds.map((p) => {
    const accepted = taggedCount(data, 'accept_ok', p.name);
    const limited = taggedCount(data, 'http_429', p.name);
    const conflict = taggedCount(data, 'http_409', p.name);
    const server = taggedCount(data, 'http_5xx', p.name);
    const down = taggedCount(data, 'http_down', p.name);
    const rest = taggedCount(data, 'http_other', p.name);
    const total = accepted + limited + conflict + server + down + rest;
    const acceptRate = total === 0 ? null : accepted / total;
    const realized = p.dur > 0 ? accepted / p.dur : 0;
    const latency = taggedDuration(data, p.name);
    const held = total > 0
      && acceptRate >= 0.98
      && realized >= p.rate * 0.95
      && down === 0
      && server / Math.max(total, 1) < 0.02;
    return {
      stage: p.name,
      target_rps: p.rate,
      hold_seconds: p.dur,
      total,
      accepted,
      http_429: limited,
      http_409: conflict,
      http_5xx: server,
      http_down: down,
      other: rest,
      accept_rate: acceptRate,
      realized_accept_qps: Number(realized.toFixed(1)),
      p95_ms: latency.p95 == null ? null : Number(latency.p95.toFixed(1)),
      p99_ms: latency.p99 == null ? null : Number(latency.p99.toFixed(1)),
      held,
    };
  });
  let maxHeld = 0;
  for (const row of stages) {
    if (row.held) maxHeld = row.target_rps;
  }
  const report = {
    scenario: 'ladder-dispersed',
    course_from: env.courseFrom,
    course_to: env.courseTo,
    student_from: env.studentFrom,
    student_to: env.studentTo,
    max_held_qps: maxHeld,
    dropped_iterations: data.metrics.dropped_iterations ? metricCount(data.metrics.dropped_iterations) : 0,
    stages,
  };
  return {
    '/out/ladder-summary.json': JSON.stringify(report, null, 2),
    stdout: textReport(report),
  };
}

function pct(v) {
  if (v == null) return 'n/a';
  return `${(v * 100).toFixed(2)}%`;
}

function textReport(report) {
  const lines = [
    '',
    '=== 阶梯分散选课受理 ===',
    `课程 ${report.course_from}-${report.course_to}，学生 ${report.student_from}-${report.student_to}`,
    `最高稳住受理 QPS: ${report.max_held_qps}（k6 stage 标签若为空，以 database.csv 窗口为准）`,
    `dropped_iterations=${report.dropped_iterations}`,
    '台阶 (仅 hold；受理=202/200；429/409 单独计，不算系统崩溃):',
  ];
  for (const row of report.stages) {
    lines.push(
      `  ${row.stage} target=${row.target_rps} realized=${row.realized_accept_qps} `
      + `accept=${pct(row.accept_rate)} p95=${row.p95_ms ?? 'n/a'}ms `
      + `429=${row.http_429} 409=${row.http_409} 5xx=${row.http_5xx} down=${row.http_down} `
      + `held=${row.held ? 'yes' : 'no'}`,
    );
  }
  lines.push('');
  return lines.join('\n');
}
