# Concurrent order-creation load test (Phase 1 item 3.3 benchmark)

This directory holds a k6 load test used to build a before/after benchmark for
Phase 1 item 3.3 — "multi-item stock deduction has no fixed lock order ->
possible deadlock under concurrency -> fixed by locking in `productId`
order" (commit `a197192`).

Files:
- `order-load-test.js` — the k6 script.
- `RESULTS.md` — recorded before/after numbers from an actual run on this
  machine (not fabricated; see that file for methodology and raw output).

## 1. Environment used for the recorded run

- Windows 11, Docker Desktop (WSL2 backend), MySQL 8.0 via the repo's
  `docker-compose.yml`.
- Backend: Java 21, Maven 3.9, Spring Boot 3.3.5, run with
  `java -jar target\shopping-backend-1.0.0.jar` (packaged with
  `mvn -q -DskipTests package`).
- k6 v2.2.0, installed with `winget install --id=GrafanaLabs.k6 -e` (the
  official `grafana/k6` Docker image was the first choice per the task, but
  running k6-in-Docker against a host-port backend needs
  `host.docker.internal`, and the native Windows binary was simpler and
  equally valid here — see "k6 install notes" below).
- Two environment quirks specific to this machine required workarounds
  (details in the sections below and in `RESULTS.md`): Docker Desktop would
  not start at all until its "Docker AI" feature was disabled, and MySQL had
  to be published on host port **3308** instead of 3306 because a native
  `mysqld.exe` was already using 3306 (and 3307 was also unavailable).

## 2. Starting MySQL and resetting to clean seed data

From the repo root (using port 3308 — see the environment note above; use
the default 3306 by omitting `$env:DB_PORT` if it's free on your machine):

```powershell
$env:DB_PORT = "3308"
docker compose up -d
```

To reset the database back to the clean initial stock (`P001`=5, `P002`=50,
`P003`=20) between runs — e.g. before switching from "pre-fix" to
"post-fix" — wipe the volume so the init scripts (`backend/DB/01_schema.sql`,
`02_data.sql`, `03_stored_procedures.sql`) re-run from scratch:

```powershell
docker compose down -v
$env:DB_PORT = "3308"
docker compose up -d
```

Wait for MySQL to finish initializing before starting the backend
(`docker compose logs mysql` until you see "ready for connections... port:
3306" — that's the container's internal port, not the host-mapped one).

## 3. Building and running the backend

From `backend/`:

```powershell
mvn -q -DskipTests package
$env:DB_PORT = "3308"
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://localhost:3308/esun_shop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Taipei&characterEncoding=utf8"
java -jar target\shopping-backend-1.0.0.jar
```

Default port is 8080 (`server.port` in `application.yml`, overridable with
`SERVER_PORT`).

`SPRING_DATASOURCE_URL` is set explicitly here because MySQL 8's default
`caching_sha2_password` auth plugin fails with "Public Key Retrieval is not
allowed" under the app's `useSSL=false` unless `allowPublicKeyRetrieval=true`
is also present — a pre-existing gap in `application.yml`'s JDBC URL that is
unrelated to Phase 1 item 3.3. This is a Spring Boot environment-variable
override of the `spring.datasource.url` property (env vars take precedence
over `application.yml`), not a source edit, so it stays within this task's
"`bench/`-only changes" restriction. If your MySQL is configured with
`mysql_native_password` instead, or the JDBC driver version differs, you may
not need this override at all.

To capture backend logs to a file for later grepping (used to disambiguate
deadlocks from other DB errors — see "Limitation" below):

```powershell
java -jar target\shopping-backend-1.0.0.jar *> ..\bench\backend-run.log
```

## 4. Running the k6 script

Native binary (what was used for the recorded results):

```powershell
& "C:\Program Files\k6\k6.exe" run -e BASE_URL=http://localhost:8080 -e VUS=40 -e DURATION=45s bench\order-load-test.js
```

Environment variables the script reads:
- `BASE_URL` — backend base URL (default `http://localhost:8080`).
- `VUS` — concurrent virtual users (default 30).
- `DURATION` — how long to run (default 45s).

Alternative: official Docker image (needs Docker Desktop running and the
backend reachable from inside the container):

```powershell
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e VUS=40 -e DURATION=45s grafana/k6 run - < bench\order-load-test.js
```

### k6 install notes

`k6` was not installed on this machine. Two install paths were considered:
1. `grafana/k6` Docker image — works, but adds a network hop
   (`host.docker.internal`) and a container cold-pull; only worth it if
   Docker is already the chosen toolchain for everything else.
2. `winget install --id=GrafanaLabs.k6 -e` — native Windows binary, no
   network indirection between k6 and the backend. This is what was
   actually used (`C:\Program Files\k6\k6.exe`).

## 5. Reproducing the before/after comparison

```powershell
# --- PRE-FIX (commit 36185d9, one commit before the 3.3 fix) ---
git worktree add ../esun-prefix 36185d9
cd ../esun-prefix/backend
mvn -q -DskipTests package
$env:DB_PORT = "3308"
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://localhost:3308/esun_shop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Taipei&characterEncoding=utf8"
java -jar target\shopping-backend-1.0.0.jar *> ..\..\esun-shopping\bench\backend-prefix.log
# (separate shell, DB already reset per step 2) then:
& "C:\Program Files\k6\k6.exe" run -e VUS=40 -e DURATION=45s bench\order-load-test.js > bench\k6-prefix.txt

# --- POST-FIX (advanced-v2 HEAD, already includes the 3.3 fix) ---
# stop the pre-fix backend (java.exe), reset DB again (step 2), then:
cd ../esun-shopping/backend
mvn -q -DskipTests package
$env:DB_PORT = "3308"
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://localhost:3308/esun_shop?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Taipei&characterEncoding=utf8"
java -jar target\shopping-backend-1.0.0.jar *> ..\bench\backend-postfix.log
& "C:\Program Files\k6\k6.exe" run -e VUS=40 -e DURATION=45s bench\order-load-test.js > bench\k6-postfix.txt

# --- cleanup ---
cd ../esun-shopping
git worktree remove ../esun-prefix
docker compose down   # stop the MySQL container started for this benchmark
```

Then grep both backend logs for the deadlock/lock-timeout signatures (see
below) to get the authoritative counts. `RESULTS.md` already has this
filled in from an actual run on this machine.

### Docker Desktop wouldn't start at all

On this machine, Docker Desktop failed to start with every attempt failing
in `%LOCALAPPDATA%\Docker\backend.error.json` with
`initializing Inference manager: listening on unix://...\run\dockerInference:
remove ...\dockerInference: The file cannot be accessed by the system.` —
a stale AF_UNIX socket reparse point for Docker's "Docker AI" / Model Runner
feature that even an elevated `Remove-Item`/`takeown` could not delete
(consistent with known Docker Desktop/Windows AF_UNIX-socket bugs; a reboot
would likely also have cleared it, but rebooting the machine was avoided).
Workaround: `%APPDATA%\Docker\settings-store.json` has an `"EnableDockerAI"`
key — setting it to `false` (was `true`) and restarting Docker Desktop let
the core engine start normally, since it stopped trying to start the
inference component at all. This is an app-level preference file, not an OS
setting, and is easily reverted; re-enabling `EnableDockerAI` may reproduce
the original startup failure since the underlying stale-socket issue itself
was never fixed, just avoided.

## Limitation: HTTP status alone cannot distinguish deadlock from other DB errors

`GlobalExceptionHandler.handleDb()` catches Spring's generic
`DataAccessException` and always responds with HTTP 500 and the same message
(`"資料庫操作失敗"`), whether the underlying cause is:

- a real InnoDB deadlock (MySQL error 1213, "Deadlock found when trying to
  get lock; try restarting transaction"),
- a lock-wait timeout (MySQL error 1205, "Lock wait timeout exceeded; try
  restarting transaction"), or
- the `sp_decrease_stock` stored procedure's own `SIGNAL SQLSTATE '45000'`
  ("庫存不足或商品不存在") — a TOCTOU stock race where two concurrent
  transactions both pass the up-front in-memory stock check but only one
  can win the actual `UPDATE ... WHERE quantity >= ?`.

So the k6 script's `orders_servererror_500` counter is only an upper bound /
signal that *something* went wrong at the DB layer — it cannot say the 500s
were deadlocks specifically. To get the real deadlock/lock-timeout count,
the backend process's stdout/stderr was redirected to a log file for each
run (`*> ...log` above, since `GlobalExceptionHandler` logs the full
exception via SLF4J on every `DataAccessException`), and searched with:

```powershell
Select-String -Path bench\backend-prefix.log -Pattern "Deadlock found","Lock wait timeout exceeded" | Measure-Object | Select-Object Count
```

This out-of-band log grep — not the HTTP response body — is the source of
truth for the deadlock/lock-timeout counts reported in `RESULTS.md`.

This mismatch (uniform 500 message hiding the real cause) is itself worth
flagging as a follow-up: today an API client (or this k6 script) cannot tell
a transient, retryable deadlock apart from a permanent 500. Not fixed here —
out of scope for this benchmarking task, which is restricted to `bench/`
only.

## Known residual limitation of the 3.3 fix itself

Sorting by `productId` before locking only removes deadlocks caused by two
orders acquiring the *same two product rows in opposite order*. It does not
remove contention from the `order_detail` -> `product` foreign key: InnoDB
takes a shared lock on the parent (`product`) row when inserting a
referencing child row, and that shared lock can still conflict with another
transaction's exclusive lock from `sp_decrease_stock`'s `UPDATE`, independent
of item ordering.

**This turned out to be more than a small residual — see `RESULTS.md`.** In
the actual recorded runs, deadlock counts were statistically the same
before (144) and after (148) the fix, because this 2-product,
always-the-same-two-products workload's deadlocks are dominated by the
single-row FK/UPDATE lock-upgrade conflict above, not by the two-row
crossed-lock-order pattern the fix targets. Read `RESULTS.md`'s "Finding"
section before assuming a bigger before/after gap than what was actually
measured.

## Follow-up script: 3-item overlapping basket

`order-load-test-multiitem.js` is a separate script (does not replace the
one above) built to better isolate the multi-row crossed-lock deadlock class
the fix targets, using all 3 seeded products per request instead of 2 — see
`RESULTS.md`'s "Follow-up: 3-item overlapping basket" section for full
methodology, the runtime-only stock adjustment it requires, and the
results. Quick usage (after the same `docker compose down -v && up -d`
reset as step 2 above):

```powershell
# one-off runtime stock bump — NOT committed to backend/DB/02_data.sql,
# see RESULTS.md for why P001's seeded stock (5) is too low for this script
& "C:\AppServ\MySQL\bin\mysql.exe" -h127.0.0.1 -P3308 -uroot -p123456 esun_shop -e "UPDATE product SET quantity=200 WHERE product_id IN ('P001','P002','P003');"

& "C:\Program Files\k6\k6.exe" run -e BASE_URL=http://localhost:8080 -e VUS=40 -e DURATION=45s bench\order-load-test-multiitem.js
```

Same pre-fix/post-fix worktree comparison method as section 5 below applies
(use a distinct worktree directory name if re-running, e.g.
`../esun-prefix-multiitem`, to avoid clashing with a leftover `../esun-prefix`
from the original run).
