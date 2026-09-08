"""CSV 批次匯入商品。

用途：把 DB/data.sql 手動維護測試資料的流程，換成可重複執行、
有欄位驗證與錯誤彙總的匯入工具。
"""

from __future__ import annotations

import csv
import time
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path

import requests

from .client import ApiError, ShopApiClient

REQUIRED_COLUMNS = {"productId", "productName", "price", "quantity"}


@dataclass
class RowResult:
    line: int
    product_id: str
    status: str  # ok / invalid / duplicate / failed
    detail: str = ""


@dataclass
class SeedReport:
    results: list[RowResult] = field(default_factory=list)

    def add(self, result: RowResult) -> None:
        self.results.append(result)

    def count(self, status: str) -> int:
        return sum(1 for r in self.results if r.status == status)

    @property
    def failures(self) -> list[RowResult]:
        return [r for r in self.results if r.status not in ("ok", "duplicate")]


def validate_row(row: dict[str, str]) -> tuple[dict | None, str]:
    """在送出前先做一次本地驗證，對齊後端 Bean Validation 的規則。

    先擋掉明顯錯誤，可以少打一趟 API，錯誤訊息也比後端回的
    「productId 格式錯誤」更具體。
    """
    product_id = (row.get("productId") or "").strip()
    product_name = (row.get("productName") or "").strip()

    if not product_id:
        return None, "productId 不可為空"
    if len(product_id) > 20:
        return None, f"productId 長度 {len(product_id)} 超過 20"
    if not product_name:
        return None, "productName 不可為空"
    if len(product_name) > 100:
        return None, f"productName 長度 {len(product_name)} 超過 100"

    try:
        price = Decimal((row.get("price") or "").strip())
    except (InvalidOperation, TypeError):
        return None, f"price 不是合法數值: {row.get('price')!r}"
    if price < 0:
        return None, "price 不可為負數"

    try:
        quantity = int((row.get("quantity") or "").strip())
    except (TypeError, ValueError):
        return None, f"quantity 不是整數: {row.get('quantity')!r}"
    if quantity < 0:
        return None, "quantity 不可為負數"

    return (
        {
            "product_id": product_id,
            "product_name": product_name,
            "price": price,
            "quantity": quantity,
        },
        "",
    )


def seed_from_csv(
    client: ShopApiClient,
    csv_path: Path,
    *,
    max_retries: int = 3,
    dry_run: bool = False,
) -> SeedReport:
    report = SeedReport()

    with csv_path.open(newline="", encoding="utf-8-sig") as fh:
        reader = csv.DictReader(fh)
        missing = REQUIRED_COLUMNS - set(reader.fieldnames or [])
        if missing:
            raise ValueError(f"CSV 缺少必要欄位: {', '.join(sorted(missing))}")

        for line, row in enumerate(reader, start=2):  # 第 1 行是標題
            payload, error = validate_row(row)
            if payload is None:
                report.add(RowResult(line, row.get("productId", ""), "invalid", error))
                continue

            if dry_run:
                report.add(RowResult(line, payload["product_id"], "ok", "dry-run"))
                continue

            report.add(_send_with_retry(client, line, payload, max_retries))

    return report


def _send_with_retry(
    client: ShopApiClient, line: int, payload: dict, max_retries: int
) -> RowResult:
    product_id = payload["product_id"]

    for attempt in range(1, max_retries + 1):
        try:
            client.create_product(**payload)
            return RowResult(line, product_id, "ok")
        except ApiError as exc:
            # 業務錯誤重試沒有意義（商品已存在、欄位驗證失敗），直接記錄。
            # 「已存在」視為可接受結果，讓這支工具能重複執行而不會誤報失敗。
            if "已存在" in exc.message:
                return RowResult(line, product_id, "duplicate", exc.message)
            return RowResult(line, product_id, "failed", exc.message)
        except requests.RequestException as exc:
            # 連線層錯誤才重試，採指數退避。
            if attempt == max_retries:
                return RowResult(
                    line, product_id, "failed", f"連線失敗（重試 {max_retries} 次）: {exc}"
                )
            time.sleep(0.5 * 2 ** (attempt - 1))

    return RowResult(line, product_id, "failed", "未預期的重試流程結束")
