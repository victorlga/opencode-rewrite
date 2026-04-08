(ns opencode.domain.tool-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cognitect.anomalies :as anom]
   [matcher-combinators.test :refer [match?]]
   [opencode.domain.tool :as tool]))

;; ---------------------------------------------------------------------------
;; Fixture: reset registry between tests
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (tool/reset-registry!)
    (try (f) (finally (tool/reset-registry!)))))

;; ---------------------------------------------------------------------------
;; Registry tests
;; ---------------------------------------------------------------------------

(def sample-tool
  {:tool/name        "test_tool"
   :tool/description "A test tool"
   :tool/parameters  [:map [:input :string]]
   :tool/dangerous?  false})

(deftest register-and-get-tool-test
  (testing "register-tool! + get-tool round-trips"
    (let [result (tool/register-tool! sample-tool)]
      (is (match? {:tool/name "test_tool"} result))
      (is (match? {:tool/name        "test_tool"
                   :tool/description "A test tool"
                   :tool/dangerous?  false}
                  (tool/get-tool "test_tool"))))))

(deftest get-tool-returns-nil-for-unknown-test
  (testing "get-tool returns nil for unregistered tool"
    (is (nil? (tool/get-tool "nonexistent")))))

(deftest register-tool-validation-test
  (testing "register-tool! returns anomaly for invalid tool def"
    (is (match? {::anom/category ::anom/incorrect}
                (tool/register-tool! {:tool/name "missing-fields"})))))

(deftest all-tools-test
  (testing "all-tools returns vector of registered tools"
    (tool/register-tool! sample-tool)
    (tool/register-tool! (assoc sample-tool :tool/name "another_tool"))
    (is (= 2 (count (tool/all-tools))))))

;; ---------------------------------------------------------------------------
;; API format conversion tests
;; ---------------------------------------------------------------------------

(deftest tools-for-api-test
  (testing "tools-for-api generates valid JSON Schema structure"
    (tool/register-tool! sample-tool)
    (let [api-tools (tool/tools-for-api (tool/all-tools))]
      (is (= 1 (count api-tools)))
      (is (match? {:name         "test_tool"
                   :description  "A test tool"
                   :input_schema {:type       "object"
                                  :properties {:input {:type "string"}}
                                  :required   [:input]}}
                  (first api-tools))))))

(deftest tools-for-api-optional-params-test
  (testing "tools-for-api handles optional parameters correctly"
    (tool/register-tool! {:tool/name        "opt_tool"
                          :tool/description "Tool with optional params"
                          :tool/parameters  [:map
                                             [:path :string]
                                             [:offset {:optional true} :int]]
                          :tool/dangerous?  false})
    (let [api-tools (tool/tools-for-api (tool/all-tools))
          schema    (:input_schema (first api-tools))]
      (is (match? {:type       "object"
                   :properties {:path   {:type "string"}
                                :offset {:type "integer"}}
                   :required   [:path]}
                  schema)))))

;; ---------------------------------------------------------------------------
;; Execution multimethod tests
;; ---------------------------------------------------------------------------

(deftest execute-tool-default-test
  (testing "execute-tool! :default returns unsupported anomaly"
    (is (match? {::anom/category ::anom/unsupported
                 ::anom/message  "Unknown tool: nonexistent"}
                (tool/execute-tool! "nonexistent" {} {})))))
