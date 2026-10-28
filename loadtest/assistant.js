// 助手压测：验证异步返回、Bulkhead、限流与降级。
//
// 先跑 prepare.mjs 生成 tokens.txt，或通过 TOKENS 传入逗号分隔的 token。
// 想测 Bulkhead 而不是限流时，启动后端时把 ASSISTANT_RATE_LIMIT 调高（如 100000）。
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const RULE_MESSAGE = __ENV.RULE_MESSAGE || '我的订单在哪';
const MODEL_MESSAGE = __ENV.MODEL_MESSAGE || '你好，帮我看看';
const MODEL_PERCENT = Number(__ENV.MODEL_PERCENT || 30);

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

const ok = new Counter('assistant_ok');
const busy = new Counter('assistant_busy');
const degraded = new Counter('assistant_degraded');
const rateLimited = new Counter('assistant_rate_limited');
const errors = new Counter('assistant_error');

export const options = {
  scenarios: {
    assistant: {
      executor: 'ramping-vus',
      startVUs: Number(__ENV.START_VUS || 10),
      stages: [
        { duration: __ENV.RAMP_UP || '20s', target: Number(__ENV.VUS || 100) },
        { duration: __ENV.HOLD || '30s', target: Number(__ENV.VUS || 100) },
        { duration: __ENV.RAMP_DOWN || '10s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    // 用户等待上限 4s；饱和时 p95 会贴着上限，留一点 HTTP/网络开销
    http_req_duration: ['p(95)<4500', 'p(99)<5000'],
    assistant_error: ['count==0'],
  },
};

export default function () {
  if (tokens.length === 0) {
    throw new Error('tokens.txt 为空；先跑 loadtest/prepare.mjs 或设置 TOKENS');
  }
  const token = tokens[(__VU - 1) % tokens.length];
  const useModel = Math.random() * 100 < MODEL_PERCENT;
  const message = useModel ? MODEL_MESSAGE : RULE_MESSAGE;

  const response = http.post(
    `${BASE}/api/assistant/chat`,
    JSON.stringify({ message }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      tags: { path: useModel ? 'model' : 'rule' },
    }
  );

  if (response.status === 429) {
    rateLimited.add(1);
    check(response, { 'rate limited': (res) => res.status === 429 });
    sleep(Number(__ENV.SLEEP || 0.2));
    return;
  }

  if (response.status !== 200) {
    errors.add(1);
    check(response, { 'status 200': (res) => res.status === 200 });
    sleep(Number(__ENV.SLEEP || 0.2));
    return;
  }

  let reply = '';
  try {
    reply = response.json('reply') || '';
  } catch (error) {
    reply = '';
  }

  if (reply.includes('问的人有点多')) {
    busy.add(1);
  } else if (reply.includes('暂时不可用')) {
    degraded.add(1);
  } else {
    ok.add(1);
  }

  check(response, { 'status 200': (res) => res.status === 200 });
  sleep(Number(__ENV.SLEEP || 0.2));
}
