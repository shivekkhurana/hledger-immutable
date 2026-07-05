(ns hledger-immutable.build
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.java.io :as io]))

(def babashka-version "1.12.200")
(def cli-main "hledger-immutable.cli")
(def dev-output "hledger-immutable")
(def uberjar-output "target/hledger-immutable.jar")

(defn build-uberjar! []
  (fs/create-dirs "target")
  (fs/delete-if-exists uberjar-output)
  (process/check
   (process/process ["bb" "uberjar"
                     uberjar-output
                     "-m" cli-main]
                    {:out :inherit
                     :err :inherit
                     :shutdown nil})))

(defn- target-arch []
  (let [arch (or (System/getenv "HLEDGER_IMMUTABLE_TARGET_ARCH")
                 (System/getenv "ARCH")
                 (System/getProperty "os.arch"))]
    (case arch
      ("x86_64" "amd64") "amd64"
      ("aarch64" "arm64") "aarch64"
      (throw (ex-info "Unsupported build architecture"
                      {:arch arch})))))

(defn- target-platform [os]
  (str os "-" (target-arch)))

(defn- runtime-path [platform]
  (str "target/babashka-" babashka-version "-" platform "/bb"))

(defn- ensure-runtime! [platform]
  (let [archive (format "target/babashka-%s-%s.tar.gz"
                        babashka-version platform)
        url (format "https://github.com/babashka/babashka/releases/download/v%s/babashka-%s-%s.tar.gz"
                    babashka-version babashka-version platform)
        runtime-dir (format "target/babashka-%s-%s"
                            babashka-version platform)
        runtime (runtime-path platform)]
    (fs/create-dirs runtime-dir)
    (when-not (fs/exists? archive)
      (process/check
       (process/process ["curl" "-sSL" "-o" archive url]
                        {:out :inherit
                         :err :inherit
                         :shutdown nil})))
    (when-not (fs/exists? runtime)
      (process/check
       (process/process ["tar" "-xzf" archive "-C" runtime-dir]
                        {:out :inherit
                         :err :inherit
                         :shutdown nil})))
    runtime))

(defn- concatenate-binary! [runtime output]
  (fs/create-dirs (fs/parent output))
  (fs/delete-if-exists output)
  (with-open [out (java.io.BufferedOutputStream.
                   (java.io.FileOutputStream. output))]
    (doseq [input [runtime uberjar-output]]
      (with-open [in (java.io.BufferedInputStream.
                      (java.io.FileInputStream. input))]
        (io/copy in out))))
  (fs/set-posix-file-permissions output "rwxr-xr-x")
  (println "Wrote" output))

(defn build-prod! [os]
  (let [platform (target-platform os)]
    (concatenate-binary!
     (ensure-runtime! platform)
     (str "target/hledger-immutable-" platform))))

(defn write-dev-launcher! []
  (spit dev-output
        (str "#!/usr/bin/env bb\n"
             "(require '["
             cli-main
             " :as cli])\n"
             "(apply cli/-main *command-line-args*)\n"))
  (fs/set-posix-file-permissions dev-output "rwxr-xr-x")
  (println "Wrote" dev-output))
