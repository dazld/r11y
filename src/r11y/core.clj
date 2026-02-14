(ns r11y.core
  (:require [r11y.lib.html :as html]
            [clojure.string :as str])
  (:gen-class))

(defn print-usage
  []
  (println "Usage: r11y [options] <url>")
  (println "")
  (println "Extract readable content from web pages as Markdown")
  (println "")
  (println "Options:")
  (println "  -l, --link-density N   Link density threshold 0-1 (default: 0.5)")
  (println "  -m, --with-metadata    Include YAML frontmatter with metadata")
  (println "  -h, --help             Show this help message")
  (println "")
  (println "Example:")
  (println "  r11y https://example.com")
  (println "  r11y --link-density 0.3 https://example.com")
  (println "  r11y --with-metadata https://example.com"))

(defn parse-args
  [args]
  (loop [args args
         opts {:link-density-threshold 0.5 :with-metadata false :url nil}]
    (if (empty? args)
      opts
      (let [arg (first args)
            rest-args (rest args)]
        (cond (or (= arg "-h") (= arg "--help")) (assoc opts :help true)
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
          (str/starts-with? arg "-") (assoc opts :error (str "Unknown option: " arg))
          :else (if (:url opts)
                  (assoc opts :error "Multiple URLs provided. Only one URL is allowed.")
                  (recur rest-args (assoc opts :url arg))))))))

(defn -main
  [& args]
  (let [opts (parse-args args)]
    (cond (:help opts) (do (print-usage) (System/exit 0))
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
      :else (try (let [result (html/extract-content-from-url (:url opts)
                                                             :format :markdown
                                                             :link-density-threshold
                                                             (:link-density-threshold opts)
                                                             :with-metadata (:with-metadata opts))]
                   (println (:markdown result))
                   (System/exit 0))
                 (catch Exception e
                   (binding [*out* *err*]
                     (println "Error extracting content:" (.getMessage e)))
                   (System/exit 1))))))
