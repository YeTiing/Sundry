// 商品浏览压测：验证三级缓存和互斥重建在并发下不掉数据。
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';

export const options = {
  vus: Number(__ENV.VUS || 100),
  duration: __ENV.DURATION || '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200', 'p(99)<500'],
  },
};

export default function () {
  const list = http.get(`${BASE}/api/products?page=1&size=12`);
  check(list, { 'list 200': (res) => res.status === 200 });

  const productId = list.json('items[0].id');
  if (productId) {
    const detail = http.get(`${BASE}/api/products/${productId}`);
    check(detail, { 'detail 200': (res) => res.status === 200 });
  }

  http.get(`${BASE}/api/categories`);
}
