(ns opencode.config-test
  "Tests for opencode.config — config loading, Malli validation, and Integrant component."
  (:require
   [clojure.test :refer [deftest testing is]]
   [cognitect.anomalies :as anom]
   [matcher-combinators.test :refer [match?]]
   [opencode.config :as config]
   [integrant.core :as ig]
   [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Schema validation tests
;; ---------------------------------------------------------------------------

(deftest config-schema-validates-good-config
  (testing "a valid config map passes validation"
    (let [good-config {:opencode
                       {:llm     {:provider "anthropic"
                                  :api-key  "sk-test-key"
                                  :model    "claude-sonnet-4-20250514"}
                        :tools   {:allowed #{:bash :read-file}}
                        :ui      {:type :repl}
                        :project {:directory "."}}}]
      (is (true? (m/validate config/Config good-config))))))

(deftest config-schema-rejects-bad-config
  (testing "missing :llm key is rejected"
    (let [result (config/validate-config {:opencode {:tools {:allowed #{:bash}}
                                                     :ui {:type :repl}
                                                     :project {:directory "."}}})] 
      (is (match? {::anom/category ::anom/incorrect} result))))

  (testing "wrong type for :model (integer instead of string) is rejected"
    (let [result (config/validate-config {:opencode
                                          {:llm     {:provider "anthropic"
                                                     :api-key  nil
                                                     :model    42}
                                           :tools   {:allowed #{:bash}}
                                           :ui      {:type :repl}
                                           :project {:directory "."}}})] 
      (is (match? {::anom/category ::anom/incorrect} result))))

  (testing "wrong type for provider is rejected"
    (let [result (config/validate-config {:opencode
                                          {:llm     {:provider 123
                                                     :api-key  nil
                                                     :model    "claude-sonnet-4-20250514"}
                                           :tools   {:allowed #{:bash}}
                                           :ui      {:type :repl}
                                           :project {:directory "."}}})] 
      (is (match? {::anom/category ::anom/incorrect} result))))

  (testing "invalid UI type is rejected"
    (let [result (config/validate-config {:opencode
                                          {:llm     {:provider "anthropic"
                                                     :api-key  nil
                                                     :model    "claude-sonnet-4-20250514"}
                                           :tools   {:allowed #{:bash}}
                                           :ui      {:type :invalid}
                                           :project {:directory "."}}})] 
      (is (match? {::anom/category ::anom/incorrect} result)))))

;; ---------------------------------------------------------------------------
;; Config loading tests
;; ---------------------------------------------------------------------------

(deftest load-config-from-resource
  (testing "loads the default config.edn from resources"
    (let [cfg (config/load-config)]
      (is (match? {:opencode {:llm {:provider "anthropic"
                                    :model    "claude-sonnet-4-20250514"}
                              :tools {:allowed #{:bash :read-file :write-file
                                                 :edit-file :glob :grep}}
                              :ui {:type :repl}
                              :project {:directory "."}}}
                  cfg)))))

(deftest load-config-from-edn-string
  (testing "loads config from an EDN string via Aero"
    (let [edn-str "{:opencode {:llm {:provider \"openai\" :model \"gpt-4\"} :tools {:allowed #{:bash}} :ui {:type :tui} :project {:directory \"/tmp\"}}}"
          cfg     (config/load-config (java.io.StringReader. edn-str))]
      (is (match? {:opencode {:llm {:provider "openai" :model "gpt-4"}}}
                  cfg)))))

;; ---------------------------------------------------------------------------
;; Validate config tests
;; ---------------------------------------------------------------------------

(deftest validate-config-returns-valid
  (testing "validate-config returns the config when valid"
    (let [cfg {:opencode {:llm     {:provider "anthropic"
                                    :api-key  nil
                                    :model    "claude-sonnet-4-20250514"}
                          :tools   {:allowed #{:bash}}
                          :ui      {:type :repl}
                          :project {:directory "."}}}]
      (is (= cfg (config/validate-config cfg))))))

(deftest validate-config-returns-anomaly-on-invalid
  (testing "validate-config returns an anomaly map on invalid config"
    (let [result (config/validate-config {:bad "config"})]
      (is (config/anomaly? result))
      (is (match? {::anom/category ::anom/incorrect
                   ::anom/message  "Invalid configuration"}
                  result)))))

(deftest load-and-validate-throws-on-invalid
  (testing "load-and-validate! throws ex-info when config is invalid"
    (let [bad-edn "{:opencode {:llm {:provider 123}}}"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid configuration"
                            (config/load-and-validate! (java.io.StringReader. bad-edn)))))))

;; ---------------------------------------------------------------------------
;; Integrant component tests
;; ---------------------------------------------------------------------------

(deftest integrant-config-component
  (testing "Integrant init-key loads and returns the :opencode config map"
    (let [system (ig/init {:opencode/config {}})]
      (is (match? {:llm {:provider "anthropic"}}
                  (:opencode/config system))))))
