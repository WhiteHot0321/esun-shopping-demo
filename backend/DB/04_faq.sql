USE esun_shop;

CREATE TABLE IF NOT EXISTS faq (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    question   VARCHAR(500) NOT NULL,
    answer     TEXT NOT NULL,
    category   VARCHAR(50),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS doc_embedding (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_type ENUM('product', 'faq') NOT NULL,
    source_id   VARCHAR(20) NOT NULL,
    content     TEXT NOT NULL,
    embedding   JSON NOT NULL,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_doc_embedding_source (source_type, source_id)
);

INSERT INTO faq (question, answer, category) VALUES
('如何申請退換貨？', '商品到貨 7 天內若無使用痕跡且保持原包裝，可透過客服申請退換貨，請提供訂單編號與商品狀況照片。', '退換貨'),
('退款需要多久時間？', '退貨商品經確認無誤後，約 5-7 個工作天內原路退款至您的付款帳戶。', '退換貨'),
('目前提供哪些付款方式？', '目前支援信用卡付款與貨到付款兩種方式，結帳時可自由選擇。', '付款'),
('商品出貨後多久會到貨？', '一般商品下單後 2-3 個工作天內出貨，偏遠地區可能需要額外 1-2 天。', '出貨'),
('商品顯示缺貨怎麼辦？', '缺貨商品會暫時無法下單，建議關注商品頁面或聯繫客服詢問到貨時間。', '庫存'),
('可以開立發票嗎？', '每筆訂單皆會開立電子發票，發票將於出貨後寄送至您註冊的電子信箱。', '發票'),
('訂單建立後可以修改商品嗎？', '訂單一旦建立即進入處理流程，無法直接修改，如需調整請聯繫客服協助取消後重新下單。', '訂單'),
('如何查詢我的訂單狀態？', '會員可於會員中心查看歷史訂單與目前配送狀態，或提供訂單編號給客服查詢。', '訂單'),
('商品保固期限是多久？', '一般商品保固期為 1 年，詳細保固條款請參考各商品頁面說明。', '保固'),
('是否支援海外配送？', '目前僅提供台灣本島與離島配送服務，暫不支援海外訂購。', '出貨');
