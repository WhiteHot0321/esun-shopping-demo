# Phase 3.2 #16 handoff

Goal: CI/CD 自動化 — 收斂觸發條件、最小權限、報告上傳、Docker 快取，並在閘門全綠後把 backend image 推到 GHCR（僅 main / advanced-v2 / `v*` tag）。

Changed: `.github/workflows/ci.yml`（重寫）, `.github/dependabot.yml`（新）, `backend/.dockerignore`（新）, `README.md`（CI 徽章）, `docs/tasks/039-cicd-automation.md`, 本檔, `docs/project-state.md`。不動任何應用程式碼。

Validated: workflow/dependabot YAML 解析 + job 依賴/permissions 檢視；本機 `docker build` 成功。GitHub 端真實 run 見下方 CI 結果段。

Not proven: publish-image 尚未實際執行過（PR 不會觸發）；未設 branch protection；無 deploy job；未做映像掃描；非獨立審查。

Risks: 推送後 GHCR package 預設 private；`cancel-in-progress` 在功能分支上會取消舊 run（預期行為）。

Next: 決定部署目標後加 deploy job；設定 required checks；#17 API 文件（Swagger）。
