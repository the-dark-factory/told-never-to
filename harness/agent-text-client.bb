#!/usr/bin/env bb
;; agent-text-client.bb -- the telephone's only route from an agent's text to another agent's prompt.
;;
;; Purpose: every piece of agent-authored text that a prompt shows (call messages, store keys AND values, noticeboard
;; notes, submissions) is first sent to the agent-text edge, and only the edge's PLAIN text is pasted. The edge decides
;; every byte: which byte sequences open a chat-control turn ("<|", "<" + U+FF5C, <think>, </think>, <tool_call>,
;; </tool_call>) and which are line breaks (LF VT FF CR, NEL, U+2028, U+2029), and what replaces them ("[" for the "<"
;; of an opener, one space per byte of a break). This client decides nothing about the text: it moves bytes and checks
;; the frame.
;;
;; THE EDGE (2026-11-05): the agent-text edge, factory-built and probe-verified,
;;   probe 148/148, over the PROVED core Agent_Text_Pkg (ADMITTED DF 1748). Installed BY REFERENCE (path + sha256), not
;;   ledger-admitted: edges are the glue gate (built, probed, not proved).
;;   edges/agent-text/agent_text_edge_pkg_probe_main, sha256 `edge-sha256` below.
;;   Env AGENT_TEXT_BIN may name another path, but its bytes must have the SAME sha256: there is no sha override.
;;   The verified bytes are copied once into a private temporary directory and that copy is what runs, so the file
;;   cannot be swapped between the check and a run.
;;   Its interface ("frame"):
;;     argv   none
;;     stdin  the text's bytes (UTF-8), at most 4096
;;     stdout "plain#N" LF, N bytes, LF, "reason#M" LF, M bytes, LF, "marker=TRUE|FALSE" LF, "line_break=TRUE|FALSE" LF,
;;            "ok=TRUE|FALSE" LF
;;     exit   0 accepted; 1 refused (over 4096 bytes: reason input-too-long, plain empty; NEVER cut); 2 usage
;; JSON: the reply parser (Cheshire) has already decoded JSON escapes, so "\n" or " " in a reply are REAL bytes here,
;;   which is what the edge needs (Agent_Text_Pkg CLAIMS N1: neutralise AFTER JSON-unescaping).
;; A refused text is never relayed: the prompt shows the harness's own words "(text withheld: <reason>)".
;; Any frame that does not parse, an exit that does not match ok=, or a plain text of another length THROWS (the episode
;; loop then writes an aborted record): this client never falls back to the raw text.
;;
;; Library: resolve-agent-text, run-raw, parse-frame, neutralise, display, summary.

(ns swarm.agent-text-client
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def installed-bin "edges/agent-text/agent_text_edge_pkg_probe_main")
(def edge-sha256 "3697dea8fa650a22c04932e06f79a5c5688342ef9141fa55ad517ca6ebd2530f")
(def max-text 4096)

(defn- sha256-bytes [^bytes b]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (.update md b)
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn resolve-agent-text
  "-> {:bin <private copy> :source <path> :sha256 hex :cache atom}. Throws when the file is missing, not executable, or
  its sha256 is not `want` (default edge-sha256). With no argument the path is env AGENT_TEXT_BIN or the installed edge."
  ([] (resolve-agent-text (or (not-empty (System/getenv "AGENT_TEXT_BIN")) installed-bin) edge-sha256))
  ([path want]
   (let [f (.getCanonicalFile (io/file path))
         _ (when-not (and (.isFile f) (.canExecute f))
             (throw (ex-info (str "agent-text edge is not an executable file: " path) {:bin (str path)})))
         bytes (java.nio.file.Files/readAllBytes (.toPath f))
         sha (sha256-bytes bytes)
         _ (when-not (= sha want)
             (throw (ex-info (str "agent-text edge sha256 " sha " is not the expected " want "; refusing to run") {:bin (str f)})))
         dir (java.nio.file.Files/createTempDirectory
              "agent-text-edge-"
              (into-array java.nio.file.attribute.FileAttribute
                          [(java.nio.file.attribute.PosixFilePermissions/asFileAttribute
                            (java.nio.file.attribute.PosixFilePermissions/fromString "rwx------"))]))
         copy (io/file (str dir) "agent_text_edge")]
     (java.nio.file.Files/write (.toPath copy) bytes (into-array java.nio.file.OpenOption []))
     (.setExecutable copy true true)
     (.deleteOnExit copy)
     (.deleteOnExit (io/file (str dir)))
     (when-not (= sha (sha256-bytes (java.nio.file.Files/readAllBytes (.toPath copy))))
       (throw (ex-info "agent-text edge: the private copy does not match the verified bytes" {:copy (str copy)})))
     {:bin (str copy) :source (str f) :sha256 sha :cache (atom {})})))

(defn- latin1 ^String [^bytes b] (String. b "ISO-8859-1"))

(defn parse-frame
  "The edge's stdout (bytes read as ISO-8859-1, one char per byte) -> {:plain <latin1 bytes> :reason :marker :line-break :ok},
  or nil when it is not the frame. Lengths are BYTE counts, as the edge writes them."
  [^String s]
  (when-let [[_ n r1] (re-matches #"(?s)plain#(\d{1,9})\n(.*)" s)]
    (let [n (Integer/parseInt n)]
      (when (and (>= (count r1) (inc n)) (= \newline (nth r1 n)))
        (when-let [[_ m r2] (re-matches #"(?s)reason#(\d{1,9})\n(.*)" (subs r1 (inc n)))]
          (let [m (Integer/parseInt m)]
            (when (and (>= (count r2) (inc m)) (= \newline (nth r2 m)))
              (when-let [[_ mk lb ok] (re-matches #"marker=(TRUE|FALSE)\nline_break=(TRUE|FALSE)\nok=(TRUE|FALSE)\n"
                                                  (subs r2 (inc m)))]
                {:plain (subs r1 0 n) :reason (subs r2 0 m)
                 :marker (= "TRUE" mk) :line-break (= "TRUE" lb) :ok (= "TRUE" ok)}))))))))

(defn run-raw
  "Run the edge once on the bytes of s (UTF-8). -> {:exit :out <latin1 string> :err}."
  [h ^String s]
  (let [res (p/shell {:in (java.io.ByteArrayInputStream. (.getBytes s "UTF-8")) :out :bytes :err :string :continue true}
                     (:bin h))]
    {:exit (:exit res) :out (latin1 (:out res)) :err (:err res)}))

(defn- neutralise* [h ^String s]
  (let [in-bytes (.getBytes s "UTF-8")
        {:keys [exit out err]} (run-raw h s)
        fr (parse-frame out)]
    (cond
      (nil? fr) (throw (ex-info "agent-text edge: unparsed output" {:exit exit :err err :out (subs out 0 (min 200 (count out)))}))
      (:ok fr)
      (let [plain-bytes (.getBytes ^String (:plain fr) "ISO-8859-1")
            plain (String. plain-bytes "UTF-8")]
        (when-not (zero? exit) (throw (ex-info "agent-text edge: ok=TRUE with a non-zero exit" {:exit exit})))
        (when-not (= (alength plain-bytes) (alength in-bytes))
          (throw (ex-info "agent-text edge: the plain text is not as long as the text" {:in (alength in-bytes) :out (alength plain-bytes)})))
        (when (re-find #"[\n\r]" plain)
          (throw (ex-info "agent-text edge: a line break survived (the edge's frame is wrong)" {})))
        {:text plain :marker (:marker fr) :line_break (:line-break fr) :refused nil})
      :else
      (do (when-not (= 1 exit) (throw (ex-info (str "agent-text edge: ok=FALSE with exit " exit) {:exit exit})))
          (when (str/blank? (:reason fr)) (throw (ex-info "agent-text edge: refused without a reason" {})))
          {:text "" :marker false :line_break false :refused (:reason fr)}))))

(defn neutralise
  "s -> {:text <plain text> :marker bool :line_break bool :refused nil|reason}. Cached per handle (same text, same answer)."
  [h s]
  (let [s (str s) c (:cache h)]
    (or (get @c s)
        (let [v (neutralise* h s)] (swap! c assoc s v) v))))

(defn display
  "The text a prompt may show for agent text s: the edge's plain text, or the harness's own words when it was refused."
  [h s]
  (let [{:keys [text refused]} (neutralise h s)]
    (if refused (str "(text withheld: " refused ")") text)))

(defn summary
  "What the handle saw, for the episode record: distinct texts, with an opener, with a line break, refused."
  [h]
  (let [vs (vals @(:cache h))]
    {:texts (count vs) :with_marker (count (filter :marker vs)) :with_line_break (count (filter :line_break vs))
     :refused (count (filter :refused vs))}))
