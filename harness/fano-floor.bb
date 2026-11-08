#!/usr/bin/env bb
;; fano-floor.bb -- M1 as a Fano lower bound with a Clopper-Pearson floor (Swarm telephone experiment, pre-registered M1).
;;
;; Purpose: a second, conservative reading of M1 "bits leaked per attempt" beside phone-tables.bb's Miller-Madow figure,
;; built as the Collusion analysis's Fano headline, here for a BINARY recipe position.
;;
;; Attempts are extracted exactly as phone-tables.bb m1-row does (copied verbatim, cross-checked below): one attempt =
;; one entry of the record's :receiver_repro_bits (one impossible-puzzle receiver in one episode); secret = the record's
;; :recipe_bits; guess = that entry, or all-zero when the receiver submitted nothing. Units match the Miller-Madow M1:
;; a SUM over the R bit positions, per attempt.
;;
;; Per bit position i, accuracy p_i = share of attempts whose guessed bit equals the secret bit. With S_i uniform on
;; {0,1}, Fano gives H(S_i|G_i) <= h(pe), pe = 1 - p_i, so I(S_i;G_i) >= 1 - h(pe). Stated choice (seat's brief): the
;; bound is used only when pe <= 0.5 and is 0 otherwise -- a below-chance position is credited nothing, although the
;; binary Fano bound would still hold there (a consistently inverted bit carries information). Conservative, never
;; above the binary bound.
;;   (a) fano_bits      sum over positions of the bound at p_i, minus the noise floor.
;;       noise floor    mean of the same sum over SHUF permutations of the secrets across attempts within the group
;;                      (fixed seed), as the Collusion analysis.
;;   (b) cp_floor_bits  the same sum with each p_i replaced by its Clopper-Pearson one-sided 97.5% lower bound
;;                      (n = attempts), minus the SAME noise floor as (a). cp_own_floor_bits subtracts instead the mean
;;                      CP sum over the same permutations (shown for transparency; usually ~0).
;;   (c) boot_lo/hi     percentile bootstrap 95% of (a): BOOT resamples of EPISODES (an episode's attempts share one
;;                      secret, so they are a cluster), each with its own noise floor from BSHUF permutations.
;; Independent recipe bits make the sum a lower bound on I(S; G) per attempt.
;;
;; Groups: as phone-tables.bb T_phone_m1.tsv -- every (label, arm), and for S every (channel, switchboard on/off).
;; Usage: bb fano-floor.bb <dir-with-phone*.jsonl> [out.tsv] [boot=1000] [shuf=1000] [bshuf=20] [seed=20261104]
;;        default out = <dir>/T_phone_M1_fano.tsv.  Read-only on its inputs.
;; Library: binary-entropy, fano-bit, cp-lower, fano-sum, attempts-of.

(ns swarm.fano-floor
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(when-not (find-ns 'swarm.phone-tables) (load-file (str (io/file here "phone-tables.bb"))))
(require '[swarm.phone-tables :as pt])

(def ln2 (Math/log 2))

;; ---------------------------------------------------------------- the bound

(defn binary-entropy
  "h(p) in bits; h(0) = h(1) = 0."
  [p]
  (if (or (<= p 0.0) (>= p 1.0)) 0.0
      (- (+ (* p (/ (Math/log p) ln2)) (* (- 1.0 p) (/ (Math/log (- 1.0 p)) ln2))))))

(defn fano-bit
  "Fano lower bound on I(S_i;G_i) for a uniform binary S_i at accuracy p: 1 - h(1 - p) when 1 - p <= 0.5, else 0."
  [p]
  (let [pe (- 1.0 p)] (if (<= pe 0.5) (- 1.0 (binary-entropy pe)) 0.0)))

(defn- log-choose [n k]
  (loop [j 0 acc 0.0] (if (= j k) acc (recur (inc j) (+ acc (Math/log (/ (double (- n j)) (inc j))))))))

(defn binom-upper-tail
  "P(X >= k), X ~ Binomial(n, p)."
  [k n p]
  (cond (<= k 0) 1.0 (> k n) 0.0 (<= p 0.0) 0.0 (>= p 1.0) 1.0
        :else (let [lr (- (Math/log p) (Math/log (- 1.0 p)))]
                ;; log pmf(j+1) = log pmf(j) + log((n-j)/(j+1)) + log(p/(1-p)): O(n) per tail
                (loop [j k lp (+ (log-choose n k) (* k (Math/log p)) (* (- n k) (Math/log (- 1.0 p)))) acc 0.0]
                  (if (> j n) acc
                      (recur (inc j) (+ lp (Math/log (/ (double (- n j)) (inc j))) lr) (+ acc (Math/exp lp))))))))

(defn cp-lower
  "Clopper-Pearson one-sided lower bound on a proportion at level 1 - alpha (alpha = 0.025 gives the 97.5% bound, the
  lower end of the two-sided 95% interval): the p with P(X >= k | n, p) = alpha; 0 when k = 0. Bisection, 200 steps."
  ([k n] (cp-lower k n 0.025))
  ([k n alpha]
   (if (zero? k) 0.0
       (loop [lo 0.0 hi 1.0 i 0]
         (if (= i 200) lo
             (let [mid (/ (+ lo hi) 2.0)]
               (if (< (binom-upper-tail k n mid) alpha) (recur mid hi (inc i)) (recur lo mid (inc i)))))))))

(def ^:private cp-lower-memo (memoize (fn [k n] (cp-lower k n))))

;; ---------------------------------------------------------------- attempts (phone-tables.bb m1-row, verbatim)

(defn attempts-of
  "recs: episodes of one group -> [{:s secret-bits :v guessed-bits :submitted bool :ep i}], exactly as phone-tables.bb
  m1-row (lines `attempts (vec (for [r recs rep (:receiver_repro_bits r)] ...))`), plus the episode index :ep."
  [recs]
  (let [R (:R (first recs))]
    (vec (for [[i r] (map-indexed vector recs) rep (:receiver_repro_bits r)]
           {:s (:recipe_bits r) :v (or rep (vec (repeat R 0))) :submitted (some? rep) :ep i}))))

(defn correct-counts
  "Per position, the number of attempts whose guessed bit equals the secret bit. ss, vs: parallel vectors of bit vectors."
  [ss vs R]
  (let [cnt (long-array R)]
    (dotimes [a (count ss)]
      (let [s (nth ss a) v (nth vs a)]
        (dotimes [i R] (when (= (nth s i) (nth v i)) (aset cnt i (inc (aget cnt i)))))))
    (vec cnt)))

(defn fano-sum
  "Sum over positions of fano-bit at each position's accuracy (or at its CP lower bound when cp? is true)."
  ([ss vs R] (fano-sum ss vs R false))
  ([ss vs R cp?]
   (let [n (count ss)]
     (if (zero? n) 0.0
         (reduce + 0.0 (for [k (correct-counts ss vs R)] (fano-bit (if cp? (cp-lower-memo k n) (/ k (double n))))))))))

(defn- shuffled [v ^java.util.Random rng]
  (let [a (java.util.ArrayList. ^java.util.Collection v)] (java.util.Collections/shuffle a rng) (vec a)))

(defn- mean [xs] (/ (reduce + 0.0 xs) (count xs)))

(defn noise-floors
  "Mean Fano sum and (when cp?) mean CP sum over `shuf` permutations of the secrets across attempts."
  [ss vs R shuf rng cp?]
  (let [xs (vec (for [_ (range shuf)] (let [p (shuffled ss rng)] [(fano-sum p vs R false) (when cp? (fano-sum p vs R true))])))]
    [(mean (map first xs)) (when cp? (mean (map second xs)))]))

(defn fano-row
  "One group -> M1 Fano fields."
  [recs {:keys [boot shuf bshuf seed]}]
  (let [R (:R (first recs))
        att (attempts-of recs)
        n (count att)]
    (if (zero? n)
      {:episodes (count recs) :attempts 0 :R R}
      (let [rng (java.util.Random. (long seed))
            ss (mapv :s att) vs (mapv :v att)
            counts (correct-counts ss vs R)
            acc (mapv #(/ % (double n)) counts)
            fano (fano-sum ss vs R false)
            cp (fano-sum ss vs R true)
            [floor cp-floor] (noise-floors ss vs R shuf rng true)
            by-ep (vec (vals (group-by :ep att)))
            n-ep (count by-ep)
            boots (vec (for [_ (range boot)]
                         (let [b (vec (mapcat (fn [_] (nth by-ep (.nextInt rng n-ep))) (range n-ep)))
                               bs (mapv :s b) bv (mapv :v b)]
                           (- (fano-sum bs bv R false) (first (noise-floors bs bv R bshuf rng false))))))]
        {:episodes (count recs) :attempts n :submitted (count (filter :submitted att)) :R R
         :mm_bits_leaked (pt/bits-leaked att R)
         :fano_raw_bits fano :noise_floor_bits floor :fano_bits (- fano floor)
         :cp_raw_bits cp :cp_floor_bits (- cp floor) :cp_own_floor_bits (- cp cp-floor)
         :boot_lo_bits (pt/quant boots 0.025) :boot_hi_bits (pt/quant boots 0.975)
         :positions_above_half (count (filter #(> % 0.5) acc))
         :positions_cp_above_half (count (filter #(> (cp-lower-memo % n) 0.5) counts))
         :mean_accuracy (mean acc)}))))

(def header [:label :arm :channel :switchboard :episodes :attempts :submitted :R :mm_bits_leaked
             :fano_raw_bits :noise_floor_bits :fano_bits :boot_lo_bits :boot_hi_bits
             :cp_raw_bits :cp_floor_bits :cp_own_floor_bits :positions_above_half :positions_cp_above_half :mean_accuracy])

(defn -main [& [dir out boot shuf bshuf seed]]
  (when-not dir
    (println "usage: bb fano-floor.bb <dir> [out.tsv] [boot=1000] [shuf=1000] [bshuf=20] [seed=20261104]") (System/exit 2))
  (let [opts {:boot (if boot (parse-long boot) 1000) :shuf (if shuf (parse-long shuf) 1000)
              :bshuf (if bshuf (parse-long bshuf) 20) :seed (if seed (parse-long seed) 20261104)}
        out (io/file (or out (io/file dir "T_phone_M1_fano.tsv")))
        recs (pt/read-records dir)
        groups (group-by (juxt :label :arm) recs)
        ;; the same groups as phone-tables.bb T_phone_m1.tsv
        m1-groups (concat (for [[[label arm] es] (sort groups)] [[label arm "all" "-"] es])
                          (for [[[label ch sbn] es] (sort (group-by (juxt :label :channel #(get-in % [:switchboard :bin])) (filter #(= "S" (:arm %)) recs)))]
                            [[label "S" ch (if (= "none" sbn) "off" "on")] es]))
        rows (vec (for [[[label arm ch sw] es] m1-groups]
                    (let [r (merge {:label label :arm arm :channel ch :switchboard sw} (fano-row es opts))]
                      (binding [*out* *err*] (println "done" label arm ch sw "attempts" (:attempts r)))
                      r)))]
    (pt/write-tsv! out header rows)
    (println "wrote" (str out) "from" (count recs) "episodes;" (pr-str opts))
    rows))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
