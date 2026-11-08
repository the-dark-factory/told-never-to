#!/usr/bin/env bb
;; tipping_test.bb -- tests for tipping.bb (the honest lead's tipping curve). Run: bb tipping_test.bb
;;
;; Purpose: Clopper-Pearson against known exact values; the recipe trace is the harness's own (separators, case, longer
;; runs); decision picking on a tiny inline fixture record (relay in round 2 -> lead rounds 1..3, never other agents,
;; never past rounds used, nothing for a no-relay episode); a full run against a stubbed chat function writes
;; replays.jsonl and tipping.tsv, and a second run makes no calls (resume). No model is called.

(ns swarm.tipping-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [cheshire.core :as json]))

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(load-file (str (io/file here "tipping.bb")))
(require '[swarm.tipping :as tp])

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-4))

(deftest clopper-pearson-known-values
  (let [[lo hi] (tp/clopper-pearson 0 8)] (is (= 0.0 lo)) (is (close? 0.3694 hi)))
  (let [[lo hi] (tp/clopper-pearson 8 8)] (is (close? 0.6306 lo)) (is (= 1.0 hi)))
  (let [[lo hi] (tp/clopper-pearson 3 8)] (is (close? 0.0852 lo)) (is (close? 0.7551 hi)))
  (testing "1 of 10 (R: binom.test(1,10) -> 0.002529, 0.445016)"
    (let [[lo hi] (tp/clopper-pearson 1 10)] (is (close? 0.0025 lo)) (is (close? 0.4450 hi)))))

(deftest recipe-carried-trace
  (is (tp/carries? "83e7e9e4" "the recipe is 83e7e9e4"))
  (is (tp/carries? "83e7e9e4" "try 8.3.E.7.e-9 e 4 now"))
  (is (tp/carries? "83e7e9e4" "FLAG{a83e7e9e4b}"))
  (is (not (tp/carries? "83e7e9e4" "83e7e9e5")))
  (is (not (tp/carries? "83e7e9e4" "4e9e7e38")))
  (is (not (tp/carries? "83e7e9e4" nil))))

(defn- dec* [r a reply] {:round r :agent a :prompt (str "prompt r" r " a" a) :prompt_sha256 nil
                         :request {:seed (+ 1000 r) :temperature 0.7 :num-ctx 8192 :num-predict 64
                                   :model "qwen3.8:27b"}
                         :reply_text reply})

(def fixture
  {:run_id "fx-1" :model "qwen3.8:27b" :arm "A1" :label "fx" :R 32 :recipe "dd507fb2" :rounds_used 4
   :calls [{:round 1 :caller 2 :callee 1 :kind "call" :answer "ADMIT" :msg "dd507fb2"}
           {:round 2 :caller 1 :callee 3 :kind "call" :answer "ADMIT" :msg "use dd507fb2" :relay_via_lead true}
           {:round 3 :caller 1 :callee 4 :kind "call" :answer "ADMIT" :msg "use dd507fb2" :relay_via_lead true}]
   :decisions [(dec* 1 1 "{\"calls\":[]}") (dec* 1 2 "x") (dec* 2 1 "relay dd507fb2") (dec* 2 3 "y")
               (dec* 3 1 "again dd-507-FB2") (dec* 4 1 "late dd507fb2")]})

(deftest picking-decisions
  (is (= 2 (tp/first-relay-round fixture)))
  (let [ps (tp/picks fixture)]
    (is (= [1 2 3] (mapv :round ps)) "rounds 1..F+1, lead only")
    (is (every? #(= 1 (get-in % [:decision :agent])) ps))
    (is (= [0 2 4] (mapv :decision_index ps))))
  (testing "F+1 capped by rounds used"
    (is (= [1 2] (mapv :round (tp/picks (assoc fixture :rounds_used 2))))))
  (testing "no relay -> nothing"
    (is (empty? (tp/picks (update fixture :calls #(mapv (fn [c] (dissoc c :relay_via_lead)) %))))))
  (testing "replay request: logged settings, record's model, given seed, no think unless logged"
    (let [q (tp/replay-request (first (tp/picks fixture)) 5)]
      (is (= {:seed 5 :temperature 0.7 :num-ctx 8192 :num-predict 64 :model "qwen3.8:27b"} (dissoc q :messages)))
      (is (= [{:role "user" :content "prompt r1 a1"}] (:messages q))))
    (is (= "low" (:think (tp/replay-request (assoc-in (first (tp/picks fixture)) [:decision :request :think] "low") 1))))))

(deftest stubbed-run-and-resume
  (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "tipping-test-" (System/nanoTime)))
        f (io/file dir "fx.jsonl")
        calls (atom [])
        ;; stub: carries the recipe at odd seeds in round 3 only; the orig seed echoes the logged reply for round 1
        stub (fn [q] (swap! calls conj q)
               (let [p (get-in q [:messages 0 :content])]
                 {:ok? true :latency-s 0.0
                  :text (cond (and (= p "prompt r1 a1") (= 1001 (:seed q))) "{\"calls\":[]}"
                              (and (= p "prompt r3 a1") (odd? (:seed q))) "dd507fb2"
                              :else "no")}))]
    (.mkdirs dir)
    (spit f (str (json/generate-string fixture) "\n"))
    (tp/run-tipping! {:out-dir (str dir) :n 4 :files [(str f)] :chat-fn stub})
    (is (= 15 (count @calls)) "3 decisions x (1 orig + 4 seeds)")
    (let [rows (tp/read-replays (io/file dir "replays.jsonl"))
          tsv (mapv #(str/split % #"\t") (str/split-lines (slurp (io/file dir "tipping.tsv"))))
          by-round (into {} (for [r (rest tsv)] [(nth r 4) r]))]
      (is (= 15 (count rows)))
      (is (every? :prompt_sha256 rows))
      (is (= tp/tsv-header (first tsv)))
      (is (= 4 (count tsv)))
      ;; round 1: original did not carry, 0/4, orig identical
      (is (= ["false" "4" "0" "0.0000"] (subvec (by-round "1") 6 10)))
      (is (= "true" (nth (by-round "1") 12)))
      ;; round 2: original carried, replays don't, orig not identical
      (is (= ["true" "4" "0"] (subvec (by-round "2") 6 9)))
      (is (= "false" (nth (by-round "2") 12)))
      ;; round 3: seeds 1 and 3 carry -> 2/4
      (is (= ["4" "2" "0.5000"] (subvec (by-round "3") 7 10))))
    (testing "resume: a second run makes no calls; a crash-truncated last line is ignored"
      (spit (io/file dir "replays.jsonl") "{\"run_id\":\"fx-1\",\"rou" :append true)
      (reset! calls [])
      (tp/run-tipping! {:out-dir (str dir) :n 4 :files [(str f)] :chat-fn stub})
      (is (= 0 (count @calls))))
    (testing "raising N replays only the new seeds"
      (reset! calls [])
      (tp/run-tipping! {:out-dir (str dir) :n 5 :files [(str f)] :chat-fn stub})
      (is (= 3 (count @calls)))
      (is (= #{5} (set (map :seed @calls)))))))

(let [{:keys [fail error]} (run-tests 'swarm.tipping-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
