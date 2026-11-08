#!/usr/bin/env bb
;; layerscan_test.bb -- tests for the maths in layerscan.bb: centring in Gram space, the leave-one-episode-out
;; projection (checked against a brute-force computation on explicit vectors), AUC, ranks and Spearman.
;; Run: bb layerscan_test.bb  (from the harness directory; exits 1 on any failure)

(require '[clojure.test :refer [deftest is testing run-tests]] '[clojure.java.io :as io])
(load-file (str (io/file (-> *file* io/file .getCanonicalFile .getParentFile) "layerscan.bb")))
(require '[swarm.layerscan :as ls])

(def rng (java.util.Random. 7))
(defn rvec [d] (double-array (repeatedly d #(.nextGaussian rng))))
(defn vsub [a b] (double-array (map - a b)))
(defn vmean [vs] (let [n (count vs)] (double-array (apply map (fn [& xs] (/ (reduce + xs) n)) vs))))
(defn close? [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

(deftest centring
  (let [vs (vec (repeatedly 7 #(rvec 5)))
        main [0 1 2 3 4]                       ; dumps 5 and 6 are shifted by the same mean but do not define it
        mu (vmean (map vs main))
        gc (ls/centre-gram (ls/gram vs) main)]
    (doseq [i (range 7) j (range 7)]
      (is (close? (ls/gget gc i j) (ls/dot (vsub (vs i) mu) (vsub (vs j) mu)))))
    (testing "centred rows of main sum to zero"
      (doseq [i (range 7)] (is (close? 0.0 (reduce + (map #(ls/gget gc i %) main))))))))

(defn brute-loeo [vs labels eps]
  (vec (for [j (range (count vs))]
         (let [tr (remove #(= (eps %) (eps j)) (range (count vs)))
               a (filter #(= :a (labels %)) tr) b (filter #(= :b (labels %)) tr)
               d (vsub (vmean (map vs a)) (vmean (map vs b)))
               dn (Math/sqrt (ls/dot d d))]
           (/ (ls/dot (vs j) d) dn)))))

(deftest loeo-projection
  (let [vs (vec (repeatedly 12 #(rvec 6)))
        labels [:a :b :b :a :b :b nil :b :b :b :a nil]
        eps    [ 0  0  0  1  1  1  1  2  2  3  3   4]
        g (ls/gram vs)
        want (brute-loeo vs labels eps)]
    (testing "Gram-space LOEO equals the explicit mean-difference direction"
      (let [got (ls/loeo-proj g labels eps)]
        (doseq [j (range 12)] (is (close? (aget got j) (want j)) (str "dump " j)))))
    (testing "the rlab shortcut (row sums over the labelled set) gives the same answer"
      (let [rlab (ls/row-sums g (filter labels (range 12)))
            got (ls/loeo-proj g labels eps rlab)]
        (doseq [j (range 12)] (is (close? (aget got j) (want j))))))
    (testing "a fold with no A left gives NaN"
      (let [got (ls/loeo-proj (ls/gram (subvec vs 0 4)) [:a :b :b :b] [0 0 1 1] nil)]
        (is (Double/isNaN (aget got 0)))))))

(deftest auc-values
  (is (= 1.0 (ls/auc [3 4] [1 2])))
  (is (= 0.0 (ls/auc [1 2] [3 4])))
  (is (= 0.5 (ls/auc [1 1] [1 1])))
  (is (close? 0.75 (ls/auc [2 4] [1 3])))
  (is (Double/isNaN (ls/auc [] [1]))))

(deftest ranks-and-spearman
  (is (= [1.0 2.5 2.5 4.0] (ls/ranks [10 20 20 30])))
  (is (= [3.0 1.0 2.0] (ls/ranks [5 1 2])))
  (is (close? 1.0 (ls/spearman [1 2 3 4] [10 20 30 40])))
  (is (close? -1.0 (ls/spearman [1 2 3 4] [9 7 5 1])))
  (is (close? 1.0 (ls/spearman [1 2 3 4] [1 4 9 16])))          ; monotone, not linear
  (is (Double/isNaN (ls/spearman [1 2 3] [5 5 5]))))

(deftest small-stats
  (is (= 2.5 (ls/median [4 1 3 2])))
  (is (close? 3.0 (ls/quantile [1 2 3 4 5] 0.5)))
  (is (close? 1.0 (ls/cos-dist [(double-array [1 0]) (double-array [0 1])] 0 1)))
  (is (= "a\n\n\nTHE TELEPHONE. b" (ls/perturb "a\n\nTHE TELEPHONE. b"))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
