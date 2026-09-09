# CLAUDE.md — 專案常數與環境

簡易電商系統（Vue 3 + Spring Boot + MySQL）。專案採 vibe-coding 流程：需求討論 →
提示詞（`docs/prompts/`）→ Sonnet 開發 → Opus review。本文件是所有 session 的共同起點。

## 技術棧（固定）

| 層 | 技術 | 決策理由 |
| --- | --- | --- |
| 前端 | Vue 3 + Vite | SPA |
| 後端 | Spring Boot 3.3.5 + **Spring JDBC**（不是 JPA） | 題目要求用 Stored Procedure |
| DB | MySQL 8 | — |
| HTTP 客戶端 | **單一 axios 實例**（`frontend/src/api/client.js`） | 避免重複定義、死碼 |
| 建置 | Maven + npm | — |

**刻意不用的東西**：Hibernate/JPA、Vuex/Pinia、複數 axios 實例。

## 環境連線

```yaml
MySQL:
  Host: 127.0.0.1:3307（非 3306）
  DB: esun_shop
  User: root / pierce
  
後端 API: http://localhost:8080/api
前端: http://localhost:5173
  - 環境變數: VITE_API_BASE_URL（預設 http://localhost:8080/api）
```

`docker-compose.yml` 與 `application.yml` 必須一致；DB 初始化腳本檔名前綴 `01_/02_/03_` 保證執行順序。

## 測試手段

`backend/src` 沒有 Java 測試，唯一驗證工具是 `tools/esun-ops`（Python CLI）：

```bash
cd tools && python -m venv .venv && .venv\Scripts\activate && pip install -r requirements.txt
pytest tests/unit                # 純邏輯
pytest tests/integration         # 需要 MySQL + Spring Boot
python -m esun_ops bench --product <id> --orders <n> --workers 20  # 併發壓測
```

**任何改動 Order/Product/SP/DB 的提示詞開發完都要跑 `bench`**，不能只靠 code review。

## 已知問題（6 項）

詳見 [tools/README.md](tools/README.md#這支工具找到的問題)；開發前掃一眼避免踩坑：

| # | 問題 | 修狀態 |
| --- | --- | --- |
| 1 | 訂單編號同秒重複 | ✅ 已修 |
| 2 | 錯誤一律 HTTP 200 | ❌ |
| 3 | HTML escape 順序錯 | ❌ |
| 4 | OrderService N+1 查詢 | ❌ |
| 5 | DB init 順序 | ✅ 已修 |
| 6 | 併發死鎖（20 concurrent 失敗率 50%） | ❌ |

## 分支慣例

- **Branch**：`phase-<N>-<X.Y>[-slug]`（如 `phase-1-4.1-axios-unified`）
- **Commit**：`Phase <N> <X.Y>: <做什麼>`，尾部加 `Co-Authored-By: Claude <model> ...`
- **提示詞**：見 [`docs/prompts/`](docs/prompts/README.md)，每個開發 session 對應一份紀錄

## 開發流程

1. 在 `docs/prompts/` 新增提示詞紀錄 → 開 branch
2. 依提示詞開發；觸碰後端邏輯時跑 `tools/tests/` + `bench`
3. 開 PR 前跑 `/code-review high`（或 `/security-review` for 交易/庫存）
4. Review 過 + 測試綠 → 合併
