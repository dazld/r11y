#!/usr/bin/env bb
;; End-to-end wall-clock comparison of r11y against other readability extractors,
;; fetch included. Prints a markdown table of mean ± stdev seconds.
;;
;;   bb bench/compare.bb [--runs 5] [url ...]
;;
;; Tools that are not on the PATH are skipped. R11Y points at the binary to time
;; (default ./r11y).
(require '[babashka.process :as p]
         '[clojure.string :as str])

(def default-urls
  ["https://www.orwellfoundation.com/the-orwell-foundation/orwell/essays-and-other-works/politics-and-the-english-language/"
   "https://developers.cloudflare.com/workers/"
   "https://nextjs.org/"])

(defn on-path? [cmd]
  (zero? (:exit (p/shell {:continue true :out :string :err :string} "which" cmd))))

(def tools
  [{:name "r11y"             :available? #(.exists (java.io.File. (or (System/getenv "R11Y") "./r11y")))
    :cmd  (fn [url] [(or (System/getenv "R11Y") "./r11y") url])}
   {:name "defuddle"         :available? #(on-path? "defuddle")
    :cmd  (fn [url] ["defuddle" "parse" url "--markdown"])}
   {:name "trafilatura"      :available? #(on-path? "trafilatura")
    :cmd  (fn [url] ["trafilatura" "-u" url "--markdown"])}
   {:name "readability-rust" :available? #(on-path? "readability-rust")
    :cmd  (fn [url] ["bash" "-c" (str "curl -sL '" url "' | readability-rust -i - -f text")])}])

(defn time-once [cmd]
  (let [t0 (System/nanoTime)]
    (p/shell {:out :string :err :string :continue true} (str/join " " (map #(str "'" % "'") cmd)))
    (/ (- (System/nanoTime) t0) 1e9)))

(defn stats [xs]
  (let [n    (count xs)
        mean (/ (reduce + xs) n)
        sd   (Math/sqrt (/ (reduce + (map #(Math/pow (- % mean) 2) xs)) (max 1 (dec n))))]
    (format "%.2f ± %.2f" mean sd)))

(let [[opts urls] (loop [args *command-line-args* opts {:runs 5} urls []]
                    (cond
                      (empty? args) [opts urls]
                      (= "--runs" (first args)) (recur (drop 2 args) (assoc opts :runs (parse-long (second args))) urls)
                      :else (recur (rest args) opts (conj urls (first args)))))
      urls      (if (seq urls) urls default-urls)
      available (filter #((:available? %)) tools)]
  (println (str "| URL | " (str/join " | " (map :name available)) " |"))
  (println (str "|-----|" (str/join "|" (repeat (count available) "------")) "|"))
  (doseq [url urls]
    (println (str "| " url " | "
                  (str/join " | " (for [{:keys [cmd]} available]
                                    (let [c (cmd url)]
                                      (time-once c)
                                      (stats (vec (repeatedly (:runs opts) #(time-once c)))))))
                  " |"))))
