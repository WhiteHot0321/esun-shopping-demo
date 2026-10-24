# 075 — Phase 3.3.#18 B3 部署管線提示詞（A 定位 / B 實作 / B' VM 實測 / C 驗收）

依 `prompt-scope-control` 拆成獨立階段，每段開獨立 session，一次只跑一個重階段。風險：**Critical**（部署、密鑰、回滾），B' 之後必須走 C 獨立審查。

## 背景（已驗證，2026-10-22）

- 已有：`docker-compose.prod.yml`、`docker-compose.tunnel.yml`、`scripts/deploy-tunnel.sh`（本機版：備份 → 記住舊映像 → up → health gate → 失敗自動回滾，已實跑成功與回滾路徑）、`scripts/vm-bootstrap.sh`（只做靜態檢查，**從未在真實主機執行**）、`scripts/mysql-backup.sh`、`.env.prod.example`。
- CI（`.github/workflows/ci.yml`）只發佈 **backend** 映像到 GHCR（SHA tag + digest 掃描 + SBOM）；**frontend 映像沒有發佈**（B2 刻意非目標），且沒有 deploy job。
- `deploy-tunnel.sh` 在本機 `--build`，與「以 commit SHA 拉映像」的 043 §4 設計不同。這是 A 階段必須先決定的缺口。
- 規格來源：`docs/tasks/043-production-deployment-plan.md` §4、`057`（外部資源清單）、`059`（VM 準備）。

## 前置（只有你能做，B'/C 才需要；A、B 不需要）

1. 租 Ubuntu 22.04/24.04 LTS VM（記憶體 ≥ 8GB，若 VM 上跑 Ollama），記下公網 IP。
   建議：Oracle Cloud「Always Free」（Ampere A1 ARM，官方規格 4 OCPU/24GB，2026 年有使用者回報新帳號可能只配到 2 OCPU/12GB，以申請當下主控台顯示為準），永久免費、給真公網 IPv4，規格符合門檻；缺點是常見「Out of host capacity」申請不到，卡住的話換區域重試，或改用 GCP（新戶 $300/90 天）、Azure（新戶 $200/30 天）等一次性額度頂上。帳號申請、信用卡驗證、VM 建立都只能你自己操作。
2. 把 `~/.ssh/esun_shop_deploy.pub` 貼進 VM（或讓 `vm-bootstrap.sh` 安裝）。
3. DuckDNS 把 `whitehot0321.duckdns.org` 指向 VM IP（token 自己用，不貼給我）。
4. 在 GitHub repo 建 Environment `production`，設 **Required reviewers = 你自己**，並填 secrets（SSH 私鑰、主機位址、`.env.prod` 所需密鑰）。**密鑰值不貼進對話、repo、Notion。**
5. 決定 GHCR 映像公開或私有（私有則 VM 需要 pull 用的 token）。

沒有 VM 時只做 A、B；B' 與 C 等 VM。

---

## A — 定位（只讀，不改任何檔案）

```
你是 Claude Code。MODE: PLAN（唯讀，禁止修改任何檔案、禁止 commit）。

目標：為 Phase 3.3.#18 B3「GitHub Actions deploy job + Environment 人工核准 + SSH 部署 + 備份 + health gate + 自動回滾」
列出缺口並提出單一建議方案。

可讀範圍（僅限）：
- docs/tasks/043-production-deployment-plan.md §4、057、059、060、070
- .github/workflows/ci.yml
- scripts/deploy-tunnel.sh、scripts/vm-bootstrap.sh、scripts/mysql-backup.sh
- docker-compose.prod.yml、docker-compose.tunnel.yml、.env.prod.example、frontend/Dockerfile、backend/Dockerfile
不要讀其他檔案、不要跑測試、不要掃全 repo。

請回答（每題給結論 + 檔案:行號證據）：
1. 映像策略：VM 上 build、或 CI 也發佈 frontend 映像後 VM 以 digest/SHA pull？比較兩者對「回滾到上一個 SHA」「供應鏈 gate 涵蓋範圍」「VM 資源」的影響，給單一建議。
2. deploy-tunnel.sh 與 VM 版的差異清單（HEALTH_URL、Caddy 80/443 與 tunnel overlay、映像來源、:previous 標記方式），建議是新增 scripts/deploy-vm.sh 還是參數化現有腳本。
3. SSH 部署的信任模型：deploy 使用者權限、金鑰是否 restricted（command=/from=）、known_hosts 固定指紋的做法，避免 Actions 首連 TOFU。
4. 密鑰流向：哪些值放 GitHub Environment secrets、哪些只存在 VM 的 .env.prod；確認任何 secret 都不會出現在 workflow log、compose config 輸出、health 輸出。
5. 觸發與保護：僅 advanced-v2/main push 與 workflow_dispatch；deploy 必須 needs 整條 CI gate 且綠；concurrency 避免同時部署；與 publish-image 的 digest 如何綁定（只部署掃描綠的 digest）。
6. 回滾定義：「失敗自動回滾」與「還原備份」何時各自適用（資料庫 migration 已跑過時映像回滾是否安全？Flyway 向前相容性）。
7. 驗收腳本：「一次真實部署成功 + 一次刻意失敗自動回滾」要如何在不傷害資料的前提下製造失敗。

輸出：缺口表 + 建議方案 + 需要使用者決定的選項（最多 3 個）+ B 階段的檔案清單。
不要實作、不要寫 Notion、不要 commit。
```

## A+B 合併版（單一 session；VM 不需要到位）

合併的前提：A 的三個「待你決定」選項由本提示詞預先給定預設值，A 的結論只要沒推翻預設就直接進 B，不必來回。工作量分級：**中高**（只動 workflow 與 shell 腳本，不動應用程式碼，不需 Docker 長時間跑）。若 A 的證據推翻任一預設，**立刻停止並回報，不進 B**。

預設決定：
1. 映像策略：CI 一併發佈 frontend 映像（沿用同一套 Trivy 掃描 + digest + SBOM），VM 以 **digest** pull，不在 VM 上 build。理由：回滾 = 重新部署上一個 digest，且供應鏈 gate 涵蓋實際部署的東西。
2. 腳本：新增 `scripts/deploy-vm.sh`，不改動 `deploy-tunnel.sh`（本機版已實測，避免回歸）；共用邏輯只有在不改變 tunnel 行為時才抽出。
3. SSH：專用 deploy 金鑰、host key 指紋存在 Environment secret/variable 並寫入 known_hosts，禁止 `StrictHostKeyChecking=no`。

```
你是 Claude Code。分兩個檢查點執行，中間不得提前改檔。

【檢查點 1：A 定位，唯讀】
閱讀（僅限）：docs/tasks/043 §4、057、059、060、070；.github/workflows/ci.yml；scripts/deploy-tunnel.sh、vm-bootstrap.sh、mysql-backup.sh；
docker-compose.prod.yml、docker-compose.tunnel.yml、.env.prod.example、frontend/Dockerfile、backend/Dockerfile。
不要掃全 repo、不要跑測試。

先驗證下列預設是否成立，每項附檔案:行號：
 (a) frontend 映像可在現有 publish-image 流程旁以同一套 gate 發佈，且 compose 可用 digest 指定映像；
 (b) deploy-tunnel.sh 與 VM 版的差異（HEALTH_URL、Caddy 80/443 與 tunnel overlay、映像來源、:previous 標記）；
 (c) 密鑰流向：Environment secrets 與 VM 上 .env.prod 的分工，確認不會進 log / compose config / health 輸出；
 (d) 回滾定義：映像回滾 vs 還原備份，Flyway 已跑過 migration 時映像回滾是否安全；
 (e) 刻意失敗部署的安全做法。
把結論寫入 docs/tasks/075「執行結果」的 A 小節。
若任一預設不成立或會讓風險上升：停止，回報原因與替代方案，等我決定。

【檢查點 2：B 實作】（僅在檢查點 1 全部成立時）
分支：從乾淨的 advanced-v2 開 feature/phase33-18-b3-deploy-pipeline（或另開 worktree；目前 checkout 有 Phase 4.#3 未提交檔案，不得混入）。
可修改（僅限）：.github/workflows/ci.yml 或新增 deploy.yml、scripts/deploy-vm.sh、docs/tasks/075、docs/project-state.md。
不得修改：應用程式碼、compose 的資料庫/網路設定、.env.prod、任何 secret。

必須滿足：
1. deploy job：environment: production（人工核准）、needs 整條 CI gate 且綠、concurrency 單一部署、僅 advanced-v2/main push 或 workflow_dispatch、permissions 最小化、不可被 PR/fork 觸發。
2. 只部署 publish-image 掃描通過的 digest。
3. 流程：備份 → 記住上一個 digest → pull 新 digest → up -d → health gate（逾時失敗）→ 失敗自動回滾；強制 SPRING_PROFILES_ACTIVE=prod。
4. known_hosts 固定指紋；任何 secret 不得 echo、不得出現在 set -x、不得進 repo。

驗證（僅限直接相關）：actionlint、shellcheck + bash -n、DRY_RUN=1 走完部署與回滾兩條路徑並貼輸出。不跑完整後端測試。
誠實標示：沒有 VM 只能算「寫好、靜態與 dry-run 驗證」，不得宣稱已部署。
完成後 commit 到該分支（不 push 到 advanced-v2、不 merge）。回報：改了哪些檔案、驗證輸出、未驗證項目、前置清單中還缺什麼。
```

---

## B — 實作（單獨版；A 的決定確認後才開；VM 不需要到位）

```
你是 Claude Code。MODE: IMPLEMENT。分支：從乾淨的 advanced-v2 開 feature/phase33-18-b3-deploy-pipeline
（目前 checkout 有 Phase 4.#3 的未提交檔案，請另開 worktree 或先確認 git status 乾淨，不要混入）。

只做 A 階段已確認的方案。可修改的檔案（僅限）：
- .github/workflows/ci.yml 或新增 .github/workflows/deploy.yml
- scripts/deploy-vm.sh（或對 deploy-tunnel.sh 的參數化）
- frontend 映像發佈所需的 workflow 片段（若 A 選此方案）
- docs/tasks/075（回填「執行結果」）、docs/project-state.md（最後一步）
不得修改：application 程式碼、compose 的資料庫/網路設定、.env.prod 內容、任何 secret。

必須滿足：
1. deploy job：`environment: production`（人工核准）、`needs` 整條 CI gate、`concurrency` 單一部署、僅 advanced-v2/main push 或 workflow_dispatch、permissions 最小化。
2. 部署流程：備份 → 記住上一個映像（SHA/digest）→ 拉新映像 → up -d → health gate（逾時失敗）→ 失敗自動回滾；腳本強制 SPRING_PROFILES_ACTIVE=prod。
3. known_hosts 固定指紋，不得使用 StrictHostKeyChecking=no。
4. 任何 secret 不得被 echo、不得出現在 `set -x`、不得寫進 repo。

驗證（只跑與本次直接相關的）：
- actionlint（或等價 workflow lint）
- shellcheck + `bash -n`
- DRY_RUN=1 走完部署與回滾兩條路徑，貼出輸出
- 不跑完整後端測試套件（程式碼未動）

驗證邊界必須誠實寫進結果：沒有 VM 時只能算「寫好、靜態與 dry-run 驗證」，不得宣稱已部署。
完成後 commit 到該分支（不要 push 到 advanced-v2、不要 merge）。回報：改了哪些檔案、驗證輸出、未驗證項目。
```

## B' — VM 實測（VM 與 Environment 到位後；你需要在旁邊核准）

```
你是 Claude Code。MODE: VERIFY（只在 B 的分支上執行與記錄，不新增功能）。

前置檢查（任何一項失敗就停止並回報，不要自行修補主機）：
ssh 能登入、docker/compose 版本、ufw 只開 22/80/443、DNS 解析到 VM、VM 上 .env.prod 已就位（只確認鍵名存在，不印值）。

步驟：
1. 若 vm-bootstrap.sh 尚未在真實主機跑過，先跑並記錄任何失敗（它從未實跑過）。
2. 觸發 deploy（workflow_dispatch），請使用者在 Environment 核准，確認 health gate 通過、docker ps 僅 80/443 對外。
3. 非破壞性 smoke：HTTPS 憑證有效、health=UP、API 文件 404、sandbox 付款被拒、過期 token 回 401 且 CORS 標頭在 401 上、登入→下單。只用自建測試帳號。
4. 刻意失敗部署：用一個必然無法通過 health gate 的映像/設定（例如指向不存在的 tag），確認自動回滾、服務持續可用、備份仍可還原。
5. 禁止對正式環境跑 k6。

回報：每一步的實際輸出、失敗與修補、仍未驗證項目。不得貼任何 secret 或主機位址進 repo/Notion。
```

## C — 獨立驗收（全新 session，建議 Codex 審 Claude；角色可依 AGENTS.md 對調）

```
MODE: REVIEW（唯讀）。只看分支 feature/phase33-18-b3-deploy-pipeline 相對 advanced-v2 的 diff 與 B' 的實測紀錄，不要重新完整審查整個 repo。

檢查清單：
1. secret 是否可能出現在 workflow log、compose config 輸出、health 輸出、錯誤訊息。
2. deploy 是否真的 needs 整條 CI gate，是否可被 fork/PR 觸發；是否只部署掃描通過的 digest。
3. SSH 信任模型：host key 是否固定、金鑰權限是否過大。
4. 回滾是否真的可用：映像回滾與資料庫 migration 的相容性、備份還原是否被實測。
5. fail-fast 是否可被繞過（例如未啟用 prod profile 就部署）。
6. 埠暴露面、CORS/同源假設。
7. 文件宣稱與實測證據是否一致，未驗證項目是否如實標示。

輸出：PASS / FAIL，每個 finding 附檔案:行號與重現方式，無 finding 要明說。不修改檔案。
```

## 完成條件

A 決定確認 → B 靜態/dry-run 通過 → B' 一次真實部署成功 + 一次刻意失敗自動回滾 → C PASS → 更新 `docs/project-state.md` 並移除「#18 B3 延後」。

## 執行結果

（尚未開始）
