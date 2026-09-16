# Task 009 — Phase 2 登入失敗修復（JDBC allowPublicKeyRetrieval）

執行者：Claude Code。

## Task

修復使用者回報的 Phase 2 登入失敗，並確認修復不影響既有測試與 coverage gate。

## Problem

針對本機以 `DB_HOST=localhost DB_PORT=3308`（對應 `esun-mysql` 容器目前的宿主機對映埠）
啟動的後端，直接呼叫 `POST /api/auth/register` 與 `POST /api/auth/login` 皆回傳
HTTP 500 `{"success":false,"message":"資料庫操作失敗","code":"DB_ERROR"}`。進一步測試
`GET /api/products/available`（同樣走 JdbcTemplate）也回傳相同錯誤，證實問題不在
`AuthService`/`AuthController` 的登入邏輯本身，而是整個資料庫連線層級的故障。

## Root Cause

`backend/src/main/resources/application.yml` 的 JDBC URL 只有 `useSSL=false`，未加
`allowPublicKeyRetrieval=true`。MySQL 8 預設帳號使用 `caching_sha2_password` 驗證
外掛：在未使用 SSL、且伺服器端該帳號的驗證快取尚未預熱時，Connector/J 需要向伺服器
索取 RSA 公鑰來加密密碼，而這個行為預設被 Connector/J 拒絕（安全預設值），因而拋出
`com.mysql.cj.exceptions.UnableToConnectException: Public Key Retrieval is not allowed`，
HikariCP 在建立第一個連線（`HikariPool.checkFailFast`）時就整個連線池初始化失敗，
導致該後端行程上所有經由 JdbcTemplate/Hikari 的請求（不只登入）都回傳 500。

## Solution

在 `application.yml` 的 datasource URL 加上 `allowPublicKeyRetrieval=true`：

```
jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:esun_shop}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Taipei&characterEncoding=utf8
```

這是本機/Docker 開發情境下的標準做法：連線仍不加密（`useSSL=false`，與現有
docker-compose 開發環境一致），但允許客戶端取得伺服器公鑰以完成
`caching_sha2_password` 的密碼加密交握。正式環境若改用 `useSSL=true`，此參數不影響
行為（因為已用 TLS 通道傳輸密碼，不需要另外索取公鑰）。

## Engineering Concept

**錯誤訊息的分層診斷**：Controller 層只看到 `BusinessException`／`DataAccessException`
被 GlobalExceptionHandler 統一包成「資料庫操作失敗」，因此从 API 回應本身無法判斷是
登入邏輯錯誤還是連線層錯誤。透過重現時同時打一個「不需要登入、也走 DB」的端點
（`/api/products/available`）做對照組，才能把故障範圍從「登入功能」正確收斂到
「整個 JdbcTemplate/DataSource 層」，再回頭看後端 log 的完整 stack trace 找到真正的
`SQLNonTransientConnectionException` 根因，而不是直接對著 `AuthService`/`AuthController`
瞎猜。

## Test Result

- 修正後以 curl 直接呼叫 API 驗證：
  - `POST /api/auth/register`：200，回傳有效 JWT。
  - `POST /api/auth/login`（正確密碼）：200，回傳與註冊時相同結構的 JWT。
  - `POST /api/auth/login`（錯誤密碼）：401 `{"success":false,"message":"帳號或密碼錯誤"}`。
- `mvn clean test`（完整套件，非窄選）：exit code 0，與既有基準（72/72 通過、四服務
  coverage gate 全數 ≥80%）一致；唯一一段完整 stack trace 來自
  `StockCacheServiceIntegrationTest.auditSchedulerHandlesUnavailableDatabaseWithoutThrowing`
  刻意模擬的 DB 不可用情境（預期行為，非本次修復造成的迴歸）。

## Trade-offs (if any)

- 無功能性取捨；`allowPublicKeyRetrieval=true` 是這個開發/測試連線模式（無 TLS）下的
  標準必要設定，不引入新風險。若未來正式環境改用強制 TLS 連線，可視情況一併加上
  `useSSL=true` 並保留此參數（對已用 TLS 的連線無副作用）。

## Next Step

- 無後續追蹤項；此為單一設定修復，已由完整測試套件與端到端 API 呼叫驗證完成。
