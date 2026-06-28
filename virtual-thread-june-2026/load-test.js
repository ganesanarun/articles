import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

const orderDuration = new Trend('order_duration_ms', true);
const errorRate     = new Rate('order_errors');
const totalOrders   = new Counter('total_orders');

export const options = {
  stages: [
    { duration: '10s', target: 10 },  // warm up — well within OLD mode's 16-thread limit
    { duration: '20s', target: 60 },
    { duration: '50s', target: 60 },
    { duration: '10s', target: 0  },  // cool down
  ],

  thresholds: {
    http_req_failed:   ['rate<0.01'],
    order_duration_ms: ['p(50)<700', 'p(95)<1000'],
  },
};

export default function () {
  const res = http.get('http://localhost:8081/', { timeout: '15s' });

  const ok = check(res, {
    'status 200':        (r) => r.status === 200,
    'order number':      (r) => r.body.includes('Order'),
    'ingredient back':   (r) => r.body.includes('FULFILLED'),
  });

  orderDuration.add(res.timings.duration);
  errorRate.add(!ok);
  totalOrders.add(1);
}
