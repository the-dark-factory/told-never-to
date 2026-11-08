#!/usr/bin/env bb
;; layerscan.bb -- "a first look inside the honest coordinator", item 2 (layer scan with controls).
;; Swarm telephone plan 2026-11-06, section "Exploratory appendices". EXPLORATORY; a method demonstration, not a result.
;;
;; Subject: qwen3.8:27b lead (agent 1) decisions from results/levers/lv-qwen38.jsonl, R = 32 episodes only.
;; Groups of dumps (one dump = the residual stream l_out-0..63 at the final prompt token, from internals/layer-dump):
;;   a  relay      each relay episode's lead prompt at its first relay round F (first :relay_via_lead call, as
;;                 tipping.bb / casebook.bb find it)
;;   b  held-back  the same lead at rounds F-1 and F-2 (it did not relay)
;;   c  never      leads of R = 32 episodes with no relay, at rounds Fm-2, Fm-1, Fm; Fm is matched within the setting
;;                 (label) to the relay episodes' F (cycled when a setting has several); settings with no relay use
;;                 the pooled F list; Fm is capped at the episode's rounds used
;;   t  tipping    every other lead decision of a relay episode that has a tipping p_hat (rounds 1..F-3, F+1);
;;                 never used to fit, only projected (for the Spearman with p_hat)
;;   n  noise      a perturbed copy of 8 prompts: the first "\n\nTHE TELEPHONE." becomes "\n\n\nTHE TELEPHONE." (one
;;                 extra blank line before the telephone rules; same words, same order)
;;   d  duplicate  2 prompts dumped twice unchanged (determinism check)
;;
;; Usage (from the harness directory):
;;   bb layerscan.bb prepare <out-dir>   writes <out-dir>/prompts/*.txt, list.tsv (for layer-dump -l) and manifest.tsv
;;   bb layerscan.bb gram <out-dir>      reads <out-dir>/dumps/*.json|f32 -> gram.bin + gram.json (64 Gram matrices)
;;   bb layerscan.bb analyse <out-dir> [n-perm] [n-boot]   -> layers.tsv, scores.tsv, results.md
;; Library: (load-file ".../layerscan.bb"); the maths (centre-gram, loeo-proj, auc, spearman) needs no harness.
;; Reads phone-episode.bb / casebook.bb / tipping.bb read-only (prepare only). No model calls.

(ns swarm.layerscan
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json])
  (:import [java.io RandomAccessFile DataOutputStream DataInputStream BufferedOutputStream BufferedInputStream
            FileOutputStream FileInputStream]
           [java.nio ByteBuffer ByteOrder]
           [java.security MessageDigest]))

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(def n-layer 64)
(def bands [[0 15] [16 31] [32 47] [48 63]])

;; ================================================================ maths (tested in layerscan_test.bb)

(defn dot ^double [^doubles a ^doubles b]
  (let [n (alength a)]
    (loop [i 0 s 0.0] (if (< i n) (recur (inc i) (+ s (* (aget a i) (aget b i)))) s))))

(defn gram
  "Vector of double-arrays (the dumps at one layer) -> Gram matrix as a vector of double-arrays."
  [vs]
  (let [n (count vs) g (vec (repeatedly n #(double-array n)))]
    (doseq [i (range n) j (range i n)]
      (let [v (dot (nth vs i) (nth vs j))]
        (aset ^doubles (nth g i) j v) (aset ^doubles (nth g j) i v)))
    g))

(defn gget ^double [g i j] (aget ^doubles (nth g i) j))

(defn centre-gram
  "Gram of x_i - mu, where mu is the mean of the vectors indexed by main (all dumps are shifted by the same mu).
  <x_i - mu, x_j - mu> = G_ij - r_i - r_j + s, r_i = mean_{k in main} G_ik, s = mean_{k,l in main} G_kl."
  [g main]
  (let [n (count g) m (double (count main))
        r (double-array (for [i (range n)] (/ (reduce + (map #(gget g i %) main)) m)))
        s (/ (reduce + (map #(aget r %) main)) m)]
    (vec (for [i (range n)]
           (double-array (for [j (range n)] (+ (- (gget g i j) (aget r i) (aget r j)) s)))))))

(defn cos-dist
  "1 - cosine between dumps i and j, from a Gram matrix (raw or centred)."
  [g i j]
  (- 1.0 (/ (gget g i j) (Math/sqrt (* (gget g i i) (gget g j j))))))

(defn row-sums
  "For each row i of g, the sum over the index set idx."
  [g idx]
  (let [n (count g)]
    (double-array (for [i (range n)] (reduce + 0.0 (map #(gget g i %) idx))))))

(defn loeo-proj
  "Leave-one-episode-out projection on the direction d = mean(A) - mean(B), all in Gram space.
  labels: vector (per dump) of :a, :b or nil (nil = not used to fit). eps: vector of episode ids (per dump).
  rlab (optional): row sums of g over the labelled (:a or :b) dumps, reused across relabelings of the same set.
  Returns a double-array: dump i projected on the unit direction fitted WITHOUT any dump of episode eps[i]
  (NaN when that fold has no A or no B)."
  ([g labels eps] (loeo-proj g labels eps nil))
  ([g labels eps rlab]
   (let [n (count g)
         ia (vec (filter #(= :a (labels %)) (range n)))
         ib (vec (filter #(= :b (labels %)) (range n)))
         ra (row-sums g ia)
         rb (if rlab (double-array (map - rlab ra)) (row-sums g ib))
         saa (reduce + 0.0 (map #(aget ra %) ia))
         sab (reduce + 0.0 (map #(aget rb %) ia))
         sbb (reduce + 0.0 (map #(aget rb %) ib))
         by-ep (group-by eps (range n))
         out (double-array n Double/NaN)]
     (doseq [[_ members] by-ep]
       (let [ae (filter #(= :a (labels %)) members)
             be (filter #(= :b (labels %)) members)
             na (- (count ia) (count ae)) nb (- (count ib) (count be))]
         (when (and (pos? na) (pos? nb))
           (let [sum2 (fn [xs ys] (reduce + 0.0 (for [i xs j ys] (gget g i j))))
                 saa' (+ (- saa (* 2.0 (reduce + 0.0 (map #(aget ra %) ae)))) (sum2 ae ae))
                 sbb' (+ (- sbb (* 2.0 (reduce + 0.0 (map #(aget rb %) be)))) (sum2 be be))
                 sab' (+ (- sab (reduce + 0.0 (map #(aget rb %) ae)) (reduce + 0.0 (map #(aget ra %) be))) (sum2 ae be))
                 d2 (+ (/ saa' (* na na)) (/ (* -2.0 sab') (* na nb)) (/ sbb' (* nb nb)))
                 dn (Math/sqrt (max d2 1e-300))]
             (doseq [j members]
               (let [ma (/ (- (aget ra j) (reduce + 0.0 (map #(gget g % j) ae))) na)
                     mb (/ (- (aget rb j) (reduce + 0.0 (map #(gget g % j) be))) nb)]
                 (aset out j (/ (- ma mb) dn))))))))
     out)))

(defn auc
  "Mann-Whitney AUC = P(pos > neg) + 0.5 P(tie). NaN when either side is empty."
  [pos neg]
  (if (or (empty? pos) (empty? neg))
    Double/NaN
    (/ (reduce + 0.0 (for [p pos q neg] (cond (> p q) 1.0 (= p q) 0.5 :else 0.0)))
       (* (count pos) (count neg)))))

(defn ranks
  "Average ranks (1-based; ties share the mean rank)."
  [xs]
  (let [idx (sort-by #(nth xs %) (range (count xs)))
        out (double-array (count xs))]
    (loop [k 0]
      (when (< k (count idx))
        (let [v (nth xs (nth idx k))
              e (loop [e k] (if (and (< (inc e) (count idx)) (= v (nth xs (nth idx (inc e))))) (recur (inc e)) e))
              r (/ (+ k e 2) 2.0)]
          (doseq [q (range k (inc e))] (aset out (nth idx q) r))
          (recur (inc e)))))
    (vec out)))

(defn pearson [xs ys]
  (let [n (double (count xs)) mx (/ (reduce + xs) n) my (/ (reduce + ys) n)
        sxy (reduce + (map #(* (- %1 mx) (- %2 my)) xs ys))
        sxx (reduce + (map #(let [d (- % mx)] (* d d)) xs))
        syy (reduce + (map #(let [d (- % my)] (* d d)) ys))]
    (if (or (zero? sxx) (zero? syy)) Double/NaN (/ sxy (Math/sqrt (* sxx syy))))))

(defn spearman [xs ys] (pearson (ranks xs) (ranks ys)))

(defn median [xs]
  (let [s (vec (sort xs)) n (count s)]
    (cond (zero? n) Double/NaN
          (odd? n) (double (s (quot n 2)))
          :else (/ (+ (s (dec (quot n 2))) (s (quot n 2))) 2.0))))

(defn mean [xs] (if (seq xs) (/ (reduce + 0.0 xs) (count xs)) Double/NaN))

(defn sd [xs]
  (let [m (mean xs) n (count xs)]
    (if (< n 2) Double/NaN (Math/sqrt (/ (reduce + 0.0 (map #(let [d (- % m)] (* d d)) xs)) (dec n))))))

(defn quantile [xs q]
  (let [s (vec (sort (remove #(Double/isNaN %) xs))) n (count s)]
    (if (zero? n) Double/NaN
        (let [h (* q (dec n)) lo (int (Math/floor h)) hi (min (dec n) (inc lo))]
          (+ (s lo) (* (- h lo) (- (s hi) (s lo))))))))

;; ================================================================ prepare (reads the harness read-only)

(defn- hv [sym] @(resolve sym))

(defn- load-harness! []
  (when-not (find-ns 'swarm.tipping) (load-file (str (io/file here "tipping.bb")))))

(defn short-id [run-id]
  (-> run-id (str/replace #"^phone-lv-qwen38-" "") (str/replace #"-s20261108" "")))

(defn sha256-hex [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(def perturb-from "\n\nTHE TELEPHONE.")
(def perturb-to "\n\n\nTHE TELEPHONE.")

(defn perturb
  "The noise-floor edit: one extra blank line before the telephone rules. Throws if the anchor is missing."
  [text]
  (when-not (str/includes? text perturb-from) (throw (ex-info "perturb anchor missing" {})))
  (str/replace-first text perturb-from perturb-to))

(def manifest-cols
  [:id :group :run_id :label :arm :episode :ep_kind :round :F :rel :recipe_in_prompt :logged_prompt_tokens
   :prompt_sha256 :sha_matches_logged :p_hat :source :prompt_path :dump_prefix])

(defn read-tipping [f]
  (let [[h & rows] (map #(str/split % #"\t") (str/split-lines (slurp f)))
        ks (map keyword h)]
    (into {} (for [r rows :let [m (zipmap ks r)] :when (= "32" (:R m))]
               [[(:run_id m) (Long/parseLong (:round m))] (Double/parseDouble (:p_hat m))]))))

(defn prepare! [out-dir]
  (load-harness!)
  (let [read-records (hv 'swarm.casebook/read-records)
        recipe-in? (hv 'swarm.phone/recipe-in?)
        first-relay (hv 'swarm.tipping/first-relay-round)
        out (.getCanonicalFile (io/file out-dir))
        pdir (io/file out "prompts") ddir (io/file out "dumps")
        _ (.mkdirs pdir) _ (.mkdirs ddir)
        recs (filterv #(= 32 (:R %)) (:records (read-records (str (io/file here "results/levers/lv-qwen38.jsonl")))))
        tip (read-tipping (str (io/file here "results/levers/tipping-qwen38/tipping.tsv")))
        relay (filterv first-relay recs)
        never (filterv (complement first-relay) recs)
        f-by-label (reduce (fn [m r] (update m (:label r) (fnil conj []) (first-relay r))) {} relay)
        pooled (vec (sort (map first-relay relay)))
        never-f (loop [rs never cnt {} acc {}]
                  (if-let [r (first rs)]
                    (let [fs (get f-by-label (:label r) pooled) k (get cnt (:label r) 0)
                          ru (or (:rounds_used r) (apply max (map :round (:decisions r))))
                          fm (max 3 (min ru (nth fs (mod k (count fs)))))]
                      (recur (rest rs) (update cnt (:label r) (fnil inc 0)) (assoc acc (:run_id r) fm)))
                    acc))
        lead-at (fn [r round] (first (filter #(and (= 1 (:agent %)) (= round (:round %))) (:decisions r))))
        row (fn [r d group f]
              (let [id (str (short-id (:run_id r)) "-r" (:round d))
                    p (io/file pdir (str id ".txt"))
                    text (:prompt d)
                    sha (sha256-hex text)]
                (spit p text)
                {:id id :group group :run_id (:run_id r) :label (:label r) :arm (:arm r) :episode (short-id (:run_id r))
                 :ep_kind (if (first-relay r) "relay" "never") :round (:round d) :F f :rel (- (:round d) f)
                 :recipe_in_prompt (boolean (recipe-in? (:recipe r) text)) :logged_prompt_tokens (:prompt_tokens d)
                 :prompt_sha256 sha :sha_matches_logged (= sha (:prompt_sha256 d))
                 :p_hat (get tip [(:run_id r) (:round d)] "NA") :source ""
                 :prompt_path (str p) :dump_prefix (str (io/file ddir id))}))
        main (vec (concat
                   (for [r relay :let [f (first-relay r)] d (sort-by :round (filter #(= 1 (:agent %)) (:decisions r)))
                         :let [rel (- (:round d) f)]
                         :when (or (<= -2 rel 0) (contains? tip [(:run_id r) (:round d)]))]
                     (row r d (case rel 0 "a" -1 "b" -2 "b" "t") f))
                   (for [r never :let [fm (never-f (:run_id r))] round [(- fm 2) (dec fm) fm]]
                     (row r (lead-at r round) "c" fm))))
        by-id (into {} (map (juxt :id identity) main))
        never-first (->> never (map (fn [r] [(:label r) r])) (reduce (fn [m [l r]] (if (m l) m (assoc m l r))) {}))
        noise-src (concat (for [r (take 4 relay)] (str (short-id (:run_id r)) "-r" (first-relay r)))
                          (for [r (take 4 (distinct (map (comp never-first :label) never)))]
                            (str (short-id (:run_id r)) "-r" (never-f (:run_id r)))))
        noise (vec (for [sid noise-src :let [s (by-id sid) id (str sid "-ws") p (io/file pdir (str id ".txt"))
                                             text (perturb (slurp (:prompt_path s)))]]
                     (do (spit p text)
                         (assoc s :id id :group "n" :source sid :prompt_sha256 (sha256-hex text)
                                :sha_matches_logged false :p_hat "NA" :prompt_path (str p)
                                :dump_prefix (str (io/file ddir id))))))
        dups (vec (for [sid [(first noise-src) (nth noise-src 4)] :let [s (by-id sid)]]
                    (assoc s :id (str sid "-dup") :group "d" :source sid :p_hat "NA"
                           :dump_prefix (str (io/file ddir (str sid "-dup"))))))
        all (vec (concat main noise dups))]
    (spit (io/file out "manifest.tsv")
          (str (str/join "\t" (map name manifest-cols)) "\n"
               (str/join (for [m all] (str (str/join "\t" (map #(str (get m %)) manifest-cols)) "\n")))))
    (spit (io/file out "list.tsv") (str/join (for [m all] (str (:prompt_path m) "\t" (:dump_prefix m) "\n"))))
    (println (format "prepare: %d R=32 episodes (%d relay, %d never); dumps: %s; sha mismatches (main): %d -> %s"
                     (count recs) (count relay) (count never)
                     (pr-str (frequencies (map :group all)))
                     (count (remove :sha_matches_logged main)) (str out)))
    all))

;; ================================================================ reading manifest and dumps

(defn read-manifest [out-dir]
  (let [[h & rows] (map #(str/split % #"\t" -1) (str/split-lines (slurp (io/file out-dir "manifest.tsv"))))
        ks (map keyword h)
        num (fn [s] (when-not (or (str/blank? s) (= "NA" s)) (Double/parseDouble s)))]
    (vec (for [r rows :let [m (zipmap ks r)]]
           (-> m
               (update :round #(Long/parseLong %)) (update :F #(Long/parseLong %)) (update :rel #(Long/parseLong %))
               (update :recipe_in_prompt #(= "true" %)) (update :p_hat num))))))

(defn dump-index [prefix] (json/parse-string (slurp (str prefix ".json")) true))

(defn read-vec
  "One tensor of a dump as a double-array, read straight from prefix.f32 at the index's offset."
  [prefix {:keys [offset nbytes]}]
  (with-open [f (RandomAccessFile. (str prefix ".f32") "r")]
    (let [bs (byte-array nbytes)]
      (.seek f (long offset)) (.readFully f bs)
      (let [bb (.order (ByteBuffer/wrap bs) ByteOrder/LITTLE_ENDIAN) n (quot nbytes 4) a (double-array n)]
        (dotimes [i n] (aset a i (double (.getFloat bb (int (* 4 i))))))
        a))))

(defn gram! [out-dir]
  (let [man (read-manifest out-dir)
        idx (mapv #(dump-index (:dump_prefix %)) man)
        _ (doseq [[m x] (map vector man idx)]
            (when-not (and (= 64 (:n_layer x)) (= 5120 (:n_embd x)) (:chat_wrapped x))
              (throw (ex-info "unexpected dump" {:id (:id m)}))))
        tens (mapv (fn [x] (into {} (map (juxt :name identity) (:tensors x)))) idx)
        n (count man)]
    (with-open [o (DataOutputStream. (BufferedOutputStream. (FileOutputStream. (io/file out-dir "gram.bin"))))]
      (doseq [l (range n-layer)]
        (let [nm (str "l_out-" l)
              vs (mapv (fn [m t] (read-vec (:dump_prefix m) (get t nm))) man tens)
              g (gram vs)]
          (doseq [i (range n) j (range n)] (.writeDouble o (gget g i j)))
          (print (str l " ")) (flush))))
    (spit (io/file out-dir "gram.json")
          (json/generate-string {:ids (mapv :id man) :n n :layers n-layer :order "layer-major, row-major, big-endian f64"
                                 :n_tokens (mapv :n_tokens idx) :greedy_next (mapv :greedy_next_token idx)}
                                {:pretty true}))
    (println "\ngram: done," n "dumps")))

(defn read-grams [out-dir]
  (let [meta (json/parse-string (slurp (io/file out-dir "gram.json")) true) n (:n meta)]
    (with-open [in (DataInputStream. (BufferedInputStream. (FileInputStream. (io/file out-dir "gram.bin"))))]
      (assoc meta :grams
             (vec (for [_ (range n-layer)]
                    (vec (for [_ (range n)] (let [a (double-array n)] (dotimes [j n] (aset a j (.readDouble in))) a)))))))))

;; ================================================================ analysis

(defn idx-where [man pred] (vec (keep-indexed #(when (pred %2) %1) man)))

(defn real-labels [man]
  (mapv #(case (:group %) "a" :a ("b" "c") :b nil) man))

(defn f4 [x] (if (and (number? x) (not (Double/isNaN (double x)))) (format "%.4f" (double x)) "NA"))
(defn f3 [x] (if (and (number? x) (not (Double/isNaN (double x)))) (format "%.3f" (double x)) "NA"))

(defn band-scores
  "Per-layer held-out projections (vector of 64 double-arrays) -> per dump, the mean over the band's layers of the
  projection standardised by that layer's SD over the main dumps."
  [projs main [lo hi]]
  (let [n (alength ^doubles (first projs))
        sds (vec (for [l (range n-layer)] (sd (map #(aget ^doubles (nth projs l) %) main))))]
    (vec (for [i (range n)]
           (mean (for [l (range lo (inc hi))] (/ (aget ^doubles (nth projs l) i) (sds l))))))))

(defn analyse! [out-dir n-perm n-boot]
  (let [man (read-manifest out-dir)
        {:keys [ids grams n_tokens greedy_next]} (read-grams out-dir)
        _ (when-not (= ids (mapv :id man)) (throw (ex-info "gram.json ids do not match manifest" {})))
        n (count man)
        rng (java.util.Random. 20261106)
        main (idx-where man #(#{"a" "b" "c" "t"} (:group %)))
        eps (mapv :episode man)
        labels (real-labels man)
        gcs (mapv #(centre-gram % main) grams)
        lab-set (idx-where man #(#{"a" "b" "c"} (:group %)))
        rlabs (mapv #(row-sums % lab-set) gcs)
        ia (idx-where man #(= "a" (:group %)))
        ib (idx-where man #(= "b" (:group %)))
        ic (idx-where man #(and (= "c" (:group %)) (zero? (:rel %))))   ; never-relay at the matched round Fm only
        relay-eps (vec (distinct (map eps ia)))
        never-eps (vec (distinct (map eps ic)))
        all-eps (vec (concat relay-eps never-eps))
        ;; per episode: the triple at rel -2, -1, 0 (relative to F or matched Fm)
        triple (into {} (for [e all-eps]
                          [e (into {} (for [i lab-set :when (= e (eps i))] [(:rel (man i)) i]))]))
        ;; --- per-layer held-out projections, real labels
        projs (mapv #(loeo-proj %1 labels eps %2) gcs rlabs)
        at (fn [arr idxs] (map #(aget ^doubles arr %) idxs))
        auc-ab (fn [arr pos neg] (auc (at arr pos) (at arr neg)))
        layer-aucs (fn [projs pos negb negc] (vec (for [p projs] [(auc-ab p pos negb) (auc-ab p pos negc)])))
        real-aucs (layer-aucs projs ia ib ic)
        bscore (fn [projs band] (double-array (band-scores projs main band)))
        best-hb (apply max-key #(first (real-aucs %)) (range n-layer))
        best-nr (apply max-key #(second (real-aucs %)) (range n-layer))
        ;; --- cluster bootstrap over episodes (no refit) of an AUC on a score array
        boot-auc (fn [arr pos-eps-sel neg-sel]
                   ;; pos-eps-sel: fn ep -> pos idxs; neg-sel: fn ep -> neg idxs; resample relay eps and the neg eps
                   (fn [neg-eps]
                     (let [stats (for [_ (range n-boot)]
                                   (let [re (repeatedly (count relay-eps) #(relay-eps (.nextInt rng (count relay-eps))))
                                         ne (repeatedly (count neg-eps) #(neg-eps (.nextInt rng (count neg-eps))))]
                                     (auc (at arr (mapcat pos-eps-sel re)) (at arr (mapcat neg-sel ne)))))]
                       [(quantile stats 0.025) (quantile stats 0.975)])))
        pos-of (fn [e] (filter #(= e (eps %)) ia))
        hb-of (fn [e] (filter #(= e (eps %)) ib))
        c-of (fn [e] (filter #(= e (eps %)) ic))
        ci-hb (fn [arr] ((boot-auc arr pos-of hb-of) relay-eps))
        ci-nr (fn [arr] ((boot-auc arr pos-of c-of) never-eps))
        ;; --- permutations (refit LOEO every time)
        perm-stats (fn [relabel stat-pos-neg]
                     (vec (for [_ (range n-perm)]
                            (let [[lbl pos neg] (relabel)
                                  ps (mapv #(loeo-proj %1 lbl eps %2) gcs rlabs)
                                  la (mapv #(auc-ab % pos neg) ps)]
                              {:layers la :max (apply max la)
                               :bands (mapv #(auc-ab (bscore ps %) pos neg) bands)}))))
        shuffle-r (fn [coll] (let [l (java.util.ArrayList. ^java.util.Collection coll)]
                               (java.util.Collections/shuffle l rng) (vec l)))
        relabel-hb (fn []
                     (let [lbl (transient (vec labels)) pos (transient []) neg (transient [])]
                       (doseq [e relay-eps]
                         (let [t (triple e) v (vec (vals t)) pick (v (.nextInt rng (count v)))]
                           (doseq [i (vals t)]
                             (if (= i pick) (do (assoc! lbl i :a) (conj! pos i)) (do (assoc! lbl i :b) (conj! neg i))))))
                       [(persistent! lbl) (persistent! pos) (persistent! neg)]))
        relabel-nr (fn []
                     (let [chosen (set (take (count relay-eps) (shuffle-r all-eps)))
                           lbl (transient (vec labels)) pos (transient []) neg (transient [])]
                       (doseq [e all-eps [rel i] (triple e)]
                         (cond (and (chosen e) (zero? rel)) (do (assoc! lbl i :a) (conj! pos i))
                               (chosen e) (assoc! lbl i :b)
                               (zero? rel) (do (assoc! lbl i :b) (conj! neg i))
                               :else (assoc! lbl i :b)))
                       [(persistent! lbl) (persistent! pos) (persistent! neg)]))
        _ (do (println "analyse: permutations" n-perm "x 2") (flush))
        perm-hb (perm-stats relabel-hb nil)
        perm-nr (perm-stats relabel-nr nil)
        pval (fn [obs xs] (/ (inc (count (filter #(>= % obs) xs))) (double (inc (count xs)))))
        ;; --- steps F-1 -> F (centred cosine distance), noise floor, duplicates
        step (fn [g e] (let [t (triple e)] (cos-dist g (t -1) (t 0))))
        noise-pairs (for [i (idx-where man #(= "n" (:group %)))]
                      [i (first (idx-where man #(= (:source (man i)) (:id %))))])
        dup-pairs (for [i (idx-where man #(= "d" (:group %)))]
                    [i (first (idx-where man #(= (:source (man i)) (:id %))))])
        dproj (fn [arr e] (let [t (triple e)] (- (aget ^doubles arr (t 0)) (aget ^doubles arr (t -1)))))
        ;; --- tipping decisions (relay episodes, p_hat known, main groups)
        tipd (idx-where man #(and (:p_hat %) (#{"a" "b" "t"} (:group %))))
        ph (mapv #(:p_hat (man %)) tipd)
        boot-spear (fn [arr]
                     (let [by-e (group-by eps tipd)
                           stats (for [_ (range n-boot)]
                                   (let [sel (mapcat (fn [_] (by-e (relay-eps (.nextInt rng (count relay-eps)))))
                                                     (range (count relay-eps)))]
                                     (spearman (vec (at arr sel)) (mapv #(:p_hat (man %)) sel))))]
                       [(quantile stats 0.025) (quantile stats 0.975)]))
        spear (fn [arr] (spearman (vec (at arr tipd)) ph))
        ;; --- recipe-restricted refit: only dumps whose prompt carries the recipe are labelled
        rlabels (mapv #(if (:recipe_in_prompt (man %2)) %1 nil) labels (range n))
        rset (idx-where man #(and (#{"a" "b" "c"} (:group %)) (:recipe_in_prompt %)))
        r-ia (filterv #(= "a" (:group (man %))) rset)
        r-ib (filterv #(= "b" (:group (man %))) rset)
        r-ic (filterv #(and (= "c" (:group (man %))) (zero? (:rel (man %)))) rset)
        r-projs (mapv #(loeo-proj % rlabels eps) gcs)
        r-aucs (layer-aucs r-projs r-ia r-ib r-ic)
        ;; --- baselines from prompt length and round (layer-independent)
        ntok (mapv double n_tokens)
        ntok-arr (double-array ntok)
        rel-arr (double-array (map #(double (:rel %)) man))
        rnd-arr (double-array (map #(double (:round %)) man))
        band-rows
        (vec (for [[k band] (map-indexed vector bands)]
               (let [bs (bscore projs band)
                     rbs (bscore r-projs band)
                     [lo hi] band
                     ls (range lo (inc hi))
                     steps-r (for [e relay-eps] (mean (for [l ls] (step (gcs l) e))))
                     steps-n (for [e never-eps] (mean (for [l ls] (step (gcs l) e))))
                     floor (for [[i j] noise-pairs] (mean (for [l ls] (cos-dist (gcs l) i j))))]
                 {:name (format "%d-%d" lo hi)
                  :auc_hb (auc-ab bs ia ib) :ci_hb (ci-hb bs) :p_hb (pval (auc-ab bs ia ib) (map #(get-in % [:bands k]) perm-hb))
                  :auc_nr (auc-ab bs ia ic) :ci_nr (ci-nr bs) :p_nr (pval (auc-ab bs ia ic) (map #(get-in % [:bands k]) perm-nr))
                  :r_auc_hb (auc-ab rbs r-ia r-ib) :r_auc_nr (auc-ab rbs r-ia r-ic)
                  :step_relay (median steps-r) :step_never (median steps-n) :step_auc (auc steps-r steps-n)
                  :dproj_auc (auc (map #(dproj bs %) relay-eps) (map #(dproj bs %) never-eps))
                  :floor_med (median floor) :floor_max (apply max floor)
                  :spear (spear bs) :ci_spear (boot-spear bs)})))
        layer-row (fn [l]
                    (let [g (gcs l) p (projs l)
                          steps-r (map #(step g %) relay-eps) steps-n (map #(step g %) never-eps)
                          floor (for [[i j] noise-pairs] (cos-dist g i j))
                          [ahb anr] (real-aucs l) [rhb rnr] (r-aucs l)]
                      {:layer l :auc_hb ahb :auc_nr anr :r_auc_hb rhb :r_auc_nr rnr
                       :step_relay (median steps-r) :step_never (median steps-n) :step_auc (auc steps-r steps-n)
                       :dproj_auc (auc (map #(dproj p %) relay-eps) (map #(dproj p %) never-eps))
                       :floor_med (median floor) :floor_max (apply max floor)
                       :dup_max (apply max 0.0 (for [[i j] dup-pairs] (cos-dist g i j)))
                       :raw_floor_med (median (for [[i j] noise-pairs] (cos-dist (grams l) i j)))
                       :raw_step_relay (median (map #(let [t (triple %)] (cos-dist (grams l) (t -1) (t 0))) relay-eps))
                       :raw_step_never (median (map #(let [t (triple %)] (cos-dist (grams l) (t -1) (t 0))) never-eps))
                       :spear (spear p)
                       :paired_up (count (filter #(pos? (dproj p %)) relay-eps))}))
        lrows (mapv layer-row (range n-layer))
        best (fn [l k ci-fn]
               (let [p (projs l)]
                 (assoc (lrows l) :ci (ci-fn p) :sel k
                        :p_max (pval (get-in real-aucs [l (if (= k :hb) 0 1)])
                                     (map :max (if (= k :hb) perm-hb perm-nr)))
                        :ci_spear (boot-spear p))))
        b-hb (best best-hb :hb ci-hb)
        b-nr (best best-nr :nr ci-nr)
        base {:ntok_hb (auc-ab ntok-arr ia ib) :ntok_hb_ci (ci-hb ntok-arr)
              :ntok_nr (auc-ab ntok-arr ia ic) :ntok_nr_ci (ci-nr ntok-arr)
              :round_nr (auc-ab rnd-arr ia ic)
              :spear_ntok (spear ntok-arr) :spear_ntok_ci (boot-spear ntok-arr)
              :spear_rel (spear rel-arr)
              :step_tok_relay (median (map #(let [t (triple %)] (- (ntok (t 0)) (ntok (t -1)))) relay-eps))
              :step_tok_never (median (map #(let [t (triple %)] (- (ntok (t 0)) (ntok (t -1)))) never-eps))
              :step_tok_auc (auc (map #(let [t (triple %)] (- (ntok (t 0)) (ntok (t -1)))) relay-eps)
                                 (map #(let [t (triple %)] (- (ntok (t 0)) (ntok (t -1)))) never-eps))}
        counts (frequencies (map :group man))
        recipe-counts {:a (count r-ia) :b (count r-ib) :c (count r-ic)}]
    ;; ---- layers.tsv
    (let [cols [:layer :auc_hb :auc_nr :r_auc_hb :r_auc_nr :step_relay :step_never :step_auc :dproj_auc :paired_up
                :floor_med :floor_max :dup_max :raw_step_relay :raw_step_never :raw_floor_med :spear]]
      (spit (io/file out-dir "layers.tsv")
            (str (str/join "\t" (map name cols)) "\n"
                 (str/join (for [r lrows] (str (str/join "\t" (map #(let [v (get r %)] (if (integer? v) (str v) (f4 v))) cols)) "\n"))))))
    ;; ---- scores.tsv (per dump: band scores with real labels, held-out)
    (let [bss (mapv #(bscore projs %) bands)]
      (spit (io/file out-dir "scores.tsv")
            (str "id\tgroup\tepisode\tround\trel\trecipe_in_prompt\tn_tokens\tgreedy_next\tp_hat\t"
                 (str/join "\t" (map #(str "band_" (first %) "_" (second %)) bands)) "\n"
                 (str/join (for [i (range n) :let [m (man i)]]
                             (str (str/join "\t" [(:id m) (:group m) (:episode m) (:round m) (:rel m) (:recipe_in_prompt m)
                                                  (ntok i) (pr-str (nth greedy_next i)) (or (:p_hat m) "NA")])
                                  "\t" (str/join "\t" (map #(f4 (aget ^doubles % i)) bss)) "\n"))))))
    ;; ---- results.md
    (let [ci (fn [[lo hi]] (str "[" (f3 lo) ", " (f3 hi) "]"))
          md (str
              "# Layer scan of the qwen3.8 lead with controls (EXPLORATORY; method demonstration)\n\n"
              "Generated by layerscan.bb analyse; n-perm " n-perm ", n-boot " n-boot ", rng seed 20261106.\n\n"
              "Dumps per group: " (pr-str (into (sorted-map) counts))
              " (a relay at F, b held-back F-1/F-2, c never-relay at matched Fm-2..Fm, t other tipping decisions, "
              "n perturbed copies, d exact duplicates). Relay episodes " (count relay-eps) ", never-relay episodes "
              (count never-eps) ". Recipe-in-prompt subset: " (pr-str recipe-counts) ".\n\n"
              "Relay-vs-never AUCs compare a with c at the matched round Fm only (one per never-relay episode); "
              "all c dumps are used to fit the direction.\n\n"
              "Distances are 1 - cosine of centred vectors (mean over a, b, c, t subtracted). Projection = held-out "
              "(leave-one-episode-out) projection on mean(a) - mean(b and c). Band score = mean over the band's layers "
              "of the per-layer projection divided by its SD. AUC intervals: cluster bootstrap over episodes (2.5-97.5%, "
              "no refit). p: permutation with full LOEO refit (hb: which of each relay episode's three dumps is the "
              "relay; nr: which episodes are the relay episodes). Best layer selected POST HOC; its p uses the max over "
              "64 layers under permutation.\n\n"
              "## Per band\n\n"
              "| band | AUC relay vs held-back [CI] p | AUC relay vs never [CI] p | recipe-only AUC hb / nr | step F-1->F median relay / never (AUC) | dproj AUC | noise floor median (max) | Spearman proj~p_hat [CI] |\n"
              "|---|---|---|---|---|---|---|---|\n"
              (str/join (for [b band-rows]
                          (str "| " (:name b) " | " (f3 (:auc_hb b)) " " (ci (:ci_hb b)) " p=" (f3 (:p_hb b))
                               " | " (f3 (:auc_nr b)) " " (ci (:ci_nr b)) " p=" (f3 (:p_nr b))
                               " | " (f3 (:r_auc_hb b)) " / " (f3 (:r_auc_nr b))
                               " | " (f4 (:step_relay b)) " / " (f4 (:step_never b)) " (" (f3 (:step_auc b)) ")"
                               " | " (f3 (:dproj_auc b))
                               " | " (f4 (:floor_med b)) " (" (f4 (:floor_max b)) ")"
                               " | " (f3 (:spear b)) " " (ci (:ci_spear b)) " |\n")))
              "\n## Single best layer (selected post hoc)\n\n"
              "| selected on | layer | AUC hb [CI] | AUC nr [CI] | p (max over layers) | step relay / never | floor median (max) | dup max | Spearman [CI] | relay eps with proj(F) > proj(F-1) |\n"
              "|---|---|---|---|---|---|---|---|---|---|\n"
              (str/join (for [b [b-hb b-nr]]
                          (str "| " (if (= :hb (:sel b)) "relay vs held-back" "relay vs never") " | " (:layer b)
                               " | " (f3 (:auc_hb b)) (if (= :hb (:sel b)) (str " " (ci (:ci b))) "")
                               " | " (f3 (:auc_nr b)) (if (= :nr (:sel b)) (str " " (ci (:ci b))) "")
                               " | " (f3 (:p_max b))
                               " | " (f4 (:step_relay b)) " / " (f4 (:step_never b))
                               " | " (f4 (:floor_med b)) " (" (f4 (:floor_max b)) ")"
                               " | " (f4 (:dup_max b))
                               " | " (f3 (:spear b)) " " (ci (:ci_spear b))
                               " | " (:paired_up b) "/" (count relay-eps) " |\n")))
              "\n## Confound baselines (no model internals)\n\n"
              "- Prompt length (tokens) as the score: AUC relay vs held-back " (f3 (:ntok_hb base)) " " (ci (:ntok_hb_ci base))
              "; relay vs never " (f3 (:ntok_nr base)) " " (ci (:ntok_nr_ci base)) ".\n"
              "- Round number as the score: relay vs never " (f3 (:round_nr base)) " (rounds matched by design).\n"
              "- Spearman p_hat ~ prompt length " (f3 (:spear_ntok base)) " " (ci (:spear_ntok_ci base))
              "; p_hat ~ round relative to F " (f3 (:spear_rel base)) " (n = " (count tipd) " decisions, "
              (count relay-eps) " episodes).\n"
              "- Tokens added F-1 -> F: median relay " (f3 (:step_tok_relay base)) ", never " (f3 (:step_tok_never base))
              ", AUC " (f3 (:step_tok_auc base)) ".\n"
              "- Duplicate dumps (same prompt twice): max centred cosine distance over layers "
              (f4 (apply max (map :dup_max lrows))) ".\n\n"
              "Per-layer numbers: layers.tsv. Per-dump band scores: scores.tsv.\n")]
      (spit (io/file out-dir "results.md") md)
      (println md))))

(defn -main [& [cmd out-dir n-perm n-boot]]
  (case cmd
    "prepare" (do (prepare! out-dir) nil)
    "gram" (gram! out-dir)
    "analyse" (analyse! out-dir (Long/parseLong (or n-perm "500")) (Long/parseLong (or n-boot "2000")))
    (do (println "usage: bb layerscan.bb prepare|gram|analyse <out-dir> [n-perm] [n-boot]") (System/exit 2))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
