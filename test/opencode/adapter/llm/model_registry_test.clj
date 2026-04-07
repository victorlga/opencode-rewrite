(ns opencode.adapter.llm.model-registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [malli.core :as m]
   [matcher-combinators.test :refer [match?]]
   [opencode.adapter.llm.model-registry :as registry]))

(deftest get-model-test
  (testing "returns valid model for known ID"
    (let [model (registry/get-model "claude-sonnet-4-20250514")]
      (is (match? {:model/id       "claude-sonnet-4-20250514"
                   :model/provider :anthropic}
                  model))
      (is (m/validate registry/ModelInfo model))))

  (testing "returns valid model for claude-haiku"
    (let [model (registry/get-model "claude-haiku-3-5-20241022")]
      (is (match? {:model/id             "claude-haiku-3-5-20241022"
                   :model/provider       :anthropic
                   :model/context-window 200000
                   :model/max-output     8192}
                  model))))

  (testing "returns anomaly for unknown model ID"
    (is (match? {::anom/category ::anom/not-found
                 ::anom/message  "Unknown model: nonexistent-model"}
                (registry/get-model "nonexistent-model")))))

(deftest models-for-provider-test
  (testing "returns only Anthropic models"
    (let [anthropic-models (registry/models-for-provider :anthropic)]
      (is (= 2 (count anthropic-models)))
      (is (every? #(= :anthropic (:model/provider %)) anthropic-models))))

  (testing "returns empty vector for unknown provider"
    (is (match? [] (registry/models-for-provider :openai)))))

(deftest all-models-test
  (testing "returns all registered models"
    (is (= 2 (count (registry/all-models))))))

(deftest model-schema-validation-test
  (testing "all model entries match the ModelInfo schema"
    (doseq [model (registry/all-models)]
      (is (m/validate registry/ModelInfo model)
          (str "Model " (:model/id model) " should match ModelInfo schema")))))

(deftest validate-model-test
  (testing "returns model when valid"
    (let [model (registry/get-model "claude-sonnet-4-20250514")]
      (is (match? {:model/id "claude-sonnet-4-20250514"}
                  (registry/validate-model model)))))

  (testing "returns anomaly for invalid model map"
    (is (match? {::anom/category ::anom/incorrect
                 ::anom/message  "Invalid model metadata"}
                (registry/validate-model {:bad "data"})))))
