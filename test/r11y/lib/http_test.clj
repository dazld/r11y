(ns r11y.lib.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [r11y.lib.html :as html]
            [r11y.lib.http :as http])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io ByteArrayOutputStream OutputStream)
           (java.util.zip GZIPOutputStream)
           (java.net InetSocketAddress)
           (java.util.concurrent Executors)))

(defn- respond [^HttpExchange ex status headers ^bytes body]
  (doseq [[k v] headers] (.add (.getResponseHeaders ex) k v))
  (.sendResponseHeaders ex status (if (pos? (alength body)) (alength body) -1))
  (with-open [out (.getResponseBody ex)] (.write out body)))

(defn- with-server
  "Run f with the base URL of a local server. routes maps a path to
   (fn [exchange]). Returns [result requested-paths]."
  [routes f]
  (let [requested (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server (Executors/newCachedThreadPool))
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [path (.getPath (.getRequestURI ex))]
                          (swap! requested conj path)
                          (if-let [route (routes path)]
                            (route ex)
                            (respond ex 404 {} (byte-array 0)))))))
    (.start server)
    (try
      [(f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
       @requested]
      (finally (.stop server 0)))))

(defn- redirect-to [location]
  (fn [ex] (respond ex 302 {"Location" location} (byte-array 0))))

(defn- ok [^String body]
  (fn [ex] (respond ex 200 {"Content-Type" "text/html"} (.getBytes body "UTF-8"))))

(defn- error-type [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(deftest follows-redirects
  (let [[resp requested] (with-server {"/a" (redirect-to "/b")
                                       "/b" (redirect-to "c")
                                       "/c" (ok "hello")}
                           #(http/get-url (str % "/a") {}))]
    (is (= 200 (:status resp)))
    (is (= "hello" (String. ^bytes (:body resp) "UTF-8")))
    (is (re-find #"/c$" (:uri resp)) ":uri is the final URL")
    (is (= "text/html" (get-in resp [:headers :content-type])))
    (is (= ["/a" "/b" "/c"] requested) "relative Locations resolve against the current URL")))

(deftest allow-url-checks-every-request
  (testing "a redirect to a rejected URL is never requested"
    (let [[type requested]
          (with-server {"/start" (redirect-to "/internal")
                        "/internal" (ok "secret")}
            (fn [base]
              (error-type #(http/get-url (str base "/start")
                                         {:allow-url? (fn [u] (not (re-find #"/internal" u)))}))))]
      (is (= :r11y/blocked-url type))
      (is (= ["/start"] requested))))
  (testing "the first URL is checked too"
    (let [[type requested] (with-server {"/a" (ok "x")}
                             (fn [base]
                               (error-type #(http/get-url (str base "/a")
                                                          {:allow-url? (constantly false)}))))]
      (is (= :r11y/blocked-url type))
      (is (= [] requested)))))

(deftest too-many-redirects
  (let [[type requested] (with-server {"/loop" (redirect-to "/loop")}
                           (fn [base]
                             (error-type #(http/get-url (str base "/loop") {:max-redirects 3}))))]
    (is (= :r11y/too-many-redirects type))
    (is (= 4 (count requested)) "the first request and 3 redirects")))

(deftest redirect-to-other-scheme-is-refused
  (let [[type] (with-server {"/a" (redirect-to "file:///etc/passwd")}
                 (fn [base] (error-type #(http/get-url (str base "/a") {}))))]
    (is (= :r11y/invalid-redirect type))))

(deftest body-size-cap
  (let [big (apply str (repeat 2000 "x"))
        [type] (with-server {"/big" (ok big)}
                 (fn [base] (error-type #(http/get-url (str base "/big") {:max-bytes 1000}))))
        [resp] (with-server {"/big" (ok big)}
                 (fn [base] (http/get-url (str base "/big") {:max-bytes 2000})))]
    (is (= :r11y/body-too-large type))
    (is (= 2000 (alength ^bytes (:body resp))) "a body at the limit is read")))

(deftest non-2xx-throws
  (let [[data] (with-server {}
                 (fn [base]
                   (try (http/get-url (str base "/missing") {}) nil
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))))]
    (is (= :r11y/http-error (:type data)))
    (is (= 404 (:status data)))))

(defn- stall [ms]
  (fn [_] (Thread/sleep (long ms))))

(deftest stalled-server-times-out
  (testing "no response headers"
    (let [started (System/currentTimeMillis)
          [type] (with-server {"/slow" (stall 5000)}
                   (fn [base] (error-type #(http/get-url (str base "/slow") {:timeout 500}))))]
      (is (= :r11y/timeout type))
      (is (< (- (System/currentTimeMillis) started) 4000))))
  (testing "headers sent, body trickled"
    (let [started (System/currentTimeMillis)
          trickle (fn [^HttpExchange ex]
                    (.sendResponseHeaders ex 200 0)
                    (let [^OutputStream out (.getResponseBody ex)]
                      (try
                        (dotimes [_ 50]
                          (.write out (.getBytes "x"))
                          (.flush out)
                          (Thread/sleep 100))
                        (catch Exception _ nil))
                      (try (.close out) (catch Exception _ nil))))
          [type] (with-server {"/trickle" trickle}
                   (fn [base] (error-type #(http/get-url (str base "/trickle") {:timeout 500}))))]
      (is (= :r11y/timeout type))
      (is (< (- (System/currentTimeMillis) started) 4000)))))

(deftest gzip-body-is-decompressed
  (let [gz (let [out (ByteArrayOutputStream.)]
             (with-open [z (GZIPOutputStream. out)] (.write z (.getBytes "hello gzip" "UTF-8")))
             (.toByteArray out))
        [resp] (with-server {"/gz" (fn [ex] (respond ex 200 {"Content-Encoding" "gzip"} gz))}
                 (fn [base] (http/get-url (str base "/gz") {:headers {"Accept-Encoding" "gzip"}})))]
    (is (= "hello gzip" (String. ^bytes (:body resp) "UTF-8")))))

(deftest extract-content-from-url-passes-limits-on
  (let [[type requested]
        (with-server {"/start" (redirect-to "/internal")
                      "/internal" (ok "<html><body><p>secret</p></body></html>")}
          (fn [base]
            (error-type #(html/extract-content-from-url
                          (str base "/start")
                          :allow-url? (fn [u] (not (re-find #"/internal" u)))))))]
    (is (= :r11y/blocked-url type))
    (is (= ["/start"] requested))))
