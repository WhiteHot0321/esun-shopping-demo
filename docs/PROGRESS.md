# 進度追蹤

## Phase 1：缺陷修復（11 項）

| # | 項目 | 內容 | 狀態 | Commit / 佐證 |
| --- | --- | --- | --- | --- |
| 1.1 | Docker/app.yml 不一致 | Port、密碼互相對不起來 | ✅ | `9260f11` |
| 1.2 | DB init 腳本順序 | `data.sql` 先於 `schema.sql` | ✅ | `84b6f79` |
| 2.1 | HTTP 狀態碼 | 所有錯誤回 200 | ✅ | `a7924c0` |
| 2.2 | DTO 驗證 | `@NotNull` 不全、payStatus 無 enum | ✅ | `8dee932` |
| 2.3 | Logging | `printStackTrace()` 未用 SLF4J | ✅ | `a7924c0` |
| 3.1 | 訂單編號 | 秒級精度、同秒碰撞 | ✅ | `36185d9` |
| 3.2 | N+1 查詢 | 每筆訂單查 2n 次 product | ✅ | `a197192` |
| 3.3 | 死鎖 | 多商品無固定鎖順序 | ✅ | `a197192` |
| 4.1 | Axios 統一 | `api.js` 死碼、App.vue 用 `fetch()` | ❌ 未動 | — |
| 4.2 | sp_get_available_products | SP 與 call 物件都是死碼 | ❌ 未動 | — |

**進度**：9/11（下一步：4.1 + 4.2，見 [`docs/PLAN.md`](PLAN.md)）

---

## Phase 2：深化功能（候選項，正在規劃分工）

見 [`docs/PLAN.md`](PLAN.md)，包含：
- Unit tests（OrderService/ProductService）
- Testcontainers 併發整合測試  
- JWT 認證 + Member 表
- App.vue 元件拆分
- GitHub Actions CI/CD
- Swagger/OpenAPI 文件

---

## Phase 3：業務功能（新增）

計劃中，未開始。
