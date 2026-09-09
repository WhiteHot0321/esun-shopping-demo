# 執行計劃

## 階段 A：收尾 Phase 1（單一 session，~30 分鐘）

兩項都很小、不重疊，依序做完：

- [ ] **4.1** 刪 `frontend/src/api.js` 死碼；`App.vue` 三處 `fetch` 改用 axios instance，讀 `VITE_API_BASE_URL`
- [ ] **4.2** `sp_get_available_products`：接上或刪除（選一），同步清 README 引用

**Checkpoint**：前端網路請求都經過 axios instance；ProductRepository 沒有死碼 call 物件。

提示詞：見 [`docs/prompts/phase-1-4.x.md`](prompts/phase-1-4.x.md)

---

## 階段 B：Phase 2 分工策略

不是所有項目都值得平行，看依賴關係：

| 候選項 | 觸碰範圍 | 與他項衝突？ | 建議做法 |
| --- | --- | --- | --- |
| **JWT Auth** | 幾乎所有 Controller（加驗證守衛） | ✅ 會——跟任何同時改 Controller 的工作都會撞 | **獨立開 branch/worktree**，一次性 merge |
| Unit tests（OrderService/ProductService） | 純新增測試檔 | ❌ 不會 | 主 session 或丟給子代理 |
| Testcontainers 併發測試 | 同上 | ❌ 不會 | 同上 |
| CI/CD workflow | 純新增 `.github/workflows/*.yml` | ❌ 不會 | 丟給子代理 |
| App.vue 元件拆分 | 只動前端 | ✅ 跟 4.1 會衝——要先做完 4.1 | 與 JWT Auth **平行**（前後端不重疊） |

**結論安排**：

1. **Track 1（主力）**：開獨立 worktree 做 **JWT Auth**。最容易與他項衝突，值得單獨隔離。
2. **Track 2（前端）**：同時另開 worktree 或直接主 session 做 **App.vue 拆分**（先確認 4.1 已完成）。與 Track 1 不重疊，可真正平行。
3. **Track 3（低風險附加件）**：**Unit tests + CI/CD** 不用開 worktree，直接丟 `Agent` 子代理，純新增、幾乎不會出錯。

**排序建議**：先開 JWT Auth（最容易衝突，越早隔離越好），前端拆分與測試/CI 接著或平行填上。

---

## 階段 C：合併順序

建議 merge 回主線的順序：

1. **Phase 1 收尾**（4.1 + 4.2）——小、快，先進主線
2. **Unit tests + CI/CD**（純新增，隨時可合，不會衝突）
3. **App.vue 拆分**（前端獨立，確認 4.1 已合）
4. **JWT Auth 最後合**（範圍最大、最容易衝突，留到最後單獨處理 conflict）

**Checkpoint**：每一步 merge 完都跑 `mvn test` + `npm run build`，確認沒碰到兩邊同時改同一檔案的潛在問題。

---

## 提示詞位置

- Phase 1 收尾：[`phase-1-4.x.md`](prompts/phase-1-4.x.md)
- Phase 2 詳細項目：見 [`docs/prompts/`](prompts/README.md)，包含 Phase 1.5（驗證層）與 Phase 2 各項
