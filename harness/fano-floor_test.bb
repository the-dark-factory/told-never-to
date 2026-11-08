#!/usr/bin/env bb
;; fano-floor_test.bb -- table-driven tests for fano-floor.bb. Run: bb fano-floor_test.bb
;;
;; Purpose: binary entropy, the clipped binary Fano bound, the Clopper-Pearson one-sided 97.5% lower bound against known
;; values (the lower ends of standard two-sided 95% exact intervals), and the per-position sum on toy attempts.

(ns swarm.fano-floor-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing run-tests]]))

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(load-file (str (io/file here "fano-floor.bb")))
(require '[swarm.fano-floor :as ff])

(defn- close? [tol a b] (< (Math/abs (- (double a) (double b))) tol))

(deftest binary-entropy-known-values
  (doseq [[p want] [[0.0 0.0] [1.0 0.0] [0.5 1.0] [0.25 0.8112781245] [0.75 0.8112781245]
                    [0.11 0.4999162] [0.01 0.0807931358]]]
    (testing (str "h(" p ")") (is (close? 1e-6 want (ff/binary-entropy p))))))

(deftest fano-bit-known-values
  (doseq [[p want why] [[1.0 1.0 "always right: 1 bit"]
                        [0.5 0.0 "chance: 0"]
                        [0.75 0.1887218755 "1 - h(0.25)"]
                        [0.89 0.5000838 "1 - h(0.11)"]
                        [0.25 0.0 "below chance: clipped to 0 (stated choice)"]
                        [0.0 0.0 "always wrong: clipped to 0 (stated choice)"]]]
    (testing why (is (close? 1e-6 want (ff/fano-bit p))))))

(deftest cp-lower-known-values
  ;; lower ends of two-sided 95% Clopper-Pearson intervals (= one-sided 97.5% lower bounds)
  (doseq [[k n want] [[0 10 0.0] [1 1 0.025] [10 10 0.6915029] [5 10 0.1870860] [10 20 0.2719578]
                      [1 10 0.0025286] [84 84 0.9570351] [9 10 0.5549839]]]
    (testing (str k "/" n) (is (close? 1e-5 want (ff/cp-lower k n))))))

(deftest binom-upper-tail-values
  (doseq [[k n p want] [[0 5 0.3 1.0] [6 5 0.3 0.0] [5 5 0.5 0.03125] [1 2 0.5 0.75]]]
    (is (close? 1e-12 want (ff/binom-upper-tail k n p)))))

(deftest fano-sum-toy
  (let [s [[1 0] [0 1] [1 1] [0 0]]]
    (doseq [[name vs want] [["copied exactly: 2 bits" s 2.0]
                            ["position 0 right, position 1 inverted: 1 bit (inverted position clipped)"
                             [[1 1] [0 0] [1 0] [0 1]] 1.0]
                            ["3 of 4 right on position 0 only" [[1 1] [0 0] [0 0] [0 1]] 0.1887218755]]]
      (testing name (is (close? 1e-6 want (ff/fano-sum s vs 2)))))
    (testing "CP version at n = 4, all right: 0.025^(1/4) = 0.398 < 0.5, so 0"
      (is (close? 1e-9 0.0 (ff/fano-sum s s 2 true))))))

(deftest attempts-match-phone-tables
  (let [recs [{:R 2 :recipe_bits [1 0] :receiver_repro_bits [[1 0] nil]}
              {:R 2 :recipe_bits [0 1] :receiver_repro_bits [[1 1]]}]
        a (ff/attempts-of recs)]
    (is (= 3 (count a)))
    (is (= [[1 0] [0 0] [1 1]] (mapv :v a)) "nil reproduction = all-zero, as phone-tables.bb m1-row")
    (is (= [true false true] (mapv :submitted a)))
    (is (= [0 0 1] (mapv :ep a)))))

(let [{:keys [fail error]} (run-tests 'swarm.fano-floor-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
