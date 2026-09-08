"""併發下單壓測與資料一致性驗證。

這支工具回答三個問題：
1. 同時湧入大量訂單時，API 還撐得住嗎？（延遲、成功率）
2. 庫存會不會被超賣？（success × 數量 是否等於實際扣減量）
3. 失敗的訂單有沒有留下髒資料？（Transaction 是否確實 rollback）

驗證一律走 SQL 直接讀 DB，不透過被測的 API，避免用有問題的程式驗證自己。
"""

from __future__ import annotations

import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field

import requests

from .client import ApiError, ShopApiClient
from .config import ApiConfig, DbConfig
from . import db


@dataclass
class Attempt:
    index: int
    ok: bool
    latency_ms: float
    order_id: str | None = None
    error: str | None = None


@dataclass
class BenchResult:
    product_id: str
    quantity_each: int
    total_orders: int
    workers: int
    wall_seconds: float
    attempts: list[Attempt] = field(default_factory=list)

    stock_before: int = 0
    stock_after: int = 0
    orders_before: int = 0
    orders_after: int = 0
    audit: list[dict] = field(default_factory=list)

    # ---------- 統計 ----------

    @property
    def succeeded(self) -> int:
        return sum(1 for a in self.attempts if a.ok)

    @property
    def failed(self) -> int:
        return len(self.attempts) - self.succeeded

    @property
    def throughput(self) -> float:
        return len(self.attempts) / self.wall_seconds if self.wall_seconds else 0.0

    @property
    def error_breakdown(self) -> Counter:
        return Counter(a.error for a in self.attempts if not a.ok)

    def latency(self, percentile: float) -> float:
        values = sorted(a.latency_ms for a in self.attempts)
        if not values:
            return 0.0
        # 最近排名法（nearest-rank），樣本數少時比線性內插好解釋
        rank = max(1, min(len(values), round(percentile / 100 * len(values))))
        return values[rank - 1]

    # ---------- 不變量 ----------

    @property
    def expected_stock_after(self) -> int:
        return self.stock_before - self.succeeded * self.quantity_each

    @property
    def stock_consistent(self) -> bool:
        return self.stock_after == self.expected_stock_after

    @property
    def orders_consistent(self) -> bool:
        return self.orders_after - self.orders_before == self.succeeded

    @property
    def unique_order_ids(self) -> int:
        return len({a.order_id for a in self.attempts if a.order_id})

    @property
    def audit_violations(self) -> int:
        return sum(check["violations"] for check in self.audit)

    @property
    def passed(self) -> bool:
        return (
            self.stock_consistent
            and self.orders_consistent
            and self.unique_order_ids == self.succeeded
            and self.audit_violations == 0
        )


_local = threading.local()


def _client(api_config: ApiConfig) -> ShopApiClient:
    """每個 worker thread 各持有一個 client。

    requests.Session 不保證 thread-safe，共用會讓連線池競爭汙染延遲數據。
    """
    if not hasattr(_local, "client"):
        _local.client = ShopApiClient(api_config)
    return _local.client


def run_benchmark(
    *,
    product_id: str,
    quantity_each: int = 1,
    total_orders: int = 50,
    workers: int = 10,
    member_id: str = "55688",
    pay_status: str = "1",
    api_config: ApiConfig | None = None,
    db_config: DbConfig | None = None,
) -> BenchResult:
    api_config = api_config or ApiConfig.from_env()
    db_config = db_config or DbConfig.from_env()

    with db.connect(db_config) as conn:
        stock_before = db.stock_snapshot(conn).get(product_id)
        if stock_before is None:
            raise ValueError(f"商品不存在: {product_id}")
        orders_before = db.order_count(conn)

    result = BenchResult(
        product_id=product_id,
        quantity_each=quantity_each,
        total_orders=total_orders,
        workers=workers,
        wall_seconds=0.0,
        stock_before=stock_before,
        orders_before=orders_before,
    )

    # 起跑閘門：讓所有 worker 盡量在同一瞬間送出請求，才問得出真正的競態問題。
    gate = threading.Event()

    def task(index: int) -> Attempt:
        client = _client(api_config)
        gate.wait()
        started = time.perf_counter()
        try:
            order_id = client.create_order(
                member_id=member_id,
                items=[(product_id, quantity_each)],
                pay_status=pay_status,
            )
            elapsed = (time.perf_counter() - started) * 1000
            return Attempt(index, True, elapsed, order_id=order_id)
        except ApiError as exc:
            elapsed = (time.perf_counter() - started) * 1000
            return Attempt(index, False, elapsed, error=exc.message)
        except requests.RequestException as exc:
            elapsed = (time.perf_counter() - started) * 1000
            return Attempt(index, False, elapsed, error=f"連線錯誤: {type(exc).__name__}")

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(task, i) for i in range(total_orders)]
        time.sleep(0.2)  # 等 thread pool 把第一批 worker 都卡在閘門上
        wall_start = time.perf_counter()
        gate.set()
        for future in as_completed(futures):
            result.attempts.append(future.result())
        result.wall_seconds = time.perf_counter() - wall_start

    result.attempts.sort(key=lambda a: a.index)

    with db.connect(db_config) as conn:
        result.stock_after = db.stock_snapshot(conn).get(product_id, 0)
        result.orders_after = db.order_count(conn)
        result.audit = db.run_audit(conn)

    return result
