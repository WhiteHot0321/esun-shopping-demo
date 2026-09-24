# Task 039 — Phase 3.2 #16 CI/CD 自動化 [Maintainer / Small-Medium]

Branch: `feature/phase32-16-cicd`（自 `advanced-v2`）。風險分級：Small-Medium（只動 CI 設定與 Docker 建置周邊，不碰應用程式碼；
唯一對外動作是 image 推送，限定在 main / advanced-v2 / `v*` tag）。

## Problem
`.github/workflows/ci.yml` 只有「跑測試 + build image」，`docker-build` 明確略過 push；且
- 任何分支的每次 push 都跑（PR 分支 push + PR 事件重複執行）；
- 同一 ref 連續 push 不會取消舊 run；
- 沒有 `permissions` 宣告（預設 token 權限過寬）；
- 測試失敗時看不到 surefire / JaCoCo 報告；
- Docker build 沒有快取，也沒有 `.dockerignore`；
- 沒有依賴更新機制。

## Solution
- **觸發**：push 只在 `main` / `advanced-v2` / `v*` tag，加 `pull_request` 與 `workflow_dispatch`；功能分支只在開 PR 時跑一次。
- **concurrency**：同 ref 新 run 取消舊 run，但 main / advanced-v2 / tag 不取消，避免 image 推送被腰斬。
- **最小權限**：全域 `contents: read`；只有 `publish-image` job 追加 `packages: write`。
- **閘門**：`mvn -B test`（含 JaCoCo 四個核心 service ≥80% 的 check）→ 前端 `npm test` + build → `docker-build`（buildx + GHA cache，不推送，PR/fork 無需憑證）。
- **發佈**：`publish-image` 需三個閘門都綠，僅 `push` 到 main / advanced-v2 / `v*` tag 才執行，推到 `ghcr.io/<repo>/backend`，用內建 `GITHUB_TOKEN`（**無新增 secret**）。tag：分支名、semver、完整 sha。
- **報告**：`backend-reports`（surefire + JaCoCo）以 `if: always()` 上傳，保留 14 天。
- **`backend/.dockerignore`**（target、uploads、log、.git）；**`.github/dependabot.yml`**（maven / npm / docker / github-actions，每週）。
- 各 job 加 `timeout-minutes`。

## Verification
- YAML 以 `yaml` 套件解析成功；job 依賴（`docker-build` ← backend-test + frontend-build；`publish-image` ← docker-build）與 `if` / `permissions` 結構已逐一檢視。
- 本機 `docker build -f backend/Dockerfile backend`（含新 `.dockerignore`）成功。
- 真實 GitHub Actions 結果見 handoff（PR 上的 run）。

## Not done / decisions left open
- **沒有 deploy job**：部署目標（雲端容器服務 / VM / k8s）尚未決定；決定後在 `publish-image` 之後加 deploy job，憑證走 GitHub Environments + secrets。
- 前端沒有 Dockerfile，所以只發佈 backend image。
- k6 負載套件（`bench/run-phase3-suite.py`）未接進 CI（耗時、吃資源、機器相依基線）；可另開 `workflow_dispatch` 手動 workflow。
- 尚未設定 branch protection / required checks（需在 repo 設定頁操作）；未做 image 漏洞掃描（Trivy）與 SBOM。
- 非 Codex 審查、非獨立審查。
- `publish-image` 的首次實際推送要等本分支合併進 `advanced-v2` 後才會發生；GHCR package 預設為 private，是否公開由使用者決定。

## Engineering note
Pipeline 的信任邊界：PR（含 fork）只做「無憑證」的驗證，寫入 registry 的權限只給已合併的受保護 ref，且只授予需要它的那一個 job。
