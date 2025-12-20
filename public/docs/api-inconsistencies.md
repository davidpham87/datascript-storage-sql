# API Inconsistencies: CLJ vs CLJS

This document outlines the API differences and inconsistencies between the Clojure (JVM) and ClojureScript (JS/WASM) implementations of `datascript-storage-sql`.

## 1. Storage Creation (`make`)

*   **CLJ**: Expects a `javax.sql.DataSource` object.
    ```clojure
    (storage-sql/make my-datasource {:dbtype :h2})
    ```
*   **CLJS**: Expects a `sqlite3.oo1.DB` connection object (from `@sqlite.org/sqlite-wasm`).
    ```clojure
    (storage-sql/make my-db-connection {:dbtype :sqlite})
    ```

    **Issue**: The term `datasource` in the arglist is misleading for CLJS. CLJ manages connections via the DataSource (opening/closing per operation or transaction), while CLJS operates on a single long-lived connection instance passed at creation.

    **Recommendation**:
    *   Rename the argument in docstrings to generic `connection-or-datasource`.
    *   Consider creating a wrapper protocol/type in CLJS that mimics a DataSource if connection management needs to be unified (e.g., auto-reconnecting), though for WASM this might be overkill.

## 2. Connection Management (`with-conn`)

*   **CLJ**: `with-conn` obtains a *new* connection from the `DataSource` for the scope of the body and closes it afterwards.
*   **CLJS**: `with-conn` binds the existing connection object (passed as "datasource") to the local var. It *does not* open or close connections.

    **Issue**: Semantics differ fundamentally. CLJ is designed for server-side concurrency/pooling. CLJS is designed for single-user browser usage.

    **Recommendation**: Explicitly document this behavioral difference. In CLJS, the user is responsible for the lifecycle of the connection (opening before `make`, closing when app unmounts).

## 3. Transaction Handling

*   **CLJ**: Uses JDBC methods `.setAutoCommit`, `.commit`, `.rollback`.
*   **CLJS**: Manually executes SQL strings `BEGIN TRANSACTION`, `COMMIT`, `ROLLBACK`.

    **Issue**: Mostly internal, but relies on string execution in CLJS.

    **Recommendation**: Ensure `sqlite-wasm` doesn't have a dedicated API for transactions that should be used instead (e.g. `db.transaction()`), although SQL execution is likely fine.

## 4. Connection Pooling

*   **CLJ**: Includes a simple `pool` implementation.
*   **CLJS**: No pooling.

    **Issue**: None, as pooling is generally not required for single-client WASM usage.

    **Recommendation**: Clarify in docs that `pool` is CLJ-only.

## 5. `close` Function

*   **CLJ**: Closes the `DataSource` (if it implements `AutoCloseable`).
*   **CLJS**: Calls `.close` on the connection object.

    **Issue**: Consistent behavior (resource cleanup), but operates on different types.

    **Recommendation**: Keep as is, but document.

## Summary of Actionable Items

1.  Update docstrings in `core.cljc` to reflect that `datasource` can be a `DB` instance in CLJS.
2.  Standardize `make` to perhaps accept a map or a more descriptive type in future versions if abstraction is desired.
3.  Ensure CLJS users are aware they must manage the DB connection lifecycle externally.
