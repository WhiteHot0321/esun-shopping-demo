// k6 load test variant for POST /api/orders — 3-item FULLY OVERLAPPING basket,
// used as a follow-up to order-load-test.js to better isolate the deadlock
// class that Phase 1 item 3.3 (commit a197192, fixed lock order by
// productId) actually targets: cross-order MULTI-ROW crossed-lock deadlocks,
// as opposed to the single-row FK-insert-vs-UPDATE lock-upgrade deadlock
// that dominated the original 2-product benchmark (see bench/RESULTS.md,
// "Finding" section, for why that first run was a null result).
//
// Design:
//   - Every request's basket contains ALL THREE products this repo has
//     seeded (P001, P002, P003), quantity 1 each — not a subset. With only
//     2 products there is exactly 1 unordered pair and only 2 possible lock
//     orders (matching/opposite); with 3 products fully overlapping there
//     are 3! = 6 possible submission orders, so far more of the concurrent
//     transaction pairs in flight at any moment are touching all 3 rows in
//     DIFFERENT orders relative to each other pre-fix, and the SAME sorted
//     order post-fix. This should raise the share of deadlocks caused by
//     the multi-row crossed-lock pattern relative to the constant single-row
//     FK/UPDATE lock-upgrade noise floor, instead of being swamped by it.
//   - VUs are split into 6 groups by `__VU % 6`, each group fixed to one of
//     the 6 permutations of [P001, P002, P003] for the whole run (mirrors
//     the original script's even/odd split, just with 6 buckets instead of
//     2). Before the fix, OrderService iterates items in request order when
//     inserting order_detail rows and decrementing stock, so 6 different
//     submission orders in flight concurrently maximizes the chance any two
//     given transactions lock the 3 rows in different sequences. After the
//     fix, all 6 groups converge on the same lock order (sorted by
//     productId), which should remove that specific deadlock cause.
//   - REQUIRES a runtime-only stock bump before running (NOT committed to
//     backend/DB/02_data.sql — see bench/RESULTS.md's "Follow-up" section
//     for the exact SQL and rationale). Seed data ships P001 at only 5
//     units, which would sell out in milliseconds under 40 VUs and collapse
//     the concurrent-overlap window this benchmark depends on. Run:
//       UPDATE product SET quantity = 200 WHERE product_id IN ('P001','P002','P003');
//     against the freshly-reset DB before starting k6.
//
// Same disambiguation caveat as order-load-test.js applies: HTTP 500 alone
// cannot distinguish a real InnoDB deadlock (1213) from a lock-wait timeout
// (1205) or the sp_decrease_stock SIGNAL (TOCTOU stock race). Capture the
// backend log and grep it — see bench/README.md "Limitation" section.

import http from 'k6/http';
import { check, sleep } from 'k6';
import crypto from 'k6/crypto';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// All 6 permutations of the 3 fully-overlapping products, quantity 1 each.
const PERMUTATIONS = [
  ['P001', 'P002', 'P003'],
  ['P001', 'P003', 'P002'],
  ['P002', 'P001', 'P003'],
  ['P002', 'P003', 'P001'],
  ['P003', 'P001', 'P002'],
  ['P003', 'P002', 'P001'],
].map((order) => order.map((productId) => ({ productId, quantity: 1 })));

export const options = {
  scenarios: {
    order_load: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 40),
      duration: __ENV.DURATION || '45s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

const ordersSuccess200 = new Counter('orders_success_200');
const ordersConflict409 = new Counter('orders_conflict_409');
const ordersNotFound404 = new Counter('orders_notfound_404');
const ordersBadRequest400 = new Counter('orders_badrequest_400');
const ordersServerError500 = new Counter('orders_servererror_500'); // ambiguous bucket, see caveat above
const ordersOtherStatus = new Counter('orders_other_status');
const ordersNoResponse = new Counter('orders_no_response'); // status 0: timeout / connection error

const newRequestId = () => {
  const bytes = new Uint8Array(crypto.randomBytes(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};

export default function () {
  const items = PERMUTATIONS[__VU % 6];
  // shop_order.member_id is VARCHAR(20) — keep this well under that limit.
  const memberId = `k6${__VU}_${__ITER % 100000}`;
  const payload = JSON.stringify({
    requestId: newRequestId(),
    memberId: memberId,
    payStatus: 'PENDING',
    items: items,
  });
  const params = {
    headers: { 'Content-Type': 'application/json' },
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

  // Same pacing rationale as order-load-test.js: keeps requests overlapping
  // without needlessly flooding the backend log.
  sleep(0.1);
}
