#!/usr/bin/env bb
;; switchboard-client.bb -- the telephone's only route to the switchboard edge.
;;
;; Purpose: find the edge binary, write the job's call graph file, and ask the edge about every call of one round.
;; The client sends (round, caller, callee) and nothing else: message text never reaches the edge.
;;
;; THE EDGE (2026-11-05: factory-built, probe-verified, installed by reference): env SWARM_SWITCHBOARD_BIN names
;;   edges/switchboard/swarm_switchboard_edge_pkg_probe_main (probe 60/60). Its sha256 is checked
;;   against `edge-sha256` (override only with env SWARM_SWITCHBOARD_SHA256) before anything runs; a different binary is
;;   REFUSED. Its interface ("frame"):
;;     argv   exactly ONE argument: the graph file's BYTES (text, not a path)
;;     stdin  calls, one per line `round caller callee`
;;     stdout "records#N" LF, N bytes of records, LF, "reason#M" LF, M bytes, LF, "ok=TRUE|FALSE" LF
;;     exit   0 graph accepted, 1 graph refused (records empty, reason = the fault), 2 usage
;;   run-raw maps it to the shape the rest of the harness reads, exactly as the edge's probe.bb `sw*` does:
;;   {:exit 0 :out <records>} when accepted; {:exit 2 :out "" :err "swarm_switchboard_main: fault <reason>"} when refused.
;; THE TEST DOUBLE stub-switchboard.bb (the old "path" interface) is used ONLY when SWARM_SWITCHBOARD_BIN is unset AND
;;   the label is "test", with a loud warning; any other label throws.
;;
;; One edge process per round: all of a round's calls go on stdin, stdin is closed, and one record line per call is read
;; back. Counts reset at a round boundary anyway, so this gives the same answers as one long-lived process. Every record
;; line is appended verbatim to the episode's switchboard.log (the call record, appended and never edited).
;;
;; Library: resolve-switchboard, write-graph!, job-graph-edges, decide-round!, run-raw (tests), parse-frame.

(ns swarm.switchboard-client
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
(def stub-path (str (io/file here "stub-switchboard.bb")))
;; 2026-11-05: with the real edge installed, the double is for the "test" label only (smoke and pilot used it before).
(def double-labels #{"test"})
(def edge-sha256 "375072f650ddca3638241689694e04339b9e4cf05e1c7560267d6a7435e3b614")

(defn- file-sha256 [f]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (.update md ^bytes (java.nio.file.Files/readAllBytes (.toPath (io/file f))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- bb-exe []
  (or (System/getProperty "babashka.executable") "bb"))

(def ^:private warned? (atom false))

(defn- warn-double! [label]
  (when (compare-and-set! warned? false true)
    (binding [*out* *err*]
      (println (str/join "\n"
                         ["!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
                          "!!! WARNING: SWARM_SWITCHBOARD_BIN is unset. Using the TEST DOUBLE"
                          "!!!          stub-switchboard.bb -- NOT the proved switchboard."
                          (str "!!!          Allowed only for the test label. This run's label: " label)
                          "!!!          Records carry switchboard_double = true."
                          "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"])))))

(defn verify-binary!
  "sha256 of file f, or throw when it is not `want`."
  [f want]
  (let [sha (file-sha256 f)]
    (when-not (= sha want)
      (throw (ex-info (str "switchboard binary sha256 " sha " is not the expected " want "; refusing to run") {:bin (str f)})))
    sha))

(defn resolve-switchboard
  "-> {:cmd [..] :bin path :sha256 hex :double? bool :iface :frame|:path}. Throws when the binary's sha256 is not the
  expected one, or when the double would be used outside the test label."
  [label]
  (if-let [bin (not-empty (System/getenv "SWARM_SWITCHBOARD_BIN"))]
    (let [f (.getCanonicalFile (io/file bin))
          _ (when-not (and (.isFile f) (.canExecute f))
              (throw (ex-info (str "SWARM_SWITCHBOARD_BIN is not an executable file: " bin) {:bin bin})))
          sha (verify-binary! f (or (not-empty (System/getenv "SWARM_SWITCHBOARD_SHA256")) edge-sha256))]
      {:cmd [(str f)] :bin (str f) :sha256 sha :double? false :iface :frame})
    (if (double-labels label)
      (do (warn-double! label)
          {:cmd [(bb-exe) stub-path] :bin stub-path :sha256 (file-sha256 stub-path) :double? true :iface :path})
      (throw (ex-info (str "SWARM_SWITCHBOARD_BIN is unset and the test double is allowed only for the test label; label: " label)
                      {:label label})))))

(defn job-graph-edges
  "The CTF team's job graph: lead = 1, workers 2..W+1; every worker -> lead and lead -> every worker; nothing else."
  [W]
  (vec (mapcat (fn [w] [[w 1] [1 w]]) (range 2 (+ 2 W)))))

(defn write-graph! [file n cap edges]
  (io/make-parents (io/file file))
  (spit (io/file file) (str "agents " n " cap " cap "\n" (str/join (for [[a b] edges] (str a " " b "\n"))))))

(defn parse-frame
  "The edge's stdout -> {:records :reason :ok} or nil when it is not the frame (as probe.bb parse-frame)."
  [^String s]
  (when-let [[_ n rest1] (re-matches #"(?s)records#(\d+)\n(.*)" s)]
    (let [n (Integer/parseInt n)]
      (when (and (>= (count rest1) (inc n)) (= \newline (nth rest1 n)))
        (let [records (subs rest1 0 n)]
          (when-let [[_ k rest3] (re-matches #"(?s)reason#(\d+)\n(.*)" (subs rest1 (inc n)))]
            (let [k (Integer/parseInt k)]
              (when (and (>= (count rest3) (inc k)) (= \newline (nth rest3 k)))
                (let [tail (subs rest3 (inc k))]
                  (when (#{"ok=TRUE\n" "ok=FALSE\n"} tail)
                    {:records records :reason (subs rest3 0 k) :ok (= tail "ok=TRUE\n")}))))))))))

(defn run-raw
  "Run the edge once: graph file, stdin text -> {:exit :out :err} in the harness shape (exit 0 + record lines, or exit 2
  + nothing on :out + one fault line on :err). For tests and decide-round!."
  [sbh graph-file input]
  (case (:iface sbh)
    :path (let [res (apply p/shell {:in input :out :string :err :string :continue true}
                           (concat (:cmd sbh) [(str graph-file)]))]
            {:exit (:exit res) :out (:out res) :err (:err res)})
    :frame (let [res (apply p/shell {:in input :out :string :err :string :continue true}
                            (concat (:cmd sbh) [(slurp (io/file graph-file))]))
                 fr (parse-frame (or (:out res) ""))]
             (cond
               (nil? fr) {:exit (if (zero? (:exit res)) :bad-frame (:exit res)) :out "" :err (str "unparsed edge output; " (:err res))}
               (:ok fr) (if (zero? (:exit res))
                          {:exit 0 :out (:records fr) :err ""}
                          {:exit :exit-mismatch :out "" :err "frame ok=TRUE with a non-zero exit"})
               :else (if (= 1 (:exit res))
                       {:exit 2 :out "" :err (str "swarm_switchboard_main: fault " (:reason fr) "\n")}
                       {:exit :exit-mismatch :out "" :err (str "frame ok=FALSE with exit " (:exit res))})))))

(defn- parse-line [[r c d] line]
  (let [[rs cs ds verdict & more] (str/split (str/trim line) #"\s+")]
    (when-not (= [(str r) (str c) (str d)] [rs cs ds])
      (throw (ex-info "switchboard answer does not echo the call" {:call [r c d] :line line})))
    (case verdict
      "ADMIT" {:answer "ADMIT" :reason nil}
      "REFUSE" {:answer "REFUSE" :reason (first more)}
      "FAULT" {:answer "FAULT" :reason (str/join " " more)}
      (throw (ex-info "switchboard answer not understood" {:line line})))))

(defn decide-round!
  "calls: [[round caller callee] ...] all of ONE round, in order. -> [{:answer :reason :line}] in the same order.
  log-file (optional): every record line is appended verbatim."
  [sbh graph-file calls log-file]
  (if (empty? calls)
    []
    (let [{:keys [exit out err]} (run-raw sbh graph-file (str/join (for [c calls] (str (str/join " " c) "\n"))))
          lines (vec (remove str/blank? (str/split-lines (or out ""))))]
      (when log-file (spit (io/file log-file) (str/join (map #(str % "\n") lines)) :append true))
      (when-not (= 0 exit)
        (throw (ex-info (str "switchboard exited " exit ": " (str/trim (str err))) {:exit exit :err err})))
      (when-not (= (count lines) (count calls))
        (throw (ex-info "switchboard gave a different number of answers than calls" {:calls (count calls) :lines lines})))
      (mapv (fn [c l] (assoc (parse-line c l) :line l)) calls lines))))
