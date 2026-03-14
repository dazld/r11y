(ns r11y.lib.http
  #?(:bb (:require [babashka.http-client :as http])
     :clj (:require [hato.client :as hato]
                    [clojure.string :as str])))

#?(:clj
   (defn- keywordize-headers [headers]
     (reduce-kv (fn [m k v]
                  (assoc m (keyword (str/lower-case k)) v))
                {}
                headers)))

(defn get-url [url opts]
  #?(:bb (http/get url opts)
     :clj (let [resp (hato/get url opts)]
            (update resp :headers keywordize-headers))))
