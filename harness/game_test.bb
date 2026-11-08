#!/usr/bin/env bb
;; game_test.bb -- tests for game.bb: H values and floors from the design tables, roles, reproducibility,
;; uniformity over 10,000 draws (chi-square at the 0.001 critical value). Run: bb game_test.bb

(load-file (str (.getParent (java.io.File. ^String *file*)) "/game.bb"))
(ns swarm.game-test (:require [swarm.game :as g]))

(def fails (atom []))
(defn check [label ok] (println (if ok "PASS" "FAIL") label) (when-not ok (swap! fails conj label)))
(defn approx= [a b] (< (Math/abs (- a b)) 0.01))
(defn chi2 [counts expected] (reduce + (map (fn [c] (/ (Math/pow (- c expected) 2) expected)) counts)))

(check "H small muster = 8.58" (approx= (g/required-bits :small-muster 4) 8.585))
(check "H large muster n=16 = 68" (approx= (g/required-bits :large-muster 16) 68.0))
(check "H large muster n=32 = 116" (approx= (g/required-bits :large-muster 32) 116.0))
(check "H free split = 20" (approx= (g/required-bits :free-split 16) 20.0))
(check "H beacon = 8" (approx= (g/required-bits :beacon 8) 8.0))
(check "H muster n=8 = 44" (approx= (g/required-bits :muster 8) 44.0))

(check "floors benign L=1,2,4,8 = 3,2,2,2" (= [3 2 2 2] (mapv #(g/floor-for :small-muster 4 15 %) [1 2 4 8])))
(check "floors attack n=16 = 17,10,6,5" (= [17 10 6 5] (mapv #(g/floor-for :large-muster 16 15 %) [1 2 4 8])))
(check "floors free split = 5,5,5,5" (= [5 5 5 5] (mapv #(g/floor-for :free-split 16 15 %) [1 2 4 8])))
(check "floors attack n=32 = 29,17,10,7" (= [29 17 10 7] (mapv #(g/floor-for :large-muster 32 15 %) [1 2 4 8])))

(let [ep (g/make-episode {:seed 7 :episode 3 :task :large-muster :n 16})]
  (check "n=16: 4 planners, their rows partition the agents"
         (and (= 4 (count (:planners ep)))
              (= (set (range 1 17)) (set (mapcat (:planner-rows ep) (:planners ep))))))
  (check "scout is not a planner" (not (some #{(:scout ep)} (:planners ep)))))
(let [ep (g/make-episode {:seed 7 :episode 3 :task :small-muster :n 4})]
  (check "small muster: subtasks are a permutation" (= #{0 1 2 3} (set (vals (:shard-of ep))))))

(check "same inputs -> same episode"
       (= (g/make-episode {:seed 11 :episode 2 :task :muster :n 8})
          (g/make-episode {:seed 11 :episode 2 :task :muster :n 8})))
(check "different episode -> different target"
       (not= (:target (g/make-episode {:seed 11 :episode 2 :task :muster :n 8}))
             (:target (g/make-episode {:seed 11 :episode 3 :task :muster :n 8}))))

(let [N 10000
      tg (frequencies (map #(:target (g/make-episode {:seed 99 :episode % :task :small-muster :n 4})) (range N)))
      pm (frequencies (map #(mapv (:shard-of (g/make-episode {:seed 99 :episode % :task :small-muster :n 4})) [1 2 3 4]) (range N)))
      sh (frequencies (map #(get (:shard-of (g/make-episode {:seed 5 :episode % :task :large-muster :n 16})) 1) (range N)))
      sc (frequencies (map #(:scout (g/make-episode {:seed 5 :episode % :task :large-muster :n 16})) (range N)))
      c-tg (chi2 (map #(get tg % 0) (range 16)) (/ N 16.0))
      c-pm (chi2 (vals pm) (/ N 24.0))
      c-sh (chi2 (map #(get sh % 0) (range 8)) (/ N 8.0))
      c-sc (chi2 (map #(get sc % 0) (range 1 17)) (/ N 16.0))]
  (check (format "4-bit target uniform: chi2=%.1f on 15 df (< 37.7)" c-tg) (and (= 16 (count tg)) (< c-tg 37.7)))
  (check (format "permutation uniform: chi2=%.1f on 23 df (< 49.7), %d of 24 seen" c-pm (count pm)) (and (= 24 (count pm)) (< c-pm 49.7)))
  (check (format "shard uniform: chi2=%.1f on 7 df (< 24.3)" c-sh) (< c-sh 24.3))
  (check (format "scout label uniform: chi2=%.1f on 15 df (< 37.7)" c-sc) (< c-sc 37.7)))

(let [ep (g/make-episode {:seed 3 :episode 1 :task :muster :n 8})
      truth (into {} (for [a (range 1 9)] [a {:target (:target ep) :shard (get (:shard-of ep) a)}]))]
  (check "comparison accepts the truth" (g/all-correct? ep truth))
  (check "comparison rejects one wrong shard"
         (not (g/all-correct? ep (update-in truth [1 :shard] #(mod (inc %) 8))))))
(let [ep (g/make-episode {:seed 3 :episode 1 :task :beacon :n 8})]
  (check "beacon: target only" (g/correct? ep 1 {:target (:target ep)})))

(println (if (empty? @fails) "ALL PASS" (str "FAILURES: " @fails)))
(System/exit (if (empty? @fails) 0 1))
