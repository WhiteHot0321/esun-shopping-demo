// k6 load test for POST /api/orders — used to compare deadlock/lock-timeout
// behavior before and after Phase 1 item 3.3 (fixed lock order by productId).
//
// Design:
//   - Half the VUs (even __VU) submit items in order [P002, P003].
//   - The other half (odd __VU) submit items in REVERSED order [P003, P002].
//     Before the fix, OrderService iterated items in request order when
//     inserting order_detail rows and decrementing stock, so two concurrent
//     transactions with opposite item order can lock product rows in
//     opposite sequences -> classic deadlock setup. After the fix, both
//     groups converge on the same lock order (sorted by productId), which
//     should remove that specific deadlock cause.
//   - Uses P002 (stock 50) and P003 (stock 20) instead of P001 (stock 5) so
//     there is a long enough window of concurrent in-flight requests before
//     stock runs out and requests start failing with 409 (business rule),
//     which is a different bucket from DB-level errors.
//
// IMPORTANT CAVEAT (see bench/README.md "Limitation" section):
//   GlobalExceptionHandler.handleDb() catches the generic Spring
//   DataAccessException and always returns HTTP 500 with the same message
//   ("資料庫操作失敗"), regardless of whether the underlying cause was a
//   real InnoDB deadlock (MySQL error 1213), a lock-wait timeout (error
//   1205), or the sp_decrease_stock stored procedure's own SIGNALed
//   "庫存不足或商品不存在" (a TOCTOU stock race under concurrency, distinct
//   from the up-front 409 check). This script can only bucket by HTTP
//   status; it CANNOT tell these three apart from the HTTP response alone.
//   To get the authoritative deadlock/lock-timeout count, the backend
//   process's stdout/stderr must be captured to a log file during the run
//   and grepped afterwards for "Deadlock found" / "Lock wait timeout
//   exceeded" (see bench/README.md step 5). This script's 5xx counter is
//   an upper bound / signal, not the final deadlock count.

import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const ITEMS_FORWARD = [
  { productId: 'P002', quantity: 1 },
  { productId: 'P003', quantity: 1 },
];
const ITEMS_REVERSED = [
  { productId: 'P003', quantity: 1 },
  { productId: 'P002', quantity: 1 },
];

export const options = {
  scenarios: {
    order_load: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 30),
      duration: __ENV.DURATION || '45s',
    },
  },
  // Extend default trend stats so p99 shows up in the summary alongside p90/p95.
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

// Custom counters, bucketed by HTTP status so we can compare pre/post-fix runs.
const ordersSuccess200 = new Counter('orders_success_200');
const ordersConflict409 = new Counter('orders_conflict_409'); // up-front "stock insufficient" business check
const ordersNotFound404 = new Counter('orders_notfound_404');
const ordersBadRequest400 = new Counter('orders_badrequest_400');
const ordersServerError500 = new Counter('orders_servererror_500'); // ambiguous bucket, see caveat above
const ordersOtherStatus = new Counter('orders_other_status');
const ordersNoResponse = new Counter('orders_no_response'); // status 0: timeout / connection error
export function setup() {
  const auth = http.post(`${BASE_URL}/api/auth/register`, JSON.stringify({ email: `k6-${Date.now()}@example.com`, password: 'k6-password-123' }), { headers: { 'Content-Type': 'application/json' } });
  check(auth, { 'registered benchmark account': (r) => r.status === 200 });
  return { token: auth.json('data.token') };
}

const newRequestId = () => {
  const bytes = new Uint8Array(crypto.randomBytes(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};

export default function (data) {
  const items = __VU % 2 === 0 ? ITEMS_FORWARD : ITEMS_REVERSED;
  // shop_order.member_id is VARCHAR(20) — keep this well under that limit
  // (an earlier version used a timestamp suffix and overflowed the column,
  // which produced a flood of unrelated DataIntegrityViolationException
  // errors that had nothing to do with locking/deadlocks).
  const memberId = `k6${__VU}_${__ITER % 100000}`;
  const payload = JSON.stringify({
    requestId: newRequestId(),
    memberId: memberId,
    payStatus: 'PENDING',
    items: items,
  });
  const params = {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` },
    timeout: '30s',
  };

  const res = http.post(`${BASE_URL}/api/orders`, payload, params);

  switch (res.status) {
    case 200:
      ordersSuccess200.add(1);
      break;
    case 400:
      ordersBadRequest400.add(1);
      break;
    case 404:
      ordersNotFound404.add(1);
      break;
    case 409:
      ordersConflict409.add(1);
      break;
    case 500:
      ordersServerError500.add(1);
      break;
    case 0:
      ordersNoResponse.add(1);
      break;
    default:
      ordersOtherStatus.add(1);
  }

  check(res, {
    'got a response (status != 0)': (r) => r.status !== 0,
  });

  // Small pacing gap: without it, a VU with no think time fires as fast as
  // the event loop allows (thousands of iterations/sec across 40 VUs on this
  // machine), which does not add more *deadlock opportunity* — deadlocks
  // need overlapping transactions, not raw request count — it just floods
  // the backend log and burns through the limited P002/P003 stock in a
  // fraction of a second. This keeps the request rate high enough to keep
  // transactions overlapping while staying observable.
  sleep(0.1);
}
