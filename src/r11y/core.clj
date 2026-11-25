(ns r11y.core
  (:require [r11y.lib.html :as html]
            [clojure.string :as str])
  (:gen-class))

(def version (or (System/getProperty "r11y.version") "dev"))

(defn print-usage
  []
  (println "Usage: r11y [options] <url>")
  (println "       r11y [options] < page.html")
  (println "")
  (println "Extract readable content from web pages as Markdown")
  (println "")
  (println "Options:")
  (println "  -l, --link-density N   Link density threshold 0-1 (default: 0.5)")
  (println "  -m, --with-metadata    Include YAML frontmatter with metadata")
  (println "  -u, --base-url URL     URL of HTML read from stdin, for links and metadata")
  (println "  -v, --version          Show version")
  (println "  -h, --help             Show this help message")
  (println "")
  (println "With no URL, or with -, r11y reads HTML from stdin.")
  (println "-- ends the options: what follows is the URL even if it starts with -.")
  (println "")
  (println "Exit status: 0 on success, 1 when fetching, extracting or writing fails,")
  (println "2 when the command line is wrong.")
  (println "")
  (println "Example:")
  (println "  r11y https://example.com")
  (println "  r11y --link-density 0.3 https://example.com")
  (println "  r11y --with-metadata https://example.com")
  (println "  r11y --base-url https://example.com/article < page.html"))

(defn parse-args
  [args]
  (loop [args args
         opts {:link-density-threshold 0.5 :with-metadata false :url nil}]
    (if (empty? args)
      opts
      (let [arg (first args)
            rest-args (rest args)]
        (cond (= arg "--")
          (cond (next rest-args) (assoc opts :error "Multiple URLs provided. Only one URL is allowed.")
                (empty? rest-args) opts
                (:url opts) (assoc opts :error "Multiple URLs provided. Only one URL is allowed.")
                :else (assoc opts :url (first rest-args)))
          (or (= arg "-h") (= arg "--help")) (assoc opts :help true)
          (or (= arg "-v") (= arg "--version")) (assoc opts :version true)
          (or (= arg "-m") (= arg "--with-metadata")) (recur rest-args (assoc opts :with-metadata true))
          (or (= arg "-l") (= arg "--link-density"))
          (if (empty? rest-args)
            (assoc opts :error "Missing value for --link-density")
            (let [val (first rest-args)
                  parse-result (try (let [n (Double/parseDouble val)]
                                      (if (and (>= n 0.0) (<= n 1.0))
                                        {:success true :value n}
                                        {:success false :error (str "Link density must be between 0 and 1, got: " val)}))
                                    (catch NumberFormatException _
                                      {:success false :error (str "Invalid number for --link-density: " val)}))]
              (if (:success parse-result)
                (recur (rest rest-args) (assoc opts :link-density-threshold (:value parse-result)))
                (assoc opts :error (:error parse-result)))))
          (or (= arg "-u") (= arg "--base-url"))
          (if (empty? rest-args)
            (assoc opts :error "Missing value for --base-url")
            (recur (rest rest-args) (assoc opts :base-url (first rest-args))))
          (= arg "-") (if (:url opts)
                        (assoc opts :error "Multiple URLs provided. Only one URL is allowed.")
                        (recur rest-args (assoc opts :url arg)))
          (str/starts-with? arg "-") (assoc opts :error (str "Unknown option: " arg))
          :else (if (:url opts)
                  (assoc opts :error "Multiple URLs provided. Only one URL is allowed.")
                  (recur rest-args (assoc opts :url arg))))))))

;; Some servers, often behind bot protection, reset or stall connections from
;; clients they do not take for a browser. The JDK reports these as bare
;; IOExceptions, so they are recognised by type and message.
(def ^:private dropped-connection-pattern
  #"RST_STREAM|GOAWAY|Connection reset|connection closed|EOF reached")

(defn dropped-connection?
  "True when an exception, or one of its causes, means the server dropped or
   stalled the connection. A connect timeout is not one: the host could not
   be reached at all."
  [^Throwable e]
  (boolean
   (some (fn [^Throwable t]
           (or (= :r11y/timeout (:type (ex-data t)))
               (and (instance? java.net.http.HttpTimeoutException t)
                    (not (instance? java.net.http.HttpConnectTimeoutException t)))
               (and (instance? java.io.IOException t)
                    (some->> (.getMessage t) (re-find dropped-connection-pattern)))))
         (take-while some? (iterate #(.getCause ^Throwable %) e)))))

(defn- stdin-input?
  "True when the URL argument means: read HTML from stdin."
  [url]
  (or (nil? url) (= url "-")))

(defn- stdin-terminal?
  "True when stdin is a terminal, not a pipe or a file. Java cannot test stdin
   alone, so test(1) does it on the inherited stdin. Where that fails, a
   console means a terminal: then r11y prints usage rather than wait."
  []
  (try
    (zero? (.waitFor (-> (ProcessBuilder. ^java.util.List ["test" "-t" "0"])
                         (.redirectInput java.lang.ProcessBuilder$Redirect/INHERIT)
                         (.start))))
    (catch Exception _
      (some? (System/console)))))

(defn shell-quote
  "s quoted for a POSIX shell"
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn- describe
  "A readable message for e. JDK network errors often have none: an unknown
   host is a chain of exceptions whose messages are all nil."
  [^Throwable e]
  (let [chain (take-while some? (iterate #(.getCause ^Throwable %) e))]
    (cond
      (some #(or (instance? java.net.UnknownHostException %)
                 (instance? java.nio.channels.UnresolvedAddressException %))
            chain)
      "cannot resolve the host name"

      :else
      (or (some #(let [m (.getMessage ^Throwable %)] (when-not (str/blank? m) m)) chain)
          (.getSimpleName (class e))))))

(defn error-message
  "Message to show when extraction from url fails with exception e."
  [url ^Throwable e]
  (if (and (not (stdin-input? url)) (dropped-connection? e))
    (str "Error fetching " url ": the server dropped the connection (" (describe e) ").\n"
         "Some sites block clients that do not look like a browser. "
         "You can save the page from a browser and pipe it in:\n"
         "  r11y --base-url " (shell-quote url) " < page.html")
    (str (if (::input-error (ex-data e)) "Error: " "Error extracting content: ")
         (describe e))))

(defn- extract
  [{:keys [url base-url link-density-threshold with-metadata]}]
  (if (stdin-input? url)
    (let [body (.readAllBytes System/in)]
      (cond
        (str/blank? (String. body "UTF-8"))
        (throw (ex-info "No input on stdin. Give a URL, or pipe in HTML." {::input-error true}))

        ;; Like the URL path, pass Markdown through rather than parse it as HTML
        (html/looks-like-markdown? body)
        {:markdown (str/trimr (String. body "UTF-8"))}

        :else
        (html/extract-content body
                              :format :markdown
                              :base-url (or base-url (html/page-url body))
                              :link-density-threshold link-density-threshold
                              :with-metadata with-metadata)))
    (html/extract-content-from-url url
                                   :format :markdown
                                   :link-density-threshold link-density-threshold
                                   :with-metadata with-metadata)))

(defn- usage-error
  "Print msg and usage to stderr; return the exit status for a wrong command line."
  [msg]
  (binding [*out* *err*]
    (println "Error:" msg)
    (println)
    (print-usage))
  2)

(defn run
  "Run r11y with command-line args and return the exit status."
  [args]
  (let [opts (parse-args args)]
    (cond
      (:version opts) (do (println (str "r11y " version))
                          (println "https://github.com/dazld/r11y/releases")
                          0)
      (:help opts) (do (print-usage) 0)
      (:error opts) (usage-error (:error opts))
      (and (nil? (:url opts)) (stdin-terminal?)) (usage-error "No URL provided")
      (and (:base-url opts) (not (stdin-input? (:url opts))))
      (usage-error "--base-url is only used when reading HTML from stdin")
      :else (let [result (try (extract opts)
                              (catch Exception e
                                (binding [*out* *err*]
                                  (println (error-message (:url opts) e)))
                                nil))]
              (if result
                (do (println (:markdown result))
                    0)
                1)))))

(defn- broken-pipe?
  "True when e says the reader of stdout went away, as with r11y URL | head."
  [^java.io.IOException e]
  (boolean (some->> (.getMessage e) (re-find #"(?i)broken pipe"))))

(defn -main
  [& args]
  ;; Write stdout straight to its file descriptor: System.out drops write
  ;; errors, so a full disk or closed stdout would still exit 0.
  (let [stdout (java.io.BufferedWriter.
                (java.io.OutputStreamWriter.
                 (java.io.FileOutputStream. java.io.FileDescriptor/out)
                 java.nio.charset.StandardCharsets/UTF_8))
        status (try
                 (binding [*out* stdout]
                   (let [status (run args)]
                     (flush)
                     status))
                 (catch java.io.IOException e
                   ;; A reader that closed the pipe early wanted no more
                   ;; output: fail, but without a message, like SIGPIPE.
                   (when-not (broken-pipe? e)
                     (binding [*out* *err*]
                       (println "Error: could not write output:" (.getMessage e))))
                   1))]
    (System/exit status)))
