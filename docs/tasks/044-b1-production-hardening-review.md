# 044 審查合約 — Phase 3.3 #18 B1 正式部署程式碼硬化

## 044-C1 correction — 2026-09-29 Asia/Taipei

Baseline: advanced-v2 @ 429346d94b8995d9025fb27ab1d722d9c764cee1. Implementer: Codex; independent reviewer: Claude Code (static correction PASS). MODE: IMPLEMENT uses the user's earlier explicit authorization; the preceding read-only review ended before this correction. Scope: ProductionConfigValidator.java, ProductionConfigValidatorTest.java and this record only; preserve all pre-existing changes. No commit/push/merge/deploy.

The current-tree static review found that isPlainOrigin accepted user-info URLs such as https://user@shop.example.com. Correction adds a getRawUserInfo() == null requirement. The malformed-origin cases now include username, username/password, empty user-info and a mixed valid/invalid list; existing valid single/multiple-origin cases remain.

Executed: `mvn '-Dtest=ProductionConfigValidatorTest' '-Djacoco.skip=true' test` from backend — exit 0, 19/19, 0 failures/errors/skips, total 9.318 seconds. JaCoCo explicitly skipped; no Docker/full-suite/browser/deployment verification. Evidence: backend/target/surefire-reports/com.esun.shop.config.ProductionConfigValidatorTest.txt; command log in the OS temp directory esun-044-c1-test.log. One target command, no repair round. Independent correction review pending. This is not full B1/B2/B3 acceptance; historical health-only expectations below must be read alongside the subsequently accepted MON-01 health/metrics policy.

Independent review: PASS for 044-C1 only, no edits/tests. Report: `.git/codex-claude-runs/58eb324f-774a-494e-9e8d-60931e1e1e69/result.json`. Reviewer inspected the two core files; executed evidence was checked separately by Codex (19/19, exit0). This does not complete the broader B1/production acceptance.

Remaining #18 requirements: B2 Compose/Caddy/frontend image/non-root container and local-stack acceptance; B3 external VM prerequisites and deployment/rollback; C integrated independent acceptance. User selected Ollama and plans Ubuntu VM; DNS/secrets/real deployment availability are not proven. Proposed B2 expansion to eight files is awaiting response. Overall elapsed time/context/token/cost unknown.

**審查方式**：唯讀靜態與行為分析。審查者不修改程式碼、不執行測試、不部署。  
**審查者**：Codex（由 Claude Code 實作）  
**實作者**：Claude Code  
**基準**：`feature/phase33-18-b1-prod-hardening` @ `58d1b07`（已 push origin）  
**風險等級**：Critical（認證路徑、啟動安全閘門、密鑰驗證）  

## 審查前置要求

先於審查讀取實作者的規劃文件和結果紀錄，再獨立形成判斷：
1. `docs/tasks/043-production-deployment-plan.md` — 計畫與缺口表（§1-2）
2. `docs/tasks/043-production-deployment-plan.md` 的 §2.1 — B1 實作結果與驗證小結
3. `docs/project-state.md` 頂部的 #18 B1 紀錄
4. commit message：`git log --oneline -1 58d1b07`
5. git diff：`git diff advanced-v2..feature/phase33-18-b1-prod-hardening`

**不可**讀取 commit message 之外的實作者描述或自審結論，直到你已形成獨立判斷。

## 驗收標準（來自缺口表 §1 之 G1/G3/G4/G5）

### G1：JWT 密鑰

**需求**：`prod` profile 下缺少、過短或含開發值的密鑰使應用**啟動失敗**。

**審查項**：
- [ ] `application-prod.yml` 中 `jwt.secret` 無預設值（缺少即解析失敗，不是 null 預設）
- [ ] `ProductionConfigValidator.validate()` 檢查 ≥32 bytes 且非 `dev-only` 前綴
- [ ] 檢查失敗時拋出 `IllegalStateException` 且訊息只含「JWT_SECRET」，不含密鑰值本身
- [ ] 應用在 `@PostConstruct` 中呼叫驗證，即啟動時執行
- [ ] 「啟動失敗」是指 `@Profile("prod")` 下 Spring 拒絕完成初始化，不是啟動後才檢查

### G3：付款與 API 文件

**需求**：`PAYMENT_PROVIDER=sandbox` 或 `API_DOCS_ENABLED=true` 在 prod 拒絕啟動；ECPay 缺任一設定也拒絕。

**審查項**：
- [ ] `application-prod.yml` 中 `payment.provider` 和 `payment.callback-secret` 無預設值
- [ ] `ProductionConfigValidator` 拒絕 `provider=sandbox` 且理由點名 sandbox
- [ ] `provider=ecpay` 時檢查全部六項 ECPay 設定（merchant-id, hash-key, hash-iv, payment-url, callback-url, return-url）
- [ ] `API_DOCS_ENABLED` 預設 `false`（prod 環境）
- [ ] 檢查失敗訊息不洩漏密鑰或 API key 值

### G4：CORS 來源

**需求**：`CORS_ALLOWED_ORIGINS` 必填；拒絕 `*` 和格式不當（路徑、通配符、非 http(s)）。

**審查項**：
- [ ] `application-prod.yml` 中 `cors.allowed-origins` 無預設值
- [ ] `CorsConfig` 讀取此設定並解析為陣列
- [ ] `ProductionConfigValidator` 驗證每一項都是純 origin（http/https + host，無通配符 `*`、無路徑、無查詢字串、無 fragment）
- [ ] 驗證邏輯覆蓋至少：`*`、`https://*.example.com`、`shop.example.com`（缺 scheme）、`https://shop.example.com/app`（含路徑）、`ftp://...` 都被拒
- [ ] 多來源逗號分隔時，各自獨立驗證

### G5：健康檢查

**需求**：健康檢查僅在獨立管理埠提供（不發布），`readiness` 檢查資料庫但**Redis 刻意不列入**。

**審查項**：
- [ ] `application-prod.yml` 中 `management.server.port` 預設 8081（dev 無此設定或 null，不限制）
- [ ] `management.endpoints.web.exposure.include` 只含 `health`
- [ ] `management.endpoint.health.show-details: never`（不含元件詳情）
- [ ] `management.health.redis.enabled: false`（Redis 不列為就緒條件）
- [ ] `management.endpoint.health.group.readiness` 只含 `readinessState,db`（不含 redis）
- [ ] `JwtAuthFilter.isPublicRoute()` 未改動，management endpoints 不走 filter（隱含它們不走 JWT）
- [ ] `OpenApiConfig` 未改動，不把 health endpoint 列入 API 文件

### 額外風險點

- [ ] `DB_PASSWORD` 無預設值（prod），且驗證拒絕空白與常見預設（123456, password, root, changeme）；測試含這些案例
- [ ] `REDIS_PASSWORD` 預設空白，但當 `stock.redis.enabled=true` 時驗證檢查非空
- [ ] `DB_USE_SSL` 雖可設定但預設仍是 false，文件有說明這僅適用同一私有網路部署
- [ ] 驗證訊息中**沒有任何密鑰、密碼、API key 的值**（即使截短或摘要）
- [ ] 通過驗證的應用啟動後，健康檢查是否真的走 management 埠而非主埠（測試涵蓋）
- [ ] 密鑰驗證不能被繞過（例如沒有 `prod` profile 運行時仍可啟動——這靠部署流程保證，不是 B1 責任，但可指出風險）

## 測試涵蓋的預期

（實作者報告已執行；審查者驗證測試邏輯是否合理，無需重跑）

- `ProductionConfigValidatorTest`（13 案）：缺密鑰、短密鑰、dev-only 密鑰、弱 DB 密碼、sandbox、ECPay 不完整、CORS 格式不當、多來源、全部違規不洩漏值、prod profile 下失敗、非 prod profile 下不檢查
- `CorsConfiguredOriginsTest`（2 案）：設定的來源被允許、dev 預設來源被拒
- `ProductionProfileIntegrationTest`（4 案，real MySQL）：health 在 management 埠、readiness = DB、health 不在主埠、API 文件 404
- `CorsOnAuthFailureTest`（5 案，來自上一次）：已 passing

## 審查輸出

發現任何以下情況皆判定 **FAIL**：

1. **啟動安全閘門被繞過**：例如 `ProductionConfigValidator` 只有 warn log 沒有拋 exception、或虛值就通過。
2. **敏感資訊洩漏**：密鑰、密碼、API key 出現在訊息、日誌、異常堆棧。
3. **health endpoint 仍在主埠可達**或在主埠仍需 JWT。
4. **CORS 驗證不完善**：通配符、路徑、非 http(s) 未被拒。
5. **redis enabled 但檢查未補**。
6. **測試邏輯有漏洞**（例如變異驗證時沒有真的拒絕）。

判定 **PASS** 當且僅當：
- 上述全部風險點皆已防守
- 測試邏輯合理且涵蓋足夠範圍
- 沒有密鑰洩漏的證據
- 已知風險（例如部署流程必須設 SPRING_PROFILES_ACTIVE=prod）有文件記錄

有「should-fix」的低風險項（例如註解補充）可報告但不阻斷；審查者建議，實作者決定採納。

## 後續（不在本審查範圍）

- B2（容器化與拓撲）：Flyway、frontend 映像、docker-compose.prod.yml、Caddy
- B3（部署管線）：GitHub Actions、backup、health gate、回滾
- C（獨立驗收 B1/B3 後的上線 smoke test）

---

**審查者簽署欄**（完成後補充）

開始時間：  
結論：[ ] PASS [ ] FAIL [ ] CONDITIONAL(described below)  
關鍵發現（若有）：  
完成時間：  
審查者：Codex
