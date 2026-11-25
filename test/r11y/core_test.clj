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

(deftest parse-args-end-of-options
  (testing "-- makes the next argument the URL, even if it starts with -"
    (is (= "-not-an-option" (:url (core/parse-args ["--" "-not-an-option"])))))
  (testing "options before -- still apply"
    (let [opts (core/parse-args ["-m" "--" "https://example.com"])]
      (is (:with-metadata opts))
      (is (= "https://example.com" (:url opts)))))
  (testing "-- with nothing after it leaves the URL unset"
    (is (nil? (:url (core/parse-args ["--"])))))
  (testing "only one URL, before or after --"
    (is (:error (core/parse-args ["--" "https://a.test" "https://b.test"])))
    (is (:error (core/parse-args ["https://a.test" "--" "https://b.test"])))))

(defn- run-captured
  "Run args; return {:status :out :err}."
  [args]
  (let [err (java.io.StringWriter.)
        status (atom nil)
        out (with-out-str
              (binding [*err* err]
                (reset! status (core/run args))))]
    {:status @status :out out :err (str err)}))

(deftest exit-status-and-streams
  (testing "a wrong command line exits 2, with the error and usage on stderr only"
    (doseq [args [["--bogus"]
                  ["-l" "5" "https://example.com"]
                  ["--base-url" "https://example.com" "https://example.com"]
                  ["https://a.test" "https://b.test"]]]
      (let [{:keys [status out err]} (run-captured args)]
        (is (= 2 status) (pr-str args))
        (is (= "" out) (pr-str args))
        (is (re-find #"^Error: " err) (pr-str args))
        (is (re-find #"Usage: r11y" err) (pr-str args)))))
  (testing "help and version go to stdout with status 0"
    (doseq [args [["-h"] ["--version"]]]
      (let [{:keys [status out err]} (run-captured args)]
        (is (= 0 status))
        (is (seq out))
        (is (= "" err))))))
