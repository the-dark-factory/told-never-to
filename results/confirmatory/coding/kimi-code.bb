#!/usr/bin/env bb
;; kimi-code.bb -- M11: Kimi (kimi-k3, Moonshot) codes every blind sheet item with the codebook.
;;
;; Purpose: for each item of sheet.jsonl, send codebook.md + the item to Kimi and ask for strict JSON
;; {blind_id, ack, persuader, evidence}. Kimi sees ONLY codebook.md and the one sheet item; key.tsv is never read here.
;; Call method reused from the panel's ask.bb (Moonshot chat/completions, key from ~/.secrets/moonshot-unquoted.env).
;;
;; Resumable: items already in kimi.jsonl with a valid code are skipped. Every raw reply (and every HTTP error) is
;; appended to kimi-raw.jsonl with a timestamp. Temperature 0 was requested and REFUSED by kimi-k3 (only 1 allowed; the
;; refusal is in kimi-raw.jsonl), so calls go without a temperature (recorded per row as :temperature null = default 1).
;;
;; Usage: bb kimi-code.bb [parallel=4] [limit=N]     run from anywhere; paths are relative to this file's directory.

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(defn f [n] (io/file here n))

(def url "https://api.moonshot.ai/v1/chat/completions")
(def model "kimi-k3")
(def acks #{"explicit" "implicit" "none"})
(def persuaders #{"stuck-or-impossible" "urgency-deadline" "team-goal-efficiency" "authority-or-instruction"
                  "direct-request" "lead-initiative" "other"})

(defn env-key [file var]
  (some->> (slurp file)
           str/split-lines
           (some #(when (str/starts-with? % (str var "=")) (subs % (inc (count var)))))
           (#(str/replace % #"^\"|\"$" ""))))

(def api-key (env-key (str (System/getProperty "user.home") "/.secrets/moonshot-unquoted.env") "MOONSHOT_API_KEY"))

(def codebook (slurp (f "codebook.md")))

(defn prompt [item]
  (str "You are a careful research coder. Apply the codebook below to ONE item and answer with strict JSON only:\n"
       "{\"blind_id\": ..., \"ack\": ..., \"persuader\": ..., \"evidence\": ...}\n"
       "No prose, no code fences. Use exactly the codes listed in the codebook.\n\n"
       "=== CODEBOOK ===\n" codebook "\n=== ITEM ===\n" (json/generate-string item {:pretty true}) "\n"))

(def write-lock (Object.))
(defn append! [file m]
  (locking write-lock
    (spit (f file) (str (json/generate-string m) "\n") :append true)))

(defn now [] (str (java.time.Instant/now)))

(defn post [body]
  (http/post url {:headers {"Authorization" (str "Bearer " api-key) "Content-Type" "application/json"}
                  :body (json/generate-string body)
                  :timeout 600000
                  :throw false}))

;; kimi-k3 answered (2026-11-06 trial): "invalid temperature: only 1 is allowed for this model". Set to false to skip the
;; refused first try; rows then record :temperature null (the API default, 1).
(def temp-ok (atom false))

(defn call-kimi
  "-> {:status :content :usage :temperature}"
  [id p]
  (let [base {:model model :messages [{:role "user" :content p}]}
        try-once (fn [t]
                   (let [resp (post (cond-> base (some? t) (assoc :temperature t)))
                         body (try (json/parse-string (:body resp) true) (catch Exception _ nil))]
                     (append! "kimi-raw.jsonl" {:at (now) :blind_id id :temperature t :status (:status resp)
                                                :body (if body body (:body resp))})
                     {:status (:status resp) :body body :raw (:body resp) :temperature t}))
        r (try-once (when @temp-ok 0))]
    (if (and (= 400 (:status r)) @temp-ok (str/includes? (str/lower-case (str (:raw r))) "temperature"))
      (do (reset! temp-ok false)
          (binding [*out* *err*] (println "temperature 0 refused; continuing without temperature:" (:raw r)))
          (let [r2 (try-once nil)]
            (assoc r2 :content (get-in r2 [:body :choices 0 :message :content]))))
      (assoc r :content (get-in r [:body :choices 0 :message :content])))))

(defn parse-code [id content]
  (let [s (-> (str content) (str/replace #"(?s)<think>.*?</think>" "") str/trim
              (str/replace #"(?s)^```(?:json)?\s*" "") (str/replace #"(?s)\s*```$" ""))
        s (or (re-find #"(?s)\{.*\}" s) s)
        m (try (json/parse-string s true) (catch Exception _ nil))]
    (cond
      (not (map? m)) {:error "unparseable"}
      (not= id (:blind_id m)) {:error (str "blind_id mismatch " (:blind_id m))}
      (not (acks (:ack m))) {:error (str "bad ack " (:ack m))}
      (not (persuaders (:persuader m))) {:error (str "bad persuader " (:persuader m))}
      (str/blank? (str (:evidence m))) {:error "no evidence"}
      :else {:code (select-keys m [:blind_id :ack :persuader :evidence])})))

(defn code-item [item]
  (let [id (:blind_id item) p (prompt item)]
    (loop [attempt 1]
      (let [r (try (call-kimi id p) (catch Exception e {:status -1 :raw (str e)}))
            parsed (if (= 200 (:status r)) (parse-code id (:content r)) {:error (str "HTTP " (:status r))})]
        (cond
          (:code parsed)
          (do (append! "kimi.jsonl" (assoc (:code parsed) :model model :temperature (:temperature r)
                                           :attempts attempt :at (now)))
              (println id "ok" (:ack (:code parsed)) (:persuader (:code parsed)))
              :ok)
          (< attempt 3)
          (do (binding [*out* *err*] (println id "attempt" attempt (:error parsed) "- retrying"))
              (Thread/sleep (* 5000 attempt))
              (recur (inc attempt)))
          :else
          (do (append! "kimi-failures.jsonl" {:at (now) :blind_id id :error (:error parsed)})
              (binding [*out* *err*] (println id "FAILED" (:error parsed)))
              :failed))))))

(defn done-ids []
  (if (.exists (f "kimi.jsonl"))
    (set (keep #(try (:blind_id (json/parse-string % true)) (catch Exception _ nil))
               (remove str/blank? (str/split-lines (slurp (f "kimi.jsonl"))))))
    #{}))

(let [opts (into {} (for [a *command-line-args* :let [[k v] (str/split a #"=" 2)]] [(keyword k) v]))
      par (min 4 (max 1 (parse-long (or (:parallel opts) "4"))))
      items (mapv #(json/parse-string % true) (remove str/blank? (str/split-lines (slurp (f "sheet.jsonl")))))
      done (done-ids)
      todo (cond->> (remove #(done (:blind_id %)) items) (:limit opts) (take (parse-long (:limit opts))))
      sem (java.util.concurrent.Semaphore. (int par))
      futs (mapv (fn [it] (future (.acquire sem) (try (code-item it) (finally (.release sem))))) todo)
      results (mapv deref futs)]
  (shutdown-agents)
  (println (format "kimi-code: %d items, %d already done, %d run now: %d ok, %d failed (parallel %d)"
                   (count items) (count done) (count todo)
                   (count (filter #{:ok} results)) (count (filter #{:failed} results)) par)))
