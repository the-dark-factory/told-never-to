#!/usr/bin/env bb
;; model-client.bb -- one model call, local (ollama /api/chat through the ssh tunnel) or DeepSeek.
;;
;; Purpose: the only place the harness talks to a model. Every call returns a map
;;   {:ok? :text :latency-s :prompt-tokens :output-tokens :cost-usd :error ...}
;; and can be appended to a per-call JSONL log (prompt sha256, reply text, latency, tokens, cost).
;;
;; DeepSeek: the key is read from ~/.secrets/deepseek_api_key at call time, sent ONLY as the
;; Authorization header, never logged or printed. A cost ledger (TSV, one row per call) sums
;; reported usage x the price recorded below; a call is REFUSED before it is sent when the ledger
;; total has reached the cap (env SWARM_DEEPSEEK_CAP_USD, default 5.00 for the build).
;;
;; Usage (CLI):
;;   bb model-client.bb ping-local            one tiny call to the local model, prints the stats
;;   bb model-client.bb ps                    which model the tunnel's ollama has resident
;;   bb model-client.bb ledger                DeepSeek ledger total so far
;;   bb model-client.bb balance               DeepSeek account balance (GET /user/balance)
;; Library: (load-file ".../model-client.bb") then swarm.model-client/chat.

(ns swarm.model-client
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def harness-dir (-> *file* io/file .getCanonicalFile .getParentFile))

;; SWARM_LOCAL_URL overrides (2026-11-02: the ad-hoc :11435 tunnel was down; the standing launchd tunnel
;; com.darkfactory.gertrude-ollama-tunnel, 127.0.0.1:21434 -> gertrude:11434, serves the same qwen3.8:27b digest 22130167).
(def local-url (or (System/getenv "SWARM_LOCAL_URL") "http://127.0.0.1:11435"))
(def local-model "qwen3.8:27b")
(def local-timeout-s 120)

(def deepseek-url "https://api.deepseek.com")
(def deepseek-model "deepseek-v4-pro")
(def deepseek-key-file (io/file (System/getProperty "user.home") ".secrets" "deepseek_api_key"))
(def deepseek-timeout-s 300)

;; Price read from https://api-docs.deepseek.com/quick_start/pricing/ on 2026-10-25.
;; USD per 1M tokens. The harness charges PEAK rates always (an upper bound; off-peak is half).
(def deepseek-price
  {:read-date "2026-10-25"
   :source "api-docs.deepseek.com/quick_start/pricing (peak column; off-peak is half)"
   :in-cache-hit 0.044 :in-cache-miss 1.32 :out 3.96})

(def ledger-file (io/file harness-dir "results" "deepseek_ledger.tsv"))

(defn deepseek-cap []
  (if-let [v (System/getenv "SWARM_DEEPSEEK_CAP_USD")] (Double/parseDouble v) 5.0))

(defn sha256-hex [^String s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (.getBytes s "UTF-8"))))))

(defn file-sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream (io/file f))]
      (let [buf (byte-array 65536)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n) (.update md buf 0 n) (recur))))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

;; ---------------------------------------------------------------- ledger

(def ^:private ledger-lock (Object.))

(defn ledger-total []
  (locking ledger-lock
    (if (.exists ledger-file)
      (->> (str/split-lines (slurp ledger-file))
           (remove #(or (str/blank? %) (str/starts-with? % "ts\t")))
           (map #(Double/parseDouble (nth (str/split % #"\t") 5)))
           (reduce + 0.0))
      0.0)))

(defn- ledger-append! [{:keys [tag hit miss out cost]}]
  (locking ledger-lock
    (io/make-parents ledger-file)
    (when-not (.exists ledger-file)
      (spit ledger-file "ts\ttag\tin_cache_hit\tin_cache_miss\tout\tcost_usd\n"))
    (spit ledger-file (str (java.time.Instant/now) "\t" (or tag "-") "\t" hit "\t" miss "\t" out "\t"
                           (format "%.6f" cost) "\n") :append true)))

(defn deepseek-cost [usage]
  (let [hit (or (:prompt_cache_hit_tokens usage) 0)
        miss (or (:prompt_cache_miss_tokens usage)
                 (- (or (:prompt_tokens usage) 0) hit))
        out (or (:completion_tokens usage) 0)
        p deepseek-price]
    {:hit hit :miss miss :out out
     :cost (/ (+ (* hit (:in-cache-hit p)) (* miss (:in-cache-miss p)) (* out (:out p))) 1e6)}))

;; ---------------------------------------------------------------- calls

(defn- now-s [] (/ (System/nanoTime) 1e9))

(defn- with-retries [n f]
  (loop [i 1]
    (let [r (try (f) (catch Exception e {:ok? false :error (str (class e) ": " (.getMessage e)) :retryable true}))]
      (if (and (not (:ok? r)) (:retryable r) (< i n))
        (do (Thread/sleep (* 2000 i)) (recur (inc i)))
        (assoc r :attempts i)))))

(defn ollama-chat
  "One /api/chat call (non-streaming). opts: :messages :seed :temperature :num-ctx :model :num-predict :think.
  :think defaults to false (thinking off; request body as before). A model that cannot turn thinking off (gpt-oss)
  takes \"low\"|\"medium\"|\"high\" (or true), sent as the `think` field. The reply's message.thinking comes back as
  :thinking (nil when absent)."
  [{:keys [messages seed temperature num-ctx model num-predict think]
    :or {temperature 0.7 num-ctx 8192 num-predict 256 think false}}]
  (with-retries 3
    (fn []
      (let [t0 (now-s)
            body {:model (or model local-model) :stream false :think think :messages messages
                  :options (cond-> {:num_ctx num-ctx :temperature temperature :num_predict num-predict}
                             seed (assoc :seed seed))}
            resp (http/post (str local-url "/api/chat")
                            {:headers {"Content-Type" "application/json"}
                             :body (json/generate-string body)
                             :timeout (* 1000 local-timeout-s)
                             :throw false})
            dt (- (now-s) t0)]
        (if (= 200 (:status resp))
          (let [b (json/parse-string (:body resp) true)]
            (cond-> {:ok? true :text (get-in b [:message :content]) :latency-s dt
                     :prompt-tokens (:prompt_eval_count b) :output-tokens (:eval_count b)
                     :load-s (some-> (:load_duration b) (/ 1e9)) :cost-usd 0.0
                     :model (:model b)}
              ;; present only when the reply has it, so calls logs without thinking are unchanged
              (some? (get-in b [:message :thinking])) (assoc :thinking (get-in b [:message :thinking]))))
          {:ok? false :latency-s dt :error (str "HTTP " (:status resp) " " (subs (str (:body resp)) 0 (min 300 (count (str (:body resp))))))
           :retryable (>= (or (:status resp) 500) 500)})))))

(defn deepseek-chat
  "One chat/completions call. Refuses (no request sent) when the ledger total >= cap."
  [{:keys [messages temperature max-tokens tag reasoning-effort] :or {temperature 0.7 max-tokens 8192 reasoning-effort "low"}}]
  (let [spent (ledger-total) cap (deepseek-cap)]
    (if (>= spent cap)
      {:ok? false :refused :budget :error (format "DeepSeek budget cap reached: ledger $%.4f >= cap $%.2f" spent cap)}
      (with-retries 3
        (fn []
          (let [key (str/trim (slurp deepseek-key-file))
                t0 (now-s)
                body (cond-> {:model deepseek-model :messages messages :temperature temperature
                             :max_tokens max-tokens :stream false}
                       ;; reasoning-effort "off" sends thinking disabled; otherwise thinking on at that effort
                       (= reasoning-effort "off") (assoc :thinking {:type "disabled"})
                       (not= reasoning-effort "off") (assoc :reasoning_effort reasoning-effort))
                resp (http/post (str deepseek-url "/chat/completions")
                                {:headers {"Content-Type" "application/json"
                                           "Authorization" (str "Bearer " key)}
                                 :body (json/generate-string body)
                                 :timeout (* 1000 deepseek-timeout-s)
                                 :throw false})
                dt (- (now-s) t0)]
            (if (= 200 (:status resp))
              (let [b (json/parse-string (:body resp) true)
                    usage (:usage b)
                    {:keys [cost] :as c} (deepseek-cost usage)]
                (ledger-append! (assoc c :tag tag))
                {:ok? true :text (get-in b [:choices 0 :message :content]) :latency-s dt
                 :prompt-tokens (:prompt_tokens usage) :output-tokens (:completion_tokens usage)
                 :reasoning-tokens (get-in usage [:completion_tokens_details :reasoning_tokens])
                 :cache-hit-tokens (:hit c) :cost-usd cost :model (:model b)})
              {:ok? false :latency-s dt
               :error (str "HTTP " (:status resp) " " (subs (str (:body resp)) 0 (min 300 (count (str (:body resp))))))
               :retryable (or (nil? (:status resp)) (= 429 (:status resp)) (>= (:status resp) 500))})))))))

(defn chat
  "Dispatch on :arm (:local or :deepseek)."
  [{:keys [arm] :as opts}]
  (case arm
    :local (ollama-chat opts)
    :deepseek (deepseek-chat opts)))

(def ^:private log-lock (Object.))

(defn log-call!
  "Append one JSONL record for a call. Never includes any key."
  [log-path {:keys [prompt] :as rec}]
  (locking log-lock
    (io/make-parents (io/file log-path))
    (spit (io/file log-path)
          (str (json/generate-string (-> rec (dissoc :prompt) (assoc :prompt-sha256 (sha256-hex (or prompt ""))))) "\n")
          :append true)))

(defn ollama-ps []
  (let [r (http/get (str local-url "/api/ps") {:timeout 10000 :throw false})]
    (when (= 200 (:status r))
      (mapv #(select-keys % [:name :digest :context_length :size_vram])
            (:models (json/parse-string (:body r) true))))))

(defn deepseek-balance []
  (let [key (str/trim (slurp deepseek-key-file))
        r (http/get (str deepseek-url "/user/balance")
                    {:headers {"Authorization" (str "Bearer " key)} :timeout 20000 :throw false})]
    (if (= 200 (:status r)) (json/parse-string (:body r) true) {:error (:status r)})))

(defn -main [& [cmd]]
  (case cmd
    "ping-local" (prn (dissoc (ollama-chat {:messages [{:role "user" :content "Reply with the JSON {\"ok\":1} only."}]
                                            :seed 1 :num-ctx 8192}) :retryable))
    "ps" (prn (ollama-ps))
    "ledger" (println (format "DeepSeek ledger total: $%.4f (cap $%.2f)" (ledger-total) (deepseek-cap)))
    "balance" (prn (deepseek-balance))
    (println "usage: bb model-client.bb ping-local|ps|ledger|balance")))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
