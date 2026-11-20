(ns r11y.core
  (:require [r11y.lib.html :as html]
            [clojure.string :as str])
  (:gen-class))

(def version (or (System/getProperty "r11y.version") "dev"))

(defn print-usage
  []
  (println "Usage: r11y [options] <url>")
  (println "")
  (println "Extract readable content from web pages as Markdown")
  (println "")
  (println "Options:")
  (println "  -l, --link-density N   Link density threshold 0-1 (default: 0.5)")
  (println "  -m, --with-metadata    Include YAML frontmatter with metadata")
  (println "  -f, --content-file P   Parse the HTML in P instead of fetching <url>")
  (println "                         (use P = - to read the HTML from stdin)")
  (println "                         (<url> is still used for metadata and relative links)")
  (println "  -v, --version          Show version")
  (println "  -h, --help             Show this help message")
  (println "")
  (println "Example:")
  (println "  r11y https://example.com")
  (println "  r11y --link-density 0.3 https://example.com")
  (println "  r11y --with-metadata https://example.com")
  (println "  r11y --content-file rendered.html https://example.com")
  (println "  browser_dump_html | r11y -f - https://example.com"))

(defn parse-args
  [args]
  (loop [args args
         opts {:link-density-threshold 0.5 :with-metadata false :url nil :content-file nil}]
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
              (or (= arg "-f") (= arg "--content-file"))
              (if (empty? rest-args)
                (assoc opts :error "Missing value for --content-file")
                (recur (rest rest-args) (assoc opts :content-file (first rest-args))))
              (str/starts-with? arg "-") (assoc opts :error (str "Unknown option: " arg))
              :else (if (:url opts)
                      (assoc opts :error "Multiple URLs provided. Only one URL is allowed.")
                      (recur rest-args (assoc opts :url arg))))))))

(defn- console-connected?
  "True when this process is attached to a terminal. Defensive: some GraalVM
   images restrict System/console rather than returning nil."
  []
  (try
    (some? (System/console))
    (catch Throwable _ false)))

(defn read-content
  "HTML to extract when the caller supplies it: a file path, or stdin for -.

   Caller-supplied content is read strictly. Blank input is an error, not a
   successful extraction of nothing: a caller that piped a blank page (a browser that
   rendered nothing, a truncated file) would otherwise get exit 0 and an empty
   document, which is indistinguishable from a page that genuinely has no prose.
   The plain-URL path is untouched -- this only governs --content-file."
  [path]
  (let [from-stdin? (= "-" path)]
    (when (and from-stdin? (console-connected?))
      (throw (Exception. "stdin is a terminal: pipe the HTML in (r11y -f - URL < page.html), or pass a file path")))
    (let [content (if from-stdin? (slurp *in*) (slurp path))]
      (when (str/blank? content)
        (throw (Exception. (if from-stdin?
                             "no HTML on stdin"
                             (str "file contains no HTML: " path)))))
      content)))

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
          (nil? (:url opts)) (do
                               (println "Error: No URL provided")
                               (println)
                               (print-usage)
                               (System/exit 1))
          :else (try (let [result (apply html/extract-content-from-url
                                         (:url opts)
                                         :format :markdown
                                         :link-density-threshold
                                         (:link-density-threshold opts)
                                         :with-metadata (:with-metadata opts)
                                         (when-let [path (:content-file opts)]
                                           [:content (read-content path)
                                            :content-type "text/html"]))]
                       (println (:markdown result))
                       (System/exit 0))
                     (catch Exception e
                       (binding [*out* *err*]
                         (println "Error extracting content:" (.getMessage e)))
                       (System/exit 1))))))
