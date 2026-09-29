# 059 — Phase 3.3 #18 B3 VM 準備提示詞

給下一個 session（或現在這個 session 直接接續）用的提示詞。目標：把「VM 一旦到位就能立刻接上 B3」所需的東西先準備好。B3 本身（GitHub Actions deploy job／GitHub Environment 核准／部署腳本）**不在本提示詞範圍**，那是 VM 就緒之後的下一步。

## 人機分工（先講清楚——有些事我做不到）

**只能使用者自己做**（建立帳號、花錢屬於安全規則明確禁止我代辦的事項）：
1. 挑一家雲端服務商，租一台 **Ubuntu 22.04 或 24.04 LTS** VM。因為決定維持自架 Ollama（見 `docs/tasks/057` §0），記憶體規格要抓比純 web 服務更高一級——實際數字建議先參考 B2 本機驗收時 `docker stats` 觀察到的 Ollama 常駐用量再決定，沒有量過的話至少抓 8GB 以上起跳比較保險。
2. 記下 VM 的公網 IP。
3. 把我準備好的部署用 SSH 公鑰貼進 VM（多數雲端商在建立當下就能貼公鑰；沒有的話事後用臨時密碼登入一次手動加進 `~/.ssh/authorized_keys`）。
4. 登入 DuckDNS 網頁，把 `whitehot0321.duckdns.org` 的 IP 改成這台 VM 的公網 IP（或用之前那組 update token 呼叫 DuckDNS 的更新 API——token 不要貼給我，你自己執行就好）。

**我現在就可以先準備好，等你有 VM 立刻能用**：
1. 生成一組**專用**的部署 SSH 金鑰對（不是你平常用的金鑰，只給這次部署用），私鑰留在本機、不進 repo。
2. 寫一份 VM 初始化腳本（`scripts/vm-bootstrap.sh`）：安裝 Docker Engine + Docker Compose plugin、建立一個非 root 的部署使用者、`ufw` 只開 22（SSH）／80／443、SSH 硬化（禁密碼登入、只認金鑰）、把這個 repo clone 到 VM 上的固定路徑。
3. 一份簡短的「VM 到手後照著做」清單：貼公鑰 → 跑 bootstrap 腳本 → 更新 DuckDNS → 驗證 SSH/Docker/防火牆都正常。

## 交付物

- `deploy_key` / `deploy_key.pub`（SSH 金鑰對，私鑰只存在本機，不 commit）
- `scripts/vm-bootstrap.sh`
- 更新本文件的「執行結果」章節，記錄金鑰指紋（不是私鑰內容）與腳本涵蓋的項目

## 驗收（VM 到位後才能跑，不是現在）

- `ssh -i deploy_key <deploy-user>@<VM_IP>` 能登入
- `docker --version`、`docker compose version` 正常
- `sudo ufw status` 只顯示 22/80/443 三個埠
- `ping whitehot0321.duckdns.org` 解析到 VM 的公網 IP
- repo 已 clone 在 VM 上，`.env.prod`（真實值，只在 VM 上填，不進 repo）已就位

## 明確不做（本提示詞範圍外）

- 不建立任何雲端帳號、不代刷卡、不登入使用者的雲端主控台
- 不建立 GitHub Environment 或其 secrets（屬 B3 主線）
- 不寫 GitHub Actions 的 `deploy` job（屬 B3 主線）
- 不把 DuckDNS token 或任何雲端服務密鑰寫進 repo/Notion/對話紀錄

## 下一步

VM 真的到位、上面章節驗收都過了之後，才展開 B3 本身：GitHub Actions `deploy` job＋GitHub Environment 人工核准＋部署前備份（沿用 `scripts/mysql-backup-restore` 的邏輯，Linux 版）＋health gate＋失敗自動回滾。

## 執行結果（2026-09-29，Claude Code）

「我現在就可以先準備好」的三項已完成：

1. **部署用 SSH 金鑰對**：`ssh-keygen -t ed25519` 產生於使用者本機 `~/.ssh/esun_shop_deploy`（私鑰，權限 600）／`~/.ssh/esun_shop_deploy.pub`（公鑰，權限 644）。私鑰只存在本機，未進 repo、未貼進本文件、未貼進任何對話。公鑰指紋：`SHA256:gVt6PtXdddN1e7DLgpvvjmKs12XV1DMfw6ulWUWpzq4`（公鑰內容不是機密，已直接寫入 `scripts/vm-bootstrap.sh` 供腳本自動安裝到 VM 上）。
2. **VM 初始化腳本**：`scripts/vm-bootstrap.sh`（`bash -n` 語法檢查通過；因為還沒有真實 VM，**未在真實主機上執行過**，僅靜態核對）。涵蓋：安裝 Docker Engine + Compose plugin（官方 apt repo）、建立非 root 的 `deploy` 使用者並裝好上面那把公鑰、`ufw` 只放行 22/80/443、SSH 硬化（`PasswordAuthentication no`、`PermitRootLogin no`）、把 repo clone 到 `/opt/esun-shopping`。每一步都先檢查是否已存在，可重複執行。
3. **VM 到手後的操作清單**：已內嵌在腳本執行完的輸出裡（驗證 SSH／Docker／ufw、更新 DuckDNS、在 VM 上自己建立 `.env.prod`、手動 smoke test）。

**尚未做、也不屬於這次範圍**：任何雲端帳號/VM 本身、GitHub Environment、B3 的 deploy job。`scripts/vm-bootstrap.sh` 因為只含公鑰（非機密）與不含真實網域外的任何值，可以安全提交進 repo；已請示使用者是否提交。
