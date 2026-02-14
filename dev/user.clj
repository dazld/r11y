(ns user
  (:require [r11y.lib.html :as html]))

(defn crawl-links
  ([start-url] (crawl-links start-url 10))
  ([start-url max-links]
   (let [visited (atom #{})
         results (atom [])
         queue (atom [start-url])]
     (while (and (seq @queue)
                 (< (count @results) max-links))
       (let [url (first @queue)]
         (swap! queue subvec 1)
         (when-not (contains? @visited url)
           (swap! visited conj url)
           (try
             (println "Fetching:" url)
             (let [data (html/extract-content-from-url url :with-metadata true)
                   new-links (->> (:links data)
                                  (map :url)
                                  (filter #(and % (string? %)))
                                  (remove @visited))]
               (swap! results conj {:url url :data data})
               (swap! queue into (take (- max-links (count @results)) new-links)))
             (catch Exception e
               (println "Failed:" url "-" (.getMessage e)))))))
     @results)))
