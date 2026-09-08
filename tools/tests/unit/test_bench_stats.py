"""壓測結果的統計與不變量判斷——不需要後端或資料庫即可執行。"""

from __future__ import annotations

from esun_ops.bench import Attempt, BenchResult


def _result(**overrides) -> BenchResult:
    defaults = dict(
        product_id="P002",
        quantity_each=1,
        total_orders=4,
        workers=4,
        wall_seconds=2.0,
    )
    defaults.update(overrides)
    return BenchResult(**defaults)


def test_latency_percentiles_use_nearest_rank():
    r = _result()
    r.attempts = [Attempt(i, True, latency) for i, latency in enumerate([10, 20, 30, 40])]

    assert r.latency(50) == 20
    assert r.latency(95) == 40
    assert r.latency(100) == 40


def test_latency_on_empty_result_is_zero():
    assert _result().latency(95) == 0.0


def test_stock_consistency_detects_oversell():
    """成功 3 筆、每筆買 1 個，庫存卻掉了 4 個 —— 必須判定為不一致。"""
    r = _result(stock_before=10)
    r.attempts = [Attempt(i, True, 5.0, order_id="A{}".format(i)) for i in range(3)]
    r.stock_after = 6

    assert r.expected_stock_after == 7
    assert r.stock_consistent is False
    assert r.passed is False


def test_duplicate_order_ids_fail_the_run():
    r = _result(stock_before=10, stock_after=8, orders_before=0, orders_after=2)
    r.attempts = [
        Attempt(0, True, 5.0, order_id="Ms20250908120000"),
        Attempt(1, True, 5.0, order_id="Ms20250908120000"),
    ]

    assert r.unique_order_ids == 1
    assert r.succeeded == 2
    assert r.passed is False


def test_clean_run_passes_every_invariant():
    r = _result(stock_before=10, stock_after=8, orders_before=0, orders_after=2)
    r.attempts = [
        Attempt(0, True, 5.0, order_id="Ms1"),
        Attempt(1, True, 7.0, order_id="Ms2"),
        Attempt(2, False, 6.0, error="商品庫存不足: P002"),
    ]
    r.audit = [{"key": "k", "description": "d", "violations": 0, "rows": []}]

    assert r.succeeded == 2
    assert r.failed == 1
    assert r.stock_consistent is True
    assert r.orders_consistent is True
    assert r.passed is True
    assert r.error_breakdown["商品庫存不足: P002"] == 1
