(ns r11y.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
  "Run args, with stdin holding input if given; return {:status :out :err}."
  ([args] (run-captured args nil))
  ([args ^String input]
   (let [err (java.io.StringWriter.)
         status (atom nil)
         stdin System/in
         out (try
               (when input
                 (System/setIn (java.io.ByteArrayInputStream. (.getBytes input "UTF-8"))))
               (with-out-str
                 (binding [*err* err]
                   (reset! status (core/run args))))
               (finally (System/setIn stdin)))]
     {:status @status :out out :err (str err)})))

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

(deftest fetch-limit-options
  (testing "--timeout takes seconds and --max-size megabytes"
    (let [opts (core/parse-args ["-t" "2.5" "--max-size" "3" "https://example.com"])]
      (is (= 2500 (:timeout opts)))
      (is (= (* 3 1024 1024) (:max-bytes opts)))))
  (testing "a missing, non-numeric or non-positive value is a usage error"
    (doseq [args [["--timeout"] ["-t" "soon" "https://a.test"] ["--max-size" "0" "https://a.test"]
                  ["--max-size" "-1" "https://a.test"] ["--timeout" "NaN" "https://a.test"]]]
      (is (= 2 (:status (run-captured args))) (pr-str args))))
  (testing "the limits are for fetching, not stdin"
    (is (= 2 (:status (run-captured ["-t" "5" "-"] "<p>x</p>"))))))

(deftest base-url-must-be-absolute-http
  (doseq [base ["example.com/post" "https://x.test/a b" "ftp://x.test/"]]
    (let [{:keys [status err]} (run-captured ["-u" base "-"] "<p>x</p>")]
      (is (= 2 status) base)
      (is (re-find #"--base-url must be an absolute" err) base))))

(def ^:private article
  (str "<html><head><script>var a=b**2;c=d**3;</script></head><body><article><h1>Title</h1><p>"
       (apply str (repeat 30 "Some words of real content. "))
       "<a href=\"/next\">Next</a></p></article></body></html>"))

(deftest stdin-html-is-extracted
  (testing "HTML with Markdown-like text near the top is still parsed as HTML"
    (let [{:keys [status out]} (run-captured ["-u" "https://example.com/a/" "-"] article)]
      (is (= 0 status))
      (is (re-find #"(?m)^# Title" out))
      (is (re-find #"\(https://example.com/next\)" out))
      (is (not (re-find #"<script>" out)))))
  (testing "a canonical URL that java.net.URI rejects is ignored, not fatal"
    (let [html (str "<html><head><link rel=\"canonical\" href=\"https://site.test/a b\"></head>"
                    (subs article (count "<html><head>")))
          {:keys [status out err]} (run-captured ["-"] html)]
      (is (= 0 status) err)
      (is (re-find #"(?m)^# Title" out)))))

(deftest stdin-markdown-passes-through
  (let [md "---\ntitle: Notes\nauthor: Someone\n---\n\n# Notes\n\n- one\n- two\n"]
    (testing "frontmatter is stripped without -m"
      (let [{:keys [status out]} (run-captured ["-"] md)]
        (is (= 0 status))
        (is (str/starts-with? out "# Notes"))))
    (testing "with -m, frontmatter is rebuilt in r11y's schema"
      (let [{:keys [out]} (run-captured ["-m" "-u" "https://site.test/notes" "-"] md)]
        (is (str/starts-with? out "---"))
        (is (re-find #"(?m)^title: Notes$" out))
        (is (re-find #"(?m)^hostname: site.test$" out))
        (is (re-find #"(?m)^# Notes$" out))))))

(deftest limit-error-messages
  (let [url "https://slow.test/page"]
    (testing "a timeout names the limit and --timeout, not a dropped connection"
      (let [e (ex-info "Timed out" {:type :r11y/timeout :url url :timeout-ms 30000}
                       (java.net.http.HttpTimeoutException. "request timed out"))
            msg (core/error-message url e)]
        (is (re-find #"no complete response within 30 s" msg))
        (is (re-find #"--timeout" msg))
        (is (not (re-find #"dropped" msg)))))
    (testing "a page over the size limit names it and --max-size"
      (let [msg (core/error-message url (ex-info "big" {:type :r11y/body-too-large :url url
                                                         :max-bytes (* 10 1024 1024)}))]
        (is (re-find #"larger than 10 MB" msg))
        (is (re-find #"--max-size" msg))))))
