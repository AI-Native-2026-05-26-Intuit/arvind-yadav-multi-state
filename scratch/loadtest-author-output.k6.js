// scratch/loadtest-author-output.k6.js
// loadtest-author Skill output (audit reference — NOT the CI gate).
// Deviations from loadtests/multistate-api-p99.js documented in
// multistate-api/SRE-CAPSTONE.md.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const costPerReq = new Trend('cost_per_request_usd');

export const options = {
  stages: [
    { duration: '4m', target: 280 },
    { duration: '6m', target: 280 },
    { duration: '2m', target: 0 },
  ],
  thresholds: {
    // DEVIATION: avg<900 invents wrong statistic for p99 SLO — REJECTED.
    http_req_duration: ['avg<900'],
    http_req_failed:   ['rate<0.005'],
    // DEVIATION: cost_usd threshold dropped — REJECTED in our gate.
  },
};

const BASE = __ENV.TARGET || 'http://multistate-api.staging.svc.cluster.local:8080';

// DEVIATION: weights 0.7+0.2+0.05+0.05=1.0 but Skill renormalised to
// 0.636/0.182/0.091/0.091 — REJECTED; we keep explicit 0.7/0.2/0.1.
const MIX = [
  { weight: 0.636, run: writeHotPath },
  { weight: 0.182, run: writeColdPath },
  { weight: 0.091, run: readPath },
  { weight: 0.091, run: readPath },
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
  http.post(`${BASE}/v1/tenants`, JSON.stringify({ tenant: 'tenant-synth' }), {
    headers: { 'Content-Type': 'application/json' },
  });
}

function writeColdPath() {
  http.post(`${BASE}/v1/tenants`, JSON.stringify({ tenant: 'tenant-synth', size: 'large' }), {
    headers: { 'Content-Type': 'application/json' },
  });
}

function readPath() {
  http.get(`${BASE}/v1/tenants/00000000-0000-0000-0000-000000000001`);
}
