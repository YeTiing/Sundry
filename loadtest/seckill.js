// 秒杀压测：需要先准备一个进行中的数字商品活动，以及一批互不相同的用户 token。
// 运行示例见 loadtest/README.md
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const ACTIVITY_ID = Number(__ENV.ACTIVITY_ID || 0);
const TOKENS = (__ENV.TOKENS || '')
  .split(',')
  .map((token) => token.trim())
  .filter(Boolean);

export const options = {
  scenarios: {
    seckill: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 100),
      iterations: Number(__ENV.ITERATIONS || 1000),
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(99)<500'],
  },
};

export function setup() {
  if (!ACTIVITY_ID) {
    throw new Error('缺少 ACTIVITY_ID');
  }
  if (TOKENS.length === 0) {
    throw new Error('缺少 TOKENS，需要逗号分隔的一批用户 accessToken');
  }
}

export default function () {
  const token = TOKENS[(__VU - 1) % TOKENS.length];
  const response = http.post(
    `${BASE}/api/seckill/orders`,
    JSON.stringify({ activityId: ACTIVITY_ID }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
    },
  );

  check(response, {
    'accepted or expected rejection': (res) => [202, 409, 429].includes(res.status),
  });
}
