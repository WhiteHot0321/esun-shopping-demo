# 088 — Spring Boot 3.5.16 → 4.x 遷移（A 段：定位，只讀；B 段：實作，見 §10）

Date: 2026-11-26 (Asia/Taipei)。Executor: Claude Code。基準：`origin/advanced-v2` @ `c32a123`（`git fetch` 後確認與本機 HEAD 相同）。
Scope：只讀調查。本檔以外沒有改任何檔案、沒有 commit。B 階段（實作）由 owner 確認後另開 session；C 階段由 Codex 獨立審查。

## 1. 任務與背景

| 項目 | 內容 |
|---|---|
| 目的 | 關掉 `.trivyignore.yaml` 的兩條 `spring-webmvc` CRITICAL 例外：CVE-2026-47884、CVE-2026-47890 |
| 為什麼只能遷移 | 兩者在 6.2.x 只有 Enterprise Support 版（6.2.20）；OSS 修補只在 Framework **7.0.9**（Spring 安全公告）。Boot 3.5.16 / Framework 6.2.19 已是 OSS 最新（task 083） |
| 期限 | 例外 **2026-12-14** 到期（距今 18 天）；Codex 審查與 CI 要在 12/10 前完成 |
| 附帶 | 同檔 CVE-2026-78661（Caddy）2026-11-30 到期，與本任務無關，已在 `chore/trivy-caddy-2026-11` 處理 |

兩條例外本身的可達性結論不變（無 view layer、無 SSE）；遷移是為了**讓 gate 不靠例外就綠**，不是因為有被利用的風險。
公告嚴重度：-47884 MEDIUM、-47890 LOW；Trivy 顯示 CRITICAL（推測來自第三方資料庫的 9.x 評分，未查證），兩邊不一致，以 gate 為準。

## 2. 方法與證據（A 段做了什麼）

| 證據 | 做法 | 結論 |
|---|---|---|
| BOM 實測 | 直接讀 Maven Central 的 `spring-boot-dependencies` 4.0.10 / 4.1.3 / 4.2.0 pom | 見 §3 表；4.1.3 → Framework **7.0.11**（≥ 7.0.9） |
| 相依樹探針 | 在 scratchpad（**不在 repo**）複製 `backend/pom.xml`，套用 §5.1 的 pom 改動，只跑 `mvn dependency:tree` | **解析成功**（exit 0）；證明 starter 名稱、版本、artifact id 都存在。**沒有編譯**，所以不代表程式碼能過 |
| 類別搬家實測 | 下載 4.1.3 / TC 2.0.5 / springdoc 3.2.1 / Jackson 3.1.8 的 jar，`unzip -l` 與 `javap` 找新位置 | §5 各表的「新位置」都是從 jar 讀出來的，不是記憶 |
| 官方文件 | Boot 4.0 Migration Guide、4.1/4.2 Release Notes、Spring 安全公告兩頁 | 缺的項目在 §8 明列「未確認」 |
| 本機盤點 | `grep` 實數，見各表 | 與提示詞數字一致：main 6 / test 22 Jackson 檔、14 個 Mock 註解檔、9 個 Testcontainers 檔 |

未做：沒跑 Trivy、沒編譯、沒跑測試、沒起容器（只讀任務）。

## 3. 問題 1：版本選擇

| | **4.1.3（建議）** | 4.2.0 | 4.0.10 |
|---|---|---|---|
| Spring Framework | **7.0.11**（BOM 實測） | 7.1.0 | 7.0.11 |
| 含 7.0.9 修補 | **確定**（同分支、更高 patch；公告：7.0.9 為 OSS 修補版） | 未確認：公告兩頁都沒提 7.1.x（沒列受影響也沒列已修）。7.1.0 晚於 7.0.9 發布，**很可能**含，但沒有文件證實 | 確定 |
| 成熟度 | 4.1.0 之後已有 4.1.1（08/20）、4.1.2（10/22）、4.1.3（11/19） | **GA 與 4.1.3 同日（11/19）**，零個 patch；遷移期間若踩到 .0 的 bug 沒有 .1 可升 | 最成熟 |
| Jackson | 3.1.8（+ jackson-2-bom 2.21.8） | 同左 | 同左 |
| Tomcat | 11.0.27 | 11.0.27（4.2 改 `server.tomcat.servlet.*`、`use-relative-redirects` 預設 true） | 11.0.27 |
| Flyway | 12.4.0 | 12.11.0 | 11.14.1 |
| Testcontainers / Mockito | 2.0.5 / 5.23.0 | 2.0.5 / 5.24.0 | 2.0.5 / 5.20.0 |
| Logback / Lettuce | 1.5.38 / 7.5.2 | **1.6.6** / 7.8.0（Logback 1.6 與 logstash encoder 8.0 的相容性未驗證） | 1.5.38 / 6.8.2 |
| 測試相關 | `TestRestTemplate` 正常（我們 4 個檔用到） | **`TestRestTemplate` 被標 deprecated**，改 `RestTestClient`；Framework 7.1 也棄用 `RestTemplate` | 同 4.1 |
| OSS 支援期 | **未找到**（spring.io 頁面沒有時間表） | 未找到 | 未找到；4.0.0 是最舊的 4.x，推測最早結束 |

**建議 4.1.3**，理由依序：
1. 修補是否包含——唯一「有文件可證」的選項（4.0.10 也是，但是最舊的一條線）。
2. 期限只剩 18 天，風險要往「已知」收斂：4.2.0 沒有 patch 歷史，且多帶 Flyway 12.11、Logback 1.6、Lettuce 7.8、TestRestTemplate 棄用四個額外變數，這些都與本次目的無關。
3. 生態相容性：springdoc 3.2.1、Testcontainers 2.0.5、Flyway 12.4.0 都在 4.1.3 解析成功（§2 探針）；4.2 的 Logback 1.6 對 logstash-logback-encoder 8.0 沒有證據。

代價（要明講）：4.1 線比 4.2 早出，OSS 結束日較早，**之後還要再升一次小版**。建議把「升 4.2.x（等 4.2.2 以後）並把 `TestRestTemplate` 換成 `RestTestClient`」列為 088 之後的獨立 follow-up，不併入本次。

## 4. 問題 2 總覽：breaking change 對照

下列每一項的「新位置 / 新名稱」均由 jar 或官方文件實測；「影響檔案」為 `grep` 實數。

### 4.1 pom 與版本覆寫

| 現況（`backend/pom.xml`） | Boot 4.1.3 下的處理 | 為什麼不能留 |
|---|---|---|
| parent `3.5.16` | → `4.1.3` | — |
| `<testcontainers.version>1.21.4</testcontainers.version>` | **刪除** | BOM 已管 2.0.5；留著會把 TC 降回 1.x，artifact id 也對不上 |
| `<tomcat.version>10.1.60</tomcat.version>` | **刪除** | Boot 4 = Tomcat 11（Servlet 6.1）；留著會降成 Tomcat 10，啟動即壞 |
| `<jackson-bom.version>2.21.7</jackson-bom.version>` | **刪除** | **屬性語意變了**：Boot 4 的 `jackson-bom.version` 是 **Jackson 3** BOM（3.1.8），Jackson 2 改叫 `jackson-2-bom.version`。留著會要求不存在的 `tools.jackson` 2.21.7 |
| `<netty.version>4.1.137.Final</netty.version>` | **刪除** | BOM 已是 4.2.20；Lettuce 7 走 Netty 4.2，降回 4.1 會壞 |
| `spring-boot-starter-web` | → `spring-boot-starter-webmvc`（舊名在 4.x 為 deprecated） | 官方 guide |
| `spring-boot-starter-aop` | → `spring-boot-starter-aspectj` | 官方 guide；探針確認會帶 `aspectjweaver` 1.9.27 |
| `flyway-mysql`（單獨） | **加** `spring-boot-starter-flyway`，保留 `flyway-mysql` | §4.4 |
| `spring-retry`（無版本） | 必須**明寫版本**（`2.0.13`） | Boot 4 不再管理 Spring Retry（guide）；§4.5 |
| springdoc `2.8.17` | → `3.2.1` | springdoc 3.x 才是 Boot 4 線（README） |
| `org.testcontainers:junit-jupiter` / `:mysql` | → `testcontainers-junit-jupiter` / `testcontainers-mysql` | TC 2.0 改名；探針解析成功 |
| 測試依賴 | **加** `spring-boot-starter-webmvc-test`（帶 `spring-boot-webmvc-test`、`spring-boot-resttestclient`）與 `spring-boot-starter-actuator-test`（帶 `spring-boot-micrometer-metrics-test`） | `@WebMvcTest`/`@AutoConfigureMockMvc`/`TestRestTemplate`/`@AutoConfigureMetrics` 都搬出 `spring-boot-starter-test` 了 |
| `micrometer-registry-prometheus`、`jjwt-*` 0.12.6、`logstash-logback-encoder` 8.0、`datasource-proxy` 1.10、`spring-security-crypto` | 不動（BOM 管理的由 BOM 給版本：security 7.1.3） | 探針解析成功 |

### 4.2 Jackson 3

規則（官方 + jar 實測）：套件 `com.fasterxml.jackson.*` → `tools.jackson.*`，**唯獨 annotations 留在 `com.fasterxml.jackson.annotation`**；`JsonProcessingException` 消失，改為**非受檢**的 `tools.jackson.core.JacksonException`；Boot 自動設定的是 `JsonMapper`（`ObjectMapper` 子類）。

| 檔案 | 影響 | 改動 |
|---|---|---|
| `dto/AuditLogEntry.java` | `JsonNode` | import → `tools.jackson.databind.JsonNode` |
| `repository/AuditLogRepository.java` | `ObjectMapper`、`JsonNode`、`JsonProcessingException` | import ×3；`catch` 改 `JacksonException` |
| `repository/DocEmbeddingRepository.java` | `ObjectMapper`、`JsonProcessingException` ×2 處 | 同上 |
| `service/AuditLogService.java` | `ObjectMapper`、`JsonProcessingException` | 同上 |
| `security/JwtAuthFilter.java` | `ObjectMapper.writeValueAsString`（寫 401/403 body） | import 一行 |
| `model/ProductReview.java` | `@JsonIgnore` | **不用改**（annotations 套件不變） |
| 測試 22 檔 | `ObjectMapper`/`JsonNode` import | 21 檔純換 import；`JsonNode.fields()` 已移除 → `properties()`（回 `Set<Map.Entry>`，不是 `Iterator`）：`OpenApiDocsTest` 4 處、`EcpayPaymentIntegrationTest` 1 處 |
| `asText()` ×92、`asLong/asInt/asBoolean` ×25 | `javap` 確認 `asText()` 仍在（final，標準做法是 `asString()`） | **不改**，避免擴大 diff；之後可批次換 |

重點判斷：

| 問題 | 回答 |
|---|---|
| jjwt-jackson 仍依賴 Jackson 2 怎麼處理？ | **照留**。jjwt-jackson 0.12.6 / 0.13.0（最新）都只吃 `com.fasterxml.jackson`，且內部自己 `new ObjectMapper()`，不經過 Spring 的 `JsonMapper`，所以兩套 Jackson 並存不衝突。Boot 4 BOM 另外管 `jackson-2-bom` **2.21.8**，探針確認 `jackson-databind:2.21.8` 被解析進來。`logstash-logback-encoder` 8.0 也是 Jackson 2，同一版本 |
| 會讓 Trivy 變紅嗎？ | **A 段沒有證據**（沒跑 Trivy）。已知：2.21.8 比現在 pom 手釘的 2.21.7 新；Jackson 3.1.8 也出現在樹中（Flyway 12、springdoc 3 帶入）。若 Trivy 對 2.21.8 或 3.1.8 報出「已修」的 HIGH，處理方式是釘 `<jackson-2-bom.version>`（**注意是新屬性名**）。要在 B-7 用 pinned Trivy 0.74.0 掃兩個映像才知道 |
| 行為風險 | Jackson 3 的預設值與 2 不同（`MapperFeature.SORT_PROPERTIES_ALPHABETICALLY` 存在，預設值未確認）；Boot 提供 `spring.jackson.use-jackson2-defaults` 當暫時橋。需要**用 JSON 回應差異檢查**證明前端契約沒變（§6 B-6） |
| `spring.jackson.time-zone: Asia/Taipei` | 屬性是否仍有效**未確認**；由整合測試中時間欄位的值驗證 |

### 4.3 Boot 4 模組化：套件搬家（皆為 jar 實測）

| 舊 | 新（4.1.3） | 檔案 |
|---|---|---|
| `org.springframework.boot.actuate.health.{Health,HealthIndicator,Status}` | `org.springframework.boot.health.contributor.*`（模組 `spring-boot-health`，已由 actuator starter 帶入） | main 3：`CachedHealthGauge`、`ExternalDependenciesHealthConfiguration`、`TelemetryMetricsConfiguration`；test 1：`CachedHealthGaugeTest` |
| `org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy` | `org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy`（`spring-boot-flyway`） | main 1：`FlywayAdoptionConfiguration` |
| `...test.autoconfigure.web.servlet.WebMvcTest` / `AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure.*` | `@WebMvcTest` 8 檔、`@AutoConfigureMockMvc` 17 檔（共 23 個測試檔用 MockMvc） |
| `...test.autoconfigure.actuate.observability.AutoConfigureObservability` | `...micrometer.metrics.test.autoconfigure.AutoConfigureMetrics` | 1：`ProductionProfileIntegrationTest` |
| `org.springframework.boot.test.web.client.TestRestTemplate` | `org.springframework.boot.resttestclient.TestRestTemplate`，且**每個類別要加 `@AutoConfigureTestRestTemplate`**（`@SpringBootTest` 不再自動提供） | 4：`OrderRetryTest`、`ProductionProfileIntegrationTest`、`ExternalDependenciesHealthIntegrationTest`、`RedisLiveOutageIntegrationTest` |
| 不變 | `SpringBootTest`、`LocalServerPort`、`LocalManagementPort`、`TestConfiguration`、`ApplicationContextRunner`、`OutputCaptureExtension`、`ConditionalOnProperty`、`ImportAutoConfiguration`、`FilterRegistrationBean`、`SpringBootApplication`、`ApplicationReadyEvent`（皆在 4.1.3 jar 中找到原套件） | — |

靜默失敗的特別注意：**漏加 `@AutoConfigureTestRestTemplate` 只會在執行期出 `NoSuchBeanDefinitionException`，編譯照過**——所以 B-3 一定要跑含這 4 個類別的測試，不能只看編譯。

`JwtAuthFilter` / `CorrelationIdFilter`（`OncePerRequestFilter`）、`CorsConfig`（`WebMvcConfigurer`、`CorsFilter`、`FilterRegistrationBean`）、`OllamaLlmClient` 與 `ExternalDependenciesHealthConfiguration` 的 `RestClient`/`SimpleClientHttpRequestFactory`：套件皆不變，A 段判斷不需改碼（以 `grep` 與 jar 位置判斷，**行為**要靠測試證明）。

### 4.4 Flyway：「少加 starter → 靜默不執行」要用什麼證明

先講一個**現況事實**，這會改變測試設計：

| 事實 | 來源 |
|---|---|
| 正式環境的 migration 由獨立容器 `flyway/flyway:11-alpine`（Flyway CLI）執行，後端 `FLYWAY_ENABLED=false` | `docker-compose.prod.yml` |
| 應用內 Flyway 只有 opt-in 路徑（`FLYWAY_ENABLED` 預設 false；`shop.flyway.adopt-existing`） | `application.yml`、task 053 |
| **`FlywayAdoptionIntegrationTest` 完全不啟動 Spring**：它用 `Flyway.configure()` 直接呼叫，再手動 `new FlywayAdoptionConfiguration().flywayMigrationStrategy(...)` | 已讀全檔 |

所以：(a) 這個測試過了**不能**證明 Boot 的 `FlywayAutoConfiguration` 有被啟用；(b) 真正會靜默壞掉的只有「應用內 opt-in 路徑」，正式環境的 CLI 容器不受影響；(c) 好消息是缺 `spring-boot-flyway` 時，`FlywayAdoptionConfiguration` 的 `FlywayMigrationStrategy` import **編譯就會失敗**（會大聲），真正的靜默風險是**以後有人改掉這支檔或只留 `flyway-core`**。

要新增的證明（B-4 的新測試，名稱建議 `FlywayAutoConfigurationWiringIntegrationTest`）：

| 斷言 | 為什麼 |
|---|---|
| `@SpringBootTest` + 空白 MySQL 容器 + `spring.flyway.enabled=true` + `stock.redis.enabled=false` | 走 Boot 自動設定，不手動呼叫 |
| `context.getBean(Flyway.class)` 存在 | 直接證明 auto-config 啟用 |
| 啟動後 `flyway_schema_history` 有 version 1、2 且 `success=1`，`SHOW CREATE`/欄位簽章與 `FlywayAdoptionIntegrationTest` 的 EMPTY 路徑一致 | 證明 migration 真的跑了，不是只有 bean 在 |
| 第二個 case：legacy initdb 容器 + `shop.flyway.adopt-existing=true`，**經由 Spring 路徑**完成 baseline | 證明 `FlywayMigrationStrategy` bean 被 Boot 4 的 auto-config 採用（不是被忽略） |
| **紅燈證明**（B 階段執行並記錄，不進 repo）：暫時移除 `spring-boot-starter-flyway`，此測試必須紅 | 「少加 starter 會被抓到」要親眼看過它變紅才算數 |

另：Flyway 12.4.0 是否仍支援 MySQL 8.0、與 compose CLI 的 11.x 版差，都要由 `FlywayAdoptionIntegrationTest`（真 `mysql:8.0`）驗證，見 §8。

### 4.5 Spring Retry / AOP（本次**最大風險**）

| 事實 | 來源 |
|---|---|
| `OrderService.createOrder` 用 `@Retryable(retryFor=…, maxAttemptsExpression=…, backoff=@Backoff(delay=50, multiplier=2, maxDelay=200, random=true))` + 4 個 `@Recover` + `RetrySynchronizationManager.getContext().getRetryCount()` 計數；`@EnableRetry` 在 `EsunShoppingApplication` | 已讀 |
| 這是死鎖重試的核心，並連動 metrics（`shop.orders.lock.retry`、`…exhausted`）與多組測試（`OrderRetryTest`、`RealDeadlockRetryIntegrationTest`、`Phase25K6DeadlockAcceptance`） | grep |
| Boot 4 **不再管理** spring-retry 版本；官方建議改用 Framework 7 內建的 `org.springframework.core.retry` | Migration Guide |
| `spring-retry` 最新為 **2.0.13**（2026-06-08），但它的 pom 對 Spring 是 **6.2.19**，與 Framework 7 的相容性**沒有文件** | Maven Central pom |
| Framework 7.0.11 內建 `org.springframework.resilience.annotation.{Retryable,EnableResilientMethods}` 與 `core.retry.{RetryTemplate,RetryPolicy}`（jar 實測）；**沒有 `@Recover`、沒有 `RetrySynchronizationManager`** | spring-context / spring-core 7.0.11 jar |

兩條路：

| | R1（B 階段採用） | R2（拆成獨立 follow-up） |
|---|---|---|
| 做法 | 明寫 `spring-retry:2.0.13` + `spring-boot-starter-aspectj`，**程式碼不動** | 換成 Framework 7 `@Retryable` + `@EnableResilientMethods`；`@Recover` 與重試次數改用手動 `RetryTemplate` 或 listener 重寫 |
| 風險 | 執行期相容性未知（編譯過不代表 AOP 攔截正確） | 語意不同（`maxRetries` 是「重試次數」而非「嘗試次數」、無 recover 分派）→ 要重新證明 metrics 與例外保留 |
| 判斷 | 先試；用 `OrderRetryTest` + `RealDeadlockRetryIntegrationTest` 證明 | R1 失敗且 12/10 前補不起來時，才是 plan B；否則留到 088 之後 |

**最重要的測試證明**：`OrderRetryTest` 與 `RealDeadlockRetryIntegrationTest` 必須在升級後仍綠，而且要確認「重試真的發生」（`shop.orders.lock.retry` 計數 > 0）——不能只看沒有例外。

### 4.6 Mockito 註解

`@MockBean`（9 檔 import）/ `@SpyBean`（5 檔 import）合計 14 檔、43 處：→ `org.springframework.test.context.bean.override.mockito.MockitoBean` / `MockitoSpyBean`（Framework 6.2+ 的 `spring-test`，4.x 移除舊註解）。

| 注意 | 說明 |
|---|---|
| 不能放在 `@Configuration` 類別 | 官方 guide；用 class-level `@MockitoBean(types=…)` 或自訂註解。本專案是欄位上用，預期不受影響，但 `OpenApiDocsTest`（15 處）要逐一看 |
| 受影響檔案 | `CorsConfiguredOriginsTest`、`AdminProductControllerTest`、`OrderControllerTest`、`OrderControllerValidationTest`、`OpenApiDocsDisabledTest`、`OpenApiDocsTest`、`Phase25K6DeadlockAcceptance`、`ProductReviewAuditConcurrencyIntegrationTest`、`RealDeadlockRetryIntegrationTest`、`RecommendationIntegrationTest`、`RedisUnavailableOrderIntegrationTest`、`CorsOnAuthFailureTest`、`JwtAuthFilterTest`、`OrderRetryTest` |
| 隱性風險 | `@MockitoBean` 對 context cache 的行為與 `@MockBean` 不同，可能讓整合測試的 context 重用率變化 → 全套測試時間（基準 6 分 29 秒）要記錄 |

### 4.7 Testcontainers 2.0（含 task 041 的 Docker 29 相容性）

| 項目 | 結論（jar 實測） |
|---|---|
| artifact id | `mysql` → `testcontainers-mysql`；`junit-jupiter` → `testcontainers-junit-jupiter` |
| `MySQLContainer` | **舊的 `org.testcontainers.containers.MySQLContainer<?>` 在 2.0.5 jar 仍存在**，所以 2 個檔（`AbstractMySqlIntegrationTest`、`FlywayAdoptionIntegrationTest`）可以先不改碼；新類是非泛型的 `org.testcontainers.mysql.MySQLContainer`。建議 B 階段**先不換**，避免與本次目的無關的改動 |
| 不變 | `GenericContainer`（7 檔）、`DockerImageName`（8）、`MountableFile`（2）、`@Testcontainers`/`@Container`（1） |
| 041 的 override | `testcontainers.version=1.21.4` 要刪（§4.1）。041 的根因是舊版 docker-java 3.3.6 對 Docker Engine 29 回 HTTP 400；TC 2.0.5 帶 docker-java **3.7.1**（pom 實測），理論上比 1.21.4 的 3.4.2 新，但**未在這台機器驗證** |
| 驗證方式 | 照 041 的做法：先單跑 `mvn '-Dtest=OrderServiceIntegrationTest' '-Djacoco.skip=true' test`（041 當時 4/4、50 秒），再跑全套 |
| 單例容器模式 | `AbstractMySqlIntegrationTest` 靠 static initializer + Ryuk；TC 2.0 行為變化未確認，由全套測試證明 |

### 4.8 springdoc 3.x

| 項目 | 結論 |
|---|---|
| 版本 | `3.2.1`（2026-11-20），Maven Central 最新；3.x 為 Boot 4 線 |
| 套件 | `org.springdoc.core.*`、`org.springdoc.webmvc.*` 沿用；`OpenApiConfig` 與 12 個 Controller 用到的 `io.swagger.v3.oas.models.*`、`io.swagger.v3.oas.annotations.*` 在 swagger-core **3.0.0** jar 中位置不變（11 個類別實測全在） |
| 內部 Jackson | springdoc 3.2.1 改用 `tools.jackson.dataformat:jackson-dataformat-yaml`（Jackson 3） |
| 風險 | `OpenApiDocsTest` 以 `@ImportAutoConfiguration` **手動列出 7 個 springdoc 類別**（`SpringDocConfiguration` 等，jar 中都存在）——在 Boot 4 的 `@WebMvcTest` 切片下是否仍能組起來、swagger-core 3.0.0 的輸出格式是否變（其測試以 `JsonNode.fields()` 走訪，需改 `properties()`）要實跑；這是 15 個 `@MockBean` 的集中處，建議第一個修 |
| `springdoc.*` 設定 | `application.yml` 的 `api-docs.enabled`、`swagger-ui.enabled`、sorter 屬性是否仍同名：未確認，由 `OpenApiDocsTest` / `OpenApiDocsDisabledTest` 驗證 |

### 4.9 TestRestTemplate 與 MockMvc

| | 影響 | 處理 |
|---|---|---|
| MockMvc | `@SpringBootTest` **不再自動提供 MockMvc**（guide）；本專案 14 個 `@SpringBootTest(MOCK)` 已有 `@AutoConfigureMockMvc`，只需換 import；`@AutoConfigureMockMvc(addFilters=false)` ×3 同 | 換 import；`spring-boot-starter-webmvc-test` |
| TestRestTemplate | 見 §4.3：換 import + 加 `@AutoConfigureTestRestTemplate`，4 檔 | 不換成 `RestTestClient`（留給 4.2 follow-up） |

### 4.10 其他

| 項目 | 判斷 |
|---|---|
| Java 17 | Boot 4 / Framework 7 基準仍 Java 17，`eclipse-temurin:17-jre` 映像與 CI `java-version: 17` 不用動 |
| Tomcat 11（Servlet 6.1 / Jakarta EE 11） | 無 war、無自訂 Tomcat 設定；`server.*` 屬性無自訂 |
| `management.*` 設定 | `percentiles-histogram`、`health.group.*`、`validate-group-membership`、`server.address/port` 是否同名未確認；`ProductionProfileIntegrationTest`、`ExternalDependenciesHealthIntegrationTest` 驗證 |
| `logstash-logback-encoder` 8.0（prod JSON log） | 仍為 Jackson 2、Logback 1.5.38，應相容；**但沒有任何測試覆蓋 `logback-spring.xml` 的 JSON 輸出**（grep 全測試無命中）→ 缺口，見 §6 B-7 |
| `spring-security-crypto` | 7.1.3；只用 `BCryptPasswordEncoder`（`AuthService` 1 處），套件不變 |
| Hibernate Validator 9.1.6 | 只用標準 `@NotNull`/`@Valid` 系列；低風險，未逐一驗 |
| DB migration SQL / `DB/*.sql` / Redis Lua | 不受影響 |

## 5. 問題 3：可獨立驗證的步驟順序

分支：由 `advanced-v2` 開新分支（如 `chore/spring-boot-4`），單一寫入者。每一步都要 commit 前綠燈；`-Djacoco.skip=true` 只用於中間步驟，**最後一步必須不跳過**。

| 步驟 | 內容 | 要跑的驗證 | 通過標準 |
|---|---|---|---|
| **B-0** | 開分支；沿用 083 基準：`mvn -B clean test` = **322 tests / 0 fail / 6 分 29 秒** | 無（引用 083；若 base 有變動才重跑） | 基準數字記下來 |
| **B-1** | 只改 pom（§4.1 全部）。先**不**碰程式碼 | `mvn -B dependency:tree` 與 `mvn -B -DskipTests compile` | 相依解析成功；編譯錯誤**只**出現在 §4.2/4.3 列出的 main 檔（5+3+1），沒有意外檔案 |
| **B-2** | main 程式碼：Jackson 5 檔、health 3 檔、Flyway 1 檔 | `mvn -B -DskipTests package`（含 repackage） | 編譯綠、可執行 jar 產出 |
| **B-3** | test 程式碼：Jackson import、`fields()`→`properties()`、`@MockitoBean/@MockitoSpyBean`、`@WebMvcTest/@AutoConfigureMockMvc` import、`TestRestTemplate` + `@AutoConfigureTestRestTemplate`、`@AutoConfigureMetrics`、`CachedHealthGaugeTest` | `mvn -B test-compile`；再跑**不需容器**的切片：`'-Dtest=OpenApiDocsTest,OpenApiDocsDisabledTest,JwtAuthFilterTest,CorsOnAuthFailureTest,CorsConfiguredOriginsTest,OrderControllerTest,OrderControllerValidationTest,AdminProductControllerTest,AuditLogServiceTest,CachedHealthGaugeTest,ProductionConfigValidatorTest' '-Djacoco.skip=true' test` | 全綠；`OpenApiDocsTest` 優先處理 |
| **B-4** | 需 Docker 的核心驗證：<br>① 041 煙霧測試 `OrderServiceIntegrationTest`<br>② `FlywayAdoptionIntegrationTest`<br>③ **新增** `FlywayAutoConfigurationWiringIntegrationTest`（§4.4，含紅燈證明）<br>④ `ProductionProfileIntegrationTest`<br>⑤ `ExternalDependenciesHealthIntegrationTest` | 各以 `-Dtest=… -Djacoco.skip=true` 單跑 | ① 4/4 且無 Docker 29 初始化錯誤 ② 通過 ③ 通過，且移除 starter 後變紅 ④⑤ 通過 |
| **B-5** | Retry：`OrderRetryTest`、`RealDeadlockRetryIntegrationTest`、`RedisUnavailableOrderIntegrationTest`（連動 retry + Redis 降級） | 單跑 | 全綠，且重試計數 metrics 斷言通過（不是「沒丟例外」就算過） |
| **B-6** | 全套 + **JSON 契約差異檢查** | `mvn -B clean test`（不跳過 JaCoCo）；另在 3.5.16 與新分支各起一次，對 6–8 個代表性 GET/POST（商品列表、登入、訂單建立、稽核 log、時間欄位）存回應 body，`jq -S` 後 diff | 322+ tests 全綠、JaCoCo `check-core-services` 通過；JSON 只允許欄位**順序**差異，值/格式/null 處理需一致 |
| **B-7** | 映像與供應鏈：<br>① 兩個 `docker build --pull`<br>② 用 `aquasec/trivy:0.74.0`、**CI 一樣的旗標**、**空的 ignore 檔（或只含 Caddy 那條）**掃<br>③ 刪 `.trivyignore.yaml` 兩條 Spring 例外並更新檔頭註解<br>④ `bash scripts/check-trivy-expiry.sh`<br>⑤ `docker-compose.prod.yml` 本機起整套（隨機臨時密碼，比照 083）看 `/actuator/health/readiness`、Caddy 代理 `/api/products/available`<br>⑥ 補一個最小的 `logback-spring.xml` JSON 輸出測試（prod profile 一行 JSON 含 `message`/`level`） | 見左 | 兩映像 exit 0；若 Jackson/其他有已修 HIGH，依 §4.2 釘 `jackson-2-bom.version` 後重掃 |
| **B-8** | 前端不受影響確認：`npm test`（Vitest）、`vite build`（前端原始碼零改動，預期不變） | `cd frontend && npm test` | **8 files / 91 tests** 通過（083 基準） |
| **B-9** | 文件：本檔 B 段結果、`docs/project-state.md`、`.trivyignore.yaml` 檔頭；開 PR | CI 全綠（含 `supply-chain-gate`、`docker-build`） | 六個 check 全綠 |
| **C** | Codex 獨立審查（見 AGENTS.md：critical 變更需獨立審查） | 審 diff、§4.4 紅燈證據、Trivy 輸出 | 無 blocking finding |

## 6. 問題 4：驗收條件

| # | 條件 | 證據形式 |
|---|---|---|
| 1 | 完整 `mvn -B clean test` 全綠（0 fail / 0 error / 0 skip，數量 ≥ 322 + 新增），且 JaCoCo `check-core-services`（`OrderService`、`OrderTransactionService`、`StockCacheService`、`ProductService` 行覆蓋 ≥ 80%）通過 | Surefire 摘要 + JaCoCo check 輸出 |
| 2 | Vitest 不受影響：8 files / 91 tests；`vite build` 成功；前端目錄 `git diff` 為空 | 輸出 + diff |
| 3 | 兩個映像（backend、frontend）過 Trivy 0.74.0（CI 旗標）exit 0 | 掃描輸出；後端**不靠** Spring 例外 |
| 4 | `.trivyignore.yaml` 刪除 CVE-2026-47884、CVE-2026-47890 兩條；其餘條目與檔頭仍合法；`check-trivy-expiry.sh` exit 0 | diff + 腳本輸出 |
| 5 | **負向證明**：把那兩條放回去 → 仍綠（證明不是靠例外）；而把 pom 改回 3.5.16 的映像用空 ignore 掃 → 仍是紅（證明 gate 有效），比照 083 的負向測試 | 兩次掃描輸出 |
| 6 | `FlywayAdoptionIntegrationTest` 通過 | Surefire |
| 7 | `ProductionProfileIntegrationTest` 通過（含 Prometheus scrape、管理埠隔離） | Surefire |
| 8 | 新增 Flyway 接線測試通過 + 移除 starter 時為紅的紀錄（§4.4） | 測試 + 紅燈輸出 |
| 9 | `OrderRetryTest`、`RealDeadlockRetryIntegrationTest` 通過，且重試計數斷言成立（§4.5） | Surefire |
| 10 | JSON 回應與 3.5.16 在代表性端點一致（§5 B-6） | diff 輸出 |
| 11 | `docker-compose.prod.yml` 本機整套起得來（readiness UP、代理 200）；之後清掉 | 指令輸出 |
| 12 | 單一寫入者、PR CI 六個 check 全綠；Codex C 階段無 blocking finding | PR / 審查報告 |

## 7. 問題 5：風險分級、工時、12/10 檢查點

### 7.1 風險分級

| 等級 | 項目 | 原因 |
|---|---|---|
| **高** | Spring Retry 與 Framework 7 的執行期相容（§4.5） | 與死鎖重試/metrics 綁定；2.0.13 針對 Framework 6.2.19 編譯，無相容文件；失敗時 R2 需重寫 `@Recover` 語意 |
| **高** | 版本覆寫沒刪乾淨（`jackson-bom.version`、`tomcat.version`） | 屬性語意改變，最容易造成「看起來解析成功、實際降級」；已在 §4.1 單獨列出 |
| **中** | Jackson 3 預設值差異 → API 回應變動 | 前端契約風險；無自動化 JSON 契約測試，靠 B-6 手動差異 |
| **中** | Trivy 對 Jackson 2.21.8 / 3.1.8 / swagger-core 3.0.0 等新樹的結果未知 | 可能出現新的已修 HIGH，要多一輪釘版 |
| **中** | `OpenApiDocsTest` 的手動 springdoc 自動設定在新切片下組不起來 | 15 個 Mock 註解集中處 |
| **中** | Testcontainers 2.0 + Docker 29 + singleton 容器模式 | 041 的前車之鑑；需要實機證明 |
| **低** | 純機械改動：import 搬家（health/flyway/MockMvc/Mockito）、starter 改名 | 編譯器會大聲報錯；`TestRestTemplate` 漏註解是例外（執行期才錯），已在 B-3 列為必跑 |
| **低** | 前端、DB script、Lua、Dockerfile | 不動 |

### 7.2 B 階段工時估計（單人、本機 Docker 可用、全套 6.5 分鐘/次）

| 工作 | 樂觀 | 預期 | 悲觀（R1 失敗） |
|---|---|---|---|
| B-1 pom 與解析 | 1h | 1.5h | 2h |
| B-2 main 改碼 | 1h | 1.5h | 2h |
| B-3 test 改碼（62 個測試檔，其中 ~40 檔機械換 import） | 2h | 3.5h | 5h |
| B-4 容器測試 + 新 Flyway 測試 + 紅燈證明 | 2h | 3h | 4h |
| B-5 Retry 驗證 | 0.5h | 1.5h | **+8–12h（R2 重寫與重證）** |
| B-6 全套 + JSON 差異 | 1.5h | 3h | 5h |
| B-7 映像 + Trivy + compose 冒煙 + JSON log 測試 | 2h | 3h | 5h |
| B-8/B-9 前端確認、文件、PR、CI 往返 | 1.5h | 2.5h | 4h |
| **合計** | **~12h** | **~19.5h（約 2.5–3 個工作天）** | **~27h（R1 成功）／~37–39h（R2）** |

### 7.3 時程與 12/10 檢查點（今天 11/26，例外 12/14 到期）

| 日期 | 里程碑 | 判斷 |
|---|---|---|
| 11/28 前 | owner 確認版本（4.1.3）與 R1 策略，開 B 階段 | 沒確認就會吃掉緩衝 |
| 12/01 | B-1～B-3 完成（編譯綠、切片測試綠） | 落後 → 預期工時偏悲觀 |
| 12/05 | B-4～B-6 完成（全套綠、Flyway/Retry 證明齊） | **若 B-5 已證實 R1 失敗** → 立刻決定 R2 或延期，不要等到 12/10 |
| 12/08 | B-7～B-9 完成、PR 開出、CI 跑過一輪 | — |
| **12/10 go / no-go** | 判準見下 | 預留 12/11–12/14 給 Codex 審查回補與 CI |

**12/10 判準（全部成立才 go）：**

| # | 條件 |
|---|---|
| 1 | PR 的 CI 六個 check 全綠，包含 `supply-chain-gate` 與 `docker-build`，且 `.trivyignore.yaml` 已**刪掉**兩條 Spring 例外 |
| 2 | §6 的 1、3、6–10 條各有證據 |
| 3 | Codex C 階段已完成且沒有 blocking finding（若 12/10 才開始審，視為不成立） |
| 4 | 沒有「已知但未處理」的 Retry / JSON 回應差異 |

任何一項不成立 → **no-go**：**不要把半成品合進 `advanced-v2`**；改走第三次例外續期（沿用 083 的格式：可達性重驗、新到期日**不超過 4 週**即 2027-01-11 前、statement 寫明遷移分支與進度、owner 重新決定），遷移繼續在分支上做。no-go 的決定權在 owner，不是 B 階段的 session。

## 8. 問題 6：未確認事項清單

| # | 未確認 | 影響 | 怎麼確認 |
|---|---|---|---|
| 1 | Framework **7.1.0 是否含 7.0.9 的修補碼**（公告只列到 7.0.x，沒提 7.1） | 只影響「選 4.2.0」；選 4.1.3 無關 | 看 7.1.0 release notes / commit；選 4.1.3 就不用 |
| 2 | Boot 4.0 / 4.1 / 4.2 的 **OSS 支援結束日**（spring.io 頁面無時間表，我只讀到 enterprise 連結，未再追） | 決定 4.1 線要撐多久 | 查官方 support policy 頁 |
| 3 | `spring-retry` **2.0.13 在 Framework 7.0.11 的執行期相容性** | 高風險（§4.5） | B-5 |
| 4 | **Trivy** 對新相依樹的結果（Jackson 2.21.8、3.1.8、swagger-core 3.0.0、Tomcat 11.0.27…）。A 段沒跑 Trivy | 可能多出新的 HIGH/CRITICAL | B-7 |
| 5 | Jackson 3 **預設序列化行為**與 `spring.jackson.time-zone` 是否沿用 | API 契約 | B-6 JSON 差異 |
| 6 | `ObjectMapper`（`tools.jackson.databind`）按型別注入 Boot 的 `JsonMapper` 是否在所有場景都成立（含 `@WebMvcTest`） | JwtAuthFilter / 3 個 repository/service 建構式注入 | B-3 |
| 7 | **Flyway 12.4.0 對 MySQL 8.0** 的支援與 `FlywayAdoptionIntegrationTest` 行為；compose 的 `flyway/flyway:11-alpine` 與應用內 12.4.0 的版本差 | Flyway 路徑 | B-4 ②；版本差是否要對齊另議 |
| 8 | **Docker 29 + Testcontainers 2.0.5（docker-java 3.7.1）**，以及 singleton 容器模式 | 041 的舊問題重演 | B-4 ① |
| 9 | springdoc 3.2.1 + swagger-core 3.0.0 在 Boot 4.1.3 `@WebMvcTest` 切片下，`OpenApiDocsTest` 是否能組起來；`springdoc.*` 屬性是否同名 | 該測試 + 文件端點 | B-3 |
| 10 | `management.*` 各屬性在 Boot 4 是否仍同名（`validate-group-membership`、`health.redis.enabled`、`percentiles-histogram`…） | 健康/指標設定 | B-4 ④⑤ |
| 11 | `@LocalServerPort`/`@LocalManagementPort` 在 4.1.3 仍在原套件（jar 中找到，是否已 deprecated 未確認） | 編譯警告 | B-3 |
| 12 | `logstash-logback-encoder` 8.0（Jackson 2）在 prod JSON log 實際輸出；目前**沒有測試**覆蓋 | 無聲壞掉的風險 | B-7 ⑥ 新增測試 |
| 13 | 4.0 migration guide 對 `JsonProcessingException`、`@WebMvcTest` 搬移、`AutoConfigureObservability`、Testcontainers 都**沒寫**；我是由 jar 與 `javap` 得到答案，不是官方文字 | 本檔 §4.2/4.3 的來源強度 | 這些項目以編譯器為最終裁判 |
| 14 | 4.1 release notes 列的 Framework 版本與 BOM 不一致（頁面連結寫 7.0.8，BOM 實測 7.0.11） | 無，採 BOM 實測值 | — |
| 15 | `jjwt-jackson` 0.13.0 是否改善（最新版仍是 Jackson 2）；jjwt 何時支援 Jackson 3 | 不影響本任務 | 之後再看 |

## 9. 交接

- 本檔是 A 段全部產出；**程式碼、pom、`.trivyignore.yaml`、`docs/project-state.md` 均未改**，未 commit。
- 需要 owner 決定三件事再開 B：（1）版本 4.1.3 還是 4.2.0；（2）Retry 走 R1（先試、不改碼）或直接 R2；（3）12/10 no-go 時是否接受第三次例外續期。
- 暫存的探針 pom 與 jar 放在 session scratchpad，不在 repo，不需清理。

## 10. B 段結果（實作）

Date: 2026-11-26 (Asia/Taipei)。Executor: Claude Code（單一寫入者）。Branch: `claude/spring-boot-4-4d89e7`（自 `advanced-v2` @ `c32a123`）。
**不 merge、未動 `advanced-v2`**；C 階段（Codex 獨立審查）另開。已決定項目全照辦：Boot **4.1.3**（Framework 7.0.11）、Retry **R1**（`spring-retry:2.0.13` + `spring-boot-starter-aspectj`，`OrderService` 零改動）、`MySQLContainer` 不換類、`TestRestTemplate` 不換 `RestTestClient`、`asText()` 不動。

### Problem

兩條 `spring-webmvc` CRITICAL 例外（CVE-2026-47884、-47890）2026-12-14 到期，OSS 修補只在 Framework 7.0.9，只能遷到 Boot 4。

### Solution

| 步驟 | 內容 |
|---|---|
| B-1/B-2 | pom：parent 4.1.3；刪四個版本覆寫（`testcontainers.version`、`tomcat.version`、`jackson-bom.version`、`netty.version`）；starter 改名（`webmvc`、`aspectj`）、加 `spring-boot-starter-flyway`；`spring-retry` 明寫 2.0.13；springdoc 3.2.1；TC 2.x artifact id；加 `webmvc-test`、`actuator-test` 測試 starter。main：Jackson 3 import（5 檔，`JsonProcessingException`→`JacksonException`）、`health.contributor`（3 檔）、`flyway.autoconfigure`（1 檔）。**B-1 編譯錯誤只出現在文件預測的 main 檔（Flyway 1、health 3），沒有意外檔案** |
| B-3 | 測試：import 搬家、`JsonNode.fields()`→`properties()`（`OpenApiDocsTest` 4 處、`EcpayPaymentIntegrationTest` 1 處）、`@MockitoBean/@MockitoSpyBean`、`@AutoConfigureMetrics`、4 個含 `TestRestTemplate` 的檔案（`ExternalDependenciesHealthIntegrationTest` 內共 3 個類別）加 `@AutoConfigureTestRestTemplate` |
| B-4 | 新增 `FlywayAutoConfigurationWiringIntegrationTest`（同檔 2 個類別各 1 case）；補測試依賴 `spring-boot-starter-restclient`（見下表） |
| B-7 | 新增 `ProdJsonLoggingTest`（`logback-spring.xml` 原本零覆蓋）；`.trivyignore.yaml` 刪兩條 Spring 例外並更新檔頭 |

### 與 A 段預測的差異（執行時才發現）

| 項目 | 發現 | 處理 |
|---|---|---|
| `TestRestTemplate` 還缺 `spring-boot-restclient` | 加了 `@AutoConfigureTestRestTemplate` 後，執行期 `NoClassDefFoundError: org.springframework.boot.restclient.RestTemplateBuilder`（15 個測試紅，編譯不報）。A 段只列了 `spring-boot-resttestclient` | pom 加 test-scope `spring-boot-starter-restclient`；重跑 4 個類別全綠 |
| `mvn package` 會編譯測試 | `-DskipTests` 不跳過 test-compile，B-2 的驗證改用 `-Dmaven.test.skip=true`（Dockerfile 的 `-DskipTests clean package` 在測試可編譯後不受影響） | — |
| JSON log 測試不能起 Spring context | 第一版用 `@SpringBootTest(profile=prod)`：單跑綠，**全套中紅**（先前快取的 context 仍擁有 JVM 的 logging system，Boot 不會重新套用 `logback-spring.xml`）。改為把真正的 `logback-spring.xml` 套到私有 `LoggerContext`（`SpringBootJoranConfigurator` 是 package-private，用反射建構） | 全套 341/341 綠；測試不依賴 JVM 全域狀態 |
| 我自己的兩次失誤（已修，記錄以免重犯） | ① 第一次改 `.trivyignore.yaml` 時 `index('vulnerabilities:')` 先命中註解範例，造成重複 key，Trivy 報 `mapping key "vulnerabilities" already defined`（當時 scan exit 1 的真因，不是漏洞）；還原後重做，下表的 scan 都是修正版。② 第一版 JSON 契約腳本的訂單請求 `requestId` 不是 UUID、帶了無效 `payStatus`，訂單 400；修正後才真正走到 200 路徑 | — |

### Verification（本機，Docker 29.8.0 / Maven 3.9.14 / 本機 JDK 21，映像內為 JDK 17）

| # | 驗收（§6） | 結果 |
|---|---|---|
| 1 | 全套 `mvn -B clean test`，不跳 JaCoCo | **341 tests / 0 fail / 0 error / 0 skip，6 分 00 秒**；`All coverage checks have been met`（`check-core-services`）。基準 083 是 322 / 6:29；**322 已過時**（`c32a123` 起已有更多測試；341 − 本分支新增 3 = 338 為推算，**未在 base 重跑**）。時間沒有變慢 |
| 2 | 前端不受影響 | Vitest **8 files / 91 tests**；`vite build` 成功；`frontend/` 無 diff |
| 3 | 兩映像過 Trivy 0.74.0（`--ignore-unfixed --severity HIGH,CRITICAL --exit-code 1`） | 後端 **空 ignore 檔 exit 0**（ubuntu 0、`app/app.jar` 0、`usr/bin/pebble` 0）；前端用 committed ignore exit 0（唯一發現是 Caddy CVE-2026-78661，空 ignore 時 exit 1）。方法：`docker build --pull` → `docker save` → `aquasec/trivy:0.74.0 image --input` |
| 4 | `.trivyignore.yaml` 刪兩條、`check-trivy-expiry.sh` exit 0 | 已刪；剩 CVE-2026-78661（Caddy，2026-11-30，非本任務）；腳本 exit 0、`trivy-expiry.test.sh` 29/29；repo `trivy fs`（CI 旗標）exit 0 |
| 5 | 負向證明 | ① **3.5.16 映像（`c32a123` 的 `backend/`）+ 空 ignore → exit 1**，列出 CVE-2026-47884、-47890（spring-webmvc 6.2.19，fixed 7.0.9）；② 同一映像 + 舊 ignore → exit 0（例外原本是 gate 變綠的唯一原因）；③ 新映像 + 還原含兩條的舊 ignore → exit 0（新映像不靠例外） |
| 6 | `FlywayAdoptionIntegrationTest` | 通過（真 `mysql:8.0`、Flyway 12.4.0；74 s） |
| 7 | `ProductionProfileIntegrationTest` | 6/6，含 Prometheus scrape、管理埠隔離 |
| 8 | Flyway 接線測試 + 紅燈 | 綠燈 2/2。**紅燈 ①**（照 §4.4 字面：移除 `spring-boot-starter-flyway`）：乾淨編譯直接失敗（`FlywayAdoptionConfiguration` 的 `org.springframework.boot.flyway.autoconfigure` 不存在）；增量編譯時兩個新測試都紅（`NoClassDefFoundError: ...FlywayMigrationStrategy`）。**紅燈 ②**（模擬真正靜默：starter 在，`-DargLine=-Dspring.autoconfigure.exclude=...FlywayAutoConfiguration`）：兩個測試都紅（空庫 case 在 `getBeansOfType(Flyway)` 斷言失敗；legacy case `flyway_schema_history` 不存在 → `BadSqlGrammar`）。pom 事後已還原（`git diff` 驗證） |
| 9 | Retry | `OrderRetryTest` 6/6、`RealDeadlockRetryIntegrationTest` 1/1（20 個併發呼叫，真的死鎖）、`RedisUnavailableOrderIntegrationTest` 1/1。**R1 成立**：`getRetryCount()` 差值 = 2、`shop.orders.lock.retry` 差值 = 2.0、`...exhausted` 在耗盡情境 = 1.0、不可重試例外只計 1 次且保留原例外。**沒有走 R2** |
| 10 | JSON 契約差異 | 3.5.16（`c32a123` 的 jar）與 4.1.3 各起一次（各自乾淨的 `mysql:8.0` + `backend/DB` initdb，Redis 關閉），25 個請求，`sort_keys` 後 diff：**狀態碼與 body 一致**。只遮蔽 token、訂單編號、時間戳的數字內容（保留時間戳格式形狀；小數秒位數也遮蔽，因為一次 `.99` vs `.999` 是尾端 0 被省略的取樣差異，不是版本差異） |
| 11 | `docker-compose.prod.yml` 整套本機起（隨機臨時密碼、`127.0.0.1:18088`、專案名 `b088smoke`） | `migrate` exit 0；backend healthy；管理埠 readiness `{"status":"UP"}`；Prometheus scrape 正常；Caddy 代理 `/api/products/available` 200；`/`=SPA；`/actuator/health` 經 edge 回 `text/html`（不是 actuator）；`/api/v3/api-docs` 401；backend 日誌為一行一個 JSON，OpenJDK 17.0.21。已 `down -v`，scratch 檔刪除 |
| 12 | PR CI 六個 check、Codex C 階段 | **待 CI／C 階段（見 Next step）** |

JSON 契約請求清單（皆 old==new）：商品列表、評論（空／有資料）、register、login（成功／錯誤 401）、profile、address 建立／列表、訂單建立（200）／驗證失敗（400）／列表／詳情／不存在（404）／無 token（401）、評論建立（201）、cart、malformed JSON（400）、admin login、admin 建商品與補貨、**audit-logs（含 `before`/`after` JSON 欄位，走 Jackson 3 的 repository 路徑）**、admin products／members／orders。**未涵蓋**：`/v3/api-docs` 輸出（springdoc 3 / swagger-core 3.0.0 的 spec 預期有差異，只由 `OpenApiDocsTest` 驗結構）、付款流程、優惠券、Redis 路徑、`spring.jackson.time-zone` 只間接由 `createdAt` 的格式與值驗證。

§8 結案：#3 Retry 執行期相容、#4 Trivy、#5 Jackson 預設、#6 `ObjectMapper` 注入（`@WebMvcTest` 切片 67 tests 綠）、#7 Flyway 12.4.0 + MySQL 8.0、#8 Docker 29.8.0 + TC 2.0.5（`OrderServiceIntegrationTest` 4/4，40 s，無初始化錯誤）、#9 `OpenApiDocsTest` 5/5 與 `OpenApiDocsDisabledTest` 1/1、#10 `management.*` 屬性、#12 prod JSON log 均已有實測。**仍未驗證**：#2（OSS 支援結束日）、#11（`@LocalServerPort` 是否 deprecated）、#15。

### Trade-offs

- `jjwt-jackson` 0.12.6 與 `logstash-logback-encoder` 8.0 仍是 Jackson 2；樹中同時有 Jackson 2.21.8 與 3.1.8（Trivy 對兩者皆 0 發現）。之後 jjwt 支援 Jackson 3 再收斂。
- 本分支沒有升到 4.2.x；4.1 線 OSS 結束日未查到，**之後仍要再升一次小版**（follow-up：4.2.x + `RestTestClient`）。
- `ProdJsonLoggingTest` 用反射建構 package-private 的 `SpringBootJoranConfigurator`；Boot 之後改名會在該測試執行時大聲失敗（不是靜默）。
- Caddy CVE-2026-78661 的例外屬於 `chore/trivy-caddy-2026-11`，本分支不處理（11/30 到期）。
- 本機用 JDK 21 跑測試套件，映像與 CI 為 JDK 17；容器內 17.0.21 已實跑，但**測試套件本身沒有在 JDK 17 跑過**，由 CI 補。

### Next step

1. 開 PR、讓 CI 跑（六個 check）；**不 merge**。
2. C 階段：Codex 獨立審查（diff、§4.4 兩種紅燈輸出、Trivy 三組輸出、JSON 契約 diff 方法與其未涵蓋範圍）。
3. 12/10 go/no-go 仍按 §7.3 判準，決定權在 owner。
