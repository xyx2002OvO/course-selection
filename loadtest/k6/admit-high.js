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

const pieces = [
  { dur: 20, rate: 200, name: 'warmup', score: false },
  { dur: 15, rate: 1000, name: 'ramp-1000', score: false },
  { dur: 20, rate: 1000, name: 'hold-1000', score: true },
  { dur: 8, rate: 1200, name: 'ramp-1200', score: false },
  { dur: 20, rate: 1200, name: 'hold-1200', score: true },
  { dur: 8, rate: 1400, name: 'ramp-1400', score: false },
  { dur: 20, rate: 1400, name: 'hold-1400', score: true },
  { dur: 8, rate: 1600, name: 'ramp-1600', score: false },
  { dur: 20, rate: 1600, name: 'hold-1600', score: true },
  { dur: 8, rate: 1800, name: 'ramp-1800', score: false },
  { dur: 20, rate: 1800, name: 'hold-1800', score: true },
  { dur: 8, rate: 2000, name: 'ramp-2000', score: false },
  { dur: 20, rate: 2000, name: 'hold-2000', score: true },
];

const holdOk = {};
const holdDur = {};
const holdDown = {};
const hold429 = {};
const hold409 = {};
const hold5xx = {};
const holdOther = {};
for (const p of pieces) {
  if (!p.score) continue;
  const k = `hold_${p.rate}`;
  holdOk[k] = new Counter(`ok_${k}`);
  holdDur[k] = new Trend(`dur_${k}`, true);
  holdDown[k] = new Counter(`down_${k}`);
  hold429[k] = new Counter(`lim_${k}`);
  hold409[k] = new Counter(`conf_${k}`);
  hold5xx[k] = new Counter(`err_${k}`);
  holdOther[k] = new Counter(`oth_${k}`);
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    ladder: {
      executor: 'ramping-arrival-rate',
      startRate: 200,
      timeUnit: '1s',
      preAllocatedVUs: 400,
      maxVUs: 3000,
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

function recordHold(stage, kind, duration) {
  if (!stage.score) return;
  const k = `hold_${stage.rate}`;
  if (kind === 'ok') {
    holdOk[k].add(1);
    holdDur[k].add(duration);
    return;
  }
  if (kind === 'down') holdDown[k].add(1);
  else if (kind === '429') hold429[k].add(1);
  else if (kind === '409') hold409[k].add(1);
  else if (kind === '5xx') hold5xx[k].add(1);
  else holdOther[k].add(1);
}

export default function (data) {
  const elapsed = (Date.now() - data.start) / 1000;
  const stage = stageAt(elapsed);
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
    { headers, tags: { name: 'submit', stage: stage.name }, timeout: '5s' },
  );

  if (res.status === 202 || res.status === 200) {
    acceptOk.add(1);
    acceptDuration.add(res.timings.duration);
    recordHold(stage, 'ok', res.timings.duration);
    check(res, { accepted: () => true });
    return;
  }
  if (res.status === 0) {
    httpDown.add(1);
    recordHold(stage, 'down');
    check(res, { accepted: () => false });
    return;
  }
  if (res.status >= 500) {
    http5xx.add(1);
    recordHold(stage, '5xx');
    check(res, { accepted: () => false });
    return;
  }
  if (res.status === 429) {
    http429.add(1);
    recordHold(stage, '429');
    check(res, { accepted: () => false });
    return;
  }
  if (res.status === 409) {
    http409.add(1);
    recordHold(stage, '409');
    check(res, { accepted: () => false });
    return;
  }
  other.add(1);
  recordHold(stage, 'other');
  check(res, { accepted: () => false });
}

function metricCount(metric) {
  if (!metric || !metric.values) return 0;
  return Number(metric.values.count || 0);
}

function trendOf(metric) {
  if (!metric || !metric.values) return { p95: null, p99: null, avg: null };
  const v = metric.values;
  return {
    p95: v['p(95)'] != null ? Number(v['p(95)']) : null,
    p99: v['p(99)'] != null ? Number(v['p(99)']) : null,
    avg: v.avg != null ? Number(v.avg) : null,
  };
}

export function handleSummary(data) {
  const holds = pieces.filter((p) => p.score);
  const stages = holds.map((p) => {
    const k = `hold_${p.rate}`;
    const accepted = metricCount(data.metrics[`ok_${k}`]);
    const limited = metricCount(data.metrics[`lim_${k}`]);
    const conflict = metricCount(data.metrics[`conf_${k}`]);
    const server = metricCount(data.metrics[`err_${k}`]);
    const down = metricCount(data.metrics[`down_${k}`]);
    const rest = metricCount(data.metrics[`oth_${k}`]);
    const total = accepted + limited + conflict + server + down + rest;
    const acceptRate = total === 0 ? null : accepted / total;
    const realized = p.dur > 0 ? accepted / p.dur : 0;
    const latency = trendOf(data.metrics[`dur_${k}`]);
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
      avg_ms: latency.avg == null ? null : Number(latency.avg.toFixed(1)),
      held,
    };
  });
  let maxHeld = 0;
  for (const row of stages) {
    if (row.held) maxHeld = row.target_rps;
  }
  const report = {
    scenario: 'admit-high',
    course_from: env.courseFrom,
    course_to: env.courseTo,
    max_held_qps: maxHeld,
    dropped_iterations: data.metrics.dropped_iterations ? metricCount(data.metrics.dropped_iterations) : 0,
    accept_ok: metricCount(data.metrics.accept_ok),
    http_429: metricCount(data.metrics.http_429),
    http_409: metricCount(data.metrics.http_409),
    http_5xx: metricCount(data.metrics.http_5xx),
    http_down: metricCount(data.metrics.http_down),
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
    '=== 受理侧 1000-2000 ===',
    `课程 ${report.course_from}-${report.course_to}`,
    `最高稳住受理 QPS: ${report.max_held_qps}`,
    `dropped=${report.dropped_iterations} ok=${report.accept_ok} 429=${report.http_429} 409=${report.http_409} 5xx=${report.http_5xx} down=${report.http_down}`,
    '台阶:',
  ];
  for (const row of report.stages) {
    lines.push(
      `  ${row.stage} target=${row.target_rps} realized=${row.realized_accept_qps} `
      + `accept=${pct(row.accept_rate)} p95=${row.p95_ms ?? 'n/a'}ms avg=${row.avg_ms ?? 'n/a'}ms `
      + `429=${row.http_429} 409=${row.http_409} 5xx=${row.http_5xx} down=${row.http_down} `
      + `held=${row.held ? 'yes' : 'no'}`,
    );
  }
  lines.push('');
  return lines.join('\n');
}
