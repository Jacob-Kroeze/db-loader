;; see https://ask.clojure.org/index.php/10905/control-transient-deps-that-compiled-assembled-into-uberjar?show=10913#c10913
(require 'clojure.tools.deps.alpha.util.s3-transporter)

(ns build
  (:refer-clojure :exclude [compile])
  (:require
    [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def version "0.0.1")

(def uber-basis
  (b/create-basis {:project "deps.edn"
                   :aliases [:native-deps]}))

(defn clean [_]
      (b/delete {:path "target"}))

(defn uber [_]
      (println "Removing target dir")
      (clean nil)
      (println "Compiling sources sources.")
      (b/compile-clj {:basis uber-basis
                      :src-dirs ["src"]
                      :class-dir class-dir
                      :ns-compile '[dbloader.main]})
      (b/copy-dir {:src-dirs ["resources"]
                   :target-dir class-dir})
      (println "Building uberjar.")
      (b/uber {:class-dir class-dir
               :uber-file "dbloader.jar"
               :basis     uber-basis
               :exclude   [#"(?i)^META-INF/license/.*"
                           #"^license/.*"
                           #"(?i)^META-INF/LICENSE.txt"
                           ]
               :main      'dbloader.main
               :jvm-opts ["-Dtech.v3.datatype.graal-native=true"
                          "-Dclojure.compiler.direct-linking=true"
                          "-Dclojure.spec.skip-macros=true"]}))