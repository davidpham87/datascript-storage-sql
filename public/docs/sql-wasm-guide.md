# Using DataScript SQL Storage with SQLite WASM

This library supports persisting DataScript databases to SQLite in the browser using the `@sqlite.org/sqlite-wasm` library.

## Prerequisites

You need to add `@sqlite.org/sqlite-wasm` to your `package.json`:

```json
{
  "dependencies": {
    "@sqlite.org/sqlite-wasm": "^3.51.1"
  }
}
```

## Setup & Asset Management

SQLite WASM relies on `.wasm` files that must be served by your web server. Due to build tool (like `shadow-cljs`) compatibility issues with ES modules containing private fields, it is recommended to treat the WASM library as a static asset rather than bundling it directly.

1.  **Copy WASM assets**: You need to copy the `jswasm` directory from `node_modules/@sqlite.org/sqlite-wasm/sqlite-wasm/` to your public assets directory (e.g., `public/wasm`).

    You can add a script to your `package.json` to automate this:

    ```json
    "scripts": {
      "setup:wasm": "mkdir -p public/wasm && cp -r node_modules/@sqlite.org/sqlite-wasm/sqlite-wasm/jswasm/* public/wasm/"
    }
    ```

2.  **Load the script**: In your HTML file, you can load the SQLite initialization script or import it.

    ```html
    <!-- Example if serving statically -->
    <script src="wasm/sqlite3.js"></script>
    ```

## Initialization in ClojureScript

You need to initialize the SQLite module and then create a database connection. This connection is then passed to `datascript.storage.sql.core/make`.

```clojure
(ns my-app.core
  (:require
    [datascript.core :as d]
    [datascript.storage.sql.core :as storage-sql]
    [cljs.core.async :refer [go]]
    [cljs.core.async.interop :refer-macros [<p!]]))

(defn init-db []
  (go
    (try
      ;; Initialize SQLite WASM
      ;; Ensure sqlite3InitModule is available (e.g. loaded via script tag or dynamic import)
      (let [sqlite3 (<p! (js/sqlite3InitModule #js {:print js/console.log :printErr js/console.error}))

            ;; Access the OO1 API
            oo1 (.-oo1 sqlite3)

            ;; Create a database instance.
            ;; For in-memory: ":memory:"
            ;; For OPFS (persistent): "my-database.sqlite3" (requires appropriate headers)
            db (new (.-DB oo1) ":memory:" "ct")

            ;; Create the DataScript storage adapter
            ;; Note: In CLJS, we pass the DB connection object itself as the 'datasource'
            storage (storage-sql/make db {:dbtype :sqlite})]

        ;; Create DataScript connection backed by the storage
        ;; You might want to define a schema here
        (let [conn (d/create-conn {} {:storage storage})]
          (println "DataScript DB Initialized with SQLite WASM backend")
          conn))

      (catch :default e
        (js/console.error "Failed to init DB" e)))))
```

### Important Notes

*   **Persistence**: Using `":memory:"` will create a transient database. To persist data across reloads, you should use the Origin Private File System (OPFS) by providing a filename.
    *   **Headers**: using OPFS often requires your server to send specific headers:
        *   `Cross-Origin-Opener-Policy: same-origin`
        *   `Cross-Origin-Embedder-Policy: require-corp`
*   **Connection Object**: The `storage-sql/make` function in CLJS expects the direct SQLite connection object (an instance of `oo1.DB`), unlike the CLJ version which expects a `DataSource`.
*   **Lifecycle**: In the CLJS implementation, the connection provided to `make` is used directly. The library does not automatically open or close this connection for each operation; it assumes the connection is open. You should manage the closing of the database (e.g., `(.close db)`) when your application unmounts or terminates, if necessary.
