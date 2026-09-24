#!/usr/bin/env python3
"""Runs bench/phase3-suite.js end to end against a disposable MySQL 8 + the real backend jar and reconciles the
database after every workload. Nothing here touches the user's compose MySQL/Redis.

Usage: python bench/run-phase3-suite.py [--skip-build] [normal deadlock soldout coupon ...]
"""
import json, os, re, subprocess, sys, time, urllib.request, pathlib, shutil

ROOT = pathlib.Path(__file__).resolve().parent.parent
DB_PORT, APP_PORT, CONTAINER = 3317, 8081, 'esun-mysql-k6-suite'
BASE = f'http://localhost:{APP_PORT}'
args = [a for a in sys.argv[1:] if not a.startswith('--')]
out_dir = ROOT / 'bench' / 'phase3'
WORKLOADS = args or ['normal', 'deadlock', 'soldout', 'coupon']
out_dir.mkdir(parents=True, exist_ok=True)
run_id = time.strftime('%H%M%S')
log_path = out_dir / 'backend.log'
failures = []


def sh(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', **kw)


def sql(statement):
    r = sh(['docker', 'exec', CONTAINER, 'mysql', '-uroot', '-p123456', '-N', '-B', 'esun_shop', '-e', statement])
    if r.returncode != 0:
        raise RuntimeError(r.stderr)
    return [line.split('\t') for line in r.stdout.strip().splitlines() if line]


def scalar(statement):
    rows = sql(statement)
    return int(float(rows[0][0])) if rows and rows[0][0] != 'NULL' else 0


def check(name, actual, expected):
    ok = actual == expected
    print(f'    [{"PASS" if ok else "FAIL"}] {name}: {actual}' + ('' if ok else f' (expected {expected})'))
    if not ok:
        failures.append(name)
    return ok


def start_infra():
    sh(['docker', 'rm', '-f', CONTAINER])
    db_dir = str(ROOT / 'backend' / 'DB')
    r = sh(['docker', 'run', '-d', '--name', CONTAINER, '-e', 'MYSQL_ROOT_PASSWORD=123456', '-e', 'MYSQL_DATABASE=esun_shop',
            '-p', f'{DB_PORT}:3306', '-v', f'{db_dir}:/docker-entrypoint-initdb.d', 'mysql:8.0',
            '--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci',
            '--character-set-client-handshake=FALSE'], env={**os.environ, 'MSYS_NO_PATHCONV': '1'})
    if r.returncode != 0:
        sys.exit(r.stderr)
    for _ in range(90):
        try:
            if scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='esun_shop' AND table_name='coupon'") == 1 \
                    and scalar('SELECT COUNT(*) FROM product') >= 3:
                break
        except Exception:
            pass
        time.sleep(3)
    else:
        sys.exit('MySQL did not initialise')
    time.sleep(5)


def start_backend():
    jar = ROOT / 'backend' / 'target' / 'shopping-backend-1.0.0.jar'
    if '--skip-build' not in sys.argv or not jar.exists():
        r = sh(['mvn', '-q', '-DskipTests', '-Djacoco.skip=true', 'package'], cwd=ROOT / 'backend', shell=os.name == 'nt')
        if r.returncode != 0:
            sys.exit(r.stdout + r.stderr)
    # Pin every connection setting so a DB_*/REDIS_* variable in the caller's shell can never redirect the run.
    env = {**os.environ, 'DB_HOST': 'localhost', 'DB_PORT': str(DB_PORT), 'DB_NAME': 'esun_shop', 'DB_USERNAME': 'root',
           'DB_PASSWORD': '123456', 'SERVER_PORT': str(APP_PORT), 'STOCK_REDIS_ENABLED': 'false',
           'REDIS_HOST': 'localhost', 'REDIS_PORT': '1', 'LLM_INDEXING_ENABLED': 'false'}
    log = open(log_path, 'w', encoding='utf-8')
    proc = subprocess.Popen(['java', '-jar', str(jar)], env=env, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(90):
        try:
            urllib.request.urlopen(f'{BASE}/api/products/available', timeout=2)
            return proc
        except Exception:
            time.sleep(2)
    proc.kill()
    sys.exit('backend did not start; see ' + str(log_path))


def log_hits():
    text = log_path.read_text(encoding='utf-8', errors='replace')
    return (len(re.findall(r'Deadlock found', text)), len(re.findall(r'Lock wait timeout', text)))


def innodb_counters():
    # Authoritative: counts every deadlock/timeout InnoDB resolved, including ones a retry later hid from the app log.
    rows = {name: (int(count), status) for name, count, status in
            sql("SELECT name, count, status FROM information_schema.INNODB_METRICS WHERE name IN ('lock_deadlocks','lock_timeouts')")}
    if any(status != 'enabled' for _, status in rows.values()) or len(rows) != 2:
        sys.exit('INNODB_METRICS lock counters are not enabled; the deadlock check would be vacuous')
    return rows['lock_deadlocks'][0], rows['lock_timeouts'][0]


def prepare(workload):
    pa, pb = f'{workload[:3].upper()}_A', f'{workload[:3].upper()}_B'
    stock = {'normal': 10_000_000, 'deadlock': 10_000_000, 'soldout': 200, 'coupon': 10_000_000}[workload]
    for pid in (pa, pb):
        sql(f"DELETE FROM product WHERE product_id='{pid}'")
        sql(f"INSERT INTO product(product_id, product_name, price, quantity, creator_id) VALUES('{pid}','k6 {workload} {pid}',1000,{stock},'k6')")
    code = f'K6HOT{run_id}'
    if workload == 'coupon':
        sql(f"INSERT INTO coupon(code, discount_type, discount_value, total_quota, per_member_limit, starts_at, expires_at, created_by) "
            f"VALUES('{code}','PERCENT',10,100,1000,NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 7 DAY,'k6')")
    return pa, pb, stock, code


def k6_metric(summary, name):
    m = summary['metrics'].get(name, {})
    return m.get('count', m.get('values', {}).get('count', 0))


def run(workload):
    print(f'\n=== {workload} ===')
    pa, pb, stock, code = prepare(workload)
    d0, l0 = log_hits()
    id0, it0 = innodb_counters()
    summary_path = out_dir / f'{workload}-summary.json'
    env = {**os.environ, 'SCENARIO': workload, 'BASE_URL': BASE, 'RUN_ID': run_id, 'PRODUCT_A': pa, 'PRODUCT_B': pb,
           'COUPON_CODE': code}
    r = sh(['k6', 'run', '--summary-export', str(summary_path), str(ROOT / 'bench' / 'phase3-suite.js')], env=env)
    (out_dir / f'{workload}-k6.log').write_text(r.stdout + r.stderr, encoding='utf-8')
    summary = json.loads(summary_path.read_text(encoding='utf-8'))
    d1, l1 = log_hits()
    id1, it1 = innodb_counters()
    dur = summary['metrics']['order_latency_ms']
    ok, c409, s5xx = (k6_metric(summary, n) for n in ('orders_success_200', 'orders_conflict_409', 'orders_servererror_5xx'))
    other = sum(k6_metric(summary, n) for n in ('orders_notfound_404', 'orders_badrequest_400', 'orders_other_status', 'orders_no_response'))
    total = ok + c409 + s5xx + other
    reqs = summary['metrics']['iterations']
    race409 = k6_metric(summary, 'orders_conflict_409_stock_race')
    print(f'    409s from the stored-procedure race path={race409}')
    print(f'    requests={total} 200={ok} 409={c409} 5xx={s5xx} other={other} orders_per_s={reqs.get("rate", 0):.1f} avg={dur.get("avg", 0):.0f}ms '
          f'p50={dur.get("med", 0):.0f}ms p95={dur.get("p(95)", 0):.0f}ms p99={dur.get("p(99)", 0):.0f}ms max={dur.get("max", 0):.0f}ms '
          f'innodb_deadlocks={id1 - id0} innodb_lock_timeouts={it1 - it0} log_deadlocks={d1 - d0} log_lock_timeouts={l1 - l0}')

    who = f"member_id LIKE 'k6-{workload}-{run_id}-%'"
    orders = scalar(f'SELECT COUNT(*) FROM shop_order WHERE {who}')
    check('orders committed == HTTP 200 answers', orders, ok)
    check('5xx / no-response / unexpected statuses', s5xx + other, 0)
    check('InnoDB lock_deadlocks counter delta', id1 - id0, 0)
    check('InnoDB lock_timeouts counter delta', it1 - it0, 0)
    check('backend log: deadlock / lock-timeout lines', (d1 - d0) + (l1 - l0), 0)
    for pid in (pa, pb):
        sold = scalar(f"SELECT COALESCE(SUM(d.quantity),0) FROM order_detail d JOIN shop_order o ON o.order_id=d.order_id "
                      f"WHERE d.product_id='{pid}' AND o.{who}")
        left = scalar(f"SELECT quantity FROM product WHERE product_id='{pid}'")
        if workload == 'soldout' and pid == pb:
            continue
        check(f'stock reconciles for {pid} (initial - sold == current)', left, stock - sold)
    if workload in ('normal', 'deadlock'):
        check('no conflict answers with unlimited stock', c409, 0)
    if workload == 'soldout':
        check('sold exactly the stock (no oversell, no undersell)', ok, stock)
        check('every other attempt refused with 409', c409, total - stock)
        check('ending stock', scalar(f"SELECT quantity FROM product WHERE product_id='{pa}'"), 0)
    if workload == 'coupon':
        used = scalar(f"SELECT used_count FROM coupon WHERE code='{code}'")
        check('coupon redeemed exactly its quota', ok, 100)
        check('coupon.used_count == successful orders', used, ok)
        check('orders carrying the coupon == successes',
              scalar(f"SELECT COUNT(*) FROM shop_order o JOIN coupon c ON c.id=o.coupon_id WHERE c.code='{code}' AND o.{who}"), ok)
        check('sum(coupon_member_usage) == used_count',
              scalar(f"SELECT COALESCE(SUM(u.used_count),0) FROM coupon_member_usage u JOIN coupon c ON c.id=u.coupon_id WHERE c.code='{code}'"), used)
        check('every applied discount is > 0 and price = 900',
              scalar(f"SELECT COUNT(*) FROM shop_order WHERE {who} AND coupon_id IS NOT NULL AND (discount_amount <= 0 OR price <> 900)"), 0)
        check('every other attempt refused with 409', c409, total - 100)


def main():
    start_infra()
    try:
        proc = start_backend()
    except BaseException:
        sh(['docker', 'rm', '-f', CONTAINER])
        raise
    try:
        for w in WORKLOADS:
            run(w)
    finally:
        proc.terminate()
        try:
            proc.wait(20)
        except Exception:
            proc.kill()
        sh(['docker', 'rm', '-f', CONTAINER])
    print('\nRESULT:', 'PASS' if not failures else f'FAIL ({len(failures)}): ' + '; '.join(failures))
    sys.exit(1 if failures else 0)


main()
