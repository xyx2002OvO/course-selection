import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';
import { env, uuid } from './helpers.js';

const term = 202601;
const timeToTerminal = new Trend('time_to_terminal', true);

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      vus: 8,
      duration: '15s',
      tags: { phase: 'warmup' },
    },
    steady: {
      executor: 'constant-vus',
      vus: 20,
      duration: '45s',
      startTime: '15s',
      tags: { phase: 'steady' },
    },
  },
  thresholds: {
    time_to_terminal: ['p(95)<8000', 'p(99)<15000'],
    checks: ['rate>0.95'],
  },
};

export default function () {
  const student = env.studentFrom + (exec.vu.idInTest - 1);
  const id = uuid();
  const headers = {
    'X-Student-Id': String(student),
    'Idempotency-Key': id,
    'Content-Type': 'application/json',
  };
  const started = Date.now();
  const accepted = http.post(
    `${env.base}/api/terms/${term}/selections`,
    JSON.stringify({ courseId: env.courseOpen }),
    { headers, tags: { name: 'submit' } },
  );
  if (accepted.status !== 202 && accepted.status !== 200) {
    check(accepted, { 'submit ok': () => false });
    sleep(0.2);
    return;
  }
  let body = accepted.json();
  if (body.state === 'SUCCESS' || body.state === 'REJECTED' || body.state === 'CANCELLED') {
    timeToTerminal.add(Date.now() - started);
    check(accepted, { 'terminal': () => true });
    return;
  }
  let delay = 0.25;
  for (let i = 0; i < 20; i += 1) {
    sleep(delay);
    const status = http.get(`${env.base}/api/terms/${term}/selections/${id}`, {
      headers,
      tags: { name: 'status' },
    });
    if (status.status === 200) {
      body = status.json();
      if (body.state === 'SUCCESS' || body.state === 'REJECTED' || body.state === 'CANCELLED') {
        timeToTerminal.add(Date.now() - started);
        check(status, { 'reached terminal': () => body.state === 'SUCCESS' });
        return;
      }
    }
    delay = Math.min(2, delay * 1.5);
  }
  check(null, { 'reached terminal': () => false });
}
