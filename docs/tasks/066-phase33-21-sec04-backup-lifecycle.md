# 066 — Phase 3.3 #21 SEC-04: encrypted backups, retention and restore drill

Date: 2026-09-29 (Asia/Taipei). Executor: Claude Code. Branch: `advanced-v2`. Scope source: `056` (SEC-04, gap G7).

The existing `scripts/mysql-backup-restore.ps1` (task 024) proves dump/restore correctness on Windows. This task adds the Linux-side lifecycle for the Compose stack.
**The off-host half of G7 is not achieved:** no destination exists, so nothing left this machine.

## What was added

- `scripts/mysql-backup.sh` (`backup`, `restore`, `drill`, `prune`): `mysqldump --single-transaction` (routines, triggers, events) → gzip → AES-256-CBC with PBKDF2
  (200k iterations, per-file salt, passphrase read from a `600` file that is git-ignored) → sha256 sidecar. Retention keeps the newest `BACKUP_KEEP` (default 14) generations.
  `BACKUP_OFFHOST_CMD` runs a user-supplied command with the file path after each backup (for example `rclone copyto "$1" remote:bucket/`).
  `restore` needs `--force`, refuses the source database name, verifies the sha256 and proves the passphrase *before* it drops/creates the target. `drill` does
  backup → restore into a random scratch database → compares the table list with per-table row counts and a data-only dump hash → drops the scratch database and checks it is gone.
- `scripts/mysql-init/20-backup-user.sh` (optional, mounted by compose): user `esun_backup` with `SELECT, SHOW VIEW, TRIGGER, EVENT` on the schema and `SHOW_ROUTINE` globally — enough
  for the dump, nothing that writes. The dump uses it when `BACKUP_DB_PASSWORD` is set (otherwise root, with a warning); restores always use root.
- `.gitignore`: `.backup-passphrase`.

## Verification (local stack with real data, 2026-09-29)

- Dump as `esun_backup` (grants confirmed by `SHOW GRANTS`), restore as root into a scratch database, drop it: `DRILL_OK tables=18 routines=3 rows_and_data_hash_match=yes scratch_removed=yes`
  (17 application tables plus `flyway_schema_history`; the three stored procedures came back).
- The encrypted file starts with the `Salted__` header and contains none of the probe strings (`Backup Item`, an e-mail domain, `CREATE TABLE`).
- Restore into a named copy: row counts equal for the five business tables checked; changing one row in the copy changes the data hash, so the comparison is sensitive.
- Safety: restore over the source name is refused; restore without `--force` is refused; a wrong passphrase fails with "decryption failed" **and creates no database**; a flipped ciphertext
  byte is caught by the sha256 check.
- Retention on a synthetic directory (5 fake generations, keep 2): the 3 oldest and their sidecars were pruned, 2 remain. The off-host hook ran and the file appeared in the
  target directory (a local directory standing in for a remote — this is a hook test, not an off-host proof).
- Two script defects were found and fixed during this run: the drill's per-table count loop stopped after the first table (`docker exec -i` swallowed the loop's stdin; the data hash still covered
  everything), and a wrong passphrase used to create an empty target database before failing.

## Not covered (G7 remains open)

- No off-host storage, no upload from the VM under a separate identity, no recovery from a downloaded off-host copy, no lifecycle/expiry policy on a remote, no backup scheduling (cron/systemd timer).
- RPO/RTO are not measured: the drill dataset is a few rows and the 17 s "restore_seconds" includes the backup and is not representative. Encryption is AES-CBC (not authenticated), so integrity relies on the
  sha256 sidecar; a keyed hash or `age` would be stronger. The passphrase must be stored somewhere other than the host that holds the backups, which this task does not arrange.
- On Windows filesystems the "passphrase file must be mode 600" check is skipped (Git Bash cannot set permission bits).
- No independent review.
