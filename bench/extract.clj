;; Processing time of extract-content on saved pages, without the network.
;; Each page is a NAME.html file next to a NAME.url file holding its address.
;; Prints the median and p90 of 20 runs after 10 warm-up runs.
;;
;;   clojure -M bench/extract.clj <dir>
(require '[r11y.lib.html :as h] '[clojure.java.io :as io] '[clojure.string :as str])
(let [[in] *command-line-args*]
  (doseq [f (sort (.listFiles (io/file in))) :when (str/ends-with? (.getName f) ".html")]
    (let [base (str/trim (slurp (str/replace (str f) #"\.html$" ".url")))
          bytes (java.nio.file.Files/readAllBytes (.toPath f))
          run #(h/extract-content bytes :format :markdown :base-url base :with-metadata true)
          _ (dotimes [_ 10] (run))
          ts (vec (for [_ (range 20)] (let [t0 (System/nanoTime)] (run) (/ (- (System/nanoTime) t0) 1e6))))
          sorted (sort ts)]
      (printf "%-60s median %7.1fms  p90 %7.1fms%n" (.getName f) (nth sorted 10) (nth sorted 18)))))
(flush)
