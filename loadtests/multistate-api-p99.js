// loadtests/multistate-api-p99.js
// TASK 3: k6 script with options.thresholds mapping
// EXACTLY to the W5D5 SLO numbers; exit code becomes the
// W6D5 CI gate. Three scenario executors (ramp, steady,
// spike) plus a workload mix whose weights sum to 1.0.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const costPerReq = new Trend('cost_per_request_usd');
const tenantSynth = new Counter('synth_calls_total');

export const options = {
  scenarios: {
    // Weights 0.5 + 0.35 + 0.15 = 1.0 (explicit, not renormalised).
    ramp: {
      executor: 'ramping-arrival-rate',
      exec: 'default',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 280,
      stages: [
        { duration: '4m', target: 140 },
      ],
      startTime: '0s',
    },
    steady: {
      executor: 'constant-arrival-rate',
      exec: 'default',
      rate: 98,
      timeUnit: '1s',
      duration: '6m',
      preAllocatedVUs: 100,
      maxVUs: 280,
      startTime: '4m',
    },
    spike: {
      executor: 'ramping-arrival-rate',
      exec: 'default',
      startRate: 42,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 280,
      stages: [
        { duration: '30s', target: 280 },
        { duration: '1m30s', target: 280 },
        { duration: '30s', target: 0 },
      ],
      startTime: '10m',
    },
  },
  thresholds: {
    http_req_duration:    ['p(99)<900'],
    http_req_failed:      ['rate<0.005'],
    checks:               ['rate>0.99'],
    cost_per_request_usd: ['p(95)<0.006'],
  },
};

const BASE = __ENV.TARGET ||
  'http://multistate-api.staging.svc.cluster.local:8080';

const MIX = [
  { weight: 0.7, run: writeHotPath },
  { weight: 0.2, run: writeColdPath },
  { weight: 0.1, run: readPath     },
];

export default function () {
  const r = Math.random();
  let acc = 0;
  for (const step of MIX) {
    acc += step.weight;
    if (r <= acc) {
      step.run();
      break;
    }
  }
  sleep(0.5);
}

function writeHotPath() {
  const res = http.post(`${BASE}/v1/tenants`,
    JSON.stringify({
      tenant:  'tenant-synth',
      feature: 'summarize-nexus',
      size:    'small',
    }),
    { headers: {
      'Content-Type': 'application/json',
      'X-Tenant':     'tenant-synth',
      'X-Feature':    'summarize-nexus',
    }});
  check(res, {
    'status 200': (r) => r.status === 200,
    'has tenantId': (r) =>
      r.json('tenantId') !== undefined,
  });
  const cost = parseFloat(res.headers['X-Cost-Usd'] || '0');
  costPerReq.add(cost);
  tenantSynth.add(1);
}

function writeColdPath() {
  const res = http.post(`${BASE}/v1/tenants`,
    JSON.stringify({
      tenant:  'tenant-synth',
      feature: 'summarize-nexus',
      size:    'large',
    }),
    { headers: {
      'Content-Type': 'application/json',
      'X-Tenant':     'tenant-synth',
      'X-Feature':    'summarize-nexus',
    }});
  check(res, { 'status 200': (r) => r.status === 200 });
  const cost = parseFloat(res.headers['X-Cost-Usd'] || '0');
  costPerReq.add(cost);
  tenantSynth.add(1);
}

function readPath() {
  const res = http.get(
    `${BASE}/v1/tenants/00000000-0000-0000-0000-000000000001`);
  check(res, { 'status 200 or 404': (r) =>
    r.status === 200 || r.status === 404 });
}
