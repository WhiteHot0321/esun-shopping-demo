# 075 — Phase 3.3.#18 B3 部署管線提示詞（A 定位 / B 實作 / B' VM 實測 / C 驗收）

依 `prompt-scope-control` 拆成獨立階段，每段開獨立 session，一次只跑一個重階段。風險：**Critical**（部署、密鑰、回滾），B' 之後必須走 C 獨立審查。

## 背景（已驗證，2026-10-22）

- 已有：`docker-compose.prod.yml`、`docker-compose.tunnel.yml`、`scripts/deploy-tunnel.sh`（本機版：備份 → 記住舊映像 → up → health gate → 失敗自動回滾，已實跑成功與回滾路徑）、`scripts/vm-bootstrap.sh`（只做靜態檢查，**從未在真實主機執行**）、`scripts/mysql-backup.sh`、`.env.prod.example`。
- CI（`.github/workflows/ci.yml`）只發佈 **backend** 映像到 GHCR（SHA tag + digest 掃描 + SBOM）；**frontend 映像沒有發佈**（B2 刻意非目標），且沒有 deploy job。
- `deploy-tunnel.sh` 在本機 `--build`，與「以 commit SHA 拉映像」的 043 §4 設計不同。這是 A 階段必須先決定的缺口。
- 規格來源：`docs/tasks/043-production-deployment-plan.md` §4、`057`（外部資源清單）、`059`（VM 準備）。

## 前置（只有你能做，B'/C 才需要；A、B 不需要）

1. 租 Ubuntu 22.04/24.04 LTS VM（記憶體 ≥ 8GB，若 VM 上跑 Ollama），記下公網 IP。
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

## B — 實作（A 的決定確認後才開；VM 不需要到位）

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

## A 階段確認紀錄（2026-10-22，使用者已確認）

**這份紀錄與 057 §3 的「雙存」不同，以本紀錄為準。**

使用者決定：

1. 應用程式密鑰（JWT、DB、Redis、PAYMENT_CALLBACK_SECRET、ECPay）只放 VM 的 `.env.prod`（mode 600）。GitHub Environment `production` 只放連線用的值：`DEPLOY_SSH_KEY`（secret）、`DEPLOY_HOST`（secret）、`DEPLOY_KNOWN_HOSTS`（variable）。
2. 只自動部署 `advanced-v2`。`main` 是未動的面試原版、沒有 prod profile，不部署。
3. VM 以 `git fetch` + checkout 精確 SHA 取得 compose 與 migration 檔。前提：repo 公開、`vm-bootstrap.sh` 的 `REPO_URL` 正確。

採用方案：CI 同時發布 backend 與 frontend 映像並各自做 Trivy digest 掃描與 SBOM；deploy job `needs: [publish-image]`，只部署掃描綠的 digest；觸發為 (push || workflow_dispatch) 且 ref == `advanced-v2`；Environment 設 Required reviewers（使用者本人）與 Deployment branches 限 `advanced-v2`；job 層級 `concurrency: deploy-production`（不取消進行中）；VM 只 pull `backend`、`caddy`；部署前核對映像標籤 `org.opencontainers.image.revision` 等於目標 SHA；目標 SHA 必須是已部署 SHA 的後代；回滾依據是 VM 上的狀態檔，且只在 health gate 通過後才更新；回滾用 `up -d --no-deps backend caddy`、不重跑 migrate；health gate 為 backend 容器 healthy 加上經 Caddy 打 `/api/products/available` 回 200（`curl --resolve` 驗證憑證）；備份還原永遠手動。

SSH 信任模型：另建 Actions 專用新金鑰（既有 `esun_shop_deploy` 留作操作者登入，不放進 GitHub）；`authorized_keys` 為 `restrict,command="/usr/local/sbin/esun-deploy-entry"`，入口腳本只接受 `deploy <40 hex SHA> <backend digest> <frontend digest>` 與 `rollback`；不用 `from=`；host key 由使用者在帶外取得存入 `DEPLOY_KNOWN_HOSTS`，`StrictHostKeyChecking=yes`、`IdentitiesOnly=yes`，禁止 `ssh-keyscan`；不用第三方 SSH action。

密鑰與 log 規則（repo 公開，Actions log 視為公開）：禁止 `set -x`；只用 `compose config -q`；禁止 `docker inspect` 輸出整個容器；失敗時不倒 compose logs；腳本檢查 `.env.prod` 與 `.backup-passphrase` 權限為 600。

## 執行結果

### B 階段（2026-10-22，分支 `feature/phase33-18-b3-deploy-pipeline`，從乾淨的 `advanced-v2` @ `1bda247` 另開 worktree）

**狀態：寫好、靜態檢查與 stub 測試通過。沒有 VM，從未部署過，不得宣稱已部署。**

改了什麼：

| 檔案 | 內容 |
|---|---|
| `.github/workflows/ci.yml` | 新增 `deploy-script-test` job（shellcheck + `bash -n` + stub 測試）並加入 `docker-build` 的 `needs`；`docker-build` 多建置並掃描 frontend 映像；`publish-image` 同時發布 backend/frontend（各自 digest 掃描與 SBOM），輸出兩個 digest，並允許 `advanced-v2` 上的 `workflow_dispatch`；新增 `deploy` job（`environment: production`、`needs: [publish-image]`、job 層級 `concurrency`、`permissions: contents: read`、不 checkout、`timeout-minutes: 25`）。buildx 快取改成 `scope=backend` / `scope=frontend`（兩個映像共用預設 scope 會互相覆蓋）。 |
| `scripts/deploy-vm.sh`（新） | VM 端部署：flock → 權限檢查 → fetch → 祖先檢查 → checkout 精確 SHA → 只 pull backend/caddy → 標籤核對 → 備份（首次部署略過並明確印出）→ `up -d` → health gate → 失敗時回滾到上一個已知良好版本。`deploy` 與 `rollback` 兩個指令。結束碼 0/1/10/11/12 見檔頭。 |
| `scripts/esun-deploy-entry`（新） | forced command：用精確的 regex 只放行兩種指令，其餘（多餘字、大寫、換行、分號、互動登入）一律拒絕；以 `env -i` 清空環境再 exec deploy-vm.sh，Actions 端無法設定任何可調參數。 |
| `scripts/test/deploy-vm.test.sh`（新） | stub docker/curl/flock + 真的 git（暫時 bare remote）的測試。 |
| `scripts/vm-bootstrap.sh` | 改 clone `advanced-v2`（`GIT_TERMINAL_PROMPT=0`，私有 repo 會直接失敗）；建 `/var/lib/esun-deploy`（700）與 `backups/`；已存在的 `.env.prod`、`.backup-passphrase` 設為 deploy 擁有且 600（不建立、不改內容）；安裝 root 擁有的入口腳本；用 `ACTIONS_DEPLOY_PUBKEY` 安裝 `restrict,command=` 受限金鑰（格式嚴格驗證、拒絕與操作者金鑰相同、重跑不會重複）。`REPO_URL` 核對：與 `git remote` 一致，為 `esun-shopping-demo.git`。 |
| `.env.prod.example` | 映像變數改為 digest 寫法並註明留空由腳本提供；標頭註明密鑰只放 VM（取代 057 §3 雙存）。 |
| `.gitattributes` | 加一行 `scripts/esun-deploy-entry text eol=lf`（它沒有 `.sh` 副檔名，原規則涵蓋不到；CRLF shebang 會讓它在 Linux 上無法執行）。**這是清單之外的小幅新增。** |

狀態檔（`/var/lib/esun-deploy/state`，deploy 擁有、mode 600）記 `CURRENT_*`（最後已知良好）與 `PREVIOUS_*`：自動回滾回到 `CURRENT`；手動 `rollback` 回到 `PREVIOUS` 並清空它（只能退一步）。狀態檔只在 health gate 通過後才寫入，讀入時逐欄驗證格式。

驗證（實際輸出）：

- stub 測試：在 WSL Ubuntu（真的 Linux 檔案權限）**83/83 PASS**。Git Bash 在 Windows 上沒有 POSIX 權限位元，測試會明確拒絕執行（exit 2），不會給出假結果。涵蓋：首次部署成功（無 volume → 略過備份並印出）、第二次部署（備份先於 up、狀態位移）、gate 失敗自動回滾（結束碼 10、rollback 的 up 帶 `--no-deps`、migrate 不重跑、checkout 退回、狀態檔不變）、首次部署 gate 失敗（結束碼 12、不嘗試回滾）、降版被拒、非 `advanced-v2` 的 commit 被拒、backend 與 frontend 標籤不符各自被拒且 checkout 還原、`.env.prod` / `.backup-passphrase` 權限不是 600 被拒、`DOMAIN=localhost` 被拒、參數格式錯誤被拒、手動回滾只能一次、入口腳本接受兩種合法指令並拒絕 14 種惡意/錯誤輸入、呼叫端環境變數不會傳進去。
- 變異檢查（刻意弄壞腳本，確認測試會失敗）：移除祖先檢查、移除分支檢查、移除 backend 標籤檢查、移除 frontend 標籤檢查、rollback 拿掉 `--no-deps`、狀態在 gate 前寫入、gate 的 curl 加 `-k`、入口 regex 去掉 `^` 或 `$`、移除首次部署訊息：共 10 個變異，**全部被測試抓到**。第一輪有 3 個變異存活（標籤檢查只測了兩個同時不符、`-k` 的偵測 regex 寫錯、入口 regex 沒測前綴注入），已補測試後重跑。
- `actionlint`（rhysd/actionlint 容器，內含 shellcheck）：`ci.yml` 無 finding；另以刻意寫錯的 `needs` 與 output 名稱驗證它確實會報錯。
- `shellcheck` 0.11.0：`deploy-vm.sh`、`esun-deploy-entry`、`vm-bootstrap.sh`、測試腳本全部乾淨（測試內一處刻意的 SC2016 已加說明註解）；`bash -n` 全過。
- bootstrap 金鑰邏輯：因 bootstrap 需 root 與 apt，只把金鑰驗證與 `authorized_keys` 改寫段落抽出在 WSL 實跑：合法金鑰安裝成功、重跑仍只有一行受限金鑰且操作者金鑰保留、操作者金鑰/帶選項的金鑰/rsa/多行注入皆被拒，`ssh-keygen -l` 能解析產出的檔案。**bootstrap 整支仍未在真實主機執行。**
- 沒跑後端測試（程式碼未動）。

### 使用者要做的設定（只有你能做）

1. 把 repo 設為公開（`vm-bootstrap.sh` 與 VM 的 `git fetch` 不帶憑證），第一次發布後到 GitHub Packages 把 `backend`、`frontend` 兩個套件設為 public。
2. 建 Actions 專用金鑰：`ssh-keygen -t ed25519 -f esun_actions_deploy -C esun-actions-deploy`；公鑰在 VM 上以 `ACTIONS_DEPLOY_PUBKEY="…" bash vm-bootstrap.sh` 安裝，私鑰只放進 Environment secret `DEPLOY_SSH_KEY`。
3. GitHub Environment `production`：Required reviewers = 你自己；Deployment branches 限 `advanced-v2`；secrets `DEPLOY_SSH_KEY`、`DEPLOY_HOST`；variable `DEPLOY_KNOWN_HOSTS`。`DEPLOY_KNOWN_HOSTS` 的主機名稱要與 `DEPLOY_HOST` 寫法一致（IP 對 IP、網域對網域），否則會 fail closed；host key 從供應商主控台或 VM 上的 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` 帶外核對。可用 `ssh-keygen -H` 雜湊主機名稱避免 IP 明文出現在 variable。
4. VM 上自行建立 `.env.prod`（`DOMAIN` 填真正主機名）與 `.backup-passphrase`，各 `chmod 600`，passphrase 另存一份在 VM 之外。

### 未驗證項目（B' 必須處理，或維持如實標示）

- ~~整條管線從未在 GitHub 上跑過~~ → PR #13 的 CI（run 39793275163）全綠：`deploy-script-test`、frontend 建置、frontend/backend 映像掃描都在 GitHub 上跑過。**仍未跑過**：`publish-image`（推 GHCR、digest 掃描、SBOM）、`deploy` job、Environment 核准——要合進 `advanced-v2` 後才會第一次執行。
- **frontend 映像的 Trivy 掃描**（`caddy:2-alpine` 是否有可修補的 HIGH/CRITICAL，是否需要 `.trivyignore.yaml` 例外，需 owner 與到期日）：見下方「Trivy 本機結果」與「Trivy exceptions」（已加 4 筆限時例外，2026-11-05 到期）。
- ~~migrate 失敗時舊 backend 是否繼續運行~~ → **已實測：不會**，見下方「A 階段未驗證項目：本機 Compose 實測」。
- ~~Flyway 遇到「資料庫已套用、checkout 沒有」的 migration 是否只警告~~ → **已實測：只警告**，同一節。另外實測出「失敗的 migration 會擋住之後所有 migrate，直到手動 repair」。
- GHCR 套件需手動確認為 public；repo 需為公開。
- `frontend/Caddyfile` 的憑證申請：首次部署 Caddy 向 Let's Encrypt 申請憑證，可能超過 gate 逾時；首次部署的逾時預設放寬為 300 秒（`FIRST_DEPLOY_HEALTH_TIMEOUT`），但從未在真實主機實測。
- gate 用的 `/api/products/available` 需要資料庫與 backend 都正常才會 200，這是刻意的；但它不涵蓋 Redis 降級、付款、登入。
- `flock` 的互斥在測試裡是 stub（永遠成功），沒有測並行；容器內 `curl`、`docker compose pull` 對 digest 參照的實際行為、`compose ps -q` 輸出格式，都只在 stub 裡模擬。
- 入口腳本與 `authorized_keys` 的 `restrict,command=` 組合，沒有經過真正的 sshd 驗證（只在本機用 bash 模擬 `SSH_ORIGINAL_COMMAND`）。
- backend 容器是否有 `printenv`／是否真的以 prod profile 啟動：腳本只靜態檢查 `docker-compose.prod.yml` 在目標 SHA 釘死了 `SPRING_PROFILES_ACTIVE: prod`，沒有從執行中的容器讀取（避免倒出環境變數）。

### Trivy 本機結果：frontend 映像目前**過不了**閘門（需要你決定）

2026-10-22 在本機用與 CI 相同的旗標（`--scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 --ignorefile .trivyignore.yaml`）掃描 `frontend/Dockerfile` 建出的映像（Trivy 用本機 `aquasec/trivy:latest`，不是 CI 釘的 v0.74.0；資料庫為當天下載）：

- 第一次用本機快取的舊基底（`caddy:2-alpine`，建於 2026-09-17）：24 個 HIGH，全在 `usr/bin/caddy` 這支 Go 二進位。
- 改用 `docker build --pull` 取得當前上游映像（Alpine 3.23.7、Caddy v2.11.4）後：**仍有 5 個可修補的 HIGH**，皆在 `usr/bin/caddy`（OS 套件 0 個）：`golang.org/x/net` v0.59.0 → 0.60.0（CVE-2026-78669），以及 Go 標準庫 1.26.8 → 1.26.10 / 1.27.3（CVE-2026-111943、CVE-2026-78667、CVE-2026-78669、CVE-2026-97031）。

意義：新加進 `docker-build` 的「Scan frontend image」步驟，以及 `publish-image` 的 frontend digest 掃描，在今天的上游映像上**會失敗**。失敗是安全的方向（CI 紅，`publish-image` 與 `deploy` 都不會執行）。owner 在 2026-10-22 選擇「評估可達性後加限時例外」，結果見下一節。

### Trivy exceptions（2026-10-22，owner 決定；`.trivyignore.yaml`）

對照 `frontend/Caddyfile` 評估（`caddy adapt` 確認：無 `ech` 全域選項、有 `file_server`、未限制協定即 HTTP/2 預設開啟、監聽 `:443`）：

| CVE | 內容 | 在本設定下 | 影響 |
|---|---|---|---|
| CVE-2026-78669 | HTTP/2 SETTINGS 幀造成 CPU 耗盡（x/net 與標準庫同一 ID） | **可達**（對所有人開放的 :443） | 只影響可用性 |
| CVE-2026-78667 | `Range` 標頭含大量小區間，`ServeContent`/`FileServer` CPU 耗盡 | **可達**（`file_server` 提供 SPA 靜態檔） | 只影響可用性，受標頭大小上限約束 |
| CVE-2026-97031 | ECH 外層擴充多重參照造成記憶體耗盡 | **不可達**：需伺服器處理 ECH，而 ECH 是 Caddy 的選用功能（全域 `ech`），本設定沒有 | — |
| CVE-2026-111943 | `crypto/x509` 拒絕已知不可信中繼憑證（避免 MITM） | **低**：需要對 Caddy 當客戶端驗證的連線（對 CA 的 ACME）做 MITM；沒有用戶端憑證驗證，往 backend 是內網明文 HTTP | 找不到比 Go/Red Hat 摘要更詳細的公開說明 |

做法：四個 ID 各一筆例外，`paths: ["usr/bin/caddy"]`（只對這支二進位有效，不會順帶放行 backend 映像裡的同 ID），`owner: WhiteHot0321`，`expired_at: 2026-11-05`（14 天，Go 修補版已發布，預期上游很快重建）。可用性風險的緩解：Caddy 容器 `mem_limit: 256m`、`restart: unless-stopped`、`cap_drop: ALL`、唯讀根檔案系統；單 VM demo 無資料外洩面。**沒有套用但可做的加固**（要改 `frontend/Caddyfile`，超出本任務範圍）：只開 h1/h3、在 Caddy 剝除 `Range` 標頭。

驗證（本機 Trivy `latest`，對 `docker build --pull` 的前端映像，旗標同 CI）：套用例外後 exit 0；把 `expired_at` 改成過去日期後 5 個 HIGH 全部重新出現、exit 1；把 `paths` 改成別的路徑後同樣 exit 1。也就是例外會到期、且確實只限定在 `usr/bin/caddy`。

仍未驗證／限制：
- CI 釘的是 Trivy v0.74.0 而我用本機最新版，且資料庫會每天更新，到 CI 實際執行時 finding 集合可能不同（多出新的就會紅，需再評估；上游修好了則例外變成多餘，應移除）。
- 例外 2026-11-05 到期後，若上游仍未重建，gate 會再次變紅——這是設計目的，屆時要重新決定（延期並重新評估，或選用自行編譯 Caddy 的選項 3）。
- CVE-2026-97031「ECH 為選用」是依 Caddy 文件與 `caddy adapt` 結果判斷，我沒有用 ECH 客戶端實測伺服器行為；CVE-2026-111943 沒找到獨立的公開公告。

### CI 第一輪結果與後續修正（PR #13，2026-10-22）

第一輪 CI 有 3 個 job 失敗，第二輪還有 1 個，原因都是**新公布的漏洞或工具版本差異**，不是管線設計問題（`advanced-v2` 現在推送也會紅）：

| 失敗 | 處理 |
|---|---|
| `deploy-script-test`：runner 的 shellcheck 對 `deploy-vm.sh` 兩行 `A && B \|\| die` 報 SC2015 | 改成明確的 `if`，行為不變（1bc4709） |
| `frontend-build`、`supply-chain-gate`：前端 runtime 依賴 4 個 HIGH（vue/`@vue/server-renderer` 3.5.31、follow-redirects 1.16.0、source-map-js 1.2.1） | 只改 `frontend/package-lock.json`，全在原 semver 範圍內（755e8bd）。`npm audit fix` 單獨跑動不了 vue（vue 與 `@vue/server-renderer` 互相鎖死同一個確切版本），所以把這兩個 lock 項目移除後用 `npm install --package-lock-only` 重新解析。`npm audit --omit=dev` 0、Trivy repo 掃描乾淨、Vitest 84/84、build 成功 |
| `docker-build` 的後端映像掃描：10 個 findings | 見下 |

### Trivy exceptions - backend image（2026-10-22，owner 決定）

owner 要求：Jackson 升級，其餘對照程式碼評估可達性後加限時例外。

- **Jackson（4 個 HIGH）：修掉，不是例外。** `jackson-bom.version` 2.21.6 → 2.21.7（`backend/pom.xml` 唯一改動）。解析結果 jackson-core/databind 2.21.7。後端完整測試 **314/314 PASS**（0 failures/errors/skips），JaCoCo 閘門通過；本機重建映像（`--pull`）後 Jackson 的 finding 消失。
- **Spring `spring-webmvc` 6.2.19（2 個 CRITICAL）：加例外，到期 2026-11-19。** Trivy 只列出 7.0.9 為修正版，6.2.x 沒有修正；補丁要升 Spring Framework 7（Spring Boot 4 級的遷移，另案）。
  - CVE-2026-47884（XsltView + `/**` 映射 → SSRF/RCE）：**不可達**。13 個 controller 檔全是 `@RestController`、沒有 `@Controller`、沒有 ViewResolver、沒有模板引擎依賴（只有 `spring-boot-starter-web` 與 springdoc）、沒有 `templates` 目錄、沒有 XSLT。
  - CVE-2026-47890（SSE 搭配 view fragments 造成串流損壞）：**不可達**。沒有 `SseEmitter`／`ResponseBodyEmitter`／`text/event-stream`／webflux，也沒有 view 層。
  - 風險不是零：這個判斷建立在「應用沒有 view 層」上，之後若加入 view 層或 SSE 要重新評估。例外的 `paths` 限定在 `spring-webmvc-*.jar`。
- **`usr/bin/pebble`（4 個 Go 標準庫 HIGH，同 Caddy 那批 CVE）：加例外，到期 2026-11-05。** 這是 `eclipse-temurin:17-jre`（本機 `--pull` 後為 Ubuntu 26.04）基底層裡的 Canonical Pebble 客戶端 v1.33.0，不屬於任何 dpkg 套件。**不可達**：我們的 ENTRYPOINT 是 `sh -c exec java ... -jar app.jar`，容器內沒有 pebble 行程或監聽（`ps` 只有 sh），並以非 root 的 `spring` 使用者執行；要利用必須先取得容器內程式碼執行權。預期由上游重建基底映像解決。

驗證（本機 Trivy `latest`，旗標同 CI）：套用全部例外後，**後端與前端映像都 exit 0**；把所有 `expired_at` 改成過去日期後後端 6 個 finding（2 CRITICAL + 4 HIGH）全部回來、exit 1；把 `paths` 改成別的路徑後同樣 exit 1。

限制：CI 釘 Trivy v0.74.0、本機用最新版，資料庫每天更新，CI 實際掃到的集合可能不同；`docker-build` 在 CI 上仍未實際跑過（上一輪卡在這一步之前）。

### A 階段未驗證項目：本機 Compose 實測（2026-10-22）

A 的決定早已確認並在 B 實作；這一節關掉 A 留給 B/B' 的未驗證項目中**不需要 VM** 的部分。做法：用真實的 `docker-compose.prod.yml`（另取專案名 `b3probe`、全新 volume、非機密的測試值），起 MySQL + Redis + `migrate` + 本分支建出的 prod-profile backend 映像（不起 Caddy，避免佔用 80/443）；以 override 檔把 `migrate` 的 SQL 目錄換成「V1 + 刻意寫錯的 V999」或「V1 + 正常的 V999」。做完已 `down -v` 並刪除實驗映像，沒有碰既有的 `esun-tunnel` 等容器。

**1. migrate 失敗時，舊 backend 不會繼續運行（會中斷，直到回滾）。** `docker events` 時間軸（UTC）：

```
14:51:33-34  舊 backend（上一版映像）kill / stop / destroy，新版容器建立但未啟動   ← migrate 還沒開始
14:51:34     migrate start
14:51:36     migrate die（V999 語法錯誤，exit 1）；`up -d` 結束碼 1；backend 停在 Created
14:51:37     回滾：`up -d --no-deps --no-build backend`（上一版映像）→ start
14:51:42     backend healthy；migrate 沒有再被啟動
```

- Compose 在 recreate 時先移除舊容器、再等依賴（migrate）完成，所以**失敗的部署一定會造成中斷**；`deploy-vm.sh` 在 `up` 非零時立刻走回滾（不等 gate），本機中斷約 8 秒。真實 VM 上還要加 Caddy 與 JVM 啟動時間。
- 連帶結論：**成功的部署也有停機**（舊 backend 在 migrate 前就被停掉，到新 backend healthy 為止）。單 VM + Compose 沒有零停機，這是目前架構的已知限制，不是腳本錯誤。
- 回滾路徑（stub 測試裡只能模擬的那一段）在真實 Compose 下可用：上一版映像重新起來並 healthy，`migrate` 容器的結束時間沒有變（沒重跑）。

**2. 失敗的 migration 會擋住之後所有 migrate，直到手動 repair。** MySQL 的 DDL 不是交易性的，Flyway 失敗後在 `flyway_schema_history` 留下 `999 bad success=0`；之後就算換成正確的 SQL，`migrate` 仍回 `Validate failed: Detected failed migration to version 999`，exit 1。也就是說：一次 migration 失敗後，**後續每次部署都會在 migrate 失敗並自動回滾**（安全的方向），直到操作者處理。處理方式（在 VM 上手動，先確認沒有半套用的 DDL）：

```
docker compose -p esun -f docker-compose.prod.yml --env-file .env.prod run --rm --no-deps migrate repair
```

實測 `repair` 會刪掉失敗紀錄，之後正確的 V999 成功套用。

**3. 「資料庫已套用、checkout 沒有」的 migration：只警告。** DB 在 V999 時，用只有 V1 的舊 SQL 目錄跑 `migrate`：`WARNING: Schema has a version (999) that is newer than the latest available migration (1)`、`Schema is up to date`，exit 0（連「失敗的 future migration」也只是警告）。回滾本來就不跑 migrate，所以這只影響有人手動從舊 checkout 執行 migrate 的情況。

**4. 舊程式碼對上新 schema。** V999 加了一張表後，上一版 backend 仍 healthy：**新增式（expand）變更可安全回滾映像**；刪除或改名欄位（contract）則不行，那種 migration 要拆成兩次部署，否則只能從備份還原（手動）。

仍未驗證（需要 VM）：Caddy 首次申請憑證所需時間 vs. `FIRST_DEPLOY_HEALTH_TIMEOUT`、真實 sshd 的 `restrict,command=`、`flock` 互斥、`vm-bootstrap.sh` 整支執行、GHCR 拉取 digest。

### 合併前後的 Environment 設定清單（給 owner）

**合併前（建議先做，不填任何 secret）**

1. GitHub repo → Settings → Environments → New environment，名稱 `production`（必須完全一致）。
2. Deployment protection rules → 勾 Required reviewers → 加入自己（`WhiteHot0321`）。
3. Deployment branches and tags → Selected branches and tags → 加 `advanced-v2`。
4. 先不要加 secret/variable。這時合併後觸發的 `deploy` 會停在等核准；**有 VM 之前一律按 Reject**。若沒先建 Environment，GitHub 會自動建一個沒有保護的同名 Environment，`deploy` 會直接執行並因缺少連線設定而失敗（不危險，但之後填 secret 時會有「還沒設核准」的空窗）。

**有 VM 之後（B' 前）**

5. 依「使用者要做的設定」第 1–4 項：repo 與 GHCR 套件設 public、產生 Actions 專用金鑰並以 `ACTIONS_DEPLOY_PUBKEY` 跑 `vm-bootstrap.sh`、在 VM 建 `.env.prod` 與 `.backup-passphrase`（600）。
6. 在 `production` Environment 加 secrets `DEPLOY_SSH_KEY`（Actions 金鑰私鑰）、`DEPLOY_HOST`，variable `DEPLOY_KNOWN_HOSTS`（帶外核對過的 host key）。
7. 先做 C 獨立審查，再以 workflow_dispatch（branch `advanced-v2`）觸發第一次部署並核准。

### 合併與第一次發布（2026-10-22）

- **Environment `production`**（合併前建立）：Required reviewers = `WhiteHot0321`（由 owner 手動加入並儲存，重新載入頁面確認存在）、Prevent self-review 未勾、Deployment branches = Selected → `advanced-v2`、**沒有任何 secret/variable**。「Allow administrators to bypass configured protection rules」維持 GitHub 預設的勾選：管理員可以手動略過核准，但那需要刻意操作，不會自動略過；要更嚴格可取消勾選。
- **PR #13 已合併**（merge commit `110afbc`，Create a merge commit）。合併確認由 owner 本人按下：Claude 的點擊被自動模式的安全分類器擋下。
- **`advanced-v2` 的 CI（run 39801996364）**：backend-test、frontend-build、supply-chain-gate、config-validate、deploy-script-test、docker-build、**publish-image 全部成功**——`publish-image` 是第一次真正執行：
  - `ghcr.io/whitehot0321/esun-shopping-demo/backend@sha256:351c91967d2c86702fe499b252cde7800fa08710be08d94c3644a90e8ca30c8e`
  - `ghcr.io/whitehot0321/esun-shopping-demo/frontend@sha256:1c923342e305cab54f267fe3576ce89faf36e82b6f6ed9519c42c06eeb04c5b3`
  - 兩個 digest 都通過 digest 掃描，CycloneDX SBOM 已上傳為 artifact `sbom-110afbc…`。
  - 兩個套件**已可匿名拉取**（匿名向 `ghcr.io/token` 取得 pull token，`HEAD /v2/.../manifests/sha-110afbc…` 回 200），「GHCR 套件需手動設 public」這一項不需要再做。
- **`deploy` job 停在「production waiting for review」**，證明 Environment 保護有效；依計畫以「No VM yet」為由 **Reject**。被拒絕的部署 GitHub 標成 failure、整個 run 顯示 failure，但 `deploy` 沒有執行任何步驟（沒有嘗試 SSH）。在有 VM 之前，之後每次推到 `advanced-v2` 都會出現一個等待核准的 `deploy`，照樣 Reject（或放著，30 天後自動失效）即可。

仍未完成（075 完成條件）：B'（需要 VM：真實部署成功一次 + 刻意失敗自動回滾一次）與 C 獨立審查。
