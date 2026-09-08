# esun-ops — Python 維運與驗證工具箱

針對 ESUN Shopping Demo（Spring Boot + MySQL）的 Python CLI，涵蓋
**批次資料匯入**、**併發壓測**、**資料一致性稽核** 與 **API 自動化測試**。

## 為什麼需要這個工具

Java 後端本身沒有任何測試，而下單流程牽涉 `@Transactional`、Stored Procedure
與庫存扣減三個環節——這類跨層行為用 Mockito 是驗不出來的，必須打真實的 API
再回頭查真實的資料庫。這支工具就是為此而生，並刻意遵守一條原則：

> **驗證一律直接查 SQL，不透過被測的 API。**
> 否則一旦後端邏輯有問題，驗證程式會跟著一起錯。

## 安裝

```bash
cd tools
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

連線設定預設對齊 `backend/src/main/resources/application.yml` 與
`docker-compose.yml`，可用環境變數覆寫：

| 變數 | 預設值 |
| --- | --- |
| `ESUN_API_BASE` | `http://localhost:8080` |
| `ESUN_DB_HOST` / `ESUN_DB_PORT` | `127.0.0.1` / `3307` |
| `ESUN_DB_USER` / `ESUN_DB_PASSWORD` | `root` / `pierce` |
| `ESUN_DB_NAME` | `esun_shop` |

## 指令

### `health` — 環境檢查

```bash
python -m esun_ops health
```

確認後端 API 與 MySQL 都連得上，是其他指令的前置檢查。

### `seed` — CSV 批次匯入商品

```bash
python -m esun_ops seed --csv data/products.csv
python -m esun_ops seed --csv data/products.csv --dry-run
```

在送出前先做一次本地欄位驗證（對齊後端的 Bean Validation 規則），
連線層失敗採指數退避重試，業務錯誤則不重試；結束後輸出成功／已存在／
格式錯誤／失敗的彙總表。可重複執行——已存在的商品會被歸類為「已存在」而非失敗。

### `bench` — 併發下單壓測 + 一致性驗證

```bash
python -m esun_ops bench --product P002 --orders 50 --workers 20
```

所有 worker 先卡在同一道起跑閘門，再同時送出請求，藉此把競態問題放到最大。
壓測前後各取一次 SQL 快照，驗證四組不變量：

1. **庫存扣減正確** — `扣減量 == 成功筆數 × 每單數量`（抓超賣／漏扣）
2. **訂單筆數相符** — 新增的主檔數量等於成功筆數（抓失敗訂單留下髒資料）
3. **訂單編號不重複** — 相異編號數等於成功筆數
4. **資料一致性稽核** — 見下方 `audit`

失敗時回傳 exit code 1，可直接串進 CI。

### `audit` — 資料一致性稽核

```bash
python -m esun_ops audit
```

不需要壓測也能單獨執行的對帳檢查：

- 訂單主檔總價 vs 明細加總
- 明細小計 vs 單價 × 數量
- 有主檔卻沒有明細的訂單（Transaction 中途失敗的跡象）
- 負庫存
- 重複訂單編號

### `reset` — 還原測試資料

```bash
python -m esun_ops reset -y
```

執行 `backend/DB/reset.sql`，把 product / shop_order / order_detail 還原成初始狀態。

## 測試

```bash
pytest                    # 全部
pytest tests/unit         # 純邏輯，不需要啟動任何服務
pytest tests/integration  # 需要 MySQL + Spring Boot，未啟動則自動 skip
```

- `tests/unit` — 欄位驗證、統計與不變量判斷邏輯
- `tests/integration` — 對執行中的後端做黑箱契約測試，每個測試前自動 reset 資料庫

整合測試涵蓋：庫存扣減、多商品總價計算、庫存不足時的完整回滾、
不存在的商品、Bean Validation 各種邊界、XSS 跳脫，以及下方的訂單編號缺陷。

## 這支工具找到的問題

| # | 問題 | 位置 | 說明 |
| --- | --- | --- | --- |
| 1 | ~~訂單編號同秒撞主鍵~~ **已修復** | `OrderService.generateOrderId()` | 原編號格式為 `Ms` + `yyyyMMddHHmmss`，只精確到秒，而 `shop_order.order_id` 是主鍵，同一秒內的第二張訂單必然失敗。已改為毫秒時間戳 + 程序內原子序號（`Ms` + `yyyyMMddHHmmssSSS` + 3 位序號），同一毫秒可產生 1000 組不重複編號。對應測試：`test_two_orders_in_the_same_second_both_succeed`、`test_concurrent_orders_in_same_millisecond_all_succeed` |
| 2 | **錯誤一律回 HTTP 200** | `GlobalExceptionHandler` | 業務失敗與系統錯誤都回 200，只靠 body 的 `success` 欄位區分。任何標準 HTTP client、監控或 API Gateway 都無法辨識失敗 |
| 3 | **跳脫後長度可能溢位** | `ProductService.escapeHtml()` | `@Size(max = 100)` 檢查的是跳脫「前」的長度，但單引號會展開成 6 個字元寫入 `VARCHAR(100)`。應先跳脫再驗長度，或把 escape 移到輸出端 |
| 4 | **下單有 N+1 查詢** | `OrderService.createOrder()` | 每個商品呼叫 `findById` 兩次（檢查一次、建明細一次） |
| 5 | **DB 初始化順序錯誤** | `docker-compose.yml` | `docker-entrypoint-initdb.d` 依檔名字母序執行，原本會讓 `data.sql` 先於 `schema.sql`。已改為掛載時加上編號前綴修正 |

## 專案結構

```
tools/
├─ esun_ops/
│  ├─ config.py    連線設定（環境變數覆寫）
│  ├─ client.py    REST API client
│  ├─ db.py        SQL 驗證與稽核查詢
│  ├─ seed.py      CSV 批次匯入
│  ├─ bench.py     併發壓測與不變量計算
│  └─ cli.py       argparse 命令列入口
├─ tests/
│  ├─ unit/        純邏輯測試
│  └─ integration/ 黑箱契約測試
├─ data/products.csv
└─ requirements.txt
```
