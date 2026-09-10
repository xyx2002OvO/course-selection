import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { env, authHeader, uuid } from './helpers.js';

const term = 202601;
const accepted = new Counter('submit_accepted');
const conflict = new Counter('submit_conflict');

export const options = {
  scenarios: {
    burst: {
      executor: 'shared-iterations',
      vus: 40,
      iterations: 200,
      maxDuration: '30s',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
  },
};

export default function () {
  const student = env.studentFrom + exec.scenario.iterationInTest;
  const headers = {
    Authorization: authHeader(student, env.password),
    'Idempotency-Key': uuid(),
    'Content-Type': 'application/json',
  };
  const res = http.post(
    `${env.base}/api/terms/${term}/selections`,
    JSON.stringify({ courseId: env.courseScarce }),
    { headers, tags: { name: 'submit' } },
  );
  if (res.status === 202 || res.status === 200) accepted.add(1);
  else if (res.status === 409) conflict.add(1);
  check(res, {
    'accepted or sold out': (r) => r.status === 202 || r.status === 200 || r.status === 409,
  });
}
