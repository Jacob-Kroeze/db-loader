(ns dbloader.main
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as string]
            [hyperfiddle.rcf :as rcf]
            [tech.v3.libs.parquet :as parquet]
            [tech.v3.dataset :as ds]
            [tech.v3.dataset.sql :as sql]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [clojure.edn :as edn]
            [taoensso.timbre :as log]
            [slf4j-timbre.adapter])
  (:gen-class))


(set! *warn-on-reflection* true)

(log/set-level! :warn)

(def release "TODO parse release from build")

(def cli-options
  [["-v" "--verbose" "TODO display debug"]
   ["-V" "--version"]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["Use this program to load a <file.parquet>
   file into a Microsoft SQL database"
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


(defn load-parquet
  "Returns dataset"
  [file]
  (ds/->dataset file {:key-fn clean-cols}))

;;;; sql
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

(defn file-path-to-table-name [filename]
  ;(def path-vec ["a" "b" "c" "d"])
  (let [path-vec (string/split (.getName (io/file filename))
                               #"\.")
        n (count path-vec)]
    (if (= 2 n) (first path-vec)
                (string/join "."
                             (conj []
                                   (first path-vec)
                                   (string/join "_" (butlast (rest path-vec))))))))

(rcf/tests
  (file-path-to-table-name "this/is/a/test.test.test.parquet") := "test.test_test"
  )


(defn file->db
  "Given tbl and file, use tech ml dataset to parse, then load to db"
  [conn file-in]
  (let [
        DS (load-parquet file-in)
        table-name (file-path-to-table-name file-in)
        DS (ds/set-dataset-name DS table-name)]
    (sql/ensure-table! conn DS)
    (sql/insert-dataset! conn DS)
    table-name))


(rcf/tests
  (def conn (jdbc/get-connection
              (db-spec (secrets))
              {:auto-commit false}))
  (def filename "test/data/fact.test.parquet")
  (def DS (ds/->dataset [{:x 1 :y 2} {:x 2 :y 4}]))
  (ds/write! DS filename)
  (def DS2 (load-parquet filename))
  (file->db conn filename) := "fact.test"

  (def filename2 "test/data/test.parquet")
  (def DS3 (ds/->dataset [{:x 1 :y 2} {:x 2 :y 4}]))
  (ds/write! DS3 filename2)
  (def DS4 (load-parquet filename2))
  (file->db conn filename2) := "test"

  (def DS (ds/->dataset [{:x 1 :y 2} {:x 2 :y 4}]))
  (ds/write! DS "test/data/test.parquet")
  (-> (load-parquet (io/file "test/data/test.parquet")) ffirst) := (-> DS ffirst)
  (ds/write! DS "test/data/fact.test.test.parquet")
  )



(defn -main [& args]
  ;; todo exit after this works
  ;; todo add resource dir to build to configure logging
  (let [{:keys [options arguments file-in exit-message ok?]}
        (validate-args args)
        {:keys [:parquet :version :help]}
        options]
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (cond file-in                                         ;; only parquet implemented
            (file->db (jdbc/get-connection
                        (db-spec (secrets))
                        {:auto-commit false})
                      file-in))))
  (shutdown-agents))

