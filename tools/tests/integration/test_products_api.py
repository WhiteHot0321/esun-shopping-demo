"""商品 API 契約測試：POST /api/products、GET /api/products/available。"""

from __future__ import annotations

import pytest

from esun_ops import db
from esun_ops.client import ApiError


def test_available_products_only_returns_in_stock(client, conn):
    """庫存為 0 的商品不應出現在可購買清單。"""
    client.create_product("PZERO", "缺貨測試商品", "100", 0)

    listed = {p["productId"] for p in client.list_available_products()}

    assert "PZERO" not in listed
    assert {"P001", "P002", "P003"} <= listed


def test_create_product_persists_to_database(client, conn):
    client.create_product("PNEW1", "壓力測試商品", "1234.50", 7)

    rows = db.query(
        conn,
        "SELECT product_name, price, quantity FROM product WHERE product_id = %s",
        ("PNEW1",),
    )

    assert len(rows) == 1
    assert rows[0]["product_name"] == "壓力測試商品"
    assert float(rows[0]["price"]) == 1234.50
    assert rows[0]["quantity"] == 7


def test_duplicate_product_id_is_rejected(client):
    with pytest.raises(ApiError) as exc_info:
        client.create_product("P001", "重複編號商品", "100", 1)

    assert "已存在" in exc_info.value.message


@pytest.mark.parametrize(
    "product_id, product_name, price, quantity, reason",
    [
        ("", "空白編號", "100", 1, "productId 不可為空"),
        ("P" * 21, "編號過長", "100", 1, "productId 超過 20 字"),
        ("PBAD1", "", "100", 1, "productName 不可為空"),
        ("PBAD2", "負價格", "-1", 1, "price 不可為負"),
        ("PBAD3", "負庫存", "100", -1, "quantity 不可為負"),
    ],
)
def test_invalid_product_is_rejected(
    client, conn, product_id, product_name, price, quantity, reason
):
    """後端的 Bean Validation 必須擋下這些輸入，且不可留下任何資料。"""
    with pytest.raises(ApiError):
        client.create_product(product_id, product_name, price, quantity)

    rows = db.query(
        conn, "SELECT 1 FROM product WHERE product_id = %s", (product_id,)
    )
    assert rows == [], "被拒絕的商品不應寫入資料庫：{}".format(reason)


def test_product_name_is_html_escaped(client, conn):
    """XSS 防護：商品名稱中的角括號應在寫入前被跳脫。"""
    client.create_product("PXSS1", "<script>alert(1)</script>", "100", 5)

    stored = db.query(
        conn, "SELECT product_name FROM product WHERE product_id = %s", ("PXSS1",)
    )[0]["product_name"]

    assert "<script>" not in stored
    assert "&lt;script&gt;" in stored


def test_escaping_does_not_overflow_column_length(client):
    """邊界案例：名稱在跳脫「前」合法，跳脫「後」可能超過 VARCHAR(100)。

    ProductService.escapeHtml 會把單引號展開成 6 個字元（&#39;），
    因此 100 個單引號會膨脹成 600 字元。Bean Validation 的 @Size(max = 100)
    檢查的是跳脫前的長度，所以這個請求能通過驗證卻在寫入 DB 時炸掉。

    後端目前會回「資料庫操作失敗」——能擋下來，但錯誤訊息沒有指出真正原因。
    正確做法是先跳脫再驗長度，或把 escape 移到輸出端。
    """
    with pytest.raises(ApiError) as exc_info:
        client.create_product("POVER", "'" * 100, "100", 1)

    assert exc_info.value.message
