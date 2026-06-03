(ns r11y.lib.http
  (:require [clojure.string :as str])
  #?(:bb (:require [babashka.http-client :as http])
     :clj (:require [hato.client :as hato]))
  #?(:clj (:import [java.io ByteArrayOutputStream InputStream
                    OutputStream OutputStreamWriter IOException]
                   [java.net URI InetSocketAddress Proxy Proxy$Type Socket]
                   [javax.net.ssl SSLSocket SSLContext])))

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

(defn- parse-proxy-uri
  [^String proxy-str]
  (let [uri (URI. proxy-str)
        host (.getHost uri)
        port (if (pos? (.getPort uri)) (.getPort uri) 1080)]
    (when (or (nil? host) (zero? (count host)))
      (throw (ex-info "Invalid proxy URL: missing host" {:proxy proxy-str})))
    [host port]))

#?(:clj
   (do
     (defn- socks5-socket
       "Open a TCP socket to host:port routed through a SOCKS5 proxy.
        Wraps the socket in SSL when the target scheme is https."
       [^String target-url ^String proxy-host ^long proxy-port
        connect-timeout-ms]
       (let [target-uri (URI. target-url)
             target-host (.getHost target-uri)
             target-port (if (pos? (.getPort target-uri))
                           (.getPort target-uri)
                           (case (.getScheme target-uri)
                             "https" 443
                             "http" 80
                             80))
             proxy-addr (InetSocketAddress. ^String proxy-host ^long proxy-port)
             proxy (Proxy. Proxy$Type/SOCKS proxy-addr)
             sock (doto (Socket. proxy)
                    (.connect (InetSocketAddress. ^String target-host ^long target-port)
                              ^long connect-timeout-ms))
             secure? (= "https" (.getScheme target-uri))
             sock (if secure?
                    (let [ssl-context (SSLContext/getDefault)
                          ssl-factory (.getSocketFactory ssl-context)
                          ssl-sock (.createSocket ssl-factory sock ^String target-host ^long target-port true)]
                      (.startHandshake ^SSLSocket ssl-sock)
                      ssl-sock)
                    sock)]
         sock))

     (defn- drain-stream
       "Read all bytes from an InputStream into a byte array."
       [^InputStream in]
       (with-open [out (ByteArrayOutputStream.)]
         (let [buf (byte-array 8192)]
           (loop []
             (let [n (.read in buf)]
               (when (pos? n)
                 (.write out buf 0 n)
                 (recur)))))
         (.toByteArray out)))

     (defn- read-line-raw
       "Read a line from a byte stream (delimited by CRLF) without
        consuming beyond the newline."
       [^InputStream in]
       (let [buf (ByteArrayOutputStream.)]
         (loop []
           (let [b (.read in)]
             (cond
               (neg? b) (when (pos? (.size buf))
                          (.toString buf "UTF-8"))
               (= b 13) (do
                          (.read in) ; consume LF
                          (.toString buf "UTF-8"))
               :else (do
                       (.write buf b)
                       (recur)))))))

     (defn- read-chunked
       "Read HTTP/1.1 chunked transfer-encoded body from an InputStream.
        Each chunk: <size-hex>\r\n<bytes>\r\n, terminated by 0\r\n\r\n."
       [^InputStream in]
       (let [out (ByteArrayOutputStream.)]
         (loop []
           (let [size-line (read-line-raw in)]
             (when (nil? size-line)
               (throw (IOException. "EOF in chunked body before end chunk")))
             (let [size (Integer/parseInt (str/trim size-line) 16)]
               (cond
                 (zero? size) (do
                                ;; consume trailing CRLF after end chunk
                                (read-line-raw in)
                                (.toByteArray out))
                 (pos? size) (let [arr (byte-array size)
                                   read (atom 0)]
                               (while (< @read size)
                                 (let [r (.read in arr @read (- size @read))]
                                   (when (neg? r)
                                     (throw (IOException. "EOF mid-chunk")))
                                   (swap! read + r)))
                               (.write out arr)
                               (read-line-raw in) ; consume CRLF after chunk
                               (recur))))))))

     (defn- read-headers-raw
       "Read HTTP headers (raw, byte-oriented)."
       [^InputStream in]
       (loop [headers {}]
         (let [line (read-line-raw in)]
           (cond
             (nil? line) headers
             (zero? (count line)) headers
             :else
             (let [idx (.indexOf ^String line (int \:))]
               (if (neg? idx)
                 (recur headers)
                 (let [k (subs ^String line 0 idx)
                       v (.trim (subs ^String line (inc idx)))]
                   (recur (assoc headers
                                 (keyword (.toLowerCase ^String k))
                                 v)))))))))

     (defn- gunzip
       "Decompress a gzipped byte array."
       [^bytes body]
       (let [in (java.util.zip.GZIPInputStream. (java.io.ByteArrayInputStream. body))
             out (ByteArrayOutputStream.)]
         (try
           (let [buf (byte-array 8192)]
             (loop []
               (let [n (.read in buf)]
                 (when (pos? n)
                   (.write out buf 0 n)
                   (recur)))))
           (.toByteArray out)
           (finally
             (.close in)))))

     (defn- inflate
       "Inflate a deflate-encoded byte array. Tries zlib header first,
        then raw deflate if that fails."
       [^bytes body]
       (let [try-inflate (fn [with-header?]
                           (let [in (java.util.zip.InflaterInputStream.
                                     (java.io.ByteArrayInputStream. body)
                                     (java.util.zip.Inflater. ^boolean with-header?))
                                 out (ByteArrayOutputStream.)]
                             (try
                               (let [buf (byte-array 8192)]
                                 (loop []
                                   (let [n (.read in buf)]
                                     (when (pos? n)
                                       (.write out buf 0 n)
                                       (recur)))))
                               (.toByteArray out)
                               (finally
                                 (.close in)))))]
         (try (try-inflate true)
              (catch Exception _
                (try-inflate false)))))

     (defn- maybe-decode
       "Apply Content-Encoding decoding to body bytes. Handles gzip and
        deflate; returns bytes unchanged if no encoding or unknown."
       [^bytes body headers]
       (let [enc (or (get headers :content-encoding) "")]
         (cond
           (nil? body) body
           (re-find #"(?i)gzip" enc) (gunzip body)
           (re-find #"(?i)deflate" enc) (inflate body)
           :else body)))

     (defn- socks5-get
       "Issue an HTTP(S) GET through a SOCKS5 proxy by speaking HTTP/1.1
        over a manually connected socket."
       [url proxy-str headers timeout-ms]
       (let [[proxy-host proxy-port] (parse-proxy-uri proxy-str)
             sock (socks5-socket url proxy-host proxy-port timeout-ms)
             result (try
                      (let [target-uri (URI. url)
                            target-host (.getHost target-uri)
                            sock-in (.getInputStream ^Socket sock)
                            sock-out (.getOutputStream ^Socket sock)
                            out (OutputStreamWriter. sock-out "UTF-8")
                            path (str (or (.getRawPath target-uri) "/")
                                      (when-let [q (.getRawQuery target-uri)]
                                        (str "?" q)))
                            request-lines (cons (str "GET " path " HTTP/1.1")
                                                (map (fn [[k v]] (str k ": " v))
                                                     (assoc headers
                                                            "Host" target-host
                                                            "Connection" "close")))
                            request (str (str/join "\r\n" request-lines) "\r\n\r\n")]
                        (.write out request)
                        (.flush out)
                        (let [status-line (read-line-raw sock-in)
                              _ (when (nil? status-line)
                                  (throw (IOException. "Empty response from server")))
                              status (or (some #(when (re-matches #"\d{3}" %)
                                                  (Integer/parseInt %))
                                               (str/split status-line #" "))
                                         (throw (IOException. (str "Bad status line: " status-line))))
                              resp-headers (read-headers-raw sock-in)
                              body (cond
                                     (= "chunked" (get resp-headers :transfer-encoding))
                                     (read-chunked sock-in)
                                     (get resp-headers :content-length)
                                     (let [cl (get resp-headers :content-length)
                                           n (Integer/parseInt cl)
                                           arr (byte-array n)
                                           read (atom 0)]
                                       (while (< @read n)
                                         (let [r (.read sock-in arr @read (- n @read))]
                                           (when (neg? r)
                                             (throw (IOException. "EOF before Content-Length")))
                                           (swap! read + r)))
                                       arr)
                                     :else
                                     (drain-stream sock-in))
                              body (maybe-decode body resp-headers)]
                          {:status status
                           :headers resp-headers
                           :body body}))
                      (finally
                        (.close ^Socket sock)))]
         result))))

(defn proxy->opts
  "Convert a proxy URL string (e.g. socks5://127.0.0.1:9050) to a marker
   that get-url recognises and applies via a SOCKS5-aware code path."
  [proxy-str]
  (when proxy-str
    (let [[host port] (try (parse-proxy-uri proxy-str)
                           (catch Exception _ [nil nil]))]
      (when (and host port)
        (let [marker [:socks5 proxy-str]]
          #?(:clj {:__socks5__ marker}
             :bb {:proxy proxy-str}))))))

(defn- socks5?
  [opts]
  (and (:__socks5__ opts) (vector? (:__socks5__ opts))
       (= :socks5 (first (:__socks5__ opts)))))

(def ^:const default-socks5-timeout-ms 30000)

(defn get-url [url opts]
  (let [opts (normalize-opts opts)
        opts (merge opts (proxy->opts (:proxy opts)))
        timeout-ms (or (:__socks5_timeout__ opts) default-socks5-timeout-ms)]
    (if (socks5? opts)
      #?(:clj
         (let [proxy-str (second (:__socks5__ opts))
               headers (or (:headers opts) {})
               resp (socks5-get url proxy-str headers timeout-ms)
               body (if (= :string (:as opts))
                      (String. ^bytes (:body resp) "UTF-8")
                      (:body resp))]
           (assoc resp :body body))
         :bb (throw (ex-info "SOCKS5 proxy is not supported on babashka"
                             {:proxy (:proxy opts)})))
      #?(:bb (-> (http/get url opts)
                 (update :headers keywordize-headers))
         :clj (-> (hato/get url opts)
                  (update :headers keywordize-headers))))))
