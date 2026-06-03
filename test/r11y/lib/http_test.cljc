(ns r11y.lib.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [r11y.lib.http :as http])
  #?(:clj (:import [java.net ProxySelector Proxy Proxy$Type InetSocketAddress Socket URI])))

(deftest proxy->opts-nil
  (testing "nil proxy returns nil"
    (is (nil? (http/proxy->opts nil)))))

(deftest proxy->opts-socks5
  (testing "SOCKS5 URL is recognised and produces a marker opts map"
    #?(:clj
       (let [opts (http/proxy->opts "socks5://127.0.0.1:9050")]
         (is (map? opts))
         (is (contains? opts :__socks5__))
         (is (= :socks5 (first (:__socks5__ opts))))
         (is (= "socks5://127.0.0.1:9050" (second (:__socks5__ opts)))))
       :bb
       (let [opts (http/proxy->opts "socks5://127.0.0.1:9050")]
         (is (= {:proxy "socks5://127.0.0.1:9050"} opts))))))

(deftest proxy->opts-default-port
  (testing "SOCKS5 URL without explicit port still produces a valid marker"
    #?(:clj
       (let [opts (http/proxy->opts "socks5://127.0.0.1")]
         (is (map? opts))
         (is (contains? opts :__socks5__))))))

#?(:bb
   (deftest proxy->opts-bb-socks5
     (testing "BB path passes string through to bb http-client"
       (is (= {:proxy "socks5://127.0.0.1:9050"}
              (http/proxy->opts "socks5://127.0.0.1:9050"))))))

#?(:clj
   (deftest read-chunked-decodes-chunked-body
     (testing "chunked transfer-encoded body is decoded into raw bytes"
       (let [payload "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
             in (java.io.ByteArrayInputStream. (.getBytes payload "UTF-8"))
             out (#'http/read-chunked in)]
         (is (= "hello world" (String. ^bytes out "UTF-8")))))))

#?(:clj
   (deftest read-chunked-handles-trailing-headers
     (testing "end chunk followed by trailing CRLF is consumed correctly"
       (let [payload "3\r\nfoo\r\n0\r\n\r\n"
             in (java.io.ByteArrayInputStream. (.getBytes payload "UTF-8"))
             out (#'http/read-chunked in)]
         (is (= "foo" (String. ^bytes out "UTF-8")))))))

#?(:clj
   (deftest gunzip-decompresses
     (testing "gzip byte array is decompressed"
       (let [orig "hello hello hello"
             baos (java.io.ByteArrayOutputStream.)
             _ (with-open [gz-out (java.util.zip.GZIPOutputStream. baos)]
                 (.write gz-out (.getBytes orig "UTF-8")))
             gz-bytes (.toByteArray baos)
             out (#'http/gunzip gz-bytes)]
         (is (= orig (String. ^bytes out "UTF-8")))))))

#?(:clj
   (deftest maybe-decode-skips-uncompressed
     (testing "bodies with no Content-Encoding pass through unchanged"
       (let [body (.getBytes "plain" "UTF-8")
             out (#'http/maybe-decode body {:other-header "x"})]
         (is (identical? body out))))))
