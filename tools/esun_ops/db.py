"""直接連 MySQL 的輔助層。

壓測與稽核必須繞過 API 才有意義：如果用同一條 API 路徑去驗證 API 的正確性，
一旦後端邏輯有 bug，驗證也會跟著錯。這裡一律以 SQL 讀取真實狀態。
"""

from __future__ import annotations

import re
from contextlib import contextmanager
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterator

import pymysql
from pymysql.cursors import DictCursor

from .config import DbConfig


@contextmanager
def connect(config: DbConfig | None = None) -> Iterator[pymysql.connections.Connection]:
    cfg = config or DbConfig.from_env()
    conn = pymysql.connect(
        host=cfg.host,
        port=cfg.port,
        user=cfg.user,
        password=cfg.password,
        database=cfg.database,
        charset="utf8mb4",
        cursorclass=DictCursor,
        autocommit=True,
    )
    try:
        yield conn
    finally:
        conn.close()


def query(conn: pymysql.connections.Connection, sql: str, args: Any = None) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(sql, args)
        return list(cur.fetchall())


# ---------- 庫存快照 ----------


def stock_snapshot(conn: pymysql.connections.Connection) -> dict[str, int]:
    rows = query(conn, "SELECT product_id, quantity FROM product")
    return {row["product_id"]: int(row["quantity"]) for row in rows}


def order_count(conn: pymysql.connections.Connection) -> int:
    return int(query(conn, "SELECT COUNT(*) AS c FROM shop_order")[0]["c"])


# ---------- 一致性稽核 ----------

AUDIT_CHECKS: list[tuple[str, str, str]] = [
    (
        "order_total_mismatch",
        "訂單主檔總價與明細加總不符",
        """
        SELECT o.order_id,
               o.price                AS order_price,
               COALESCE(SUM(d.item_price), 0) AS detail_total
        FROM shop_order o
        LEFT JOIN order_detail d ON d.order_id = o.order_id
        GROUP BY o.order_id, o.price
        HAVING o.price <> COALESCE(SUM(d.item_price), 0)
        """,
    ),
    (
        "item_price_mismatch",
        "明細小計不等於單價 × 數量",
        """
        SELECT order_item_sn, order_id, product_id,
               quantity, stand_price, item_price
        FROM order_detail
        WHERE item_price <> stand_price * quantity
        """,
    ),
    (
        "order_without_detail",
        "訂單主檔沒有任何明細（Transaction 可能中途失敗）",
        """
        SELECT o.order_id
        FROM shop_order o
        LEFT JOIN order_detail d ON d.order_id = o.order_id
        WHERE d.order_id IS NULL
        """,
    ),
    (
        "negative_stock",
        "商品庫存為負數（超賣）",
        "SELECT product_id, product_name, quantity FROM product WHERE quantity < 0",
    ),
    (
        "duplicate_order_id",
        "訂單編號重複",
        """
        SELECT order_id, COUNT(*) AS c
        FROM shop_order
        GROUP BY order_id
        HAVING COUNT(*) > 1
        """,
    ),
]


def run_audit(conn: pymysql.connections.Connection) -> list[dict[str, Any]]:
    results = []
    for key, description, sql in AUDIT_CHECKS:
        rows = query(conn, sql)
        results.append(
            {
                "key": key,
                "description": description,
                "violations": len(rows),
                "rows": rows[:10],
            }
        )
    return results


# ---------- 執行 SQL 檔 ----------

_COMMENT = re.compile(r"^\s*--.*$", re.MULTILINE)


def run_sql_file(conn: pymysql.connections.Connection, path: Path) -> int:
    """執行不含 DELIMITER 區塊的 SQL 檔（例如 reset.sql）。

    刻意不支援 stored procedure 的 DELIMITER 語法——那類檔案應交給
    mysql CLI 或 docker-entrypoint-initdb.d 處理，這裡不重造一個半套的 parser。
    """
    raw = path.read_text(encoding="utf-8")
    if "DELIMITER" in raw.upper():
        raise ValueError(
            f"{path.name} 含有 DELIMITER 區塊，請改用 mysql CLI 執行"
        )

    statements = [s.strip() for s in _COMMENT.sub("", raw).split(";")]
    statements = [s for s in statements if s]

    with conn.cursor() as cur:
        for statement in statements:
            cur.execute(statement)
    return len(statements)


def to_plain(value: Any) -> Any:
    """Decimal 轉成可讀輸出。"""
    return float(value) if isinstance(value, Decimal) else value
