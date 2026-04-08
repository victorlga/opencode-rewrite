(ns opencode.adapter.ui.repl
  "JLine readline-based UI adapter for opencode.
   Implements the UIAdapter protocol using ANSI colors and JLine 3 LineReader
   for interactive terminal I/O. Wired as Integrant component :opencode/ui."
  (:require
   [clojure.string :as str]
   [integrant.core :as ig]
   [opencode.logic.ui :as ui])
  (:import
   (org.jline.reader EndOfFileException LineReaderBuilder UserInterruptException)
   (org.jline.terminal TerminalBuilder)))

;; ---------------------------------------------------------------------------
;; ANSI color helpers
;; ---------------------------------------------------------------------------

(defn ansi
  "Wraps text with an ANSI escape code. code is the SGR parameter string
   (e.g. \"1;36\" for bold cyan). Resets to default after text."
  [code text]
  (str "\033[" code "m" text "\033[0m"))

(def ^:private ansi-white "0;37")
(def ^:private ansi-cyan  "1;36")
(def ^:private ansi-dim   "2")
(def ^:private ansi-red   "0;31")
(def ^:private ansi-yellow "1;33")

;; ---------------------------------------------------------------------------
;; ReplUI record
;; ---------------------------------------------------------------------------

(defrecord ReplUI [line-reader terminal]
  ui/UIAdapter

  (display-text! [_this text]
    (println (ansi ansi-white text)))

  (display-tool-call! [_this tool-name params]
    (println (str (ansi ansi-cyan (str "[" tool-name "] "))
                  (pr-str params))))

  (display-tool-result! [_this tool-name result]
    (let [summary (if (> (count result) 500)
                    (str (subs result 0 500) "...")
                    result)]
      (println (str (ansi ansi-dim (str "[" tool-name "] "))
                    (ansi ansi-dim summary)))))

  (display-error! [_this error]
    (println (ansi ansi-red (str "Error: " error))))

  (ask-permission! [_this tool-name params]
    (println (ansi ansi-yellow
                   (str "Tool \"" tool-name "\" wants to run with: "
                        (pr-str params))))
    (let [answer (try
                   (.readLine line-reader
                              (ansi ansi-yellow "Allow? [y/n] "))
                   (catch EndOfFileException _ nil)
                   (catch UserInterruptException _ nil))]
      (if (and answer (= (str/lower-case (str/trim answer)) "y"))
        :approved
        :denied)))

  (get-input! [_this prompt]
    (try
      (.readLine line-reader prompt)
      (catch EndOfFileException _ nil)
      (catch UserInterruptException _ nil))))

;; ---------------------------------------------------------------------------
;; Constructor
;; ---------------------------------------------------------------------------

(defn create-repl-ui
  "Creates a ReplUI backed by a JLine LineReader connected to the system
   terminal. The terminal and reader are created eagerly."
  []
  (let [terminal (-> (TerminalBuilder/builder)
                     (.system true)
                     (.build))
        reader   (-> (LineReaderBuilder/builder)
                     (.terminal terminal)
                     (.build))]
    (->ReplUI reader terminal)))

;; ---------------------------------------------------------------------------
;; Integrant component
;; ---------------------------------------------------------------------------

(defmethod ig/init-key :opencode/ui [_ _opts]
  (create-repl-ui))

(defmethod ig/halt-key! :opencode/ui [_ ui]
  (when-let [term (:terminal ui)]
    (.close term)))

(comment
  ;; REPL exploration (requires a real terminal)
  (def ui (create-repl-ui))
  (ui/display-text! ui "Hello, world!")
  (ui/display-tool-call! ui "bash" {:command "ls"})
  (ui/display-tool-result! ui "bash" "file1.txt\nfile2.txt")
  (ui/display-error! ui "Something went wrong")
  ;; Interactive — blocks for input:
  ;; (ui/ask-permission! ui "bash" {:command "rm -rf /"})
  ;; (ui/get-input! ui "Enter command: ")
  ,)
