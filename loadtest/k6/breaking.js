import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { env, authHeader, uuid, studentForIteration } from './helpers.js';

const term = 202601;
const acceptOk = new Counter('accept_ok');
const httpDown = new Counter('http_down');
const http5xx = new Counter('http_5xx');
const httpConflict = new Counter('http_conflict');
const http429 = new Counter('http_429');

// k6 stages must be finite. There is no business ceiling: keep multiplying until
// the instance dies, connections fail, or k6 itself cannot schedule more work.
const RAMP_SEC = 8;
const HOLD_SEC = 20;
const holds = buildHolds();
const pieces = buildPieces(holds);
const stages = pieces.map((p) => ({ duration: `${p[0]}s`, target: p[1] }));

function buildHolds() {
  // Finite only because k6 requires it. Keep climbing well past last run's 400
  // until the process dies, connections reset, or the generator is exhausted.
  const out = [];
  let rps = 100;
  while (rps <= 25000) {
    out.push(rps);
    rps = Math.max(rps + 100, Math.round(rps * 1.4));
  }
  return out;
}

function buildPieces(rates) {
  const out = [];
  for (let i = 0; i < rates.length; i += 1) {
    out.push([RAMP_SEC, rates[i]]);
    out.push([HOLD_SEC, rates[i]]);
  }
  return out;
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    breaking: {
      executor: 'ramping-arrival-rate',
      startRate: 20,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 2500,
      gracefulStop: '5s',
      stages,
    },
  },
};

function targetRps(elapsedSec) {
  let t = 0;
  for (let i = 0; i < pieces.length; i += 1) {
    t += pieces[i][0];
    if (elapsedSec <= t) return pieces[i][1];
  }
  return pieces[pieces.length - 1][1];
}

export function setup() {
  return { start: Date.now(), holds, pieces };
}

export default function (data) {
  const elapsed = (Date.now() - data.start) / 1000;
  const rps = targetRps(elapsed);
  const tags = { target_rps: String(rps) };
  const student = studentForIteration(env.studentFrom, env.studentTo, exec.scenario.iterationInTest);
  const headers = {
    Authorization: authHeader(student, env.password),
    'Idempotency-Key': uuid(),
    'Content-Type': 'application/json',
  };
  const res = http.post(
    `${env.base}/api/terms/${term}/selections`,
    JSON.stringify({ courseId: env.courseOpen }),
    { headers, tags: { name: 'submit', ...tags }, timeout: '3s' },
  );

  if (res.status === 202 || res.status === 200) {
    vuDownStreak = 0;
    vu5xxStreak = 0;
    acceptOk.add(1, tags);
    check(res, { accepted: () => true });
    return;
  }
  if (res.status === 0) {
    httpDown.add(1, tags);
    const err = String(res.error || '');
    const processGone = /connection refused|connection reset|connect:|no route|broken pipe|EOF/i.test(err)
      && !/request timeout/i.test(err);
    if (processGone && httpDownInVuStreak() >= 4) {
      exec.test.abort(`API 不再接受连接 (${err || 'status=0'})，当时目标 ${rps} rps`);
    }
    return;
  }
  if (res.status >= 500) {
    http5xx.add(1, tags);
    vu5xxStreak += 1;
    if (vu5xxStreak >= 6) {
      exec.test.abort(`连续 5xx，当时目标 ${rps} rps，多半是 DB/Redis 或 API 已撑不住`);
    }
    return;
  }
  vu5xxStreak = 0;
  vuDownStreak = 0;
  if (res.status === 429) http429.add(1, tags);
  else if (res.status === 409) httpConflict.add(1, tags);
  check(res, { accepted: () => false });
}

let vuDownStreak = 0;
let vu5xxStreak = 0;
function httpDownInVuStreak() {
  vuDownStreak += 1;
  return vuDownStreak;
}

function metricCount(metric) {
  if (!metric || !metric.values) return 0;
  return Number(metric.values.count || metric.values.rate || 0);
}

function taggedCount(data, prefix, rps) {
  const want = String(rps);
  let n = 0;
  for (const [name, metric] of Object.entries(data.metrics || {})) {
    if (name !== prefix && name.indexOf(prefix + '{') !== 0) continue;
    const hit = name.match(/target_rps[":=]+"?(\d+)/);
    if (hit && hit[1] === want) n += metricCount(metric);
  }
  return n;
}

export function handleSummary(data) {
  const stageStats = holds.map((rps) => {
    const accepted = taggedCount(data, 'accept_ok', rps);
    const down = taggedCount(data, 'http_down', rps);
    const server = taggedCount(data, 'http_5xx', rps);
    const total = accepted + down + server;
    return {
      target_rps: rps,
      accepted,
      down,
      http5xx: server,
      fail_rate: total === 0 ? null : Number(((down + server) / total).toFixed(4)),
    };
  });
  let maxSustainable = 0;
  for (const row of stageStats) {
    if (row.accepted > 0 && row.fail_rate !== null && row.fail_rate < 0.02) {
      maxSustainable = row.target_rps;
    }
  }
  const httpReqs = data.metrics.http_reqs && data.metrics.http_reqs.values;
  const dropped = data.metrics.dropped_iterations && data.metrics.dropped_iterations.values;
  const report = {
    max_sustainable_qps: maxSustainable,
    overall_http_rps: httpReqs ? httpReqs.rate : null,
    http_reqs: httpReqs ? httpReqs.count : null,
    dropped_iterations: dropped ? dropped.count : 0,
    vus_max: data.metrics.vus_max && data.metrics.vus_max.values ? data.metrics.vus_max.values.max : null,
    accept_ok: metricCount(data.metrics.accept_ok),
    http_down: metricCount(data.metrics.http_down),
    http_5xx: metricCount(data.metrics.http_5xx),
    http_429: metricCount(data.metrics.http_429),
    http_conflict: metricCount(data.metrics.http_conflict),
    metric_keys: Object.keys(data.metrics || {}).slice(0, 80),
    stages: stageStats,
  };
  return {
    '/out/breaking-summary.json': JSON.stringify(report, null, 2),
    stdout: textReport(report),
  };
}

function textReport(report) {
  const lines = [
    '',
    '=== 承压结果 ===',
    `最高可稳住的受理 QPS: ${report.max_sustainable_qps}`,
    `全程平均 HTTP QPS: ${report.overall_http_rps ? report.overall_http_rps.toFixed(1) : 'n/a'}`,
    `http_reqs=${report.http_reqs} down=${report.http_down} 5xx=${report.http_5xx} 429=${report.http_429} dropped=${report.dropped_iterations} vus_max=${report.vus_max}`,
    '各台阶 (失败=连不上或 5xx，不含 409/429):',
  ];
  for (const row of report.stages) {
    if (row.accepted === 0 && row.down === 0 && row.http5xx === 0) continue;
    const fail = row.fail_rate === null ? 'n/a' : `${(row.fail_rate * 100).toFixed(2)}%`;
    lines.push(`  ${row.target_rps} rps  accepted=${row.accepted} down=${row.down} 5xx=${row.http5xx} fail=${fail}`);
  }
  lines.push('');
  return lines.join('\n');
}
