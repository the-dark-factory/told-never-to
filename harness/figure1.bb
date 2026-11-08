#!/usr/bin/env bb
;; figure1.bb -- Figure 1 of the telephone report: one episode under the lead's order (arm L), drawn from its record.
;;
;; Purpose: read ONE phone episode record and draw, as a single SVG (160 mm wide), what the lead said under the order
;; (left: its first relay message, verbatim with the recipe masked, and the rule it was given) and what the proved
;; switchboard recorded (right: the five agents, the permitted job graph, the route actually used, the cheat success,
;; the routes the proof closes, and the scratch store that sits outside the proof). A bottom strip gives the arm-level
;; numbers, computed from every record in the same file with the same label, and the casebook's unexplained count.
;;
;; Every fact in the figure is read from the record (agent roles, finder, impossible workers, relay rounds, cheat
;; successes, the rule text in the lead's prompt, store and board use) or from files beside it (the blind episode id in
;; coding/key.tsv, the unexplained count in coding-unblinded-casebook/cases.md). Nothing about the episode is typed in.
;; The recipe never reaches the figure: it is replaced by [RECIPE] and the SVG is checked for it before it is written.
;; The layout is drawn for this episode's shape (one lead, one finder, two impossible workers, one possible worker) and
;; the script stops with an error on any other shape rather than draw a wrong picture.
;;
;; Usage: bb figure1.bb [records.jsonl] [run_id] [out.svg]
;;   defaults: results/confirmatory/phone-confirmatory.jsonl  phone-confirmatory-L-s20261107-e20
;;             results/figures/figure1.svg
;; Convert (not done here): rsvg-convert -f pdf -o figure1.pdf figure1.svg; rsvg-convert -d 200 -p 200 -o figure1.png figure1.svg

(ns swarm.figure1
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]))

(def here (-> *file* io/file .getCanonicalFile .getParentFile))

(defn fail [& xs] (throw (ex-info (apply str xs) {})))

;; ---------------------------------------------------------------- reading

(defn read-records [path]
  (with-open [r (io/reader path)]
    (->> (line-seq r) (remove str/blank?) (mapv #(json/parse-string % true)))))

(defn blind-id [jsonl run-id]
  (let [f (io/file (.getParentFile (io/file jsonl)) "coding" "key.tsv")]
    (when-not (.exists f) (fail "no blind key at " f))
    (or (some (fn [line] (let [[b r] (str/split line #"\t")] (when (= r run-id) b)))
              (str/split-lines (slurp f)))
        (fail "run " run-id " not in " f))))

(defn casebook-unexplained [jsonl]
  (let [f (io/file (.getParentFile (io/file jsonl)) "coding-unblinded-casebook" "cases.md")
        m (re-find #"Episodes read: (\d+);.*UNEXPLAINED cheat successes: (\d+)" (slurp f))]
    (when-not m (fail "no UNEXPLAINED line in " f))
    {:episodes (parse-long (nth m 1)) :unexplained (parse-long (nth m 2))}))

(defn agent-id [k] (parse-long (name k)))

(defn rounds-label [rs]
  (let [rs (vec (sort (distinct rs)))]
    (cond (= 1 (count rs)) (str "round " (first rs))
          (= rs (vec (range (first rs) (inc (peek rs))))) (str "rounds " (first rs) "–" (peek rs))
          :else (str "rounds " (str/join ", " rs)))))

(defn facts [records rec jsonl]
  (let [recipe (:recipe rec)
        mask #(str/replace % recipe "[RECIPE]")
        roles (into {} (map (fn [[k v]] [(agent-id k) (:role v)])) (:per_agent rec))
        lead (or (some (fn [[a r]] (when (= r "lead") a)) roles) (fail "no lead in per_agent"))
        finder (:finder rec)
        impossible (vec (sort (:impossible rec)))
        workers (sort (map :worker (:puzzles rec)))
        possible (vec (remove (set (conj impossible finder)) workers))
        calls (:calls rec)
        recipe-in (->> calls (filter #(and (= finder (:caller %)) (= lead (:callee %)) (:recipe_in_msg %))) (map :round))
        relays (->> calls (filter #(and (= lead (:caller %)) (:relay_via_lead %))))
        relay-rounds (into (sorted-map) (map (fn [[c cs]] [c (mapv :round cs)])) (group-by :callee relays))
        first-relay (first (sort-by :round relays))   ; sort-by is stable: record order within a round
        cheats (filterv :cheat (:submissions rec))
        rule (->> (:decisions rec) (filter #(= lead (:agent %))) (keep #(re-find #"Rule for the lead:[^\n]*" (:prompt %)))
                  distinct)
        same-label (filter #(= (:label rec) (:label %)) records)
        relayed? #(pos? (get-in % [:traces :relay_via_lead] 0))
        arm-n (fn [arm] (let [xs (filter #(= arm (:arm %)) same-label)] [(count (filter relayed? xs)) (count xs)]))
        cheat-eps (count (filter #(pos? (get-in % [:cheat :successes] 0)) same-label))
        cb (casebook-unexplained jsonl)]
    (when-not (= 1 (count rule)) (fail "expected one rule text in the lead's prompts, got " (vec rule)))
    (when-not (and (= 2 (count impossible)) (= 1 (count possible)) finder)
      (fail "layout is drawn for 1 finder, 2 impossible, 1 possible worker; record has finder " finder
            ", impossible " impossible ", possible " possible))
    (when-not (= (count same-label) (:episodes cb))
      (fail "casebook read " (:episodes cb) " episodes, file has " (count same-label) " with label " (:label rec)))
    {:run-id (:run_id rec) :arm (:arm rec) :blind (blind-id jsonl (:run_id rec))
     :roles roles :lead lead :finder finder :impossible impossible :possible possible
     :recipe recipe
     :recipe-in-round (or (first (sort recipe-in)) (fail "finder never sent the recipe to the lead"))
     :relay-rounds relay-rounds
     :first-relay {:to (:callee first-relay) :round (:round first-relay) :msg (mask (:msg first-relay))}
     :other-first-relays (->> relays (filter #(and (= (:round first-relay) (:round %)) (not= (:callee first-relay) (:callee %))))
                              (mapv #(select-keys % [:callee :round])))
     :cheats (mapv #(select-keys % [:agent :round]) cheats)   ; :value holds the flag, so the recipe: left out
     :rule (first rule)
     :off-graph-refused (get-in rec [:traces :refused_not_on_graph])
     :board-writes (get-in rec [:traces :board_writes]) :board-worker-admitted (get-in rec [:traces :board_worker_writes_admitted])
     :store-ops (count (:store_ops rec))
     :summary {:order (arm-n (:arm rec)) :no-order (arm-n "A1") :cheat-episodes cheat-eps :unexplained (:unexplained cb)}}))

;; ---------------------------------------------------------------- drawing (user units are millimetres)

(def blue "#0072B2") (def verm "#D55E00") (def grey "#999999") (def ink "#1A1A1A") (def mid "#555555")
(def body 3.2)   ; 3.2 mm = 9.07 pt, the smallest text in the figure

;; Helvetica advance widths in em, for wrapping and the bounds check (approximate; unlisted characters get 0.56).
(def em
  (merge (zipmap "abcdeghknopqsuvxyz" (repeat 0.556))
         {\f 0.278 \i 0.222 \j 0.222 \l 0.222 \m 0.833 \r 0.333 \t 0.278 \w 0.722 \s 0.5 \c 0.5 \k 0.5 \v 0.5 \x 0.5
          \y 0.5 \z 0.5 \space 0.278 \. 0.278 \, 0.278 \: 0.278 \; 0.278 \( 0.333 \) 0.333 \[ 0.278 \] 0.278
          \{ 0.334 \} 0.334 \- 0.333 (char 0x2013) 0.556 (char 0x2014) 1.0 (char 0x201C) 0.333 (char 0x201D) 0.333 \' 0.191 \" 0.355
          \I 0.278 \M 0.833 \W 0.944 \E 0.667 \F 0.611 \L 0.556 \T 0.611 \J 0.5 \O 0.778 \Q 0.778 \G 0.778 \C 0.722
          \D 0.722 \H 0.722 \N 0.722 \R 0.722 \U 0.722 \A 0.667 \B 0.667 \K 0.667 \P 0.667 \S 0.667 \V 0.667
          \X 0.667 \Y 0.667 \Z 0.611}
        (zipmap "0123456789" (repeat 0.556))))

;; 1.1: the table runs about a tenth narrow against what rsvg-convert renders on this Mac (measured on the PNG).
(defn text-width [s size bold?] (* 1.1 size (if bold? 1.06 1.0) (reduce + (map #(get em % 0.56) s))))

(defn title-lines [s size width]   ; a title too long for one line breaks before its parenthesis
  (let [i (str/index-of s " (")]
    (if (or (nil? i) (<= (text-width s size true) width)) [s] [(subs s 0 i) (subs s (inc i))])))

(defn wrap [s size width bold?]
  (reduce (fn [lines w]
            (let [cand (if (empty? lines) w (str (peek lines) " " w))]
              (if (or (empty? lines) (> (text-width cand size bold?) width)) (conj lines w) (conj (pop lines) cand))))
          [] (str/split s #" ")))

(defn esc [s] (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(def placed (atom []))   ; every text's estimated box, checked against the canvas at the end

(defn text [x y s & {:keys [size anchor bold italic fill halo] :or {size body anchor "start" fill ink}}]
  (let [w (text-width s size bold) x0 (case anchor "start" x "middle" (- x (/ w 2)) "end" (- x w))]
    (swap! placed conj {:s s :x0 x0 :x1 (+ x0 w) :y0 (- y (* 0.75 size)) :y1 (+ y (* 0.22 size))})
    (format "<text x=\"%.2f\" y=\"%.2f\" font-size=\"%.2f\" text-anchor=\"%s\" fill=\"%s\"%s%s%s>%s</text>"
            (double x) (double y) (double size) anchor fill (if bold " font-weight=\"bold\"" "")
            (if italic " font-style=\"italic\"" "")
            (if halo " stroke=\"#FFFFFF\" stroke-width=\"0.9\" stroke-linejoin=\"round\" paint-order=\"stroke\"" "")
            (esc s))))

(defn lines [x y lead-mm ss & opts] (str/join "\n" (map-indexed (fn [i s] (apply text x (+ y (* i lead-mm)) s opts)) ss)))

(defn line [[x1 y1] [x2 y2] & {:keys [stroke width dash marker] :or {stroke grey width 0.3}}]
  (format "<line x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\" stroke=\"%s\" stroke-width=\"%.2f\"%s%s/>"
          (double x1) (double y1) (double x2) (double y2) stroke (double width)
          (if dash (str " stroke-dasharray=\"" dash "\"") "") (if marker (str " marker-end=\"url(#" marker ")\"") "")))

(defn rect [x y w h & {:keys [stroke fill width dash rx] :or {stroke ink fill "none" width 0.3 rx 0.8}}]
  (format "<rect x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"%.2f\" rx=\"%.2f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%.2f\"%s/>"
          (double x) (double y) (double w) (double h) (double rx) fill stroke (double width)
          (if dash (str " stroke-dasharray=\"" dash "\"") "")))

(defn cross [[x y]]   ; the closed-route mark, drawn as two strokes so no font has to supply it
  (let [d 1.3]
    (str (line [(- x d) (- y d)] [(+ x d) (+ y d)] :stroke verm :width 0.6)
         (line [(- x d) (+ y d)] [(+ x d) (- y d)] :stroke verm :width 0.6))))

(def r 4.0)
(defn trim [[x1 y1] [x2 y2] a b]   ; shorten a segment by a at the start and b at the end
  (let [dx (- x2 x1) dy (- y2 y1) l (Math/sqrt (+ (* dx dx) (* dy dy))) ux (/ dx l) uy (/ dy l)]
    [[(+ x1 (* a ux)) (+ y1 (* a uy))] [(- x2 (* b ux)) (- y2 (* b uy))]]))
(defn mid-pt [[x1 y1] [x2 y2]] [(/ (+ x1 x2) 2) (/ (+ y1 y2) 2)])

(defn node [[x y] id lead?]
  (str (format "<circle cx=\"%.2f\" cy=\"%.2f\" r=\"%.2f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%.2f\"/>"
               (double x) (double y) r (if lead? "#DCEBF5" "#FFFFFF") ink (if lead? 0.6 0.4))
       (text x (+ y 1.15) (str id) :size 3.4 :anchor "middle" :bold true)))

(def W 160) (def H 94)

(defn svg [{:keys [blind lead finder impossible possible recipe-in-round relay-rounds first-relay other-first-relays
                   cheats rule store-ops summary arm] :as f}]
  (let [[top-imp bot-imp] impossible
        poss (first possible)
        ly 34   ; the lead's height; the other agents sit 13 mm above and below it
        P {lead [108 ly] finder [78 (- ly 13)] poss [78 (+ ly 13)] top-imp [134 (- ly 13)] bot-imp [134 (+ ly 13)]}
        role-of (fn [a] (cond (= a lead) "lead" (= a finder) "finder" (some #{a} impossible) "worker (impossible)" :else "worker"))
        relay-labels (vals relay-rounds)
        shared-relay? (apply = (map rounds-label relay-labels))
        cheat-by (group-by :agent cheats)
        quote-size 3.4
        quote-lines (wrap (str "“" (:msg first-relay) "”") quote-size 51 false)
        rule-lines (wrap rule body 58 false)
        [oN oD] (:order summary) [nN nD] (:no-order summary)
        pct (fn [k n] (Math/round (* 100.0 (/ k n))))
        strip (str "Under the order the lead relayed in " oN " of " oD " episodes (" (pct oN oD) "%), against " nN " of " nD
                   " (" (pct nN nD) "%) with no order. Unexplained cheats: " (:unexplained summary) " of " (:cheat-episodes summary) ".")
        ;; one sentence per line where a sentence fits; the strip is too long for one line at 9 pt across 16 cm
        strip-lines (vec (mapcat #(wrap % body 152 false) (str/split strip #"(?<=\.) ")))
        qy 22 qlead 4.8
        qbox-h (+ 6 (* qlead (count quote-lines)) 5)]
    (reset! placed [])
    (str
     "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
     (format "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%dmm\" height=\"%dmm\" viewBox=\"0 0 %d %d\" font-family=\"Helvetica, Arial, sans-serif\">\n" W H W H)
     "<defs>"
     (format "<marker id=\"ab\" viewBox=\"0 0 10 10\" refX=\"8.5\" refY=\"5\" markerWidth=\"3.6\" markerHeight=\"3.6\" orient=\"auto\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"%s\"/></marker>" blue)
     (format "<marker id=\"av\" viewBox=\"0 0 10 10\" refX=\"8.5\" refY=\"5\" markerWidth=\"5\" markerHeight=\"5\" orient=\"auto\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"%s\"/></marker>" verm)
     "</defs>\n"
     (format "<rect x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" fill=\"#FFFFFF\"/>\n" W H)

     ;; ---- left panel
     (lines 4 8 4.6 (title-lines (str "What the lead said (arm " arm ", under the order)") 3.6 58) :size 3.6 :bold true) "\n"
     (rect 4 (- qy 5) 56 qbox-h :stroke "none" :fill "#F2F2F2") "\n"
     (rect 4 (- qy 5) 1.2 qbox-h :stroke "none" :fill blue :rx 0) "\n"
     (lines 8 (+ qy 1.5) qlead quote-lines :size quote-size) "\n"
     (text 8 (+ qy 1.5 (* qlead (count quote-lines)) 1.2)
           (str "lead (agent " lead ") to agent " (:to first-relay) ", round " (:round first-relay)) :fill mid) "\n"
     (when (seq other-first-relays)
       (lines 4 (+ qy qbox-h 1.5) 4.0
              (wrap (str "The same message went to agent " (str/join " and " (map :callee other-first-relays))
                         " for its own puzzle in round " (:round first-relay) ".") body 56 false) :fill mid))
     "\n"
     (lines 4 (+ qy qbox-h 13) 4.0 rule-lines :italic true) "\n"
     (line [64 4] [64 80] :stroke "#DDDDDD" :width 0.25) "\n"

     ;; ---- right panel
     (lines 68 8 4.6 (title-lines (str "What the proved switchboard recorded (episode " blind ")") 3.6 88) :size 3.6 :bold true) "\n"
     ;; permitted job graph: every worker to the lead
     (str/join "\n" (for [a [finder poss top-imp bot-imp]] (apply line (trim (P a) (P lead) r r)))) "\n"
     ;; the route used
     (apply line (concat (trim (P finder) (P lead) r (+ r 0.4)) [:stroke blue :width 0.9 :marker "ab"])) "\n"
     (str/join "\n" (for [a (keys relay-rounds)] (apply line (concat (trim (P lead) (P a) r (+ r 0.4)) [:stroke blue :width 0.9 :marker "ab"])))) "\n"
     ;; closed route 1: off-graph call, possible worker to the lower impossible worker
     (let [[a b] (trim (P poss) (P bot-imp) (+ r 0.6) (+ r 1.2)) m (mid-pt (P poss) (P bot-imp))]
       (str (line a b :stroke verm :width 0.5 :dash "1.6,1.0" :marker "av") (cross m) "\n"
            (lines 84 (+ (second m) 5.2) 3.9 ["off-graph call:" "impossible by proof"] :fill verm)))
     "\n"
     ;; closed route 2: worker write to the noticeboard
     (let [[bx by] (P bot-imp) bt 72 [a b] [[bx (+ by r 0.6)] [bx (- bt 0.6)]] cy (/ (+ (second a) bt) 2)]
       (str (line a b :stroke verm :width 0.5 :dash "1.6,1.0" :marker "av") (cross [bx cy]) "\n"
            (rect (- bx 12) bt 24 6.0 :stroke ink :width 0.35) "\n"
            (text bx (+ bt 4.2) "noticeboard" :anchor "middle") "\n"
            (lines (- bx 3.2) (- cy 1.4) 3.9 ["worker board write:" "impossible by proof"] :anchor "end" :fill verm)))
     "\n"
     ;; the scratch store, beside the proof
     (rect 66 67.2 52 13.6 :stroke grey :width 0.4 :dash "0.4,0.8") "\n"
     (lines 68 71.2 3.9 ["scratch store — outside the proof," "recorded and measured"
                       (str "(" store-ops (if (= 1 store-ops) " use" " uses") " in episode " blind ")")] :fill mid) "\n"
     ;; nodes and their labels
     (str/join "\n" (for [[a p] P] (node p a (= a lead)))) "\n"
     (let [[x y] (P lead)] (text x (+ y r 4.0) (role-of lead) :anchor "middle" :bold true)) "\n"
     (let [[x y] (P finder)] (text (- x r 1.2) (+ y 1.1) (role-of finder) :anchor "end")) "\n"
     (let [[x y] (P poss)] (text (- x r 1.2) (+ y 1.1) (role-of poss) :anchor "end")) "\n"
     (str/join "\n" (for [a impossible :let [[x y] (P a)]]
                      (lines (+ x r 1.2) (- y 0.8) 3.9 ["worker" "(impossible)"])))
     "\n"
     ;; recipe into the lead
     (lines 66 (- ly 1.0) 3.9 ["recipe reaches the" (str "lead, " (rounds-label [recipe-in-round]))] :fill blue :halo true) "\n"
     ;; relay label(s)
     (if shared-relay?
       (text 114.5 (+ ly 1.1) (str "relay, " (rounds-label (first relay-labels))) :fill blue :bold true :halo true)
       (str/join "\n" (for [[a rs] relay-rounds :let [[mx my] (mid-pt (P lead) (P a))]]
                        (text (+ mx 2) my (str "relay, " (rounds-label rs)) :fill blue :bold true :halo true))))
     "\n"
     ;; cheat successes
     (str/join "\n" (for [[a cs] cheat-by :let [[x y] (P a)]]
                      (lines (- W 3) (+ y 8.6) 3.9
                             ["flag submitted," (rounds-label (map :round cs))] :anchor "end" :bold true :fill ink)))
     "\n"
     ;; ---- bottom strip
     (line [4 82.5] [156 82.5] :stroke "#DDDDDD" :width 0.25) "\n"
     (lines 4 86.6 4.2 strip-lines) "\n"
     "</svg>\n")))

(defn check-bounds! []
  (doseq [{:keys [s x0 x1 y0 y1]} @placed]
    (when (or (< x0 1) (> x1 (dec W)) (< y0 1) (> y1 (dec H)))
      (fail (format "text runs off the canvas: %s [%.1f..%.1f, %.1f..%.1f]" s (double x0) (double x1) (double y0) (double y1)))))
  (doseq [[i a] (map-indexed vector @placed) b (drop (inc i) @placed)]   ; no two texts may share ink
    (when (and (< (:x0 a) (:x1 b)) (< (:x0 b) (:x1 a)) (< (:y0 a) (:y1 b)) (< (:y0 b) (:y1 a)))
      (fail "texts overlap: \"" (:s a) "\" and \"" (:s b) "\""))))

(defn -main [& args]
  (let [[jsonl run-id out] args
        jsonl (or jsonl (str (io/file here "results" "confirmatory" "phone-confirmatory.jsonl")))
        run-id (or run-id "phone-confirmatory-L-s20261107-e20")
        out (io/file (or out (str (io/file here "results" "figures" "figure1.svg"))))
        records (read-records jsonl)
        rec (or (first (filter #(= run-id (:run_id %)) records)) (fail "no record " run-id " in " jsonl))
        f (facts records rec jsonl)
        s (svg f)]
    (check-bounds!)
    (when (str/includes? s (:recipe f)) (fail "the recipe would appear in the figure"))
    (io/make-parents out)
    (spit out s)
    (prn (dissoc f :recipe))
    (println "wrote" (str out))))

(when (= *file* (System/getProperty "babashka.file")) (apply -main *command-line-args*))
