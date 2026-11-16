# 072 — #22: one real ECPay stage payment with an inbound callback (owner steps)

Status: **not executed.** This is the only part of #22 that needs a person: paying on ECPay's stage page in your own Chrome. Everything that can be proven without it is in `docs/tasks/084-phase33-22-callback-review.md`.

Refreshed 2026-11-16: shortest path is now **local dev mode** (frontend `localhost:5173`, backend `localhost:8080`, only the callback is public). The production-like path through the tunnel stack is kept at the end as an optional variant. The commands below were assembled from the repo's configuration and from 035 (outbound leg executed 2026-09-24); the sequence as a whole has not been run end to end.

## What you need

- Docker Desktop running, Tailscale with Funnel enabled (as in 067).
- No ECPay account: the steps use ECPay's **public stage test merchant** (`3002607`, already in `docs/tasks/035` and the tests). If you use your own stage merchant instead, put its values only in your shell, never in the repo or Notion.
- ECPay's public stage test credit card (ECPay developer docs, "測試介接資訊"): card `4311-9522-2222-2222`, CVV `222`, any future expiry, SMS code `1234`. Check that page if the card is refused; this value was not re-verified here.

## Steps (PowerShell, repo root)

1. Database: `docker compose up -d mysql` (dev compose, port 3306, seed data loaded on first start).
2. Public callback URL: `tailscale funnel --bg 8080`, note the `https://<host>.ts.net` origin. If Funnel was still pointing at the tunnel stack (8088), this replaces that mapping.
3. Backend, in one terminal (replace `<host>` only):

   ```powershell
   $env:PAYMENT_PROVIDER='ecpay'; $env:ECPAY_MERCHANT_ID='3002607'
   $env:ECPAY_HASH_KEY='pwFHCqoQZGmho4w6'; $env:ECPAY_HASH_IV='EkRm7iFT261dpevs'
   $env:ECPAY_PAYMENT_URL='https://payment-stage.ecpay.com.tw/Cashier/AioCheckOut/V5'
   $env:ECPAY_CALLBACK_URL='https://<host>.ts.net/api/payments/ecpay/callback'
   $env:ECPAY_RETURN_URL='http://localhost:5173/'
   cd backend; mvn spring-boot:run
   ```

4. Frontend, in a second terminal: `cd frontend; npm run dev`.
5. Reachability check before paying (should print `0|ERROR`, which proves ECPay can reach the handler):

   ```powershell
   curl.exe -s -X POST -d "x=1" https://<host>.ts.net/api/payments/ecpay/callback
   ```

6. In your own Chrome at `http://localhost:5173`: register, add a shipping address, order any seeded product, open the order and press pay. Pay on the ECPay stage page with the test card. ECPay then calls the public URL server to server; return to the shop and reload the order list.

## Evidence to record (no hostname, no secrets)

```powershell
docker exec esun-mysql mysql -uroot -p123456 esun_shop -e "SELECT p.merchant_trade_no, p.status, p.provider_ref, p.amount, p.paid_at, o.pay_status FROM payment p JOIN shop_order o ON o.order_id = p.order_id ORDER BY p.id DESC LIMIT 3"
```

- Expected: the attempt is `SUCCEEDED`, `provider_ref` holds ECPay's trade number, `pay_status = 1`.
- Backend log: `Order <id> paid via payment <tradeNo>` and no `Rejected payment callback that failed verification`.
- Three things 035 could only assume, worth one line each: (a) the callback verified with ECPay's real field set (lowercase fields, Chinese `RtnMsg`, empty fields); (b) what ECPay sends after a declined card and whether the same page can be retried; (c) pressing pay a second time opens a new trade number without an ECPay duplicate error.
- If the order stays unpaid: a `Rejected payment callback` line means the CheckMacValue did not verify against a real callback (a code finding, record the field names only); no line at all means ECPay never reached the URL (check step 5).

## Afterwards

Stop the backend (the `ecpay` setting lives only in that shell), then `tailscale funnel reset` or point Funnel back at 8088 with `tailscale funnel --bg 8088`. Report the evidence to the lead; `docs/project-state.md` is updated by the lead.

## Optional variant: through the production-like tunnel stack

Use this only if the acceptance should also cover Caddy and the prod profile. Funnel on 8088; in the git-ignored `.env.prod` set `PAYMENT_PROVIDER=ecpay`, the five `ECPAY_*` values with `ECPAY_CALLBACK_URL=https://<host>.ts.net/api/payments/ecpay/callback` and `ECPAY_RETURN_URL=https://<host>.ts.net/`, `PAYMENT_CALLBACK_SECRET`, and `CORS_ALLOWED_ORIGINS=https://<host>.ts.net`; deploy with `bash scripts/deploy-tunnel.sh`; shop at the Funnel origin instead of `localhost:5173`. Set `PAYMENT_PROVIDER=none` again afterwards.

Known fixes already in place: callback accepted regardless of `Accept` header (`0b20f47`); case-variant field names rejected (084).
