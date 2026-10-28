# Recoverable migrations

Each migration is an ordered SQL file named `NNNN_description.up.sql` and an optional paired `.down.sql`. The runner applies SQLite migrations transactionally, records a schema ledger, and creates a verified database backup before mutation. Rollback runs the down migration only after a fresh backup. Review all SQL and test on a copy first. This utility does not migrate JSON stores, Android Room databases, or a remote production database automatically; adapters and per-store migrations are required for those stores.
