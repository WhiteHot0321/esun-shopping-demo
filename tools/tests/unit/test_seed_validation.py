"""seed 的本地欄位驗證邏輯——不需要後端或資料庫即可執行。"""

from __future__ import annotations

from decimal import Decimal

import pytest

from esun_ops.seed import validate_row


def test_valid_row_is_normalised():
    payload, error = validate_row(
        {"productId": " P101 ", "productName": " 藍牙耳機 ", "price": "1990", "quantity": "30"}
    )

    assert error == ""
    assert payload == {
        "product_id": "P101",
        "product_name": "藍牙耳機",
        "price": Decimal("1990"),
        "quantity": 30,
    }


@pytest.mark.parametrize(
    "row, expected_fragment",
    [
        ({"productId": "  ", "productName": "A", "price": "1", "quantity": "1"}, "productId 不可為空"),
        ({"productId": "P" * 21, "productName": "A", "price": "1", "quantity": "1"}, "超過 20"),
        ({"productId": "P1", "productName": "", "price": "1", "quantity": "1"}, "productName 不可為空"),
        ({"productId": "P1", "productName": "N" * 101, "price": "1", "quantity": "1"}, "超過 100"),
        ({"productId": "P1", "productName": "A", "price": "abc", "quantity": "1"}, "不是合法數值"),
        ({"productId": "P1", "productName": "A", "price": "-1", "quantity": "1"}, "price 不可為負數"),
        ({"productId": "P1", "productName": "A", "price": "1", "quantity": "1.5"}, "不是整數"),
        ({"productId": "P1", "productName": "A", "price": "1", "quantity": "-1"}, "quantity 不可為負數"),
    ],
)
def test_invalid_rows_are_rejected_with_reason(row, expected_fragment):
    payload, error = validate_row(row)

    assert payload is None
    assert expected_fragment in error


def test_price_keeps_decimal_precision():
    """用 Decimal 而非 float，避免與後端 BigDecimal 對不起來。"""
    payload, _ = validate_row(
        {"productId": "P1", "productName": "A", "price": "1234.56", "quantity": "1"}
    )

    assert payload["price"] == Decimal("1234.56")
