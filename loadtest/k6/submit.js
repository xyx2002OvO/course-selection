import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { env, uuid, studentForIteration } from './helpers.js';

const term = 202601;

export const options = {
  discardResponseBodies: true,
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      rate: 10,
      timeUnit: '1s',
      duration: '15s',
      preAllocatedVUs: 20,
      maxVUs: 40,
      gracefulStop: '5s',
      tags: { phase: 'warmup' },
    },
    steady: {
      executor: 'constant-arrival-rate',
      rate: 40,
      timeUnit: '1s',
      duration: '45s',
      preAllocatedVUs: 50,
      maxVUs: 80,
      startTime: '15s',
      gracefulStop: '5s',
      tags: { phase: 'steady' },
    },
  },
  thresholds: {
    'http_req_failed{phase:steady,name:submit}': ['rate<0.02'],
    'http_req_duration{phase:steady,name:submit}': ['p(95)<800', 'p(99)<2000'],
    checks: ['rate>0.98'],
  },
};

export default function () {
  const student = studentForIteration(env.studentFrom, env.studentTo, exec.scenario.iterationInTest);
  const headers = {
    'X-Student-Id': String(student),
    'Idempotency-Key': uuid(),
    'Content-Type': 'application/json',
  };
  const res = http.post(
    `${env.base}/api/terms/${term}/selections`,
    JSON.stringify({ courseId: env.courseOpen }),
    { headers, tags: { name: 'submit' } },
  );
  check(res, {
    'accepted': (r) => r.status === 202 || r.status === 200,
  });
}
