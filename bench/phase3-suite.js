// Phase 3 k6 suite for POST /api/orders. One script, four workloads chosen with SCENARIO=<name>:
//   normal   - single-item purchases against an effectively unlimited product (latency / throughput baseline)
//   deadlock - two-item orders in opposite item order against unlimited products (lock-order stress)
//   soldout  - single-item purchases against a small fixed stock (must sell exactly the stock, never oversell)
//   coupon   - purchases naming one hot coupon with a fixed quota (must redeem exactly the quota)
// Products and the coupon are prepared by bench/run-phase3-suite.py (direct SQL) so the expected outcome is known,
// and the same script reconciles the database after each run. k6 itself only buckets by HTTP status.
import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SCENARIO = __ENV.SCENARIO || 'normal';
const BUYERS = Number(__ENV.BUYERS || 20);
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const PRODUCT_A = __ENV.PRODUCT_A || 'K6A';
const PRODUCT_B = __ENV.PRODUCT_B || 'K6B';
const COUPON_CODE = __ENV.COUPON_CODE || 'K6HOT';

const executors = {
  normal: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 50), timeUnit: '1s',
    duration: __ENV.DURATION || '30s', preAllocatedVUs: 60, maxVUs: 200 },
  deadlock: { executor: 'constant-vus', vus: Number(__ENV.VUS || 40), duration: __ENV.DURATION || '30s' },
  // Fixed number of iterations spread over the VUs: the run ends when every attempt has been answered.
  soldout: { executor: 'shared-iterations', vus: Number(__ENV.VUS || 60), iterations: Number(__ENV.ITERATIONS || 600),
    maxDuration: '120s' },
  coupon: { executor: 'shared-iterations', vus: Number(__ENV.VUS || 60), iterations: Number(__ENV.ITERATIONS || 400),
    maxDuration: '120s' },
};

export const options = {
  scenarios: { [SCENARIO]: executors[SCENARIO] },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  // Correctness gates only. Latency is a baseline to record, not a pass/fail claim, so it is deliberately not gated.
  thresholds: {
    orders_servererror_5xx: ['count==0'],
    orders_no_response: ['count==0'],
  },
};

// Latency of POST /api/orders only: the built-in http_req_duration also contains the (bcrypt-slow) setup registrations.
const orderLatency = new Trend('order_latency_ms', true);
const ok200 = new Counter('orders_success_200');
const conflict409 = new Counter('orders_conflict_409');
// A 409 whose message is exactly the bare text came from the stored procedure's SIGNAL (the sell-out race that the
// up-front check, which appends the product id, did not catch). Only that path proves the race fix is exercised.
const conflict409Race = new Counter('orders_conflict_409_stock_race');
const notFound404 = new Counter('orders_notfound_404');
const badRequest400 = new Counter('orders_badrequest_400');
const serverError = new Counter('orders_servererror_5xx');
const noResponse = new Counter('orders_no_response');
const otherStatus = new Counter('orders_other_status');

const uuid = () => {
  const b = new Uint8Array(crypto.randomBytes(16));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  const h = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
};

// Registered once, before the load starts, so bcrypt cost is not part of the measured order latency.
export function setup() {
  const tokens = [];
  for (let i = 0; i < BUYERS; i++) {
    const email = `k6-${SCENARIO}-${RUN_ID}-${i}@example.com`;
    const reg = http.post(`${BASE_URL}/api/auth/register`, JSON.stringify({ email, password: 'k6-password-123' }),
      { headers: JSON_HEADERS });
    check(reg, { 'buyer registered': (r) => r.status === 200 });
    const token = reg.json('data.token');
    const addr = http.post(`${BASE_URL}/api/member/addresses`, JSON.stringify({
      label: 'k6', receiverName: 'k6', phone: '0912-345-678', postalCode: '100', address: 'k6 test road 1', isDefault: true,
    }), { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } });
    check(addr, { 'address created': (r) => r.status === 200 });
    tokens.push(token);
  }
  return { tokens };
}

const itemsFor = () => {
  switch (SCENARIO) {
    case 'deadlock': {
      const forward = [{ productId: PRODUCT_A, quantity: 1 }, { productId: PRODUCT_B, quantity: 1 }];
      return __VU % 2 === 0 ? forward : forward.slice().reverse();
    }
    case 'soldout':
      return [{ productId: PRODUCT_A, quantity: 1 }];
    case 'coupon':
      return [{ productId: PRODUCT_A, quantity: 1 }];
    default:
      return [{ productId: __ITER % 2 === 0 ? PRODUCT_A : PRODUCT_B, quantity: 1 }];
  }
};

export default function (data) {
  const token = data.tokens[__VU % data.tokens.length];
  const body = { requestId: uuid(), items: itemsFor() };
  if (SCENARIO === 'coupon') body.couponCode = COUPON_CODE;
  const res = http.post(`${BASE_URL}/api/orders`, JSON.stringify(body), {
    headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` }, timeout: '30s',
  });
  orderLatency.add(res.timings.duration);
  if (res.status === 200) ok200.add(1);
  else if (res.status === 409) {
    conflict409.add(1);
    if (res.body && res.body.indexOf('"message":"商品庫存不足"') !== -1) conflict409Race.add(1);
  }
  else if (res.status === 404) notFound404.add(1);
  else if (res.status === 400) badRequest400.add(1);
  else if (res.status >= 500) serverError.add(1);
  else if (res.status === 0) noResponse.add(1);
  else otherStatus.add(1);
  check(res, { 'answered': (r) => r.status !== 0 });
}
