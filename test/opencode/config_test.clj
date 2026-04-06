(ns opencode.config-test
  "Tests for opencode.config — config loading, Malli validation, and Integrant component."
  (:require
   [clojure.test :refer [deftest testing is]]
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
  (testing "missing required fields are rejected"
    (is (false? (m/validate config/Config {}))))

  (testing "wrong type for provider is rejected"
    (is (false? (m/validate config/Config
                            {:opencode
                             {:llm     {:provider 123
                                        :model    "claude-sonnet-4-20250514"}
                              :tools   {:allowed #{:bash}}
                              :ui      {:type :repl}
                              :project {:directory "."}}}))))

  (testing "invalid UI type is rejected"
    (is (false? (m/validate config/Config
                            {:opencode
                             {:llm     {:provider "anthropic"
                                        :model    "claude-sonnet-4-20250514"}
                              :tools   {:allowed #{:bash}}
                              :ui      {:type :invalid}
                              :project {:directory "."}}})))))

;; ---------------------------------------------------------------------------
;; Config loading tests
;; ---------------------------------------------------------------------------

(deftest load-config-from-resource
  (testing "loads the default config.edn from resources"
    (let [config (config/load-config)]
      (is (match? {:opencode {:llm {:provider "anthropic"
                                    :model    "claude-sonnet-4-20250514"}
                              :tools {:allowed #{:bash :read-file :write-file
                                                 :edit-file :glob :grep}}
                              :ui {:type :repl}
                              :project {:directory "."}}}
                  config)))))

(deftest load-config-from-edn-string
  (testing "loads config from an EDN string via Aero"
    (let [edn-str "{:opencode {:llm {:provider \"openai\" :model \"gpt-4\"} :tools {:allowed #{:bash}} :ui {:type :tui} :project {:directory \"/tmp\"}}}"
          config  (config/load-config (java.io.StringReader. edn-str))]
      (is (match? {:opencode {:llm {:provider "openai" :model "gpt-4"}}}
                  config)))))

;; ---------------------------------------------------------------------------
;; Validate config tests
;; ---------------------------------------------------------------------------

(deftest validate-config-returns-valid
  (testing "validate-config returns the config when valid"
    (let [cfg {:opencode {:llm     {:provider "anthropic"
                                    :model    "claude-sonnet-4-20250514"}
                          :tools   {:allowed #{:bash}}
                          :ui      {:type :repl}
                          :project {:directory "."}}}]
      (is (= cfg (config/validate-config cfg))))))

(deftest validate-config-throws-on-invalid
  (testing "validate-config throws ex-info on invalid config"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid configuration"
                          (config/validate-config {:bad "config"})))))

;; ---------------------------------------------------------------------------
;; Integrant component tests
;; ---------------------------------------------------------------------------

(deftest integrant-config-component
  (testing "Integrant init-key loads and returns the :opencode config map"
    (let [system (ig/init {:opencode/config {}})]
      (is (match? {:llm {:provider "anthropic"}}
                  (:opencode/config system))))))
