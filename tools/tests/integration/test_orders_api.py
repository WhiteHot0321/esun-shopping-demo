"""訂單 API 契約測試：POST /api/orders。

重點在 Transaction 的正確性——訂單主檔、訂單明細、庫存扣減三者必須同進同退。
"""

from __future__ import annotations

import time

import pytest

from esun_ops import db
from esun_ops.client import ApiError


def _stock(conn, product_id: str) -> int:
    return db.query(
        conn, "SELECT quantity FROM product WHERE product_id = %s", (product_id,)
    )[0]["quantity"]


def test_single_item_order_deducts_stock(client, conn):
    before = _stock(conn, "P002")

    order_id = client.create_order("55688", [("P002", 3)])

    assert _stock(conn, "P002") == before - 3

    order = db.query(
        conn, "SELECT member_id, price FROM shop_order WHERE order_id = %s", (order_id,)
    )[0]
    assert order["member_id"] == "55688"
    assert float(order["price"]) == 1200 * 3


def test_multi_item_order_totals_are_correct(client, conn):
    """總價必須等於各明細小計加總，明細小計必須等於單價 × 數量。"""
    order_id = client.create_order("1001", [("P002", 2), ("P003", 1)])

    order_price = float(
        db.query(
            conn, "SELECT price FROM shop_order WHERE order_id = %s", (order_id,)
        )[0]["price"]
    )
    details = db.query(
        conn,
        """
        SELECT product_id, quantity, stand_price, item_price
        FROM order_detail WHERE order_id = %s ORDER BY product_id
        """,
        (order_id,),
    )

    assert len(details) == 2
    for row in details:
        assert float(row["item_price"]) == float(row["stand_price"]) * row["quantity"]

    assert order_price == 1200 * 2 + 8500 * 1
    assert order_price == sum(float(r["item_price"]) for r in details)


def test_insufficient_stock_leaves_no_trace(client, conn):
    """庫存不足時，訂單主檔、明細、庫存都必須維持原狀。"""
    stock_before = {"P001": _stock(conn, "P001"), "P002": _stock(conn, "P002")}
    orders_before = db.order_count(conn)

    with pytest.raises(ApiError) as exc_info:
        # P002 買得到，P001 只有 5 個卻要買 999 個
        client.create_order("55688", [("P002", 1), ("P001", 999)])

    assert "庫存不足" in exc_info.value.message
    assert _stock(conn, "P001") == stock_before["P001"]
    assert _stock(conn, "P002") == stock_before["P002"], "整筆訂單失敗時不可扣掉任何庫存"
    assert db.order_count(conn) == orders_before, "失敗的訂單不可留下主檔"


def test_nonexistent_product_is_rejected(client, conn):
    orders_before = db.order_count(conn)

    with pytest.raises(ApiError) as exc_info:
        client.create_order("55688", [("P999", 1)])

    assert "商品不存在" in exc_info.value.message
    assert db.order_count(conn) == orders_before


@pytest.mark.parametrize(
    "member_id, pay_status, items",
    [
        ("", "1", [("P002", 1)]),
        ("55688", "9", [("P002", 1)]),
        ("55688", "1", []),
        ("55688", "1", [("P002", 0)]),
        ("55688", "1", [("P002", -1)]),
    ],
)
def test_invalid_order_payload_is_rejected(client, conn, member_id, pay_status, items):
    orders_before = db.order_count(conn)

    with pytest.raises(ApiError):
        client.create_order(member_id, items, pay_status=pay_status)

    assert db.order_count(conn) == orders_before


def test_two_orders_in_the_same_second_both_succeed(client, conn):
    """同一秒內連續下兩張訂單，兩張都應該成功且訂單編號相異。

    OrderService.generateOrderId() 原本只精確到秒（Ms + yyyyMMddHHmmss），
    而 shop_order.order_id 是主鍵，因此同一秒內的第二張訂單必然撞主鍵。
    現已改為毫秒時間戳 + 程序內原子序號（Ms + yyyyMMddHHmmssSSS + 3 位序號），
    此測試驗證修復後兩張訂單皆能成功且編號不重複。
    """
    # 對齊到秒的開頭，讓兩次請求確實落在同一秒內
    time.sleep(1.0 - (time.time() % 1.0))

    first = client.create_order("55688", [("P002", 1)])
    second = client.create_order("55688", [("P002", 1)])

    assert first != second
    assert db.order_count(conn) == 3 + 2


def test_concurrent_order_ids_are_unique_among_successes(client, conn):
    """同一毫秒內大量併發下單，成功的訂單編號必須全部相異（序號防線）。

    這裡刻意只驗證「成功訂單的編號不重複」，不要求「全部都要成功」：
    高併發下單獨存在一個未修的死鎖問題（見 tools/README.md 已知問題 #6），
    同一商品被多個交易同時處理時，MySQL 可能回報
    「Deadlock found when trying to get lock」而讓部分訂單失敗，這是與
    訂單編號產生邏輯無關的另一個問題，不該讓這個測試連帶紅燈。
    完整的併發正確性驗證（成功率、延遲、資料一致性）交給
    `python -m esun_ops bench` 處理。
    """
    from concurrent.futures import ThreadPoolExecutor

    def place_order(_: int) -> str | None:
        try:
            return client.create_order("55688", [("P002", 1)])
        except ApiError:
            return None

    with ThreadPoolExecutor(max_workers=20) as pool:
        order_ids = [oid for oid in pool.map(place_order, range(20)) if oid is not None]

    assert order_ids, "全部併發請求都失敗，無法驗證編號唯一性"
    assert len(set(order_ids)) == len(order_ids)
    assert db.order_count(conn) == 3 + len(order_ids)
