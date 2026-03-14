(ns r11y.lib.http
  #?(:bb (:require [babashka.http-client :as http])
     :clj (:require [hato.client :as hato])))

(defn- keywordize-headers [headers]
  (reduce-kv (fn [m k v]
               (assoc m (keyword #?(:bb k :clj (.toLowerCase ^String k))) v))
             {}
             headers))

(defn- normalize-opts [opts]
  #?(:bb (if (= (:as opts) :byte-array)
           (assoc opts :as :bytes)
           opts)
     :clj opts))

(defn get-url [url opts]
  (let [opts (normalize-opts opts)]
    #?(:bb (-> (http/get url opts)
               (update :headers keywordize-headers))
       :clj (-> (hato/get url opts)
                (update :headers keywordize-headers)))))
