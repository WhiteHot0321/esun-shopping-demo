---
name: esun-shopping-notion
description: 開發 esun-shopping（玉山 Spring Boot MVC 購物車專案）時，讀取本地 Markdown 與 Notion 規格、核對實作並同步進度。適用於本專案開發、交接、規劃與進度更新。
---

# esun-shopping 專案協作

以繁體中文協作。專案目標是透過既有購物車專案深化後端與全端工程實務。Claude Code 與 Codex 共用以下資料來源與進度規則；各自使用自己的 Notion 連線，skill 本身不授予頁面權限。

## 資料來源與入口

本地 Markdown（例如 AGENTS.md、CLAUDE.md、README.md、docs）與以下 Notion 主頁及其相關子頁共同構成專案文件。不要假設工作目錄已包含程式碼；先檢查檔案與 Git 分支。

- [主頁](https://app.notion.com/p/3bd708da9f9281da9c1cc36bd60e7f8f)：專案目標、Git 策略及全部子頁入口。
- [專案介紹](https://app.notion.com/p/3c5708da9f92800782fceeed862b786a)：架構、資料結構、歷史修復說明。
- [進度追蹤](https://app.notion.com/p/3c4708da9f9280d89606c93c3a6d3e53)：Phase 1 清單、Phase 2 候選與 Tasks 入口。
- [執行計劃](https://app.notion.com/p/3d4708da9f9281dc8b3ddb0423cbc0ad)：Phase 1 收尾與 Phase 2 計劃。
- [技術分析](https://app.notion.com/p/3c5708da9f9280f987cade36881ec404)：需要理解技術決策時讀取。
- [訂單客服問答系統 RAG](https://app.notion.com/p/3d7708da9f92816591ced6c89a5de7b8)：處理 RAG 工作時讀取。

其他主題（提示詞、面試、未來技術、Python 方案）由主頁取得最新子頁連結，按任務需要讀取。Notion 連結寫著 CLAUDE.md 不代表本地檔案存在。

## 開發前

1. 讀取適用的本地 Markdown，確認 git status、目前分支與實際程式碼。讀取 Notion 主頁、進度追蹤、執行計劃及本次相關子頁；注意截斷或無法讀取的區塊。
2. 使用者目前明確要求優先；文件說明需求與計劃，程式碼、commit 與測試佐證實作。遇到文件互相矛盾，指出差異並核對證據，不能僅依修改時間或勾選框宣稱已完成。
3. 已知 2026-09-14 讀取時，「專案介紹」與「進度追蹤」的 Phase 1 完成狀態不同；這是待核對的歷史觀察，不是永久狀態。
4. Notion 記載 main 保留面試原始版本，新開發使用 advanced-v2。先確認分支存在與工作樹狀態；不擅自修改 main、重設工作樹或還原他人變更。缺少 repo 或分支時明確回報，不能假裝已檢查實作。

## 實作重點

文件描述 Vue 3 前端、Spring Boot Controller → Service → Repository、MySQL 與 Stored Procedure。版本、啟動方式、測試命令以取得的專案設定為準。

修改訂單流程時保留後端計價、BigDecimal 金額、Service 交易邊界與條件式庫存扣減；訂單主檔、明細與庫存異動需同一交易。針對本次變更驗證錯誤狀態碼、驗證、回滾或併發等相關行為。Phase 2 候選不等同已核准或已實作功能。

## 任務分流與角色

依目前 repo 的 AGENTS.md 及 [AI 開發協作](https://app.notion.com/p/3db708da9f92819dbd00e7dfee4f5ab6) 選擇協作方式：小型可逆修改由單方完成並適度驗證；訂單、庫存、付款、權限、交易與併發等關鍵修改由另一方獨立審查。Codex 與 Claude 可交換實作／審查角色。審查先從需求、程式碼和測試形成判斷，再閱讀作者說明。於既有任務結果記錄耗時、使用者介入、返工輪數、有效缺陷與可取得的 token／成本；無資料寫未知。

## 同步進度與交接

使用者已要求透過此專案 Notion 追蹤開發。開發任務有實際進展、阻礙或完成時，同步相關進度；純查詢不必新增紀錄。不將本規則擴大到無關頁面或分享權限變更。

- 寫入前重新讀取目標及附近內容，避免覆蓋另一位開發者剛更新的內容。使用最小範圍修改，保留子頁、資料庫、原有順序與其他人的記錄。
- 優先更新既有對應任務；若涉及 Tasks 資料庫，先讀取 schema 與實際項目，再使用正確狀態選項。沒有對應項目時，在「進度追蹤」追加簡短紀錄即可，避免另建重複追蹤系統。
- 紀錄包含：Asia/Taipei 日期時間、執行者（Claude Code 或 Codex）、任務與狀態、分支與 commit（尚未提交則明說）、實際變更、測試命令與結果、阻礙、下一步。只有驗證完成才勾選完成；未執行的測試寫「未執行」及原因。
- 寫入逾時或結果不明，先讀回核對是否已生效再重試，以免重複追加。寫完讀回確認，最終回覆附上更新頁面連結。
- 讀取成功或 MCP 顯示 Connected 只證明可讀或已連線，不證明可寫。各執行環境必須以自己的實際寫入及讀回確認權限，不能沿用另一位代理的驗證結論。
- 若 Notion 不可用，保留待同步內容與原因，清楚回報尚未同步；可继续不依賴缺失資料的本地工作，不宣稱線上更新成功。不將 token 或登入憑證放進文件。

## Skill 維護

專案可攜版本位於 `.claude/skills/esun-shopping-notion/SKILL.md`；Codex 個人安裝位於 `~/.codex/skills/esun-shopping-notion/SKILL.md`。修改協作規則時同步兩份內容。本 skill 不保存易過時的完整進度快照，每次從 Notion 取得最新資訊。
