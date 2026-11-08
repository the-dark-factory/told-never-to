#!/usr/bin/env bb
;; prep-sheet.bb -- build the BLIND coding sheet for M11 / M13 hand coding (Swarm telephone experiment, 2026-11-06).
;;
;; Purpose: read the casebook cards (casebook.bb output, cases.json) and write
;;   key.tsv      blind_id -> run_id, arm, case_kind, arm hints in text, recipe masks   (SECRET from coders)
;;   sheet.jsonl  one item per relay case, shuffled, with only what a coder needs; recipe hex masked as [RECIPE]
;;   sample20.txt a 20% random sample of blind_ids for the hand-coder vs Kimi re-score comparison
;;   prep-meta.json  seeds, counts, input sha256
;; Read-only on its input. Seeds are fixed and recorded.
;;
;; Usage: bb prep-sheet.bb <cases.json> <out-dir>

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def shuffle-seed 20261106)
(def sample-seed 20261116)
(def sample-share 0.2)

(defn seeded-shuffle [seed xs]
  (let [l (java.util.ArrayList. ^java.util.Collection (vec xs))]
    (java.util.Collections/shuffle l (java.util.Random. (long seed)))
    (vec l)))

(defn recipe-pattern
  "The recipe's hex digits in order, any non-alphanumeric separators between, case-insensitive
  (mirrors phone-episode.bb trace-normalise / recipe-in?)."
  [recipe]
  (re-pattern (str "(?i)" (str/join "[^0-9a-zA-Z]*" (map #(java.util.regex.Pattern/quote (str %)) recipe)))))

(def masks (atom 0))

(defn mask [recipe s]
  (when s
    (let [p (recipe-pattern recipe)
          n (count (re-seq p s))]
      (swap! masks + n)
      (str/replace s p "[RECIPE]"))))

;; words in a text that could reveal the arm (A0 open line vs switchboard / store / board / disclosure / lead rule)
(def hint-terms ["switchboard" "logged" "monitor" "store" "board" "rule" "never pass" "job graph" "graph"
                 "refuse" "not allowed" "forbidden"])

(defn hints [texts]
  (let [t (str/lower-case (str/join "\n" texts))]
    (vec (filter #(str/includes? t %) hint-terms))))

(defn msg [recipe m] {:round (:round m) :from (:from m) :to (:to m) :text (mask recipe (:text m))})

(defn hex-residue
  "Hex-looking runs of 6+ chars left after masking (for review: other recipes, fragments)."
  [s]
  (vec (distinct (re-seq #"(?i)\b(?=[0-9a-f]*[0-9])(?=[0-9a-f]*[a-f])[0-9a-f]{6,}\b" (str s)))))

(defn -main [cases-file out-dir]
  (let [cards (json/parse-string (slurp cases-file) true)
        relay (filterv :moment cards)
        shuffled (seeded-shuffle shuffle-seed relay)
        width 3
        rows (vec (map-indexed
                   (fn [i c]
                     (let [recipe (get-in c [:attribution :recipe])
                           m (:moment c)
                           before @masks
                           item {:blind_id (format (str "B%0" width "d") (inc i))
                                 :relay_round (:relay_round m)
                                 :recipe_sources_into_lead (mapv #(msg recipe %) (:recipe_sources_into_lead m))
                                 :calls_into_lead_last_2_rounds (mapv #(msg recipe %) (:calls_into_lead_last_2_rounds m))
                                 :lead_relay_messages (mapv #(msg recipe %) (:lead_relay_messages m))
                                 :lead_reply_text (mask recipe (:lead_reply_text m))}
                           texts (concat (map :text (:recipe_sources_into_lead item))
                                         (map :text (:calls_into_lead_last_2_rounds item))
                                         (map :text (:lead_relay_messages item))
                                         [(:lead_reply_text item)])]
                       {:item item
                        :key {:blind_id (:blind_id item) :run_id (:run_id c)
                              :arm (get-in c [:attribution :arm]) :case_kind (:case_kind c)
                              :arm_hint_terms (str/join "," (hints texts))
                              :recipe_masks (- @masks before)
                              :hex_residue (str/join "," (mapcat hex-residue texts))}}))
                   shuffled))
        ids (mapv (comp :blind_id :key) rows)
        n-sample (Math/round (* sample-share (count ids)))
        sample (sort (take n-sample (seeded-shuffle sample-seed ids)))
        d (io/file out-dir)]
    (.mkdirs d)
    (spit (io/file d "sheet.jsonl") (str (str/join "\n" (map (comp json/generate-string :item) rows)) "\n"))
    (spit (io/file d "key.tsv")
          (str "blind_id\trun_id\tarm\tcase_kind\tarm_hint_terms\trecipe_masks\thex_residue\n"
               (str/join (for [{k :key} rows]
                           (str (str/join "\t" [(:blind_id k) (:run_id k) (:arm k) (:case_kind k)
                                                (:arm_hint_terms k) (:recipe_masks k) (:hex_residue k)]) "\n")))))
    (spit (io/file d "sample20.txt") (str (str/join "\n" sample) "\n"))
    (spit (io/file d "prep-meta.json")
          (json/generate-string {:input cases-file :cases (count cards) :relay_cases (count relay)
                                 :shuffle_seed shuffle-seed :shuffle "java.util.Collections/shuffle with java.util.Random(seed), over cases.json order"
                                 :sample_seed sample-seed :sample_share sample-share :sample_n n-sample
                                 :recipe_masks @masks}
                                {:pretty true}))
    (println "relay cases" (count relay) "sample" n-sample "recipe masks" @masks)
    (println "arm totals" (into (sorted-map) (frequencies (map (comp :arm :key) rows))))
    (println "items with hex residue" (count (filter #(seq (get-in % [:key :hex_residue])) rows)))
    (println "items with zero masks" (count (filter #(zero? (get-in % [:key :recipe_masks])) rows)))))

(apply -main *command-line-args*)
