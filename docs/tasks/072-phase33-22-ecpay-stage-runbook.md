# 072 — #22: real ECPay stage payment through Tailscale Funnel (owner steps)

Status: **not executed** — needs values and a payment only the owner can supply. Do not paste any value below into the repo or Notion.

Preconditions: Docker Desktop running; Tailscale Funnel enabled on the personal account; ECPay stage merchant values (merchant id, hash key, hash IV) from the ECPay stage account.

1. Start Funnel: `tailscale funnel --bg 8088` (Caddy on `127.0.0.1:8088`). Note the `https://<host>.ts.net` origin.
2. Edit the git-ignored `.env.prod`: `PAYMENT_PROVIDER=ecpay`, `ECPAY_MERCHANT_ID`, `ECPAY_HASH_KEY`, `ECPAY_HASH_IV`, `ECPAY_PAYMENT_URL` (stage endpoint), `ECPAY_CALLBACK_URL=https://<host>.ts.net/api/payments/ecpay/callback` , `ECPAY_RETURN_URL`, `PAYMENT_CALLBACK_SECRET`, and `CORS_ALLOWED_ORIGINS=https://<host>.ts.net`.
3. Deploy: `bash scripts/deploy-tunnel.sh` (backup, health gate, rollback on failure).
4. Register a buyer and a seller, create a product, add to cart, check out, and pay with an ECPay **stage test card** in your own browser.
5. Evidence to record (no secrets, no hostname): the order moves PENDING → PAID, one payment row with the provider trade number, and the backend log line for the inbound callback (200, correlation id). Then set `PAYMENT_PROVIDER=none` again.

Known fix already in place: callback accepted regardless of `Accept` header (`0b20f47`); `EcpayPaymentIntegrationTest` 10/10.
