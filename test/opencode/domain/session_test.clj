(ns opencode.domain.session-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cognitect.anomalies :as anom]
   [malli.core :as m]
   [matcher-combinators.test :refer [match?]]
   [opencode.domain.message :as message]
   [opencode.domain.session :as session])
  (:import
   (java.time Instant)))

(deftest create-session-test
  (testing "creates a valid session with correct defaults"
    (let [s (session/create-session "claude-sonnet-4-20250514")]
      (is (m/validate session/Session s))
      (is (match? {:session/title    "New Session"
                   :session/messages []
                   :session/model    "claude-sonnet-4-20250514"
                   :session/tokens   {:input 0 :output 0}}
                  s))
      (is (uuid? (:session/id s)))
      (is (instance? Instant (:session/created-at s))))))

(deftest append-message-test
  (testing "append-message + get-messages round-trip"
    (let [s   (session/create-session "gpt-4")
          msg (message/user-message "Hello")
          s'  (session/append-message s msg)]
      (is (match? [msg] (session/get-messages s')))
      (is (= 1 (count (session/get-messages s'))))))

  (testing "appending multiple messages preserves order"
    (let [s    (session/create-session "gpt-4")
          msg1 (message/user-message "Hello")
          msg2 (message/assistant-message "Hi there!" nil :stop)
          msg3 (message/user-message "Thanks")
          s'   (-> s
                   (session/append-message msg1)
                   (session/append-message msg2)
                   (session/append-message msg3))]
      (is (match? [msg1 msg2 msg3] (session/get-messages s')))))

  (testing "append-message returns anomaly for invalid message"
    (let [s (session/create-session "gpt-4")]
      (is (match? {::anom/category ::anom/incorrect
                   ::anom/message  "Invalid message data"}
                  (session/append-message s {:not "a valid message"}))))))

(deftest get-messages-test
  (testing "get-messages on new session returns empty vector"
    (is (match? [] (session/get-messages (session/create-session "gpt-4"))))))

(deftest session-token-count-test
  (testing "returns correct token map"
    (let [s (session/create-session "gpt-4")]
      (is (match? {:input 0 :output 0} (session/session-token-count s)))))

  (testing "returns updated token map after update-tokens"
    (let [s (-> (session/create-session "gpt-4")
                (session/update-tokens 100 50))]
      (is (match? {:input 100 :output 50} (session/session-token-count s))))))

(deftest update-tokens-test
  (testing "accumulates token deltas correctly"
    (let [s (-> (session/create-session "gpt-4")
                (session/update-tokens 100 50)
                (session/update-tokens 200 75)
                (session/update-tokens 50 25))]
      (is (match? {:input 350 :output 150} (session/session-token-count s)))))

  (testing "handles zero deltas"
    (let [s (-> (session/create-session "gpt-4")
                (session/update-tokens 0 0))]
      (is (match? {:input 0 :output 0} (session/session-token-count s))))))
