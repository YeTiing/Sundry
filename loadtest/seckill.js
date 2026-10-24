import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
// 409 售罄和 429 限流是预期结果，不计入失败
const unexpected = new Counter('seckill_unexpected_status');
const ACTIVITY_ID = __ENV.ACTIVITY_ID;

let tokens = [];
try {
  tokens = open('./tokens.txt')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
} catch (error) {
  tokens = [];
}
if (tokens.length === 0) {
  tokens = (__ENV.TOKENS || '').split(',').map((item) => item.trim()).filter(Boolean);
}

export const options = {
  scenarios: {
    seckill: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || tokens.length || 100),
      iterations: Number(__ENV.ITERATIONS || tokens.length || 100),
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
  thresholds: {
    seckill_unexpected_status: ['count==0'],
    http_req_duration: ['p(99)<800'],
  },
};

export default function () {
  if (!ACTIVITY_ID) {
    throw new Error('ACTIVITY_ID is required');
  }
  if (tokens.length === 0) {
    throw new Error('需要 tokens.txt 或 TOKENS 环境变量');
  }

  const token = tokens[(__VU - 1) % tokens.length];
  const response = http.post(
    `${BASE}/api/seckill/orders`,
    JSON.stringify({ activityId: Number(ACTIVITY_ID) }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      tags: { name: 'seckill-submit' },
    },
  );

  if (![202, 409, 429].includes(response.status)) {
    unexpected.add(1);
  }

  check(response, {
    'accepted or expected rejection': (r) => [202, 409, 429].includes(r.status),
  });
}
