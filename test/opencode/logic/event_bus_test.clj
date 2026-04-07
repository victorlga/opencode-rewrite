(ns opencode.logic.event-bus-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]
   [matcher-combinators.test :refer [match?]]
   [opencode.logic.event-bus :as bus])
  (:import
   (java.time Instant)))

(deftest publish-subscribe-test
  (testing "subscriber receives matching events"
    (let [b      (bus/create-bus)
          sub-ch (bus/subscribe! b :session/updated 16)]
      (try
        (bus/publish! b :session/updated {:session/id "abc"})
        (let [[event _] (async/alts!! [sub-ch (async/timeout 1000)])]
          (is (some? event))
          (is (match? {:event/type :session/updated
                       :event/data {:session/id "abc"}}
                      event))
          (is (instance? Instant (:event/timestamp event))))
        (finally
          (bus/close-bus! b))))))

(deftest subscriber-filtering-test
  (testing "subscriber does not receive non-matching events"
    (let [b      (bus/create-bus)
          sub-ch (bus/subscribe! b :llm/stream-done 16)]
      (try
        (bus/publish! b :session/updated {:session/id "abc"})
        ;; Give the event time to propagate (it shouldn't arrive)
        (let [[event ch] (async/alts!! [sub-ch (async/timeout 200)])]
          (is (nil? event) "should not receive non-matching event")
          (is (not= sub-ch ch) "should have timed out"))
        (finally
          (bus/close-bus! b))))))

(deftest close-bus-test
  (testing "close-bus! causes subscriber channel to close"
    (let [b      (bus/create-bus)
          sub-ch (bus/subscribe! b :tool/executing 16)]
      (bus/close-bus! b)
      ;; A closed channel returns nil
      (let [[event _] (async/alts!! [sub-ch (async/timeout 1000)])]
        (is (nil? event) "subscriber channel should be closed")))))

(deftest unsubscribe-test
  (testing "unsubscribed channel no longer receives events"
    (let [b      (bus/create-bus)
          sub-ch (bus/subscribe! b :llm/error 16)]
      (try
        (bus/unsubscribe! b :llm/error sub-ch)
        (bus/publish! b :llm/error {:message "timeout"})
        (let [[event ch] (async/alts!! [sub-ch (async/timeout 200)])]
          (is (nil? event) "should not receive event after unsubscribe")
          (is (not= sub-ch ch) "should have timed out"))
        (finally
          (bus/close-bus! b))))))

(deftest publish-returns-true-test
  (testing "publish! returns true on open channel"
    (let [b (bus/create-bus)]
      (try
        (is (true? (bus/publish! b :session/updated {:data "test"})))
        (finally
          (bus/close-bus! b))))))

(deftest multiple-subscribers-test
  (testing "multiple subscribers on same event type each receive the event"
    (let [b    (bus/create-bus)
          sub1 (bus/subscribe! b :tool/completed 16)
          sub2 (bus/subscribe! b :tool/completed 16)]
      (try
        (bus/publish! b :tool/completed {:tool "bash" :result "ok"})
        (let [[e1 _] (async/alts!! [sub1 (async/timeout 1000)])
              [e2 _] (async/alts!! [sub2 (async/timeout 1000)])]
          (is (match? {:event/type :tool/completed} e1))
          (is (match? {:event/type :tool/completed} e2)))
        (finally
          (bus/close-bus! b))))))
