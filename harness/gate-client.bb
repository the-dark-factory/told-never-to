#!/usr/bin/env bb
;; gate-client.bb -- one running Swarm_Gate_Main per episode, spoken to one line at a time.
;;
;; Purpose: spawn the factory-built binary (probe 79/79; used as is,
;; never rebuilt), send `open K L W`, then one request line in -> its `reply ...` line and its
;; `event ...` line out. Every request, reply and event goes to a per-episode transcript (TSV).
;; The binary's sha256 is computed at spawn and returned with every episode; tables.bb reports it beside
;; the lane sha below, so a run on any other binary is visible in T1. (A hard refusal to run is verdict
;; logic; per the house rule it is not hand-coded here -- see README.)
;;
;; Protocol (read from swarm_gate_main.adb, confirmed by hand 2026-10-25):
;;   open K L W        -> reply opened K L W | reply refused bad_open (then the process exits)
;;   submit WRITER CODE-> reply admitted | reply refused not_enrolled|second_line|not_canonical
;;   end-epoch         -> reply released E N        (keeps the L lowest codes; event carries the dropped count)
;;   read E            -> reply epoch E N c1 .. cN | reply refused not_released
;;   operator-read     -> reply held E H p:writer:code ...   (operator view: arrival order and writers)
;; Each request yields exactly one reply line then one event line.
;;
;; Usage: (load-file ".../gate-client.bb") then open-gate / request! / close-gate!
;;        bb gate-client.bb smoke     a scripted 2-epoch exchange, printed

(ns swarm.gate-client
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def lane-binary "swarm_gate_main")
(def lane-sha256 "0cf8fa66a2d7224863e59b994adb1dfd4e1b83ae0fd5e00b666e5b5f42c5e0b1")
(def lane-job "redacted")

(defn- file-sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream (io/file f))]
      (let [buf (byte-array 65536)]
        (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur))))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn open-gate
  "Spawn the gate and send `open K L W`. Returns a handle map; :opened? false when the gate refused."
  [{:keys [K L W transcript binary] :or {binary lane-binary}}]
  (let [sha (file-sha256 binary)
        proc (p/process [binary] {:in :pipe :out :pipe :err :inherit})
        w (io/writer (:in proc))
        r (io/reader (:out proc))
        tw (when transcript (io/make-parents (io/file transcript)) (io/writer (io/file transcript)))
        h {:proc proc :w w :r r :tw tw :sha256 sha :lane? (= sha lane-sha256) :binary binary}
        _ (when tw (.write tw "dir\tline\n"))
        res (do (.write w (str "open " K " " L " " W "\n")) (.flush w)
                (let [rep (.readLine r) ev (.readLine r)]
                  (when tw (.write tw (str ">\topen " K " " L " " W "\n<\t" rep "\n!\t" ev "\n")) (.flush tw))
                  {:reply rep :event ev}))]
    (assoc h :opened? (= (:reply res) (str "reply opened " K " " L " " W)) :open-reply (:reply res))))

(defn request!
  "Send one request line; returns {:reply \"reply ...\" :event \"event ...\"}."
  [{:keys [w r tw]} line]
  (.write w (str line "\n")) (.flush w)
  (let [rep (.readLine r) ev (.readLine r)]
    (when tw (.write tw (str ">\t" line "\n<\t" rep "\n!\t" ev "\n")) (.flush tw))
    {:reply rep :event ev}))

(defn close-gate! [{:keys [proc w tw]}]
  (try (.close w) (catch Exception _))
  (when tw (.close tw))
  (let [rc (:exit @proc)] rc))

;; ---------------------------------------------------------------- reply parsing

(defn submit-word "reply admitted -> \"admitted\"; reply refused X -> \"refused X\"" [reply]
  (str/replace (or reply "") #"^reply " ""))

(defn parse-epoch-read "reply epoch E N c1..cN -> [c1..cN] (nil if not that shape)" [reply]
  (let [t (str/split (or reply "") #" ")]
    (when (and (= "reply" (first t)) (= "epoch" (second t)))
      (mapv parse-long (drop 4 t)))))

(defn parse-released "reply released E N -> {:epoch E :kept N}" [reply]
  (let [[_ w e n] (str/split (or reply "") #" ")]
    (when (= w "released") {:epoch (parse-long e) :kept (parse-long n)})))

(defn parse-end-event "event SEQ end-epoch BATCH DROPPED released ... -> dropped" [event]
  (let [t (str/split (or event "") #" ")]
    (when (= "end-epoch" (nth t 2 nil)) {:kept (parse-long (nth t 3)) :dropped (parse-long (nth t 4))})))

(defn parse-held "reply held E H p:w:c ... -> [{:pos :writer :code}]" [reply]
  (let [t (str/split (or reply "") #" ")]
    (when (= "held" (second t))
      (mapv (fn [x] (let [[a b c] (map parse-long (str/split x #":"))] {:pos a :writer b :code c})) (drop 4 t)))))

(defn -main [& [cmd]]
  (case cmd
    "smoke" (let [h (open-gate {:K 15 :L 2 :W 4 :transcript "logs/gate-smoke.tsv"})]
              (println "binary sha256" (:sha256 h) "lane?" (:lane? h) "opened?" (:opened? h))
              (doseq [line ["submit 1 3" "submit 1 4" "submit 2 0" "submit 3 1" "submit 4 2" "operator-read" "end-epoch" "read 1"
                            "submit 2 9" "end-epoch" "read 2" "read 3" "mutate delete 1"]]
                (let [res (request! h line)]
                  (println (format "%-16s %-40s %s" line (:reply res) (:event res)))))
              (println "exit" (close-gate! h)))
    (println "usage: bb gate-client.bb smoke")))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
