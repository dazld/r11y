(ns r11y.lib.http
  "GET requests with redirect, size and time limits.

   Redirects are followed here rather than by the HTTP client, so that every
   address, the first one included, can be checked with :allow-url? before it
   is requested."
  #?(:bb (:require [babashka.http-client :as http])
     :clj (:require [hato.client :as hato]))
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net URI)
           (java.util.concurrent ExecutionException)
           (java.net.http HttpTimeoutException)))

(set! *warn-on-reflection* true)

(def default-max-redirects 10)
(def default-max-bytes (* 10 1024 1024))
(def default-connect-timeout-ms 10000)
(def default-timeout-ms 30000)

(def ^:private redirect-statuses #{301 302 303 307 308})

(defn- keywordize-headers [headers]
  (reduce-kv (fn [m k v]
               (assoc m (keyword (.toLowerCase ^String (name k))) v))
             {}
             headers))

(defn- header [response header-name]
  (some (fn [[k v]] (when (.equalsIgnoreCase ^String (name k) ^String header-name) v))
        (:headers response)))

(defn- close-body [response]
  (when-let [body (:body response)]
    (when (instance? InputStream body)
      (try (.close ^InputStream body) (catch Exception _ nil)))))

(defn- remaining-ms [deadline]
  (- deadline (System/currentTimeMillis)))

;; By name, because babashka does not expose the class.
(defn- connect-timeout? [e]
  (= "java.net.http.HttpConnectTimeoutException" (.getName (class e))))

(defn- timeout-error
  ([url timeout-ms] (timeout-error url timeout-ms nil))
  ([url timeout-ms cause]
   (ex-info (str "Timed out after " timeout-ms " ms fetching " url)
            {:type :r11y/timeout :url url :timeout-ms timeout-ms}
            cause)))

(defn- request
  "GET url without following redirects; the body is an unread InputStream."
  [url {:keys [headers connect-timeout]} timeout-ms]
  #?(:bb (http/get url {:headers headers
                        :as :stream
                        :throw false
                        :timeout timeout-ms
                        :client (http/client {:follow-redirects :never
                                              :connect-timeout connect-timeout})})
     :clj (hato/get url {:headers headers
                         :as :stream
                         :throw-exceptions? false
                         :timeout timeout-ms
                         :http-client {:redirect-policy :never
                                       :connect-timeout connect-timeout}})))

(defn- read-limited
  "Read all of in, failing when it holds more than max-bytes."
  ^bytes [^InputStream in max-bytes url]
  (let [out (ByteArrayOutputStream.)
        buf (byte-array 8192)]
    (loop [total 0]
      (let [n (.read in buf)]
        (cond
          (neg? n) (.toByteArray out)
          (> (+ total n) max-bytes)
          (throw (ex-info (str "Response from " url " is larger than " max-bytes " bytes")
                          {:type :r11y/body-too-large :url url :max-bytes max-bytes}))
          :else (do (.write out buf 0 n)
                    (recur (+ total n))))))))

(defn- read-body
  "Read the body of response before deadline. The HTTP client's own timeout
   stops at the response headers, so a server that trickles the body is cut
   off here."
  ^bytes [response url max-bytes deadline timeout-ms]
  (let [^InputStream in (:body response)
        reader (future (read-limited in max-bytes url))
        result (try (deref reader (max 0 (remaining-ms deadline)) ::timeout)
                    (catch ExecutionException e
                      (throw (or (.getCause e) e))))]
    (if (= result ::timeout)
      (do (future-cancel reader)
          (close-body response)
          (throw (timeout-error url timeout-ms)))
      result)))

(defn- resolve-location [url location]
  (let [target (try (str (.resolve (URI. url) (.trim ^String location)))
                    (catch Exception e
                      (throw (ex-info (str "Invalid redirect from " url " to " location)
                                      {:type :r11y/invalid-redirect :url url :location location}
                                      e))))]
    (if (re-find #"(?i)^https?://" target)
      target
      (throw (ex-info (str "Redirect from " url " to unsupported address " target)
                      {:type :r11y/invalid-redirect :url url :location target})))))

(defn get-url
  "GET url and return {:status :headers :body :uri}. :body is a byte array,
   :headers has lower-case keyword keys and :uri is the final URL after
   redirects. A response other than 2xx throws ex-info with :type
   :r11y/http-error and :status.

   Options:
   :headers         - request headers
   :allow-url?      - (fn [url] bool), called before every request, redirects
                      included. False stops with :type :r11y/blocked-url.
   :max-redirects   - redirects to follow (default 10); more stops with
                      :type :r11y/too-many-redirects
   :max-bytes       - largest body to read (default 10 MB); more stops with
                      :type :r11y/body-too-large
   :connect-timeout - ms to open a connection (default 10 s)
   :timeout         - ms for the whole fetch, redirects and body included
                      (default 30 s); more stops with :type :r11y/timeout"
  [url {:keys [allow-url? max-redirects max-bytes connect-timeout timeout]
        :or {max-redirects default-max-redirects
             max-bytes default-max-bytes
             connect-timeout default-connect-timeout-ms
             timeout default-timeout-ms}
        :as opts}]
  (let [opts (assoc opts :connect-timeout connect-timeout)
        deadline (+ (System/currentTimeMillis) timeout)]
    (loop [url url
           hops 0]
      (when (and allow-url? (not (allow-url? url)))
        (throw (ex-info (str "Not allowed to fetch " url)
                        {:type :r11y/blocked-url :url url})))
      (let [left (remaining-ms deadline)
            _ (when-not (pos? left) (throw (timeout-error url timeout)))
            response (try (request url opts left)
                          (catch HttpTimeoutException e
                            (throw
                             (if (connect-timeout? e)
                               (ex-info (str "Timed out after " connect-timeout
                                             " ms connecting to " url)
                                        {:type :r11y/connect-timeout :url url
                                         :timeout-ms connect-timeout}
                                        e)
                               (timeout-error url timeout e)))))
            status (:status response)
            location (header response "location")]
        (cond
          (and (redirect-statuses status) location)
          (do (close-body response)
              (when (>= hops max-redirects)
                (throw (ex-info (str "Too many redirects (more than " max-redirects ") fetching " url)
                                {:type :r11y/too-many-redirects :url url
                                 :max-redirects max-redirects})))
              (recur (resolve-location url location) (inc hops)))

          (<= 200 status 299)
          {:status status
           :headers (keywordize-headers (:headers response))
           :body (read-body response url max-bytes deadline timeout)
           :uri url}

          :else
          (do (close-body response)
              (throw (ex-info (str "HTTP " status " fetching " url)
                              {:type :r11y/http-error :url url :status status}))))))))
