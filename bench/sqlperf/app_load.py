#!/usr/bin/env python3
"""SQLPERF closeout (081): application-level measurements against a DISPOSABLE MySQL container.

  python bench/sqlperf/app_load.py --jar backend/target/shopping-backend-1.0.0.jar --out bench/sqlperf/results/app-load.json

What it does (nothing touches a real database):
  1. Starts a throwaway mysql:8.0 container published on 127.0.0.1 only, with a per-run random root password,
     applies the Flyway baseline schema plus bench/sqlperf/seed.sql (the same dataset as capture.py).
  2. Starts the backend jar against it twice: recommendation cache disabled (TTL 0) and enabled (TTL 30 s).
     For each run it measures GET /api/products/{id}/recommendations: sequential repeats of one key, a burst of
     concurrent callers on one cold key, and distinct keys (all misses). Database work per HTTP call is read from
     performance_schema (statement count, server-side statement time and CPU time) after truncating the digest table.
  3. On the TTL-30 run it registers throw-away buyers through the public API, restocks two products in the disposable
     database, and places concurrent two-line orders (hot product + second product) at two client concurrencies. It
     samples Hikari pending/active from /actuator/metrics while running, and reads Hikari acquire/usage timers,
     InnoDB row-lock wait counters and read-write transaction durations (performance_schema) as deltas.
Redis and Ollama are pointed at closed local ports; the app degrades as designed (not under test here).
"""
import argparse, json, os, secrets, statistics, subprocess, sys, tempfile, threading, time, urllib.error, urllib.request, uuid
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MIGRATIONS = os.path.join(ROOT, "backend/src/main/resources/db/migration")  # every V*.sql, in version order
SEED = os.path.join(ROOT, "bench/sqlperf/seed.sql")
DB = "esun_perf"
HOT, COLD, SECOND = "P00001", "P01201", "P00002"


def mysql(container, password, sql, db=DB):
    cmd = ["docker", "exec", "-i", "-e", f"MYSQL_PWD={password}", container, "mysql", "-uroot", "--batch", "--raw",
           "--skip-column-names", db]
    r = subprocess.run(cmd, input=sql.encode(), capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.decode(errors="replace")[:500])
    return [line.split("\t") for line in r.stdout.decode().splitlines() if line]


def http(method, url, body=None, token=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = resp.read()
            status = resp.status
    except urllib.error.HTTPError as e:
        payload, status = e.read(), e.code
    ms = (time.perf_counter() - t0) * 1000
    try:
        parsed = json.loads(payload) if payload else None
    except ValueError:
        parsed = None
    return status, parsed, ms


def pct(values, p):
    s = sorted(values)
    return round(s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))], 1) if s else None


def summary(values):
    return {"n": len(values), "median_ms": round(statistics.median(values), 1) if values else None,
            "p95_ms": pct(values, 95), "max_ms": round(max(values), 1) if values else None}


# ---------- performance_schema helpers ----------
PS_RESET = "TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;"


def ps_statements(c, pw):
    """Statement count and server-side statement time for the app schema since the last reset, in total and for the
    recommendation aggregates (digest contains COUNT(DISTINCT ...)). SUM_CPU_TIME stays 0 on a default 8.0 server,
    so CPU is taken from the container cgroup instead (container_cpu_ms)."""
    rows = mysql(c, pw, f"""
      SELECT COALESCE(SUM(COUNT_STAR),0), COALESCE(SUM(SUM_TIMER_WAIT),0)/1e9,
             COALESCE(SUM(IF(DIGEST_TEXT LIKE '%COUNT ( DISTINCT%', COUNT_STAR, 0)),0),
             COALESCE(SUM(IF(DIGEST_TEXT LIKE '%COUNT ( DISTINCT%', SUM_TIMER_WAIT, 0)),0)/1e9
      FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME = '{DB}';""", db="mysql")[0]
    return dict(zip(["stmt_count", "stmt_ms", "reco_agg_count", "reco_agg_ms"], [round(float(x), 1) for x in rows]))


def container_cpu_ms(c):
    """mysqld container CPU (user+sys) from cgroup v2; includes background threads, so it is an upper bound."""
    r = subprocess.run(["docker", "exec", c, "cat", "/sys/fs/cgroup/cpu.stat"], capture_output=True, text=True)
    for line in r.stdout.splitlines():
        if line.startswith("usage_usec"):
            return int(line.split()[1]) / 1000
    return None


def per_call(ps, calls):
    return {k + "_per_call": round(v / calls, 2) for k, v in ps.items()} | ps | {"http_calls": calls}


# ---------- app lifecycle ----------
def start_app(jar, db_port, pw, app_port, ttl, log_path):
    env = dict(os.environ, DB_HOST="127.0.0.1", DB_PORT=str(db_port), DB_NAME=DB, DB_USERNAME="root", DB_PASSWORD=pw,
               SERVER_PORT=str(app_port), FLYWAY_ENABLED="false", LLM_INDEXING_ENABLED="false",
               REDIS_HOST="127.0.0.1", REDIS_PORT="1", OLLAMA_BASE_URL="http://127.0.0.1:1", PAYMENT_PROVIDER="none")
    log = open(log_path, "w", encoding="utf-8")
    # Actuator sits behind the JWT filter on the API port; serve it from a loopback management port as prod does.
    proc = subprocess.Popen(["java", "-jar", jar, f"--recommendation.cache-ttl-seconds={ttl}",
                             f"--management.server.port={app_port + 1}", "--management.server.address=127.0.0.1"], env=env,
                            stdout=log, stderr=subprocess.STDOUT)
    base = f"http://127.0.0.1:{app_port}"
    global MGMT
    MGMT = f"http://127.0.0.1:{app_port + 1}"
    for _ in range(180):
        if proc.poll() is not None:
            raise RuntimeError(f"app exited early, see {log_path}")
        try:
            if http("GET", MGMT + "/actuator/health/readiness", timeout=2)[0] == 200:
                return proc, base
        except Exception:
            pass
        time.sleep(1)
    proc.kill()
    raise RuntimeError("app did not become ready")


def stop_app(proc):
    proc.terminate()
    try:
        proc.wait(30)
    except subprocess.TimeoutExpired:
        proc.kill()


MGMT = None


def metric(base, name, tag=None):
    url = f"{MGMT}/actuator/metrics/{name}" + (f"?tag={tag}" if tag else "")
    status, body, _ = http("GET", url, timeout=5)
    if status != 200 or not body:
        return None
    return {m["statistic"]: m["value"] for m in body.get("measurements", [])}


# ---------- scenario 1: public recommendation endpoint ----------
def reco_scenario(c, pw, base, label):
    url = lambda pid: f"{base}/api/products/{pid}/recommendations?limit=6"
    out = {"label": label}

    mysql(c, pw, PS_RESET, db="mysql")
    cpu0 = container_cpu_ms(c)
    lat = []
    for _ in range(20):
        s, _, ms = http("GET", url(HOT))
        assert s == 200, s
        lat.append(ms)
    out["sequential_same_key"] = {"first_call_ms": round(lat[0], 1), "rest": summary(lat[1:]),
                                  "db": per_call(ps_statements(c, pw) | {"container_cpu_ms": round(container_cpu_ms(c) - cpu0, 1)}, 20)}

    mysql(c, pw, PS_RESET, db="mysql")
    cpu0 = container_cpu_ms(c)
    burst, go = 32, threading.Barrier(32)

    def one(_):
        go.wait()
        return http("GET", url(COLD))

    with ThreadPoolExecutor(burst) as ex:
        res = list(ex.map(one, range(burst)))
    assert all(r[0] == 200 for r in res), [r[0] for r in res]
    out["burst_cold_key"] = {"callers": burst, "latency": summary([r[2] for r in res]),
                             "db": per_call(ps_statements(c, pw) | {"container_cpu_ms": round(container_cpu_ms(c) - cpu0, 1)}, burst)}

    mysql(c, pw, PS_RESET, db="mysql")
    cpu0 = container_cpu_ms(c)
    keys = [f"P{n:05d}" for n in range(21, 1021, 20)]  # 50 sellable products, none used above
    lat = []
    for pid in keys:
        s, _, ms = http("GET", url(pid))
        assert s == 200, (pid, s)
        lat.append(ms)
    out["distinct_keys_all_miss"] = {"latency": summary(lat), "db": per_call(ps_statements(c, pw) | {"container_cpu_ms": round(container_cpu_ms(c) - cpu0, 1)}, len(keys))}
    return out


# ---------- scenario 2: concurrent ordering ----------
def make_buyers(base, n):
    buyers = []
    for i in range(n):
        email = f"bench-{uuid.uuid4().hex[:10]}@example.test"
        pw = secrets.token_urlsafe(12)
        s, body, _ = http("POST", base + "/api/auth/register", {"email": email, "password": pw})
        assert s == 200, (s, body)
        token = body["data"]["token"]
        s, body, _ = http("POST", base + "/api/member/addresses",
                          {"label": "home", "receiverName": "Bench", "phone": "0900000000", "postalCode": "100",
                           "address": "Taipei", "isDefault": True}, token)
        assert s == 200, (s, body)
        buyers.append(token)
    return buyers


def order_scenario(c, pw, base, buyers, concurrency, total):
    samples, stop = [], threading.Event()

    def sampler():
        while not stop.is_set():
            p, a = metric(base, "hikaricp.connections.pending"), metric(base, "hikaricp.connections.active")
            if p and a:
                samples.append((p["VALUE"], a["VALUE"]))
            time.sleep(0.02)

    acq0, use0 = metric(base, "hikaricp.connections.acquire"), metric(base, "hikaricp.connections.usage")
    lock0 = dict(mysql(c, pw, "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%';"))
    mysql(c, pw, "TRUNCATE TABLE performance_schema.events_transactions_summary_global_by_event_name;", db="mysql")

    def place(i):
        token = buyers[i % len(buyers)]
        items = [{"productId": HOT, "quantity": 1}, {"productId": SECOND, "quantity": 1}]
        if i % 2:
            items.reverse()  # mixed request order; the service must still lock in a fixed order
        return http("POST", base + "/api/orders", {"requestId": str(uuid.uuid4()), "items": items}, token)

    th = threading.Thread(target=sampler, daemon=True)
    th.start()
    t0 = time.perf_counter()
    with ThreadPoolExecutor(concurrency) as ex:
        res = list(ex.map(place, range(total)))
    wall = time.perf_counter() - t0
    stop.set()
    th.join()

    acq1, use1 = metric(base, "hikaricp.connections.acquire"), metric(base, "hikaricp.connections.usage")
    lock1 = dict(mysql(c, pw, "SHOW GLOBAL STATUS LIKE 'Innodb_row_lock%';"))
    tx = mysql(c, pw, """SELECT COUNT_READ_WRITE, SUM_TIMER_READ_WRITE/1e9, MIN_TIMER_READ_WRITE/1e9,
        AVG_TIMER_READ_WRITE/1e9, MAX_TIMER_READ_WRITE/1e9 FROM performance_schema.events_transactions_summary_global_by_event_name
        WHERE EVENT_NAME = 'transaction';""", db="mysql")[0]
    codes = {}
    for r in res:
        codes[r[0]] = codes.get(r[0], 0) + 1
    d = lambda a, b, k: (b or {}).get(k, 0) - (a or {}).get(k, 0)
    acq_n, use_n = d(acq0, acq1, "COUNT"), d(use0, use1, "COUNT")
    return {
        "concurrency": concurrency, "orders": total, "status_codes": codes, "wall_s": round(wall, 2),
        "throughput_per_s": round(total / wall, 1), "client_latency": summary([r[2] for r in res]),
        "hikari": {
            "pool_max": (metric(base, "hikaricp.connections.max") or {}).get("VALUE"),
            "pending_peak_sampled": max((s[0] for s in samples), default=None),
            "active_peak_sampled": max((s[1] for s in samples), default=None),
            "samples": len(samples),
            "acquire_count": acq_n,
            "acquire_mean_ms": round(d(acq0, acq1, "TOTAL_TIME") / acq_n * 1000, 3) if acq_n else None,
            "acquire_max_ms_window": round((acq1 or {}).get("MAX", 0) * 1000, 3),
            "usage_count": use_n,
            "usage_mean_ms": round(d(use0, use1, "TOTAL_TIME") / use_n * 1000, 3) if use_n else None,
            "usage_max_ms_window": round((use1 or {}).get("MAX", 0) * 1000, 3),
        },
        "innodb_row_lock": {
            "waits": int(lock1["Innodb_row_lock_waits"]) - int(lock0["Innodb_row_lock_waits"]),
            "wait_time_ms": int(lock1["Innodb_row_lock_time"]) - int(lock0["Innodb_row_lock_time"]),
            "current_waits_after": int(lock1["Innodb_row_lock_current_waits"]),
        },
        "rw_transactions": dict(zip(["count", "total_ms", "min_ms", "avg_ms", "max_ms"],
                                    [round(float(x), 2) for x in tx])),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--db-port", type=int, default=33197)
    ap.add_argument("--app-port", type=int, default=18097)
    ap.add_argument("--buyers", type=int, default=20)
    ap.add_argument("--log-dir", default=tempfile.gettempdir(), help="where the backend logs go (not committed)")
    args = ap.parse_args()

    name = f"esun-sqlperf-app-{uuid.uuid4().hex[:8]}"
    pw = secrets.token_urlsafe(16)  # disposable, never written anywhere
    subprocess.run(["docker", "run", "-d", "--rm", "--name", name, "-p", f"127.0.0.1:{args.db_port}:3306",
                    "-e", f"MYSQL_ROOT_PASSWORD={pw}", "-e", f"MYSQL_DATABASE={DB}", "mysql:8.0"],
                   check=True, capture_output=True)
    result = {"started": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "container": name}
    try:
        for _ in range(120):
            try:
                mysql(name, pw, "SELECT 1;")
                break
            except Exception:
                time.sleep(2)
        t0 = time.time()
        migrations = sorted((f for f in os.listdir(MIGRATIONS) if f.startswith("V") and f.endswith(".sql")),
                            key=lambda f: int(f[1:f.index("__")]))
        for f in migrations:
            mysql(name, pw, open(os.path.join(MIGRATIONS, f), encoding="utf-8").read())
        result["migrations"] = migrations
        mysql(name, pw, open(SEED, encoding="utf-8").read())
        result["seed_seconds"] = round(time.time() - t0, 1)
        env = mysql(name, pw, "SELECT VERSION(), @@innodb_buffer_pool_size, @@transaction_isolation;")[0]
        result["mysql"] = {"version": env[0], "buffer_pool_mb": int(env[1]) // 1048576, "isolation": env[2]}
        result["host_cpus"] = os.cpu_count()
        logdir = args.log_dir

        for ttl in (0, 30):
            proc, base = start_app(args.jar, args.db_port, pw, args.app_port, ttl, os.path.join(logdir, f"app-ttl{ttl}.log"))
            try:
                http("GET", f"{base}/api/products/P00999/recommendations?limit=6")  # JIT/pool warm-up, distinct key
                result[f"reco_ttl{ttl}"] = reco_scenario(name, pw, base, f"cache TTL {ttl}s")
                if ttl == 30:
                    mysql(name, pw, f"UPDATE product SET quantity = 1000000 WHERE product_id IN ('{HOT}','{SECOND}');")
                    buyers = make_buyers(base, args.buyers)
                    order_scenario(name, pw, base, buyers, 4, 20)  # warm-up, discarded
                    result["orders"] = [order_scenario(name, pw, base, buyers, c, 200) for c in (8, 32)]
                    stock = mysql(name, pw, f"SELECT product_id, quantity FROM product WHERE product_id IN ('{HOT}','{SECOND}') ORDER BY product_id;")
                    result["stock_after"] = {r[0]: int(r[1]) for r in stock}
            finally:
                stop_app(proc)
    finally:
        subprocess.run(["docker", "rm", "-f", name], capture_output=True)
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(result, f, indent=2)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    sys.exit(main())
