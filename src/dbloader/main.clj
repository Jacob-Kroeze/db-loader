(ns dbloader.main
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as string]
            [hyperfiddle.rcf :as rcf]
            [tech.v3.libs.parquet :as parquet]
            [tech.v3.dataset :as ds]
            [tech.v3.dataset.sql :as sql]
            [tech.v3.dataset.io.datetime :as datetime]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [clojure.edn :as edn]
            [taoensso.timbre :as log]
            [slf4j-timbre.adapter])
  (:gen-class)
  (:import (com.microsoft.sqlserver.jdbc SQLServerException)))


(set! *warn-on-reflection* true)

(log/set-level! :warn)

(def release "TODO parse release from build")

(def cli-options
  [["-l" "--load-date-from-filename"
    "Add today's date or Parse load_date yyyy-MM-dd from filename if that exists. Add load_date column"]
   ["-V" "--version"]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Use this program to load a <file.parquet>
         file into a Microsoft SQL database. Using opt
         --load-date-from-filename Files
         prefixed with an ISO yyyy-MM-dd will have that
         date put into a col call ``load_date```. Files
         starting with a number will fail.
         E.G 2002-2-2-test.parquet fails;
         2022-02-02-test.parquet succeeds."
        ""
        "Usage: dbloader [options] action"
        ""
        "Options"
        options-summary
        ""
        "Examples:"
        "dbloader file.parquet"]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn validate-args [args]
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}

      (:version options)
      (:exit-message (println "Released: " release))

      errors
      {:exit-message (error-msg errors)}

      arguments
      {:arguments arguments
       :file-in   (first arguments)
       :file-type (last (string/split (first arguments) #"\."))
       :options   options}

      :else
      {:exit-message (usage summary)})))


(defn exit [status msg]
  (System/exit status))

(defn clean-cols [s]
  (if (keyword? s) s
                   (keyword nil                             ;;ns
                            (-> s
                                (string/replace #"'|\/|\(|\)" "")
                                (string/replace #" |[.]|-" "_")
                                (string/replace #"\uFEFF" "") ;; CSV BOM
                                (string/replace #"%" "pct")))))


(defn secrets []
  (let [env #(System/getenv %)
        secrets-file (try (slurp (io/file (env "HOME") "secrets.edn"))
                          (catch java.io.FileNotFoundException e
                            (do (print "secrets.edn not found in HOME. Trying System env")
                                nil)))
        secrets (if secrets-file
                  (clojure.edn/read-string secrets-file)
                  {:sql-server   (env "SQL_SERVER")
                   :sql-password (env "SQL_PASSWORD")
                   :sql-user     (env "SQL_USER")})]
    secrets))

(defn db-spec [secrets]
  {:dbtype                 "sqlserver"
   :user                   (:sql-user secrets)
   :password               (:sql-password secrets)
   :host                   (:sql-server secrets)
   :trustServerCertificate true}
  )

(defn remove-date [date s]
  (let [date-removed (string/replace s (re-pattern (str date)) "")
        [f & r] date-removed]
    (cond
      (nil? date) s
      (empty? date-removed) nil
      r (apply str r)
      :else r)))

(rcf/tests
  (remove-date (java.time.LocalDate/parse "2022-02-02") "2022-02-02" ) := nil
  (remove-date (java.time.LocalDate/parse "2022-02-02") "2022-02-02-" ) := nil
  (remove-date (java.time.LocalDate/parse "2022-02-02") "2022-02-02-test.parquet" ) := "test.parquet"
  (remove-date (java.time.LocalDate/parse "2022-08-10")
    "2022-08-10-test.test.test.parquet")

  )

(defn file-path-to-table-name
  "If using prefix date for popMust be iso yyyy-MM-dd"
  [filename]
  ;(def path-vec ["a" "b" "c" "d"])
  (let [path-vec (string/split (.getName (io/file filename))
                               #"\.")

        date-prefix (try (datetime/parse-local-date
                           (apply str (take 10 (first path-vec))))
                         (catch Throwable e nil))
        ;_ (def d date-prefix)
        ;_ (def a path-vec) ;path-vec
        n (count path-vec)]
    (cond (= 2 n) [date-prefix (remove-date date-prefix (first path-vec))]
          (< 2 n) [date-prefix (string/join "."
                                            (conj []
                                                  (remove-date date-prefix (first path-vec))
                                                  (string/join "_" (butlast (rest path-vec)))))])))

(rcf/tests

  (let [[date table] (file-path-to-table-name "/no/schema/test.parquet")] table) := "test"
  (let [[date table] (file-path-to-table-name "/yes/schema/test.test.test.parquet")] table) := "test.test_test"

  (let [[date table] (file-path-to-table-name "this/is/a/DATED/2022-08-10-test.parquet")] [(str date) table]) := ["2022-08-10" "test"] ;; n = 2

  (let [[date table] (file-path-to-table-name "this/is/a/DATED/2022-08-10-test.test.test.parquet")] [(str date) table]) := ["2022-08-10" "test.test_test"] ;; n > 2

  (file-path-to-table-name "test/data/2022-8-10-fact.test.parquet")

  )


(defn file->db
  "Given tbl and file, use tech ml dataset to parse, then load to db"
  [conn file-in & {:keys [load-date-from-filename]}]
  (let [
        [load-date schema-and-table] (file-path-to-table-name file-in)
        [schema table-name] (string/split schema-and-table #"\.")
        DS (ds/->dataset file-in {:key-fn clean-cols})
        DS (if load-date-from-filename
             (assoc DS "load_date" (or load-date (java.time.LocalDate/now)))
             DS)
        create-sql-stmt (sql/create-sql "Microsoft SQL Server" DS {:table-name schema-and-table})
        _ (println
            "
            Summary: \n
            \n
            Sql Create Statement:\n
            "
            create-sql-stmt
            "\n
            Target Schema and Table: " schema-and-table
            )
        ensure-table-fn #(sql/ensure-table! conn DS {:table-name schema-and-table})
        ]

    (try
      (do (ensure-table-fn)
        (println "schema-and-table var: " schema-and-table))
      (catch Throwable e                                    ;SQLServerException e
        (do
          (log/error :throwable e)
          (log/warn :sql-dll "caught exception while creating table and schema:" schema-and-table "...creating schema: " schema)
          (jdbc/execute! conn [(format "create schema %s" schema)])
          (.commit conn)
          (log/warn :sql-dll "...Done.")))
      (finally (ensure-table-fn))
      )
    (sql/insert-dataset! conn DS {:table-name schema-and-table})
    schema-and-table))

(rcf/tests


  (def conn (jdbc/get-connection
                (db-spec (secrets))
                {:auto-commit false}))

  (def filename "test/data/fact.test.parquet")
  (def DS (ds/->dataset [{:x 1 :y 2} {:x 2 :y 4}]))
  (ds/write! DS filename)
  (ds/write! DS "test/data/test.csv")
  (def to-ds #(ds/->dataset % {:key-fn clean-cols}))
  (def DS2 (to-ds filename))

  (file-path-to-table-name filename)                        ; fact.test

  (file->db conn filename) := "fact.test"

  (def filename2 "test/data/test.parquet")
  (def DS3 (ds/->dataset [{:x 1 :y 2} {:x 2 :y 4}]))
  (ds/write! DS3 filename2)
  (def DS4 (to-ds filename2))
  (file->db conn filename2) := "test"

  (def DS (ds/->dataset [{:x 1 :y 2} {:x 2 :y 4}]))

  (ds/write! DS "test/data/test.parquet")
  (-> (to-ds (io/file "test/data/test.parquet")) ffirst) := (-> DS ffirst)
  (ds/write! DS "test/data/fact.test.test.parquet")

  (def dated-file "test/data/2022-08-10-fact.test_dated.parquet")
  ;(file-path-to-table-name dated-file)
  (def dated-ds (ds/write! DS dated-file))
  (file->db conn dated-file)
  (file-path-to-table-name dated-file)
  (def ds11 (to-ds dated-file))

  (file->db conn dated-file {:load-date-from-filename true}) := "fact.test_dated"
  (-> (sql/sql->dataset conn "select * from fact.test_dated")
    (ds/column "load_date")
    first
    str) := "2022-08-10"
  )



(defn -main [& args]
  ;; todo exit after this works
  ;; todo add resource dir to build to configure logging
  (let [{:keys [options arguments file-in exit-message ok?]}
        (validate-args args)
        {:keys [:parquet :load-date-from-filename
                :version :help]}
        options]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (cond file-in                                         ;; only parquet implemented
            (file->db (jdbc/get-connection
                        (db-spec (secrets))
                        {:auto-commit false})
                      file-in
              {:load-date-from-filename load-date-from-filename}))))
  (shutdown-agents))

