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
        (cond (or (= arg "-h") (= arg "--help")) (assoc opts :help true)
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
           (or (and (instance? java.net.http.HttpTimeoutException t)
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

(defn error-message
  "Message to show when extraction from url fails with exception e."
  [url ^Throwable e]
  (if (and (not (stdin-input? url)) (dropped-connection? e))
    (str "Error fetching " url ": the server dropped the connection (" (.getMessage e) ").\n"
         "Some sites block clients that do not look like a browser. "
         "You can save the page from a browser and pipe it in:\n"
         "  r11y --base-url " (shell-quote url) " < page.html")
    (str (if (::input-error (ex-data e)) "Error: " "Error extracting content: ")
         (.getMessage e))))

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

(defn -main
  [& args]
  (let [opts (parse-args args)]
    (cond (:version opts) (do (println (str "r11y " version))
                             (println "https://github.com/dazld/r11y/releases")
                             (System/exit 0))
      (:help opts) (do (print-usage) (System/exit 0))
      (:error opts) (do
                      (println "Error:" (:error opts))
                      (println)
                      (print-usage)
                      (System/exit 1))
      (and (nil? (:url opts)) (stdin-terminal?)) (do
                           (println "Error: No URL provided")
                           (println)
                           (print-usage)
                           (System/exit 1))
      (and (:base-url opts) (not (stdin-input? (:url opts))))
      (do (println "Error: --base-url is only used when reading HTML from stdin")
          (println)
          (print-usage)
          (System/exit 1))
      :else (try (let [result (extract opts)]
                   (println (:markdown result))
                   (System/exit 0))
                 (catch Exception e
                   (binding [*out* *err*]
                     (println (error-message (:url opts) e)))
                   (System/exit 1))))))
