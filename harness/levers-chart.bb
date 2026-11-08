#!/usr/bin/env bb
;; levers-chart.bb -- Stage 1 levers: lead relays and cheats per (model, setting, arm), as a TSV and an SVG chart.
;;
;; Purpose: read the Stage 1 lever records (results/levers/lv-<model>.jsonl: 7 settings x 3 episodes x arms A1, L;
;; seed 20261108) and count, per (model, setting, arm):
;;   relays                M13 as phone-tables.bb defines it (swarm.phone-tables/lead-relay, loaded read-only): the
;;                         episode has an ADMITTED message from the lead (agent 1) -- call OR noticeboard write -- carrying
;;                         the recipe, to anyone but the finder.
;;   relays_noticeboard    of those, episodes whose first such message is a board write.
;;   relays_call_via_lead  the record's own per-call :relay_via_lead flag (phone-episode.bb, red team D19): an admitted
;;                         CALL from the lead carrying the recipe AFTER the recipe reached the lead from someone other
;;                         than the callee. Calls only. This is the flag casebook.bb uses.
;;   cheats                episodes with >= 1 cheat success (:cheat :successes > 0), as phone-tables.bb M5.
;;   cheat_successes       the summed successes.
;; The setting is the record's :label with the "lv-<model>-" prefix removed (base = D 8, R 32, share 0.5, prompt p2;
;; D6 / D12 = rounds; R8 = recipe bits; share75 = share 0.75; p0 / p1 = prompt wording dirs), checked against the
;; record's own :D :R :share :prompt_dir fields (the script stops on a mismatch).
;; Rows with setting "all" and arm "A1+L" are per-model totals. The chart is drawn twice, once per relay definition.
;;
;; Usage: bb levers-chart.bb [levers-dir=results/levers]
;;   writes <dir>/T_levers_by_setting.tsv, <dir>/levers_chart.svg (relays, phone-tables M13) and
;;   <dir>/levers_chart_relay_via_lead.svg (record flag). lv-gptoss20.jsonl (void run) is never read; gpt-oss is lv-gptoss20t.

(ns swarm.levers-chart
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]))

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(when-not (find-ns 'swarm.phone-tables) (load-file (str (io/file here "phone-tables.bb"))))
(require '[swarm.phone-tables :as pt])

(def models ["gemma4" "gptoss20t" "mistral24" "qwen38" "granite4" "glm47" "qwen36"])
(def settings ["base" "D6" "D12" "R8" "share75" "p0" "p1"])
(def arms ["A1" "L"])

(def expected-setting
  {"base" {:D 8 :R 32 :share 0.5 :p "p2"} "D6" {:D 6 :R 32 :share 0.5 :p "p2"} "D12" {:D 12 :R 32 :share 0.5 :p "p2"}
   "R8" {:D 8 :R 8 :share 0.5 :p "p2"} "share75" {:D 8 :R 32 :share 0.75 :p "p2"}
   "p0" {:D 8 :R 32 :share 0.5 :p "p0"} "p1" {:D 8 :R 32 :share 0.5 :p "p1"}})

(defn read-jsonl [f]
  (vec (for [l (str/split-lines (slurp f)) :when (not (str/blank? l))] (json/parse-string l true))))

(defn setting-of
  "Record -> setting name, from :label; throws when the record's own fields disagree with the name."
  [m rec]
  (let [s (str/replace-first (:label rec) (str "lv-" m "-") "")
        e (expected-setting s)
        got {:D (:D rec) :R (:R rec) :share (double (:share rec)) :p (.getName (io/file (str (:prompt_dir rec))))}]
    (when-not (and e (= (update e :share double) got))
      (throw (ex-info "setting label disagrees with record fields" {:run_id (:run_id rec) :setting s :fields got})))
    s))

(defn counts [es]
  (let [rel (filter pt/lead-relay es)]
    {:n (count es)
     :relays (count rel)
     :relays_noticeboard (count (filter #(= "board-write" (:kind (pt/lead-relay %))) rel))
     :relays_call_via_lead (count (filter #(some :relay_via_lead (:calls %)) es))
     :cheats (count (filter #(pos? (or (get-in % [:cheat :successes]) 0)) es))
     :cheat_successes (reduce + 0 (map #(or (get-in % [:cheat :successes]) 0) es))}))

;; ---------------------------------------------------------------- SVG

(def blue "#1f77b4") (def orange "#ff7f0e")

(defn- esc [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- text [x y s & {:keys [size anchor weight fill rotate] :or {size 11 anchor "start" weight "normal" fill "#222"}}]
  (format "<text x=\"%.1f\" y=\"%.1f\" font-size=\"%s\" text-anchor=\"%s\" font-weight=\"%s\" fill=\"%s\"%s>%s</text>"
          (double x) (double y) size anchor weight fill
          (if rotate (format " transform=\"rotate(%s %.1f %.1f)\"" rotate (double x) (double y)) "") (esc s)))

(defn- rect [x y w h fill]
  (format "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"%s\"/>" (double x) (double y) (double w) (double h) fill))

(defn- line [x1 y1 x2 y2 stroke & [width]]
  (format "<line x1=\"%.1f\" y1=\"%.1f\" x2=\"%.1f\" y2=\"%.1f\" stroke=\"%s\" stroke-width=\"%s\"/>"
          (double x1) (double y1) (double x2) (double y2) stroke (or width 1)))

(defn panel
  "One model's small multiple at (ox, oy): stacked bars, A1 below L, out of 6 episodes per setting."
  [ox oy {:keys [title subtitle cells]} rkey]
  (let [pw 270 ph 150 left 34 top 46 iw (- pw left 10) ih ph
        ymax 6 sy (fn [v] (+ oy top (- ih (* ih (/ v (double ymax))))))
        slot (/ iw (count settings)) bw (* slot 0.62)]
    (str/join "\n"
              (concat
               [(text ox (+ oy 14) title :size 13 :weight "bold")
                (text ox (+ oy 27) subtitle :size 10 :fill "#555")]
               (for [v (range 0 (inc ymax) 2)]
                 (str (line (+ ox left) (sy v) (+ ox left iw) (sy v) (if (zero? v) "#444" "#ddd"))
                      (text (+ ox left -5) (+ (sy v) 3.5) v :size 10 :anchor "end" :fill "#444")))
               (for [[j s] (map-indexed vector settings)
                     :let [a1 (get-in cells [[s "A1"] rkey] 0) l (get-in cells [[s "L"] rkey] 0)
                           x (+ ox left (* j slot) (/ (- slot bw) 2))]]
                 (str (when (pos? a1) (rect x (sy a1) bw (- (sy 0) (sy a1)) blue))
                      (when (pos? l) (rect x (sy (+ a1 l)) bw (- (sy a1) (sy (+ a1 l))) orange))
                      (when (pos? (+ a1 l)) (text (+ x (/ bw 2)) (- (sy (+ a1 l)) 3) (+ a1 l) :size 9 :anchor "middle" :fill "#333"))
                      (text (+ x (/ bw 2)) (+ (sy 0) 13) s :size 9.5 :anchor "middle" :fill "#333")))
               [(text (+ ox 10) (+ oy top (/ ih 2)) "relays / 6" :size 10 :anchor "middle" :fill "#444" :rotate -90)]))))

(defn chart-svg [panels rkey def-note]
  (let [cols 4 pw 290 ph 230 W (* cols pw) H (+ 62 (* 2 ph) 10)]
    (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" W "\" height=\"" H "\" viewBox=\"0 0 " W " " H "\" "
         "font-family=\"Helvetica, Arial, sans-serif\">\n"
         (rect 0 0 W H "#ffffff") "\n"
         (text 16 26 "Lead relays by setting, local models (exploratory)" :size 17 :weight "bold") "\n"
         (text 16 46 (str "Bars: episodes with a lead relay, of 6 per setting (3 A1 + 3 L); seed 20261108. " def-note) :size 11 :fill "#444") "\n"
         (str/join "\n" (for [[i p] (map-indexed vector panels)]
                          (panel (+ 16 (* (mod i cols) pw)) (+ 62 (* (quot i cols) ph)) p rkey)))
         "\n"
         ;; legend in the 8th cell
         (let [ox (+ 16 (* 3 pw)) oy (+ 62 ph 40)]
           (str (rect ox oy 14 14 blue) (text (+ ox 20) (+ oy 11) "A1 (3 episodes)" :size 11) "\n"
                (rect ox (+ oy 22) 14 14 orange) (text (+ ox 20) (+ oy 33) "L (3 episodes)" :size 11) "\n"
                (text ox (+ oy 60) "x: setting. base = D 8, R 32, share 0.5," :size 10 :fill "#444") "\n"
                (text ox (+ oy 74) "prompt p2; D6/D12 rounds; R8 recipe bits;" :size 10 :fill "#444") "\n"
                (text ox (+ oy 88) "share75 share 0.75; p0/p1 prompt wording." :size 10 :fill "#444") "\n"
                (text ox (+ oy 102) "y: relays out of 6 (A1 + L)." :size 10 :fill "#444") "\n"
                (text ox (+ oy 116) "Exploratory: n = 3 per bar segment." :size 10 :fill "#444")))
         "\n</svg>\n")))

(defn -main [& [dir]]
  (let [dir (io/file (or dir (io/file here "results" "levers")))
        data (into {} (for [m models]
                        (let [es (read-jsonl (io/file dir (str "lv-" m ".jsonl")))]
                          [m {:model (:model (first es))
                              :cells (into {} (for [[k g] (group-by (juxt #(setting-of m %) :arm) es)] [k (counts g)]))
                              :total (counts es)
                              :by-arm (into {} (for [[a g] (group-by :arm es)] [a (counts g)]))}])))
        rows (concat
              (for [m models s settings a arms]
                (merge {:model m :setting s :arm a} (get-in data [m :cells [s a]] {:n 0})))
              (for [m models] (merge {:model m :setting "all" :arm "A1+L"} (get-in data [m :total]))))
        header [:model :setting :arm :n :relays :relays_noticeboard :relays_call_via_lead :cheats :cheat_successes]
        panels-for (fn [rkey]
                     (for [m models :let [{:keys [model total]} (data m)]]
                       {:title (str m "  (" (str/replace model #"^hf\.co/ngxson/|-GGUF:.*$" "") ")")
                        :subtitle (format "%d relays (A1 %d, L %d); %d cheat episodes" (rkey total)
                                          (get-in data [m :by-arm "A1" rkey]) (get-in data [m :by-arm "L" rkey]) (:cheats total))
                        :cells (get-in data [m :cells])}))]
    (pt/write-tsv! (io/file dir "T_levers_by_setting.tsv") header rows)
    (spit (io/file dir "levers_chart.svg")
          (chart-svg (panels-for :relays) :relays "Relay = phone-tables.bb M13 (admitted lead call or board write with the recipe, to a non-finder)."))
    (spit (io/file dir "levers_chart_relay_via_lead.svg")
          (chart-svg (panels-for :relays_call_via_lead) :relays_call_via_lead "Relay = record flag relay_via_lead (admitted lead CALL, recipe in from another agent first)."))
    (doseq [m models :let [t (get-in data [m :total]) b (get-in data [m :by-arm])]]
      (println (format "%-10s n %d  M13 relays %2d (A1 %d, L %d; noticeboard %d)  relay_via_lead %2d (A1 %d, L %d)  cheat eps %2d  successes %d"
                       m (:n t) (:relays t) (get-in b ["A1" :relays]) (get-in b ["L" :relays]) (:relays_noticeboard t)
                       (:relays_call_via_lead t) (get-in b ["A1" :relays_call_via_lead]) (get-in b ["L" :relays_call_via_lead])
                       (:cheats t) (:cheat_successes t))))
    (println "wrote T_levers_by_setting.tsv, levers_chart.svg, levers_chart_relay_via_lead.svg in" (str dir))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
