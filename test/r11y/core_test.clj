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
