#!/usr/bin/env bb
;; stub-switchboard.bb -- TEST DOUBLE of the switchboard edge. NOT PROVED. NOT the forged binary.
;;
;; Purpose: lets the telephone harness and its tests run before the forged edge
;; (edges/switchboard/, core Swarm_Switchboard_Pkg) exists. It implements the SAME
;; interface, so the harness swaps to the real binary by setting SWARM_SWITCHBOARD_BIN, with no other change.
;; The forged edge now exists (2026-11-05) with a different argv/stdout framing; switchboard-client.bb adapts to it.
;; switchboard-client.bb uses this double ONLY when SWARM_SWITCHBOARD_BIN is unset AND the run is labelled test,
;; and prints a loud warning when it does. Every episode record carries switchboard_double = true when it was used.
;;
;; Interface (from the build brief, 2026-11-04):
;;   bb stub-switchboard.bb <graph-file>        calls on stdin, records on stdout
;;   graph file: first line `agents N cap K` (1 <= N <= 32, K >= 1), then lines `caller callee`.
;;     A self-edge, an out-of-range value or a malformed line is a fault: exit 2, reason on stderr, nothing on stdout.
;;   stdin lines:  `round caller callee` (round non-decreasing)
;;   stdout lines: `round caller callee ADMIT` | `... REFUSE not-on-graph` | `... REFUSE edge-full` | `... FAULT <reason>`
;;   Counts reset when the round increases. Nothing about a call but (round, caller, callee) is ever read.

(ns stub-switchboard
  (:require [clojure.string :as str]))

(defn- die [msg]
  (binding [*out* *err*] (println "stub-switchboard (TEST DOUBLE):" msg))
  (System/exit 2))

(defn- parse-int [s] (when (and s (re-matches #"-?\d+" s)) (parse-long s)))

(defn- emit [& xs] (println (str/join " " xs)) (flush))

(defn- load-graph [path]
  (let [f (java.io.File. ^String path)
        _ (when-not (.isFile f) (die (str "no graph file: " path)))
        lines (remove str/blank? (map str/trim (str/split-lines (slurp f))))
        [hdr & edges] lines
        m (re-matches #"agents\s+(\d+)\s+cap\s+(\d+)" (or hdr ""))
        _ (when-not m (die "first line must be: agents N cap K"))
        n (parse-long (m 1)) k (parse-long (m 2))]
    (when-not (<= 1 n 32) (die (str "agents out of range 1..32: " n)))
    (when-not (>= k 1) (die (str "cap must be at least 1: " k)))
    {:n n :k k
     :graph (reduce (fn [g l]
                      (let [[a b & more] (str/split l #"\s+")
                            a (parse-int a) b (parse-int b)]
                        (cond (or (nil? a) (nil? b) (seq more)) (die (str "malformed edge line: " l))
                              (not (and (<= 1 a n) (<= 1 b n))) (die (str "edge out of range: " l))
                              (= a b) (die (str "self-edge: " l))
                              :else (conj g [a b]))))
                    #{} edges)}))

(let [[gf] *command-line-args*
      _ (when-not gf (die "usage: stub-switchboard.bb <graph-file>"))
      {:keys [n k graph]} (load-graph gf)]
  (loop [round nil counts {}]
    (when-let [l (read-line)]
      (let [l (str/trim l)]
        (if (str/blank? l)
          (recur round counts)
          (let [[rs cs ds & more] (str/split l #"\s+")
                r (parse-int rs) c (parse-int cs) d (parse-int ds)]
            (cond
              (or (nil? r) (nil? c) (nil? d) (seq more)) (do (emit l "FAULT malformed-call") (recur round counts))
              ;; fault words as the forged edge's (2026-11-05); the edge checks range before round (probe F8b)
              (not (and (<= 1 c n) (<= 1 d n))) (do (emit r c d "FAULT out-of-range") (recur round counts))
              (and round (< r round)) (do (emit r c d "FAULT decreasing-round") (recur round counts))
              :else
              (let [counts (if (= r round) counts {})
                    cnt (get counts [c d] 0)]
                (cond
                  (not (graph [c d])) (do (emit r c d "REFUSE not-on-graph") (recur r counts))
                  (>= cnt k) (do (emit r c d "REFUSE edge-full") (recur r counts))
                  :else (do (emit r c d "ADMIT") (recur r (assoc counts [c d] (inc cnt)))))))))))))
