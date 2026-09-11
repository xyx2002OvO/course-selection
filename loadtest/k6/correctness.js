import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { env, uuid, studentForIteration, courseForIteration } from './helpers.js';

const term = 202601;
const acceptOk = new Counter('accept_ok');
const http409 = new Counter('http_409');
const http429 = new Counter('http_429');
const http5xx = new Counter('http_5xx');
const httpDown = new Counter('http_down');
const httpOther = new Counter('http_other');

export const options = {
  discardResponseBodies: true,
  scenarios: {
    scarce: {
      executor: 'shared-iterations',
      exec: 'hitScarce',
      vus: 300,
      iterations: 8000,
      maxDuration: '45s',
    },
    flood: {
      executor: 'constant-arrival-rate',
      exec: 'hitOpen',
      rate: 800,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 300,
      maxVUs: 800,
    },
  },
};

function submit(student, courseId, tags) {
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
  if (res.status === 202 || res.status === 200) acceptOk.add(1, tags);
  else if (res.status === 409) http409.add(1, tags);
  else if (res.status === 429) http429.add(1, tags);
  else if (res.status >= 500) http5xx.add(1, tags);
  else if (res.status === 0) httpDown.add(1, tags);
  else httpOther.add(1, tags);
  check(res, {
    'accepted or expected reject': (r) => r.status === 202 || r.status === 200 || r.status === 409 || r.status === 429,
  });
  return res;
}

export function hitScarce() {
  const student = env.studentFrom + exec.scenario.iterationInTest;
  submit(student, env.courseScarce, { traffic: 'scarce' });
}

export function hitOpen() {
  const iteration = exec.scenario.iterationInTest;
  const student = studentForIteration(30001, env.studentTo, iteration);
  const courseId = courseForIteration(env.courseFrom, env.courseTo, iteration);
  submit(student, courseId, { traffic: 'open' });
}

function metricCount(metric) {
  if (!metric || !metric.values) return 0;
  return Number(metric.values.count || 0);
}

export function handleSummary(data) {
  const report = {
    scenario: 'correctness-oversell-and-flood',
    course_scarce: env.courseScarce,
    course_from: env.courseFrom,
    course_to: env.courseTo,
    http_reqs: metricCount(data.metrics.http_reqs),
    accept_ok: metricCount(data.metrics.accept_ok),
    http_409: metricCount(data.metrics.http_409),
    http_429: metricCount(data.metrics.http_429),
    http_5xx: metricCount(data.metrics.http_5xx),
    http_down: metricCount(data.metrics.http_down),
    dropped_iterations: metricCount(data.metrics.dropped_iterations),
    checks: data.metrics.checks ? data.metrics.checks.values : null,
  };
  const lines = [
    '',
    '=== 正确性加压 ===',
    `http_reqs=${report.http_reqs} accept=${report.accept_ok} 409=${report.http_409} 429=${report.http_429} 5xx=${report.http_5xx} down=${report.http_down} dropped=${report.dropped_iterations}`,
    '超卖以压完后的 MySQL remaining/enrollment 为准，HTTP 409 只说明入口挡住了。',
    '',
  ];
  return {
    '/out/correctness-summary.json': JSON.stringify(report, null, 2),
    stdout: lines.join('\n'),
  };
}
