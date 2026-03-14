(ns r11y.lib.json
  #?(:bb (:require [cheshire.core :as json])
     :clj (:require [clojure.data.json :as json])))

(defn parse [s]
  #?(:bb (json/parse-string s true)
     :clj (json/read-str s :key-fn keyword)))

(defn generate [m]
  #?(:bb (json/generate-string m)
     :clj (json/write-str m)))
