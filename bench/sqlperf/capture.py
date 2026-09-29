#!/usr/bin/env python3
"""SQLPERF-01: reproducible query-plan baseline against a DISPOSABLE MySQL container.

  python bench/sqlperf/capture.py [--out bench/sqlperf/results/baseline] [--keep] [--runs 5]
  python bench/sqlperf/capture.py --schema-sql <file>     # e.g. a candidate schema variant (SQLPERF-02)

It starts mysql:8.0 (no published port, throwaway), applies the Flyway baseline schema plus seed.sql, then for every SQL
shape below records EXPLAIN (access type/key/Extra) and EXPLAIN ANALYZE (actual time/rows/loops; median of N warm runs).
Only SELECTs are executed; mutating statements are covered by plain EXPLAIN. Nothing here touches a real database.
"""
import argparse, json, os, re, statistics, subprocess, sys, time, uuid

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCHEMA = os.path.join(ROOT, "backend/src/main/resources/db/migration/V1__baseline_schema.sql")
SEED = os.path.join(ROOT, "bench/sqlperf/seed.sql")
DB = "esun_perf"
IMAGE = "mysql:8.0"

HOT_MEMBER, TYPICAL_MEMBER = "u1@example.com", "u1500@example.com"
HOT_PRODUCT, TYPICAL_PRODUCT = "P00001", "P01200"
SELLER = "u3@example.com"
IDS20 = ",".join(f"'P{n:05d}'" for n in range(101, 2101, 100))  # n % 10 == 1: sellable, not deleted
REVIEW_PRODUCT = "P00003"  # ~10 reviews, mostly visible
ORDERS20 = ",".join(f"'O{n:08d}'" for n in range(50000, 50020))

PRODUCT_COLS = "SELECT product_id, product_name, price, quantity, creator_id, deleted_at FROM product"
HEADER = ("SELECT o.order_id, o.member_id, o.order_status, o.price, o.created_at, a.receiver_name, a.phone, "
          "CONCAT_WS(' ', a.postal_code, a.address) AS full_address, o.pay_status, o.coupon_id, o.coupon_code, "
          "o.discount_amount, (SELECT p.status FROM payment p WHERE p.order_id = o.order_id ORDER BY p.id DESC LIMIT 1) "
          "AS payment_status FROM shop_order o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id")
SELLER_SCOPE = (f"EXISTS (SELECT 1 FROM order_detail d JOIN product p ON p.product_id = d.product_id "
                f"WHERE d.order_id = o.order_id AND p.creator_id = '{SELLER}')")
REVIEW = ("SELECT pr.id, pr.product_id, pr.member_id, COALESCE(NULLIF(m.display_name, ''), 'x') reviewer_name, pr.rating, "
          "pr.content, pr.visibility, pr.created_at, pr.updated_at FROM product_review pr JOIN member m ON m.id = pr.member_id")

# (id, source, sql, workload). All SELECT/EXPLAIN-able; mutating shapes use the "EXPLAIN-only" list below.
QUERIES = [
    ("product.catalog_all", "ProductRepository.findAll", f"{PRODUCT_COLS} WHERE deleted_at IS NULL", "catalog"),
    ("product.by_pk", "ProductRepository.findActiveById", f"{PRODUCT_COLS} WHERE product_id = '{TYPICAL_PRODUCT}' AND deleted_at IS NULL", "checkout"),
    ("product.lock_by_pk", "ProductRepository.findByIdForUpdate", f"{PRODUCT_COLS} WHERE product_id = '{HOT_PRODUCT}' FOR UPDATE", "checkout"),
    ("product.in_20_sellable", "ProductRepository.findSellableByIds", f"{PRODUCT_COLS} WHERE product_id IN ({IDS20}) AND deleted_at IS NULL AND quantity > 0", "catalog/checkout"),
    ("product.seller_page", "ProductRepository.findBySeller", f"{PRODUCT_COLS} WHERE creator_id = '{SELLER}' ORDER BY product_id LIMIT 20 OFFSET 0", "seller-admin"),
    ("product.seller_search", "ProductRepository.search", f"{PRODUCT_COLS} WHERE creator_id = '{SELLER}' AND (LOWER(product_id) LIKE '%chair%' ESCAPE '\\\\' OR LOWER(product_name) LIKE '%chair%' ESCAPE '\\\\') AND deleted_at IS NULL ORDER BY product_id LIMIT 20 OFFSET 0", "seller-admin"),
    ("product.seller_count", "ProductRepository.count", f"SELECT COUNT(*) FROM product WHERE creator_id = '{SELLER}'", "seller-admin"),
    ("product.rating_all", "ProductRepository.ratingSummaries(all)", "SELECT product_id, ROUND(AVG(rating), 2) average_rating, COUNT(*) review_count FROM product_review WHERE visibility = 'VISIBLE' GROUP BY product_id", "catalog"),
    ("product.rating_in_20", "ProductRepository.ratingSummaries(ids)", f"SELECT product_id, ROUND(AVG(rating), 2) average_rating, COUNT(*) review_count FROM product_review WHERE visibility = 'VISIBLE' AND product_id IN ({IDS20}) GROUP BY product_id", "catalog"),
    ("product.images_in_20", "ProductRepository.imagesFor", f"SELECT product_id, image_url FROM product_image WHERE product_id IN ({IDS20}) ORDER BY product_id, display_order, image_id", "catalog"),
    ("order.member_page_hot", "OrderRepository.findByMember(hot)", f"{HEADER} WHERE o.member_id = '{HOT_MEMBER}' ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0", "buyer-orders"),
    ("order.member_page_typical", "OrderRepository.findByMember(typical)", f"{HEADER} WHERE o.member_id = '{TYPICAL_MEMBER}' ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0", "buyer-orders"),
    ("order.member_page_status", "OrderRepository.findByMember(status)", f"{HEADER} WHERE o.member_id = '{HOT_MEMBER}' AND o.order_status = 'DELIVERED' ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0", "buyer-orders"),
    ("order.member_count_hot", "OrderRepository.countByMember", f"SELECT COUNT(*) FROM shop_order o WHERE o.member_id = '{HOT_MEMBER}'", "buyer-orders"),
    ("order.seller_page", "OrderRepository.findBySeller", "__SELLER_PAGE__", "seller-orders"),
    ("order.seller_count", "OrderRepository.countBySeller", f"SELECT COUNT(*) FROM shop_order o WHERE {SELLER_SCOPE}", "seller-orders"),
    ("order.admin_status_page", "OrderRepository.findBySeller(admin,status)", f"{HEADER} WHERE o.order_status = 'CREATED' ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0", "admin-orders"),
    ("order.lines_in_20", "OrderRepository.findLines", f"SELECT d.order_id, d.product_id, p.product_name, d.quantity, d.unit_price, d.item_price, p.creator_id FROM order_detail d JOIN product p ON p.product_id = d.product_id WHERE d.order_id IN ({ORDERS20}) ORDER BY d.order_id, d.product_id", "buyer-orders"),
    ("order.history_in_20", "OrderRepository.findHistory", f"SELECT order_id, from_status, to_status, actor_role, created_at FROM order_status_history WHERE order_id IN ({ORDERS20}) ORDER BY order_id, id", "buyer-orders"),
    ("order.header_lock", "OrderRepository.findHeaderForUpdate", f"{HEADER} WHERE o.order_id = 'O00050000' FOR UPDATE OF o", "order-status"),
    ("order.request_by_pk", "OrderRepository.findRequest", "SELECT request_id, order_id, member_id FROM order_request WHERE request_id = 'R00050000'", "checkout"),
    ("payment.by_trade_no", "PaymentRepository.findByTradeNo", "SELECT id, order_id, merchant_trade_no, provider, amount, status, failure_reason, created_at, paid_at FROM payment WHERE merchant_trade_no = 'T50000'", "payment-callback"),
    ("payment.initiated_for_order", "PaymentRepository.findInitiated", "SELECT id, order_id, merchant_trade_no, provider, amount, status, failure_reason, created_at, paid_at FROM payment WHERE order_id = 'O00050000' AND status = 'INITIATED'", "payment"),
    ("payment.order_lock", "PaymentRepository.lockOrder", "SELECT order_id, member_id, order_status, pay_status, price FROM shop_order WHERE order_id = 'O00050000' FOR UPDATE", "payment-callback"),
    ("reco.co_purchase", "RecommendationRepository.coPurchased", f"SELECT b.product_id, COUNT(DISTINCT o.member_id) AS score FROM order_detail a JOIN shop_order o ON o.order_id = a.order_id AND o.order_status <> 'CANCELLED' JOIN order_detail b ON b.order_id = a.order_id JOIN product p ON p.product_id = b.product_id AND p.deleted_at IS NULL AND p.quantity > 0 WHERE a.product_id IN ('{HOT_PRODUCT}') AND b.product_id NOT IN ('{HOT_PRODUCT}') GROUP BY b.product_id HAVING COUNT(DISTINCT o.member_id) >= 2 ORDER BY score DESC, b.product_id ASC LIMIT 8", "recommendation"),
    ("reco.popular", "RecommendationRepository.popular", "SELECT d.product_id, COUNT(DISTINCT o.member_id) AS score FROM order_detail d JOIN shop_order o ON o.order_id = d.order_id AND o.order_status <> 'CANCELLED' JOIN product p ON p.product_id = d.product_id AND p.deleted_at IS NULL AND p.quantity > 0 WHERE 1 = 1 GROUP BY d.product_id HAVING COUNT(DISTINCT o.member_id) >= 2 ORDER BY score DESC, d.product_id ASC LIMIT 8", "recommendation"),
    ("reco.new_arrival", "RecommendationRepository.newArrivals", "SELECT p.product_id FROM product p WHERE p.deleted_at IS NULL AND p.quantity > 0 ORDER BY p.created_at DESC, p.product_id ASC LIMIT 8", "recommendation"),
    ("reco.member_purchases", "RecommendationRepository.purchasedBy", f"SELECT DISTINCT d.product_id FROM order_detail d JOIN shop_order o ON o.order_id = d.order_id WHERE o.member_id = '{HOT_MEMBER}' AND o.order_status <> 'CANCELLED' ORDER BY d.product_id", "recommendation"),
    ("reco.member_cart", "RecommendationRepository.carted", f"SELECT c.product_id FROM shopping_cart c JOIN member m ON m.id = c.member_id WHERE m.email = '{TYPICAL_MEMBER}' ORDER BY c.product_id", "recommendation"),
    ("review.public_page", "ProductReviewRepository.findVisible", f"{REVIEW} WHERE pr.product_id = '{REVIEW_PRODUCT}' AND pr.visibility = 'VISIBLE' ORDER BY pr.created_at DESC, pr.id DESC LIMIT 10 OFFSET 0", "reviews"),
    ("review.public_count", "ProductReviewRepository.countVisible", f"SELECT COUNT(*) FROM product_review WHERE product_id = '{REVIEW_PRODUCT}' AND visibility = 'VISIBLE'", "reviews"),
    ("review.public_avg", "ProductReviewRepository.averageVisible", f"SELECT ROUND(AVG(rating), 2) FROM product_review WHERE product_id = '{REVIEW_PRODUCT}' AND visibility = 'VISIBLE'", "reviews"),
    ("review.verified_purchase", "ProductReviewRepository.hasPurchased", f"SELECT COUNT(*) FROM shop_order o JOIN order_detail d ON d.order_id = o.order_id JOIN member m ON m.email = o.member_id WHERE m.id = 1500 AND d.product_id = '{HOT_PRODUCT}'", "reviews"),
    ("review.seller_moderation", "ProductReviewRepository.findForSeller", f"{REVIEW} JOIN product p ON p.product_id = pr.product_id WHERE p.creator_id = '{SELLER}' ORDER BY pr.created_at DESC, pr.id DESC LIMIT 20 OFFSET 0", "reviews"),
    ("review.admin_all", "ProductReviewRepository.findForAdmin", f"{REVIEW} ORDER BY pr.created_at DESC, pr.id DESC LIMIT 20 OFFSET 0", "reviews"),
    ("audit.latest", "AuditLogRepository.find(no filter)", "SELECT id, actor, actor_role, action, target_type, target_id, before_state, after_state, created_at FROM audit_log ORDER BY id DESC LIMIT 50 OFFSET 0", "audit"),
    ("audit.by_actor", "AuditLogRepository.find(actor)", f"SELECT id, actor, actor_role, action, target_type, target_id, before_state, after_state, created_at FROM audit_log WHERE actor = '{SELLER}' ORDER BY id DESC LIMIT 50 OFFSET 0", "audit"),
    ("audit.by_target", "AuditLogRepository.find(target)", f"SELECT id, actor, actor_role, action, target_type, target_id, before_state, after_state, created_at FROM audit_log WHERE target_type = 'PRODUCT' AND target_id = '{HOT_PRODUCT}' ORDER BY id DESC LIMIT 50 OFFSET 0", "audit"),
    ("audit.action_window", "AuditLogRepository.find(action+time)", "SELECT id, actor, actor_role, action, target_type, target_id, before_state, after_state, created_at FROM audit_log WHERE action = 'PRODUCT_UPDATE' AND created_at >= NOW() - INTERVAL 7 DAY ORDER BY id DESC LIMIT 50 OFFSET 0", "audit"),
    ("audit.count_action_window", "AuditLogRepository.count", "SELECT COUNT(*) FROM audit_log WHERE action = 'PRODUCT_UPDATE' AND created_at >= NOW() - INTERVAL 7 DAY", "audit"),
    ("cart.items_by_email", "CartRepository.findItems", f"SELECT c.id, c.product_id, p.product_name, p.price, p.quantity AS stock FROM shopping_cart c JOIN member m ON m.id = c.member_id JOIN product p ON p.product_id = c.product_id WHERE m.email = '{TYPICAL_MEMBER}' ORDER BY c.id", "cart"),
    ("cart.member_lock", "CartRepository.lockMember", f"SELECT id FROM member WHERE email = '{TYPICAL_MEMBER}' FOR UPDATE", "cart/checkout"),
    ("coupon.by_code", "CouponRepository.findByCode", "SELECT * FROM coupon WHERE code = 'SAVE5'", "coupon"),
]

HEADER_COLUMNS = HEADER.split(" FROM shop_order o LEFT JOIN")[0]
SELLER_PAGE_VARIANTS = {
    # before SQLPERF-02: every matching order is joined to its header columns, then sorted
    "current": f"{HEADER} WHERE {SELLER_SCOPE} ORDER BY o.created_at DESC, o.order_id DESC LIMIT 20 OFFSET 0",
    # after SQLPERF-02: page the order ids first, join header columns afterwards (what OrderRepository now runs)
    "deferred": (f"{HEADER_COLUMNS} FROM (SELECT o.* FROM shop_order o WHERE {SELLER_SCOPE} ORDER BY o.created_at DESC, "
                 f"o.order_id DESC LIMIT 20 OFFSET 0) o LEFT JOIN shipping_address a ON a.id = o.shipping_address_id "
                 f"ORDER BY o.created_at DESC, o.order_id DESC"),
}

# Mutating shapes: plain EXPLAIN only (never executed). (id, source, sql)
EXPLAIN_ONLY = [
    ("product.update_seller_pk", "ProductRepository.update", f"UPDATE product SET product_name = 'x', price = 10 WHERE product_id = '{TYPICAL_PRODUCT}' AND creator_id = '{SELLER}' AND deleted_at IS NULL"),
    ("product.restock_pk", "ProductRepository.restock", f"UPDATE product SET quantity = quantity + 5 WHERE product_id = '{TYPICAL_PRODUCT}' AND creator_id = '{SELLER}' AND deleted_at IS NULL"),
    ("order.status_cas", "OrderRepository.updateStatus", "UPDATE shop_order SET order_status = 'CONFIRMED' WHERE order_id = 'O00050000' AND order_status = 'CREATED'"),
    ("payment.cas", "PaymentRepository.update", "UPDATE payment SET status = 'SUCCEEDED' WHERE id = 5 AND status = 'INITIATED'"),
]


def mysql(container, sql, db=DB, check=True):
    cmd = ["docker", "exec", "-i", container, "mysql", "-uroot", "--batch", "--raw", "--default-character-set=utf8mb4"]
    if db: cmd.append(db)
    r = subprocess.run(cmd, input=sql, text=True, capture_output=True, encoding="utf-8")
    if check and r.returncode != 0:
        raise RuntimeError(f"mysql failed: {r.stderr.strip()[:500]}")
    return r.stdout


def parse_table(out):
    lines = [l for l in out.splitlines() if l.strip()]
    if not lines: return []
    head = lines[0].split("\t")
    return [dict(zip(head, l.split("\t"))) for l in lines[1:]]


ACTUAL = re.compile(r"actual time=([\d.]+)\.\.([\d.]+) rows=(\d+) loops=(\d+)")
SCAN = re.compile(r"->\s*(Table scan|Index scan|Index range scan|Index lookup|Single-row index lookup|Covering index (?:scan|lookup|range scan)|Index skip scan)")


def analyze_once(container, sql):
    out = mysql(container, "EXPLAIN ANALYZE " + sql)
    plan = out.split("\n", 1)[1] if "\n" in out else out  # drop the "EXPLAIN" header line
    lines = plan.splitlines()
    top = ACTUAL.search(lines[0]) if lines else None
    total_ms = float(top.group(2)) if top else 0.0  # const lookup resolved before execution: no runtime node
    returned = int(top.group(3)) if top else -1
    rows_read = 0
    for line in lines:
        if SCAN.search(line):
            m = ACTUAL.search(line)
            if m: rows_read += int(m.group(3)) * int(m.group(4))
    return total_ms, returned, rows_read, plan


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(ROOT, "bench/sqlperf/results/baseline"))
    ap.add_argument("--runs", type=int, default=5)
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--schema-sql", default=SCHEMA)
    ap.add_argument("--label", default="baseline")
    ap.add_argument("--mysqld-arg", action="append", default=[], help="extra mysqld flag, e.g. --innodb-buffer-pool-size=1G")
    ap.add_argument("--seller-variant", choices=sorted(SELLER_PAGE_VARIANTS), default="current")
    a = ap.parse_args()

    name = "esun-sqlperf-" + uuid.uuid4().hex[:8]
    subprocess.run(["docker", "run", "-d", "--rm", "--name", name, "-e", "MYSQL_ALLOW_EMPTY_PASSWORD=yes",
                    "-e", f"MYSQL_DATABASE={DB}", IMAGE, "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci", *a.mysqld_arg], check=True, capture_output=True)
    try:
        for _ in range(90):
            if subprocess.run(["docker", "exec", name, "mysqladmin", "ping", "-h", "127.0.0.1", "--silent"],
                              capture_output=True).returncode == 0:
                time.sleep(3)  # entrypoint restarts mysqld once after init
                if subprocess.run(["docker", "exec", name, "mysqladmin", "ping", "-h", "127.0.0.1", "--silent"],
                                  capture_output=True).returncode == 0: break
            time.sleep(2)
        else:
            raise RuntimeError("mysql did not become ready")

        mysql(name, open(a.schema_sql, encoding="utf-8").read())
        t0 = time.time()
        mysql(name, open(SEED, encoding="utf-8").read())
        seed_s = time.time() - t0

        env = {r["k"]: r["v"] for r in parse_table(mysql(name,
            "SELECT 'version' k, VERSION() v UNION ALL SELECT 'innodb_buffer_pool_size', @@innodb_buffer_pool_size "
            "UNION ALL SELECT 'max_connections', @@max_connections UNION ALL SELECT 'transaction_isolation', @@transaction_isolation"))}
        env["image"] = IMAGE
        env["host"] = f"{os.cpu_count()} logical CPUs (Docker Desktop VM shared with the host)"
        env["seed_seconds"] = round(seed_s, 1)
        sizes = parse_table(mysql(name,
            "SELECT table_name t, table_rows r, ROUND(data_length/1024/1024,2) data_mb, ROUND(index_length/1024/1024,2) index_mb "
            f"FROM information_schema.tables WHERE table_schema='{DB}' ORDER BY data_length DESC"))
        indexes = parse_table(mysql(name,
            "SELECT table_name t, index_name i, GROUP_CONCAT(column_name ORDER BY seq_in_index) cols, MAX(non_unique) non_unique, "
            "MAX(cardinality) cardinality FROM information_schema.statistics "
            f"WHERE table_schema='{DB}' GROUP BY table_name, index_name ORDER BY table_name, index_name"))

        results = []
        for qid, source, sql, workload in QUERIES:
            if sql == "__SELLER_PAGE__": sql = SELLER_PAGE_VARIANTS[a.seller_variant]
            explain = parse_table(mysql(name, "EXPLAIN " + sql))
            analyze_once(name, sql)  # warm-up, discarded
            samples = [analyze_once(name, sql) for _ in range(a.runs)]
            times = [s[0] for s in samples]
            plan = samples[-1][3]
            extra = " ".join(r.get("Extra", "") for r in explain)
            results.append({
                "id": qid, "source": source, "workload": workload, "sql": sql,
                "median_ms": round(statistics.median(times), 3), "const_lookup": "Rows fetched before execution" in plan or "Impossible WHERE" in plan, "min_ms": min(times), "max_ms": max(times),
                "rows_returned": samples[-1][1], "rows_read_approx": samples[-1][2],
                "explain": explain, "plan": plan,
                "flags": [f for f, hit in (("full_scan", any(r.get("type") == "ALL" for r in explain)),
                                            ("filesort", "Using filesort" in extra),
                                            ("temporary", "Using temporary" in extra),
                                            ("no_key", any(r.get("key") in (None, "", "NULL") for r in explain))) if hit],
            })
            print(f"{qid:34} median {results[-1]['median_ms']:>9.3f} ms  returned {results[-1]['rows_returned']:>6}  read~{results[-1]['rows_read_approx']:>8}  {','.join(results[-1]['flags'])}", flush=True)

        static = []
        for qid, source, sql in EXPLAIN_ONLY:
            static.append({"id": qid, "source": source, "sql": sql, "explain": parse_table(mysql(name, "EXPLAIN " + sql))})

        os.makedirs(os.path.dirname(a.out), exist_ok=True)
        with open(a.out + ".json", "w", encoding="utf-8") as f:
            json.dump({"label": a.label, "runs_per_query": a.runs, "environment": env, "tables": sizes, "indexes": indexes,
                       "queries": results, "explain_only": static}, f, indent=2, ensure_ascii=False)
        with open(a.out + ".md", "w", encoding="utf-8") as f:
            f.write(f"# SQLPERF capture: {a.label}\n\nMySQL {env['version']}, {env['host']}, buffer pool "
                    f"{int(env['innodb_buffer_pool_size'])//1048576} MB, seed {env['seed_seconds']}s. "
                    f"Median of {a.runs} warm EXPLAIN ANALYZE runs (one discarded warm-up).\n\n")
            f.write("| id | workload | median ms | returned | rows read (approx) | flags |\n|---|---|---:|---:|---:|---|\n")
            for r in results:
                f.write(f"| {r['id']} | {r['workload']} | {'const' if r['const_lookup'] else r['median_ms']} | {r['rows_returned']} | {r['rows_read_approx']} | {', '.join(r['flags'])} |\n")
            f.write("\n## Table sizes\n\n| table | rows (est) | data MB | index MB |\n|---|---:|---:|---:|\n")
            for s in sizes: f.write(f"| {s['t']} | {s['r']} | {s['data_mb']} | {s['index_mb']} |\n")
            f.write("\n## Plans\n")
            for r in results:
                f.write(f"\n### {r['id']} ({r['source']})\n\n```sql\n{r['sql']}\n```\n\n```\n{r['plan']}\n```\n")
            f.write("\n## EXPLAIN-only (mutating) shapes\n")
            for s in static:
                f.write(f"\n### {s['id']} ({s['source']})\n\n```sql\n{s['sql']}\n```\n\n```\n{json.dumps(s['explain'], ensure_ascii=False)}\n```\n")
        print("wrote", a.out + ".json", a.out + ".md")
    finally:
        if not a.keep:
            subprocess.run(["docker", "rm", "-f", name], capture_output=True)
        else:
            print("kept container", name)


if __name__ == "__main__":
    sys.exit(main())
