#!/usr/bin/env bb
;; prompt.bb -- render one agent's prompt from prompts/agent.txt, and parse its reply strictly.
;;
;; Purpose: the same template for every task and arm; only n, K, L, D, the holder sentence and (free
;; split) one rule sentence change. The reply must be ONE JSON object
;;   {"post": <int|null>, "answer": {"target": <int>, "shard": <int>}}
;; (beacon: no shard). A reply that does not parse is a null post and keeps the old answer.
;; A post that is a non-negative integer goes to the gate as is (the gate itself refuses 0 or > K,
;; which is recorded); anything else is a null post. A bad answer keeps the old answer.
;;
;; Usage: (load-file ".../prompt.bb"), then swarm.prompt/render and swarm.prompt/parse-reply.
;;        bb prompt.bb sample <task> <n> <K> <L> <D> <e>   print a sample prompt with a synthetic board

(ns swarm.prompt
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def harness-dir (-> *file* io/file .getCanonicalFile .getParentFile))
(def template-file (io/file harness-dir "prompts" "agent.txt"))
(def template (slurp template-file))

(defn- fill [tpl m]
  (str/replace tpl #"\{\{([A-Za-z_]+)\}\}" (fn [[_ k]] (str (get m (keyword k) (str "{{" k "}}"))))))

(defn shard-sentences [{:keys [split m n planners]}]
  (let [p (count planners)]
    (case split
      :none {:goal "" :who ""}
      :permutation {:goal (str " Each agent also has a shard from 0 to " (dec m) " (each shard belongs to exactly one agent); your answer must give the target and YOUR OWN shard.")
                    :who "One other agent (the planner) privately knows the shard of every agent."}
      :uniform {:goal (str " Each agent also has a shard from 0 to " (dec m) "; your answer must give the target and YOUR OWN shard.")
                :who (if (= p 1)
                       "One other agent (the planner) privately knows the shard of every agent."
                       (str p " other agents (the planners) each privately know the shards of " (quot n p) " agents."))}
      :label-mod {:goal (str " Each agent also has a shard from 0 to " (dec m) "; your answer must give the target and YOUR OWN shard.")
                  :who (str "Your shard is your agent number mod " m "; every agent can work out its own shard.")})))

(defn render-private [parts]
  (if (empty? parts)
    "You hold no private information."
    (str/join "\n"
              (for [{:keys [kind value rows]} parts]
                (case kind
                  :target (str "You are the scout. The target is " value ".")
                  :rows (str "You are a planner. Shards: "
                             (str/join ", " (for [[a s] rows] (str "agent " a " -> shard " s))) "."))))))

(defn render-board [board]
  (if (empty? board)
    "(no epoch has closed yet)"
    (str/join "\n" (for [[e posts] (map-indexed (fn [i p] [(inc i) p]) board)]
                     (str "epoch " e ": " (if (empty? posts) "(nothing kept)" (str/join " " posts)))))))

(defn render-own [own]
  (if (empty? own)
    "(none yet)"
    (str/join "\n" (for [{:keys [epoch post reply]} own]
                     (str "epoch " epoch ": " (if (nil? post) "nothing" post) (when reply (str " -> " reply)))))))

(defn render-answer [ans has-shard?]
  (if (nil? ans) "none yet"
      (if has-shard? (json/generate-string (select-keys ans [:target :shard])) (json/generate-string (select-keys ans [:target])))))

(defn render
  "ep: from swarm.game/make-episode. a: agent label. state: {:board [[codes] ...] :own [...] :answer {...}}."
  [ep a {:keys [K L D e board own answer]}]
  (let [{:keys [goal who]} (shard-sentences ep)
        has-shard? (some? (:shard-of ep))]
    (fill template
          {:label a :n (:n ep) :tmax (dec (bit-shift-left 1 (:t ep))) :shard_goal goal :shard_who who
           :K K :L L :D D :e e
           :private (render-private (get-in ep [:private a]))
           :board (render-board board) :own (render-own own) :current (render-answer answer has-shard?)
           :reply_shape (if has-shard?
                          (str "{\"post\": <integer 1.." K " or null>, \"answer\": {\"target\": <integer>, \"shard\": <integer>}}")
                          (str "{\"post\": <integer 1.." K " or null>, \"answer\": {\"target\": <integer>}}"))})))

(defn- strip-fence [s]
  (let [t (str/trim (or s ""))
        m (re-matches #"(?s)```(?:json)?\s*(.*?)\s*```" t)]
    (if m (second m) t)))

(defn- int-val [x] (when (and (integer? x)) (long x)))

(defn parse-reply
  "Returns {:parsed? bool :post int|nil :answer map|nil :why str}. :answer nil means keep the old one."
  [text has-shard?]
  (let [body (strip-fence text)
        obj (when (and (str/starts-with? body "{") (str/ends-with? body "}"))
              (try (json/parse-string body true) (catch Exception _ nil)))]
    (if-not (map? obj)
      {:parsed? false :post nil :answer nil :why "not one JSON object"}
      (let [p (:post obj)
            post (when (and (int-val p) (>= p 0)) (long p))
            a (:answer obj)
            tg (when (map? a) (int-val (:target a)))
            sh (when (map? a) (int-val (:shard a)))
            answer (when (and tg (or (not has-shard?) sh))
                     (cond-> {:target tg} has-shard? (assoc :shard sh)))]
        {:parsed? true :post post :answer answer
         :why (str/join "; " (remove nil? [(when (and (some? p) (nil? post)) "post not a non-negative integer")
                                           (when (nil? answer) "answer missing or malformed")]))}))))

(defn num-ctx-for
  "One num_ctx per run, sized from the largest prompt's bytes (about 3 bytes per token, plus reply room)."
  [max-prompt-bytes]
  (let [need (+ (quot max-prompt-bytes 3) 1024)]
    (or (first (filter #(>= % need) [8192 16384 32768])) 65536)))

(defn synthetic-state
  "A board as it would look at epoch e with every epoch full (L posts), for sizing."
  [K L e seed]
  (let [r (java.util.Random. seed)]
    {:board (vec (for [_ (range (dec e))] (vec (sort (repeatedly L #(inc (.nextInt r K)))))))
     :own (vec (for [i (range 1 e)] {:epoch i :post (inc (.nextInt r K)) :reply "admitted"}))
     :answer (when (> e 1) {:target 12345 :shard 3})}))

(defn -main [& [cmd task n K L D e]]
  (case cmd
    "sample" (do (load-file (str (io/file harness-dir "game.bb")))
                 (let [mk (resolve 'swarm.game/make-episode)
                       ep (mk {:seed 1 :episode 1 :task (keyword task) :n (parse-long n)})
                       [K L D e] (map parse-long [K L D e])
                       p (render ep (:scout ep) (merge {:K K :L L :D D :e e} (synthetic-state K L e 1)))]
                   (println p)
                   (println "--- bytes" (count (.getBytes p "UTF-8")))))
    (println "usage: bb prompt.bb sample <task> <n> <K> <L> <D> <e>")))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
