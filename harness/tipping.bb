#!/usr/bin/env bb
;; tipping.bb -- the tipping curve of the honest lead (Swarm telephone plan 2026-11-06, appendix "Inside the honest
;; coordinator", item 1). Exploratory; a method demonstration, not a result.
;;
;; Purpose: for every episode with at least one lead relay (M13, a call with :relay_via_lead true, found exactly as
;; casebook.bb finds it), take the lead's first relay round F and every lead decision in rounds 1..min(F+1, rounds used).
;; Replay each decision's EXACT logged prompt (messages [{:role "user" :content prompt}], think as logged, else false) on
;; the local server (SWARM_LOCAL_URL, read by model-client.bb) with the record's :model and the logged temperature,
;; num_ctx and num_predict, at seeds 1..N, and record whether each reply carries the recipe -- the harness's own trace,
;; swarm.phone/recipe-in? (lower case, keep [0-9a-z], the recipe's digits in order). The ORIGINAL logged seed is also
;; replayed once and compared byte-for-byte with the logged :reply_text (the reproducibility check; the prior-work scan
;; found it does NOT reproduce, so the curve is probabilities over seeds, never a single re-run).
;;
;; Outputs (in <out-dir>):
;;   replays.jsonl  one line per replay call (seed or orig): run id, round, decision index, kind, seed, prompt sha256
;;                  (and whether it matches the logged one), request settings, ok/error, reply text, carried, latency.
;;   tipping.tsv    one row per decision, rebuilt from replays.jsonl after every decision: run_id model arm label round
;;                  first_relay_round original_carried n k_carried p_hat cp95_lo cp95_hi orig_seed_identical, then
;;                  n_failed and R (the record's recipe bits; for R <= 16 a chance match of the trace is likely --
;;                  phone-episode.bb's recipe-in? docstring -- so R8 rows are a loose test).
;; Resumable: an (run_id, round, seed) triple already present in replays.jsonl with ok true is not called again; failed
;; calls are logged and retried on the next start. A partial (crash-truncated) last line of replays.jsonl is ignored.
;;
;; Usage: bb tipping.bb <out-dir> <seeds-per-decision> <file.jsonl> ...
;; Library: (load-file ".../tipping.bb") then swarm.tipping/picks, clopper-pearson, run-tipping!.

(ns swarm.tipping
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]))

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
;; read-only reuse: casebook.bb loads phone-episode.bb (recipe-in?) and its own read-records; model-client.bb the call
(when-not (find-ns 'swarm.casebook) (load-file (str (io/file here "casebook.bb"))))
(when-not (find-ns 'swarm.model-client) (load-file (str (io/file here "model-client.bb"))))
(require '[swarm.casebook :as cb] '[swarm.phone :as ph] '[swarm.model-client :as mc])

(def lead cb/lead)

;; ---------------------------------------------------------------- the trace

(defn carries?
  "Does text carry the actual recipe? The harness's own mechanical trace (swarm.phone/recipe-in?)."
  [recipe text]
  (boolean (and recipe text (ph/recipe-in? recipe text))))

;; ---------------------------------------------------------------- picking decisions

(defn first-relay-round
  "The lead's first M13 relay round, as casebook.bb computes it; nil when the episode has no relay."
  [rec]
  (let [relays (filterv :relay_via_lead (:calls rec))]
    (some->> relays (map :round) seq (apply min))))

(defn picks
  "Episode record -> the lead decisions to replay: rounds 1..min(F+1, rounds used), in round order. Empty without a relay."
  [rec]
  (if-let [f (first-relay-round rec)]
    (let [ru (or (:rounds_used rec) (some->> (:decisions rec) (map :round) seq (apply max)) f)
          last-r (min (inc f) ru)]
      (vec (sort-by :round
                    (for [[i d] (map-indexed vector (:decisions rec))
                          :when (and (= lead (:agent d)) (<= 1 (:round d) last-r))]
                      {:run_id (:run_id rec) :model (:model rec) :arm (:arm rec) :label (:label rec) :R (:R rec)
                       :recipe (:recipe rec) :round (:round d) :first_relay_round f :decision_index i :decision d}))))
    []))

(defn replay-request
  "The ollama-chat options that replay decision d at seed: the record's model, the logged sampling settings."
  [pick seed]
  (let [d (:decision pick) q (:request d)]
    (cond-> {:messages [{:role "user" :content (:prompt d)}]
             :seed seed :temperature (:temperature q) :num-ctx (:num-ctx q) :num-predict (:num-predict q)
             :model (:model pick)}
      (some? (:think q)) (assoc :think (:think q)))))

(defn replay-one
  "One replay call -> the replays.jsonl row. kind is \"seed\" (seeds 1..N) or \"orig\" (the logged seed)."
  [chat-fn pick kind seed]
  (let [d (:decision pick)
        req (replay-request pick seed)
        res (try (chat-fn req) (catch Exception e {:ok? false :error (str (class e) ": " (.getMessage e))}))
        sha (mc/sha256-hex (str (:prompt d)))
        text (:text res)]
    (cond-> {:ts (str (java.time.Instant/now)) :run_id (:run_id pick) :model (:model pick) :round (:round pick)
             :agent lead :decision_index (:decision_index pick) :kind kind :seed seed
             :prompt_sha256 sha :prompt_sha256_matches_logged (= sha (:prompt_sha256 d))
             :request (dissoc req :messages) :ok (boolean (:ok? res)) :error (:error res)
             :reply_text text :carried (when (:ok? res) (carries? (:recipe pick) text))
             :latency_s (:latency-s res) :output_tokens (:output-tokens res)}
      (= kind "orig") (assoc :identical_to_logged (when (:ok? res) (= text (:reply_text d)))))))

;; ---------------------------------------------------------------- Clopper-Pearson (exact, no libraries)

(defn binom-cdf
  "P(X <= k) for X ~ Binomial(n, p)."
  [k n p]
  (if (neg? k) 0.0
      (loop [i 0 c 1.0 acc 0.0]
        (if (> i (min k n)) (min 1.0 acc)
            (recur (inc i) (/ (* c (- n i)) (inc i))
                   (+ acc (* c (Math/pow p i) (Math/pow (- 1.0 p) (- n i)))))))))

(defn- bisect
  "Root of g on [0,1], g increasing, g(0) <= 0 <= g(1)."
  [g]
  (loop [lo 0.0 hi 1.0 i 0]
    (if (= i 200) (/ (+ lo hi) 2.0)
        (let [m (/ (+ lo hi) 2.0)]
          (if (neg? (g m)) (recur m hi (inc i)) (recur lo m (inc i)))))))

(defn clopper-pearson
  "Exact two-sided (1 - alpha) interval [lo hi] for k successes in n trials. lo solves P(X >= k | p) = alpha/2,
  hi solves P(X <= k | p) = alpha/2 (the beta quantiles, found by bisection on the binomial CDF)."
  ([k n] (clopper-pearson k n 0.05))
  ([k n alpha]
   (let [a2 (/ alpha 2.0)]
     [(if (zero? k) 0.0 (bisect (fn [p] (- (- 1.0 (binom-cdf (dec k) n p)) a2))))
      (if (= k n) 1.0 (bisect (fn [p] (- a2 (binom-cdf k n p)))))])))

;; ---------------------------------------------------------------- replays file

(defn read-replays
  "replays.jsonl -> vector of rows; blank and unparseable lines (a crash-truncated last line) are skipped."
  [f]
  (if (.exists (io/file f))
    (vec (keep #(when-not (str/blank? %) (try (json/parse-string % true) (catch Exception _ nil)))
               (str/split-lines (slurp f))))
    []))

(defn done-keys
  "The (run_id, round, seed) triples already replayed successfully."
  [rows]
  (set (for [r rows :when (:ok r)] [(:run_id r) (:round r) (:seed r)])))

(defn- append-row! [f row]
  (let [file (io/file f)]
    ;; a crash mid-write can leave no trailing newline; start the next row on its own line
    (when (and (.exists file) (pos? (.length file))
               (not (str/ends-with? (slurp file) "\n")))
      (spit file "\n" :append true))
    (spit file (str (json/generate-string row) "\n") :append true)))

;; ---------------------------------------------------------------- the table

(def tsv-header
  ["run_id" "model" "arm" "label" "round" "first_relay_round" "original_carried" "n" "k_carried" "p_hat"
   "cp95_lo" "cp95_hi" "orig_seed_identical" "n_failed" "R"])

(defn tsv-row
  "One decision's row from the replay rows (latest ok row per seed counts; seeds outside 1..N ignored)."
  [pick rows n-seeds]
  (let [mine (filter #(and (= (:run_id pick) (:run_id %)) (= (:round pick) (:round %))) rows)
        seeds (filter #(and (= "seed" (:kind %)) (<= 1 (:seed %) n-seeds)) mine)
        ok (vals (into {} (for [r seeds :when (:ok r)] [(:seed r) r])))
        okset (set (map :seed ok))
        failed (count (remove okset (distinct (map :seed (remove :ok seeds)))))
        orig (last (filter #(and (= "orig" (:kind %)) (:ok %)) mine))
        n (count ok) k (count (filter :carried ok))
        [lo hi] (when (pos? n) (clopper-pearson k n))
        f4 #(if % (format "%.4f" (double %)) "NA")]
    [(:run_id pick) (:model pick) (:arm pick) (:label pick) (:round pick) (:first_relay_round pick)
     (carries? (:recipe pick) (get-in pick [:decision :reply_text]))
     n k (f4 (when (pos? n) (/ k (double n)))) (f4 lo) (f4 hi)
     (if orig (:identical_to_logged orig) "NA") failed (or (:R pick) "NA")]))

(defn write-tsv! [f all-picks rows n-seeds]
  (spit f (str (str/join "\t" tsv-header) "\n"
               (str/join (for [p all-picks] (str (str/join "\t" (tsv-row p rows n-seeds)) "\n"))))))

;; ---------------------------------------------------------------- run

(defn run-tipping!
  "Replay every picked decision (orig seed once, then seeds 1..n), skipping triples already done. chat-fn defaults to
  model-client's ollama-chat. Returns {:picks :calls-made}."
  [{:keys [out-dir n files chat-fn] :or {chat-fn mc/ollama-chat}}]
  (let [d (io/file out-dir)
        _ (.mkdirs d)
        rf (io/file d "replays.jsonl")
        tf (io/file d "tipping.tsv")
        recs (mapcat (comp :records cb/read-records) files)
        all-picks (vec (mapcat picks recs))
        done (atom (done-keys (read-replays rf)))
        made (atom 0)
        todo (count (for [p all-picks s (cons (get-in p [:decision :request :seed]) (range 1 (inc n)))
                          :when (not (@done [(:run_id p) (:round p) s]))] 1))]
    (println (format "tipping: %d episodes read, %d with a relay, %d decisions, N=%d; %d replay calls to make; url %s"
                     (count recs) (count (distinct (map :run_id all-picks))) (count all-picks) n todo mc/local-url))
    (flush)
    (doseq [p all-picks]
      (let [orig-seed (get-in p [:decision :request :seed])]
        (doseq [[kind s] (cons ["orig" orig-seed] (for [s (range 1 (inc n))] ["seed" s]))
                :when (not (@done [(:run_id p) (:round p) s]))]
          (let [row (replay-one chat-fn p kind s)]
            (append-row! rf row)
            (swap! made inc)
            (when (:ok row) (swap! done conj [(:run_id p) (:round p) s]))
            (println (format "  %s r%d %s seed %d ok %s carried %s%s %.1fs" (:run_id p) (:round p) kind s (:ok row)
                             (:carried row) (if (= kind "orig") (str " identical " (:identical_to_logged row)) "")
                             (double (or (:latency_s row) 0.0))))
            (flush))))
      (write-tsv! tf all-picks (read-replays rf) n))
    (write-tsv! tf all-picks (read-replays rf) n)
    (println (format "tipping: done, %d calls made -> %s" @made (str tf)))
    {:picks all-picks :calls-made @made}))

(defn -main [& [out-dir n & files]]
  (if (and out-dir n (seq files))
    (run-tipping! {:out-dir out-dir :n (Long/parseLong n) :files files})
    (do (println "usage: bb tipping.bb <out-dir> <seeds-per-decision> <file.jsonl> ...") (System/exit 2))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
