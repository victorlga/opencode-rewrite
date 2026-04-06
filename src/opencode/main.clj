(ns opencode.main
  "Entry point for opencode-rewrite.
   Parses CLI arguments with tools.cli and starts the Integrant system."
  (:require
   [clojure.tools.cli :as cli]
   [opencode.system :as system]))

(def version "0.1.0-SNAPSHOT")

(def cli-options
  "CLI option specifications for tools.cli."
  [["-h" "--help" "Show this help message"]
   ["-v" "--version" "Print version and exit"]])

(defn- usage
  "Returns a usage summary string."
  [summary]
  (str "opencode-rewrite — AI coding agent in Clojure\n\n"
       "Usage: clj -M -m opencode.main [options]\n\n"
       "Options:\n"
       summary))

(defn -main
  "Application entry point. Parses CLI args, then starts the system."
  [& args]
  (let [{:keys [options summary errors]} (cli/parse-opts args cli-options)]
    (cond
      errors
      (do (doseq [e errors] (println e))
          (System/exit 1))

      (:help options)
      (println (usage summary))

      (:version options)
      (println (str "opencode-rewrite " version))

      :else
      (do
        (system/start!)
        (println "opencode-rewrite started")))))

(comment
  ;; REPL exploration
  (-main)
  (-main "--help")
  (-main "--version")
  ,)
