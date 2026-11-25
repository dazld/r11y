(ns r11y.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [r11y.core :as core])
  (:import (java.io IOException)
           (java.net UnknownHostException)
           (java.net.http HttpConnectTimeoutException HttpTimeoutException)))

(deftest parse-args-stdin
  (testing "- reads from stdin"
    (is (= "-" (:url (core/parse-args ["-"])))))
  (testing "--base-url and -u set the base URL"
    (is (= "https://example.com"
           (:base-url (core/parse-args ["--base-url" "https://example.com" "-"]))))
    (is (= "https://example.com"
           (:base-url (core/parse-args ["-" "-u" "https://example.com"])))))
  (testing "--base-url needs a value"
    (is (= "Missing value for --base-url" (:error (core/parse-args ["--base-url"])))))
  (testing "- and a URL together are two inputs"
    (is (:error (core/parse-args ["https://example.com" "-"])))))

(deftest dropped-connection
  (testing "resets, GOAWAYs and timeouts are dropped connections"
    (is (core/dropped-connection? (IOException. "Received RST_STREAM: Internal error")))
    (is (core/dropped-connection? (IOException. "Connection reset")))
    (is (core/dropped-connection? (HttpTimeoutException. "request timed out")))
    (is (core/dropped-connection? (RuntimeException. "wrapped" (IOException. "GOAWAY received")))))
  (testing "other failures are not"
    (is (not (core/dropped-connection? (HttpConnectTimeoutException. "HTTP connect timed out"))))
    (is (not (core/dropped-connection? (IOException. "connect timed out"))))
    (is (not (core/dropped-connection? (UnknownHostException. "nope.invalid"))))
    (is (not (core/dropped-connection? (IllegalArgumentException. "bad"))))))

(deftest error-message-suggests-stdin
  (let [url "https://example.com/a"]
    (is (re-find #"r11y --base-url 'https://example.com/a' < page.html"
                 (core/error-message url (IOException. "Received RST_STREAM: Internal error"))))
    (is (= "Error extracting content: bad"
           (core/error-message url (IllegalArgumentException. "bad"))))))

(deftest shell-quote
  (is (= "'https://example.com/a'" (core/shell-quote "https://example.com/a")))
  (is (= "'https://example.com/it'\\''s'" (core/shell-quote "https://example.com/it's"))))

(deftest error-message-without-exception-messages
  (testing "an unknown host is named, though every message in the chain is nil"
    (is (= "Error extracting content: cannot resolve the host name"
           (core/error-message "https://nope.invalid/"
                               (doto (java.net.ConnectException.)
                                 (.initCause (java.nio.channels.UnresolvedAddressException.)))))))
  (testing "a nil message falls back to a cause's message, then the class name"
    (is (= "Error extracting content: inner"
           (core/error-message "https://x.test/"
                               (RuntimeException. nil (IllegalStateException. "inner")))))
    (is (= "Error extracting content: IllegalStateException"
           (core/error-message "https://x.test/" (IllegalStateException.))))))
