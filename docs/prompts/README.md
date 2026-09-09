# 提示詞紀錄（docs/prompts/）

這個目錄保存每一個開發 session 實際使用的提示詞，讓需求討論的結論不會只存在於某次
對話 session 裡。目的：

- 開發 session（Sonnet）能拿到明確、可自我檢核的任務範圍，而不是憑對話記憶重建需求。
- Review session（Opus）能對照「原始意圖 vs 實際實作」，而不只是單看程式碼好不好；
  尤其能判斷「這個改動是不是動到了不該動的既有介面」。
- 未來任何人（包含你自己）回頭看某個 phase 為什麼這樣做時，有紀錄可查，不用重新考古
  git log 或猜測。

## 使用方式

1. 需求討論定案後，複製 [TEMPLATE.md](TEMPLATE.md) 為新檔案，命名規則：

   ```
   phase-<N>-<X.Y>-<slug>.md
   ```

   例如 `phase-1-4.2-sp-products.md`，`<N>-<X.Y>` 對應開發用的 branch 名稱
   （`phase-1-4.2-sp-products` → branch `phase-1-4.2-sp-products`），`<slug>` 是這個
   任務的簡短英文描述。

2. 填完範本後，把檔案內容（或其中的「給開發 session 的提示詞」段落）直接貼給開發用的
   Sonnet session 當作任務指示。

3. 開發完成、commit 時，在 commit message 裡引用這份檔案（例如
   `Phase 1 4.2: Wire sp_get_available_products — 見 docs/prompts/phase-1-4.2-sp-products.md`），
   方便 review 時對照。

4. Review 時（`/code-review`），把這份提示詞的「驗收條件」與「不可修改的既有介面」
   當作檢查項目之一，不是只看 diff 本身合不合理。

## 命名與狀態

檔案一旦建立就不要事後改寫「目標」與「不可修改的既有介面」兩節——如果需求變了，
新增一份 `-v2` 或開下一個 phase，保留歷史紀錄的真實性。「驗收結果」一節可以在開發完成
後補上實際結果。
