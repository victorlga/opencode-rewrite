(ns opencode.adapter.persistence-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [matcher-combinators.test :refer [match?]]
   [opencode.adapter.persistence :as persistence]
   [opencode.domain.session :as session]))

(deftest save-and-load-round-trip-test
  (testing "save-session! then load-session returns the same session"
    (let [store   (persistence/create-store)
          s       (session/create-session "claude-sonnet-4-20250514")
          _       (persistence/save-session! store s)
          loaded  (persistence/load-session store (:session/id s))]
      (is (match? s loaded)))))

(deftest load-unknown-id-test
  (testing "load-session returns nil for an unknown session ID"
    (let [store (persistence/create-store)]
      (is (nil? (persistence/load-session store (java.util.UUID/randomUUID)))))))

(deftest list-sessions-sorted-test
  (testing "list-sessions returns sessions sorted by created-at descending"
    (let [store (persistence/create-store)
          s1    (session/create-session "model-a")
          ;; Ensure s2 has a later timestamp
          _     (Thread/sleep 10)
          s2    (session/create-session "model-b")]
      (persistence/save-session! store s1)
      (persistence/save-session! store s2)
      (let [result (persistence/list-sessions store)]
        (is (= 2 (count result)))
        (is (= (:session/id s2) (:session/id (first result))))
        (is (= (:session/id s1) (:session/id (second result))))))))

(deftest delete-session-test
  (testing "delete-session! removes the session and returns the session-id"
    (let [store (persistence/create-store)
          s     (session/create-session "claude-sonnet-4-20250514")
          sid   (:session/id s)]
      (persistence/save-session! store s)
      (is (some? (persistence/load-session store sid)))
      (is (= sid (persistence/delete-session! store sid)))
      (is (nil? (persistence/load-session store sid))))))

(deftest save-returns-session-test
  (testing "save-session! returns the session that was saved"
    (let [store  (persistence/create-store)
          s      (session/create-session "claude-sonnet-4-20250514")
          result (persistence/save-session! store s)]
      (is (match? s result)))))

(deftest list-empty-store-test
  (testing "list-sessions on empty store returns empty vector"
    (let [store (persistence/create-store)]
      (is (= [] (persistence/list-sessions store))))))

(deftest save-invalid-session-test
  (testing "save-session! returns anomaly for invalid session data"
    (let [store  (persistence/create-store)
          result (persistence/save-session! store {:not "a session"})]
      (is (match? {::anom/category ::anom/incorrect
                   ::anom/message  "Invalid session data"}
                  result))
      ;; Verify nothing was stored
      (is (= [] (persistence/list-sessions store))))))
