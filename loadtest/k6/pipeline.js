import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { env, authHeader, uuid, studentForIteration, courseForIteration } from './helpers.js';

const term = 202601;
const outcomes = new Counter('admission_outcomes');

// Clean 400 rps admission, traffic spread across courses 211-222 (not the 201 hot row).
// Warmup and steady use disjoint student segments so terminal states are not
// polluted by duplicate submissions across scenarios.
export const options = {
  discardResponseBodies: true,
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '15s',
      preAllocatedVUs: 40,
      maxVUs: 120,
      gracefulStop: '5s',
      tags: { phase: 'warmup' },
    },
    steady: {
      executor: 'constant-arrival-rate',
      rate: 400,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 120,
      maxVUs: 600,
      startTime: '15s',
      gracefulStop: '5s',
      tags: { phase: 'steady' },
    },
  },
};

export function setup() {
  const started = Date.now();
  console.log(`LOAD_START_MS=${started}`);
  return { started };
}

export default function () {
  const iteration = exec.scenario.name === 'steady'
    ? exec.scenario.iterationInTest + 10000
    : exec.scenario.iterationInTest;
  const student = studentForIteration(env.studentFrom, env.studentTo, iteration);
  const headers = {
    Authorization: authHeader(student, env.password),
    'Idempotency-Key': uuid(),
    'Content-Type': 'application/json',
  };
  const courseId = courseForIteration(env.courseFrom, env.courseTo, iteration);
  const res = http.post(
    `${env.base}/api/terms/${term}/selections`,
    JSON.stringify({ courseId }),
    { headers, tags: { name: 'submit' }, timeout: '1s' },
  );
  outcomes.add(1, { status: String(res.status) });
  check(res, {
    accepted: (r) => r.status === 202 || r.status === 200,
  });
}
