(ns r11y.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [r11y.core :as core]))

(deftest parse-args-default
  (testing "Defaults: no flags, just a URL"
    (is (= {:link-density-threshold 0.5 :with-metadata false :proxy nil :url "https://example.com"}
           (core/parse-args ["https://example.com"])))))

(deftest parse-args-with-metadata
  (testing "-m enables with-metadata"
    (is (= true (:with-metadata (core/parse-args ["-m" "https://example.com"]))))
    (is (= true (:with-metadata (core/parse-args ["--with-metadata" "https://example.com"]))))))

(deftest parse-args-link-density
  (testing "-l sets link density"
    (is (= 0.3 (:link-density-threshold (core/parse-args ["-l" "0.3" "https://example.com"]))))
    (is (= 0.8 (:link-density-threshold (core/parse-args ["--link-density" "0.8" "https://example.com"])))))
  (testing "Invalid link density returns error"
    (is (some? (:error (core/parse-args ["-l" "abc" "https://example.com"]))))
    (is (some? (:error (core/parse-args ["-l" "1.5" "https://example.com"]))))))

(deftest parse-args-proxy
  (testing "-p captures proxy string"
    (is (= "socks5://127.0.0.1:9050"
           (:proxy (core/parse-args ["-p" "socks5://127.0.0.1:9050" "https://example.com"])))))
  (testing "--proxy (long form) captures proxy string"
    (is (= "socks5://10.0.1.23:9090"
           (:proxy (core/parse-args ["--proxy" "socks5://10.0.1.23:9090" "https://example.com"])))))
  (testing "Proxy combined with other flags"
    (is (= {:link-density-threshold 0.3
            :with-metadata true
            :proxy "socks5://127.0.0.1:9050"
            :url "https://example.com"}
           (core/parse-args ["-m" "-l" "0.3" "-p" "socks5://127.0.0.1:9050" "https://example.com"]))))
  (testing "Proxy flag without value returns error"
    (is (some? (:error (core/parse-args ["-p"]))))))

(deftest parse-args-help-and-version
  (testing "-h sets help flag"
    (is (true? (:help (core/parse-args ["-h"])))))
  (testing "-v sets version flag"
    (is (true? (:version (core/parse-args ["-v"]))))))

(deftest parse-args-errors
  (testing "Multiple URLs return error"
    (is (some? (:error (core/parse-args ["https://a.com" "https://b.com"])))))
  (testing "Unknown option returns error"
    (is (some? (:error (core/parse-args ["--foo" "https://example.com"]))))))
