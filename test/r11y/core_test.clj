(ns r11y.core-test
  (:require [clojure.test :refer [deftest is]]
            [r11y.core :as core]))

(deftest test-parse-args-content-file-long
  (is (= {:link-density-threshold 0.5 :with-metadata false :url "https://example.com"
          :content-file "/tmp/rendered.html"}
         (core/parse-args ["--content-file" "/tmp/rendered.html" "https://example.com"]))))

(deftest test-parse-args-content-file-short-with-metadata
  (is (= {:link-density-threshold 0.5 :with-metadata true :url "https://example.com"
          :content-file "/tmp/rendered.html"}
         (core/parse-args ["-f" "/tmp/rendered.html" "-m" "https://example.com"]))))

(deftest test-content-file-requires-a-value
  (is (:error (core/parse-args ["https://example.com" "--content-file"]))))

(deftest test-url-still-required-with-content-file
  (is (nil? (:url (core/parse-args ["-f" "/tmp/rendered.html"])))))

(deftest test-content-file-dash-is-stdin
  (is (= "-" (:content-file (core/parse-args ["-f" "-" "https://example.com"])))))

(deftest test-read-content-from-stdin
  (is (= "<html>from a pipe</html>"
         (with-in-str "<html>from a pipe</html>" (core/read-content "-")))))

(deftest test-read-content-rejects-blank-stdin
  (is (thrown-with-msg? Exception #"no HTML on stdin"
                        (with-in-str "   \n" (core/read-content "-"))))
  (is (thrown-with-msg? Exception #"no HTML on stdin"
                        (with-in-str "" (core/read-content "-")))))

(deftest test-read-content-from-a-file
  (let [f (java.io.File/createTempFile "r11y-content" ".html")]
    (try
      (spit (.getPath f) "<html>from a file</html>")
      (is (= "<html>from a file</html>" (core/read-content (.getPath f))))
      (finally (.delete f)))))

(deftest test-read-content-rejects-a-blank-file
  (let [f (java.io.File/createTempFile "r11y-empty" ".html")]
    (try
      (spit (.getPath f) " \n\t ")
      (is (thrown-with-msg? Exception #"file contains no HTML"
                            (core/read-content (.getPath f))))
      (finally (.delete f)))))
