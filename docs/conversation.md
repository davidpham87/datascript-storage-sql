# Conversation Log

## 2024-05-22

Starting the task to port `datascript-storage-sql` to ClojureScript using `sql-wasm`.

### Initial Analysis

The repository contains:
- `src/datascript/storage/sql/core.clj`: Core logic.
- `test/datascript/storage/sql/`: Tests for different databases (H2, MySQL, PostgreSQL, SQLite).
- `deps.edn` and `project.clj`: Dependency management.

### Challenges

1.  **JDBC Dependency**: The current implementation likely uses `java.sql` or `javax.sql.DataSource` which are JVM specific. These need to be abstracted or conditionally compiled.
2.  **Concurrency**: JVM threads/locking might be used. CLJS is single-threaded (mostly).
3.  **Dependencies**: Need to introduce `shadow-cljs` and `sql.js` (wasm).
4.  **Testing**: Existing tests use JDBC drivers. New tests need to use `sql-wasm` in a node/browser environment.

### Progress

1.  **Dependencies**: Added `package.json` with `shadow-cljs` and `sql.js`. Added `shadow-cljs.edn`.
2.  **Code Porting**:
    - Renamed `src/datascript/storage/sql/core.clj` to `src/datascript/storage/sql/core.cljc`.
    - Abstracted database operations into conditional blocks (`#?(:clj ... :cljs ...)`).
    - Implemented `sql.js` backend logic for CLJS (using `exec`, `run`, `step`, `get`, `prepare`, `free`).
    - Guarded JVM specific code (imports, `Pool` record, `pool` function, macros with type hints) with `#?(:clj ...)`.
    - Added conditional type hints to avoid performance regressions on JVM.
3.  **Testing**:
    - Verified that existing JVM tests (H2, SQLite, Pool) pass with the new `core.cljc` file.
    - MySQL and PostgreSQL tests fail as expected due to missing database servers.

### Next Steps (Deferred)

1.  Configure `shadow-cljs` builds fully.
2.  Write CLJS tests using `cljs.test` and `sql.js`.
3.  Verify CLJS tests pass.
