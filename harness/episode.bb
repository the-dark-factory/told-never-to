#!/usr/bin/env bb
;; episode.bb -- design s1: one episode's epoch loop against the real gate.
;;
;; Purpose: for e = 1..D: every agent gets one call (bounded concurrency), its reply is parsed strictly,
;; non-null posts go to the gate as `submit <label> <post>` in a seeded random order, the operator view
;; (`operator-read`) is logged, `end-epoch`, then `read e` gives the released line every agent sees next
;; epoch. The answer slot is private (harness only). Stop when every agent's answer is right (ttc = e)
;; or at e = D (censored).
;; Agents can be model calls (:arm :local | :deepseek) or a scripted function (:agent-fn) -- the MI
;; calibration uses scripted senders through this same loop and the same gate.
;;
;; Writes, under logs/<run-id>/ : calls.jsonl (per call: prompt sha256, reply text, parse, latency, tokens,
;; cost), gate.tsv (the gate transcript: requests, replies, events verbatim). Returns the episode record
;; (T1 fields plus per-epoch detail) and appends it to results/episodes.jsonl.
;;
;; Usage: (load-file ".../episode.bb") then (swarm.episode/run-episode {...})
;;        bb episode.bb <run-id> <arm> <task> <n> <K> <L> <D> <seed> <episode> [concurrency]

(ns swarm.episode
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]))

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(doseq [f ["model-client.bb" "game.bb" "prompt.bb" "gate-client.bb"]] (load-file (str (io/file here f))))
(require '[swarm.model-client :as mc] '[swarm.game :as g] '[swarm.prompt :as pr] '[swarm.gate-client :as gc])

(def episodes-file (io/file here "results" "episodes.jsonl"))

(defn call-seed [seed episode e a]
  (mod (.hashCode (str seed "|" episode "|" e "|" a)) 2147483647))

(defn- pmap-bounded [concurrency f xs]
  (let [sem (java.util.concurrent.Semaphore. concurrency)
        futs (mapv (fn [x] (future (.acquire sem) (try (f x) (finally (.release sem))))) xs)]
    (mapv deref futs)))

(defn run-episode
  ;; prompt-xf (fn [a prompt] -> prompt) and post-xf (fn [a post] -> post) are optional hooks (the MI planted-secret
  ;; cell adds the sender's secret and forces its content); extra is merged into the record. Without them: as before.
  [{:keys [run-id arm task n K L D seed episode concurrency agent-fn num-ctx reasoning-effort log-root write-record? episodes-path
           prompt-xf post-xf extra]
    :or {concurrency 1 reasoning-effort "off" write-record? true}}]
  (let [t0 (System/nanoTime)
        log-dir (io/file (or log-root (io/file here "logs")) run-id)
        _ (.mkdirs log-dir)
        calls-log (io/file log-dir "calls.jsonl")
        ep (g/make-episode {:seed seed :episode episode :task task :n n})
        has-shard? (some? (:shard-of ep))
        num-ctx (or num-ctx
                    (pr/num-ctx-for (count (.getBytes ^String (pr/render ep (:scout ep) (merge {:K K :L L :D D :e D} (pr/synthetic-state K L D 1))) "UTF-8"))))
        gate (gc/open-gate {:K K :L L :W n :transcript (io/file log-dir "gate.tsv")})
        _ (when-not (:opened? gate) (throw (ex-info "gate did not open" {:reply (:open-reply gate)})))
        agents (range 1 (inc n))]
    (loop [e 1 board [] own (zipmap agents (repeat [])) answers {} epochs [] tallies {:calls 0 :parse-failures 0 :cost 0.0}]
      (let [ctxs (for [a agents]
                   (let [p (pr/render ep a {:K K :L L :D D :e e :board board :own (own a) :answer (answers a)})]
                     {:a a :e e :prompt (if prompt-xf (prompt-xf a p) p)}))
            results (pmap-bounded
                     concurrency
                     (fn [{:keys [a prompt] :as ctx}]
                       (if agent-fn
                         (let [{:keys [post answer]} (agent-fn (assoc ctx :ep ep :board board :own (own a) :answer (answers a)))]
                           {:a a :parsed {:parsed? true :post post :answer answer} :call {:ok? true :latency-s 0.0 :cost-usd 0.0} :scripted? true})
                         (let [r (mc/chat {:arm arm :messages [{:role "user" :content prompt}] :seed (call-seed seed episode e a)
                                           :temperature 0.7 :num-ctx num-ctx :tag run-id :reasoning-effort reasoning-effort})
                               parsed (if (:ok? r) (pr/parse-reply (:text r) has-shard?) {:parsed? false :post nil :answer nil :why (:error r)})]
                           (mc/log-call! calls-log (merge {:run-id run-id :epoch e :agent a :prompt prompt
                                                           :parsed? (:parsed? parsed) :post (:post parsed) :answer (:answer parsed) :why (:why parsed)}
                                                          (dissoc r :retryable)))
                           {:a a :parsed parsed :call r})))
                     ctxs)
            budget-stop? (some #(= :budget (get-in % [:call :refused])) results)
            posts (into {} (for [{:keys [a parsed]} results
                                 :let [p (if post-xf (post-xf a (:post parsed)) (:post parsed))]
                                 :when (some? p)] [a p]))
            order (filterv posts (g/shuffled seed episode task n (str "order-" e) (vec agents)))
            replies (into {} (for [a order] [a (gc/submit-word (:reply (gc/request! gate (str "submit " a " " (posts a)))))]))
            held (gc/parse-held (:reply (gc/request! gate "operator-read")))
            end (gc/request! gate "end-epoch")
            dropped (:dropped (gc/parse-end-event (:event end)))
            kept (gc/parse-epoch-read (:reply (gc/request! gate (str "read " e))))
            board' (conj board kept)
            own' (reduce (fn [m a] (update m a conj {:epoch e :post (posts a) :reply (replies a)})) own agents)
            answers' (reduce (fn [m {:keys [a parsed]}] (if (:answer parsed) (assoc m a (:answer parsed)) m)) answers results)
            done? (g/all-correct? ep answers')
            n-correct (count (filter #(g/correct? ep % (answers' %)) agents))
            tallies' (-> tallies
                         (update :calls + (count (remove :scripted? results)))
                         (update :parse-failures + (count (filter #(not (get-in % [:parsed :parsed?])) results)))
                         (update :cost + (reduce + 0.0 (map #(or (get-in % [:call :cost-usd]) 0.0) results))))
            epoch-rec {:e e :order order :posts posts :replies replies :held held :kept kept :dropped dropped
                       :n-correct n-correct}
            epochs' (conj epochs epoch-rec)]
        (if (or done? (>= e D) budget-stop?)
          (let [rc (gc/close-gate! gate)
                all-replies (mapcat (comp vals :replies) epochs')
                rec {:run_id run-id :arm (name (or arm :scripted)) :task (name task) :n n :K K :L L :D D :seed seed :episode episode
                     :model (cond agent-fn "scripted" (= arm :local) mc/local-model :else mc/deepseek-model)
                     :reasoning_effort (when (= arm :deepseek) reasoning-effort)
                     :num_ctx (when (= arm :local) num-ctx)
                     :gate_sha256 (:sha256 gate) :gate_lane_binary (:lane? gate) :gate_exit rc
                     :H (:H ep) :floor_epochs (g/floor-epochs ep K L)
                     :epochs_used e :success done? :ttc (if done? e nil) :censored (not done?)
                     :aborted (when budget-stop? "deepseek budget cap")
                     :posts (count all-replies)
                     :admitted (count (filter #(= "admitted" %) all-replies))
                     :released (reduce + (map (comp count :kept) epochs'))
                     :dropped (reduce + (map #(or (:dropped %) 0) epochs'))
                     :refused_by_reason (frequencies (map #(str/replace % #"^refused " "") (remove #(= "admitted" %) all-replies)))
                     :calls (:calls tallies') :parse_failures (:parse-failures tallies')
                     :wall_s (/ (- (System/nanoTime) t0) 1e9) :cost_usd (:cost tallies')
                     :final_correct n-correct
                     :epochs epochs'}
                rec (merge rec extra)]
            (when write-record?
              (io/make-parents (io/file (or episodes-path episodes-file)))
              (spit (io/file (or episodes-path episodes-file)) (str (json/generate-string rec) "\n") :append true))
            rec)
          (recur (inc e) board' own' answers' epochs' tallies'))))))

(defn -main [& [run-id arm task n K L D seed episode concurrency]]
  (if-not episode
    (println "usage: bb episode.bb <run-id> <arm local|deepseek> <task> <n> <K> <L> <D> <seed> <episode> [concurrency]")
    (let [rec (run-episode {:run-id run-id :arm (keyword arm) :task (keyword task)
                            :n (parse-long n) :K (parse-long K) :L (parse-long L) :D (parse-long D)
                            :seed (parse-long seed) :episode (parse-long episode)
                            :concurrency (if concurrency (parse-long concurrency) 1)})]
      (prn (dissoc rec :epochs)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
