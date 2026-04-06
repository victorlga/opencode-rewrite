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

;; Thin CLI output adapter — all user-facing output goes through here.
;; Will be replaced by a proper UI adapter protocol in a future session.

(defn- cli-print!
  "Writes a message to stdout via the CLI output channel.
   Adapter seam: will be replaced by the UI protocol (AGENTS.md compliance)."
  [msg]
  (println msg))

(defn- cli-error!
  "Writes an error message to stderr via the CLI output channel."
  [msg]
  (binding [*out* *err*]
    (println msg)))

(defn -main
  "Application entry point. Parses CLI args, then starts the system."
  [& args]
  (let [{:keys [options summary errors]} (cli/parse-opts args cli-options)]
    (cond
      errors
      (do (doseq [e errors] (cli-error! e))
          (System/exit 1))

      (:help options)
      (cli-print! (usage summary))

      (:version options)
      (cli-print! (str "opencode-rewrite " version))

      :else
      (do
        (system/start!)
        (cli-print! "opencode-rewrite started")))))

(comment
  ;; REPL exploration
  (-main)
  (-main "--help")
  (-main "--version")
  ,)
