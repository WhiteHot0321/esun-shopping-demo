"""esun-ops 命令列入口。

    python -m esun_ops health
    python -m esun_ops seed   --csv data/products.csv
    python -m esun_ops bench  --product P002 --orders 50 --workers 20
    python -m esun_ops audit
    python -m esun_ops reset

所有指令在偵測到問題時回傳非 0 的 exit code，可直接串進 CI。
"""

from __future__ import annotations

import argparse
import sys
import unicodedata
from pathlib import Path

import pymysql
import requests

from . import db
from .bench import BenchResult, run_benchmark
from .client import ShopApiClient
from .config import ApiConfig, DbConfig
from .seed import seed_from_csv

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CSV = Path(__file__).resolve().parents[1] / "data" / "products.csv"
RESET_SQL = PROJECT_ROOT / "backend" / "DB" / "reset.sql"

LINE = "-" * 64


def _rule(title: str = "") -> None:
    print("\n" + LINE)
    if title:
        print(title)
        print(LINE)


def _width(text: str) -> int:
    """終端機顯示寬度。

    中文、日文等全形字在終端機佔 2 個字元寬，但 len() 只算 1，
    直接拿 len() 對齊表格會讓中文欄位跑掉。
    """
    return sum(2 if unicodedata.east_asian_width(ch) in "WF" else 1 for ch in text)


def _pad(text: str, width: int) -> str:
    return text + " " * max(0, width - _width(text))


def _table(headers: list[str], rows: list[list[str]]) -> None:
    if not rows:
        return
    cells = [[str(c) for c in row] for row in rows]
    widths = [_width(h) for h in headers]
    for row in cells:
        for i, cell in enumerate(row):
            widths[i] = max(widths[i], _width(cell))

    def line(values: list[str]) -> str:
        return "  ".join(_pad(v, w) for v, w in zip(values, widths)).rstrip()

    print(line(headers))
    print(line(["-" * w for w in widths]))
    for row in cells:
        print(line(row))


# ---------- health ----------


def cmd_health(args: argparse.Namespace) -> int:
    api_config = ApiConfig.from_env()
    db_config = DbConfig.from_env()

    api_ok = ShopApiClient(api_config).health()
    status = "OK" if api_ok else "無法連線"
    print("API  {:<32} {}".format(api_config.base_url, status))

    db_ok = True
    try:
        with db.connect(db_config) as conn:
            products = len(db.stock_snapshot(conn))
            orders = db.order_count(conn)
        target = "{}:{}/{}".format(db_config.host, db_config.port, db_config.database)
        print("DB   {:<32} OK（商品 {} 筆、訂單 {} 筆）".format(target, products, orders))
    except pymysql.Error as exc:
        db_ok = False
        target = "{}:{}".format(db_config.host, db_config.port)
        print("DB   {:<32} 無法連線: {}".format(target, exc))

    return 0 if api_ok and db_ok else 1


# ---------- seed ----------


def cmd_seed(args: argparse.Namespace) -> int:
    csv_path = Path(args.csv)
    if not csv_path.exists():
        print("找不到 CSV: {}".format(csv_path), file=sys.stderr)
        return 2

    client = ShopApiClient()
    try:
        report = seed_from_csv(
            client, csv_path, max_retries=args.retries, dry_run=args.dry_run
        )
    except ValueError as exc:
        print("CSV 格式錯誤: {}".format(exc), file=sys.stderr)
        return 2
    except requests.RequestException as exc:
        print("無法連線到後端: {}".format(exc), file=sys.stderr)
        return 2

    _rule("批次匯入結果（dry-run）" if args.dry_run else "批次匯入結果")
    print(
        "成功 {}　已存在 {}　格式錯誤 {}　失敗 {}".format(
            report.count("ok"),
            report.count("duplicate"),
            report.count("invalid"),
            report.count("failed"),
        )
    )

    problem_rows = [r for r in report.results if r.status != "ok"]
    if problem_rows:
        _rule("需要處理的資料列")
        _table(
            ["行號", "productId", "狀態", "說明"],
            [[r.line, r.product_id, r.status, r.detail] for r in problem_rows],
        )

    return 1 if report.failures else 0


# ---------- bench ----------


def cmd_bench(args: argparse.Namespace) -> int:
    try:
        result = run_benchmark(
            product_id=args.product,
            quantity_each=args.qty,
            total_orders=args.orders,
            workers=args.workers,
            member_id=args.member,
        )
    except (pymysql.Error, ValueError) as exc:
        print("壓測無法開始: {}".format(exc), file=sys.stderr)
        return 2

    _print_bench_report(result)
    return 0 if result.passed else 1


def _print_bench_report(r: BenchResult) -> None:
    _rule("壓測設定")
    print(
        "商品 {}　每單數量 {}　訂單數 {}　併發 {}".format(
            r.product_id, r.quantity_each, r.total_orders, r.workers
        )
    )

    _rule("吞吐與延遲")
    success_rate = r.succeeded / len(r.attempts) * 100 if r.attempts else 0.0
    _table(
        ["指標", "數值"],
        [
            ["總耗時", "{:.2f} s".format(r.wall_seconds)],
            ["吞吐量", "{:.1f} req/s".format(r.throughput)],
            ["成功 / 失敗", "{} / {}".format(r.succeeded, r.failed)],
            ["成功率", "{:.1f} %".format(success_rate)],
            ["延遲 P50", "{:.0f} ms".format(r.latency(50))],
            ["延遲 P95", "{:.0f} ms".format(r.latency(95))],
            ["延遲 P99", "{:.0f} ms".format(r.latency(99))],
        ],
    )

    if r.error_breakdown:
        _rule("失敗原因分佈")
        _table(
            ["次數", "後端回傳訊息"],
            [[count, msg] for msg, count in r.error_breakdown.most_common()],
        )

    _rule("資料一致性驗證")
    checks = [
        [
            "庫存扣減正確",
            "PASS" if r.stock_consistent else "FAIL",
            "{} -> {}（預期 {}）".format(
                r.stock_before, r.stock_after, r.expected_stock_after
            ),
        ],
        [
            "訂單筆數相符",
            "PASS" if r.orders_consistent else "FAIL",
            "新增 {} 筆（成功 {} 筆）".format(
                r.orders_after - r.orders_before, r.succeeded
            ),
        ],
        [
            "訂單編號不重複",
            "PASS" if r.unique_order_ids == r.succeeded else "FAIL",
            "{} 個相異編號 / {} 筆成功訂單".format(r.unique_order_ids, r.succeeded),
        ],
    ]
    for check in r.audit:
        checks.append(
            [
                check["description"],
                "PASS" if check["violations"] == 0 else "FAIL",
                "{} 筆違規".format(check["violations"]),
            ]
        )
    _table(["檢查項目", "結果", "細節"], checks)

    _rule()
    print("結論：全部通過" if r.passed else "結論：發現問題，詳見上方 FAIL 項目")


# ---------- audit ----------


def cmd_audit(args: argparse.Namespace) -> int:
    try:
        with db.connect() as conn:
            checks = db.run_audit(conn)
    except pymysql.Error as exc:
        print("無法連線資料庫: {}".format(exc), file=sys.stderr)
        return 2

    _rule("資料一致性稽核")
    _table(
        ["檢查項目", "結果", "違規筆數"],
        [
            [
                c["description"],
                "PASS" if c["violations"] == 0 else "FAIL",
                c["violations"],
            ]
            for c in checks
        ],
    )

    for check in checks:
        if check["violations"]:
            _rule("{} — 前 {} 筆".format(check["description"], len(check["rows"])))
            headers = list(check["rows"][0].keys())
            _table(
                headers,
                [[db.to_plain(row[h]) for h in headers] for row in check["rows"]],
            )

    total = sum(c["violations"] for c in checks)
    _rule()
    print("結論：資料一致" if total == 0 else "結論：共 {} 筆違規".format(total))
    return 0 if total == 0 else 1


# ---------- reset ----------


def cmd_reset(args: argparse.Namespace) -> int:
    if not RESET_SQL.exists():
        print("找不到 {}".format(RESET_SQL), file=sys.stderr)
        return 2

    if not args.yes:
        prompt = "將清空並還原 {} 的測試資料，確定？[y/N] ".format(
            DbConfig.from_env().database
        )
        if input(prompt).strip().lower() not in ("y", "yes"):
            print("已取消")
            return 0

    try:
        with db.connect() as conn:
            count = db.run_sql_file(conn, RESET_SQL)
    except (pymysql.Error, ValueError) as exc:
        print("還原失敗: {}".format(exc), file=sys.stderr)
        return 2

    print("已執行 {} 條 SQL，測試資料還原完成".format(count))
    return 0


# ---------- parser ----------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="esun-ops",
        description="ESUN Shopping Demo 的維運與驗證工具箱",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("health", help="檢查後端 API 與資料庫連線")
    p.set_defaults(func=cmd_health)

    p = sub.add_parser("seed", help="從 CSV 批次匯入商品")
    p.add_argument("--csv", default=str(DEFAULT_CSV), help="CSV 路徑")
    p.add_argument("--retries", type=int, default=3, help="連線失敗重試次數")
    p.add_argument("--dry-run", action="store_true", help="只驗證欄位，不呼叫 API")
    p.set_defaults(func=cmd_seed)

    p = sub.add_parser("bench", help="併發下單壓測並驗證資料一致性")
    p.add_argument("--product", default="P002", help="下單的商品編號")
    p.add_argument("--qty", type=int, default=1, help="每張訂單購買數量")
    p.add_argument("--orders", type=int, default=50, help="總訂單數")
    p.add_argument("--workers", type=int, default=10, help="併發數")
    p.add_argument("--member", default="55688", help="會員編號")
    p.set_defaults(func=cmd_bench)

    p = sub.add_parser("audit", help="直接查 DB 做資料一致性稽核")
    p.set_defaults(func=cmd_audit)

    p = sub.add_parser("reset", help="還原 DB 初始測試資料")
    p.add_argument("-y", "--yes", action="store_true", help="略過確認")
    p.set_defaults(func=cmd_reset)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
