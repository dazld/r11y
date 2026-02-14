(ns r11y.lib.json-ld-test
  (:require [clojure.test :refer [deftest is testing]]
            [r11y.lib.html :as html])
  (:import [org.jsoup Jsoup]))

;; Test extraction of different JSON-LD schema types

(deftest test-article-schema
  (testing "Extract metadata from Article schema"
    (let [json-ld "{
        \"@context\": \"https://schema.org\",
        \"@type\": \"Article\",
        \"headline\": \"Understanding JSON-LD\",
        \"author\": {
          \"@type\": \"Person\",
          \"name\": \"John Smith\"
        },
        \"datePublished\": \"2025-01-15T10:00:00Z\",
        \"dateModified\": \"2025-01-16T12:00:00Z\",
        \"description\": \"A comprehensive guide to JSON-LD\",
        \"publisher\": {
          \"@type\": \"Organization\",
          \"name\": \"Tech Blog\"
        }
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/article")]
      (is (= "Understanding JSON-LD" (:title metadata)) "Headline should be extracted as title")
      (is (= "John Smith" (:author metadata)) "Author name should be extracted")
      (is (= "2025-01-15T10:00:00Z" (:date metadata)) "Date published should be extracted")
      (is (= "A comprehensive guide to JSON-LD" (:description metadata)) "Description should be extracted")
      (is (= "Tech Blog" (:sitename metadata)) "Publisher name should be extracted as sitename"))))

(deftest test-newsarticle-schema
  (testing "Extract metadata from NewsArticle schema"
    (let [json-ld "{
        \"@context\": \"https://schema.org\",
        \"@type\": \"NewsArticle\",
        \"headline\": \"Breaking News Story\",
        \"author\": {
          \"@type\": \"Person\",
          \"name\": \"Jane Reporter\"
        },
        \"datePublished\": \"2025-01-20T08:00:00Z\",
        \"publisher\": {
          \"@type\": \"Organization\",
          \"name\": \"News Network\",
          \"logo\": {
            \"@type\": \"ImageObject\",
            \"url\": \"https://example.com/logo.png\"
          }
        }
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/news")]
      (is (= "Breaking News Story" (:title metadata)))
      (is (= "Jane Reporter" (:author metadata)))
      (is (= "News Network" (:sitename metadata))))))

(deftest test-blogposting-schema
  (testing "Extract metadata from BlogPosting schema"
    (let [json-ld "{
        \"@context\": \"https://schema.org\",
        \"@type\": \"BlogPosting\",
        \"headline\": \"My Blog Post\",
        \"author\": {
          \"@type\": \"Person\",
          \"name\": \"Blogger Person\"
        },
        \"datePublished\": \"2025-01-10T14:30:00Z\",
        \"dateCreated\": \"2025-01-09T10:00:00Z\"
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/blog")]
      (is (= "My Blog Post" (:title metadata)))
      (is (= "Blogger Person" (:author metadata)))
      (is (= "2025-01-10T14:30:00Z" (:date metadata)) "Should prefer datePublished over dateCreated"))))

(deftest test-webpage-schema
  (testing "Extract metadata from WebPage schema"
    (let [json-ld "{
        \"@context\": \"https://schema.org\",
        \"@type\": \"WebPage\",
        \"name\": \"About Us Page\",
        \"description\": \"Learn more about our company\",
        \"publisher\": {
          \"@type\": \"Organization\",
          \"name\": \"Company Name\"
        }
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/about")]
      (is (= "About Us Page" (:title metadata)) "WebPage uses 'name' field")
      (is (= "Learn more about our company" (:description metadata)))
      (is (= "Company Name" (:sitename metadata))))))

;; Test nested structures in JSON-LD

(deftest test-nested-author-object
  (testing "Extract author from nested Person object"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"Test Article\",
        \"author\": {
          \"@type\": \"Person\",
          \"name\": \"Nested Author\",
          \"email\": \"author@example.com\",
          \"url\": \"https://example.com/author\"
        }
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (= "Nested Author" (:author metadata)) "Should extract name from nested author object"))))

(deftest test-nested-publisher-object
  (testing "Extract publisher from nested Organization object"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"Test Article\",
        \"publisher\": {
          \"@type\": \"Organization\",
          \"name\": \"Publisher Inc\",
          \"url\": \"https://publisher.example.com\",
          \"logo\": {
            \"@type\": \"ImageObject\",
            \"url\": \"https://example.com/logo.png\"
          }
        }
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (= "Publisher Inc" (:sitename metadata)) "Should extract name from nested publisher object"))))

(deftest test-deeply-nested-values
  (testing "Handle deeply nested values"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"Test\",
        \"author\": {
          \"@type\": \"Person\",
          \"name\": {
            \"@value\": \"Deep Name\"
          }
        }
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (= "Deep Name" (:author metadata)) "Should extract @value from deeply nested structure"))))

;; Test array handling in JSON-LD

(deftest test-multiple-authors-array
  (testing "Extract first author from array of authors"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"Multi-author Article\",
        \"author\": [
          {\"@type\": \"Person\", \"name\": \"First Author\"},
          {\"@type\": \"Person\", \"name\": \"Second Author\"},
          {\"@type\": \"Person\", \"name\": \"Third Author\"}
        ]
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (= "First Author" (:author metadata)) "Should extract first author from array"))))

(deftest test-author-array-of-strings
  (testing "Extract first author from array of string values"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"Test\",
        \"author\": [\"Author One\", \"Author Two\", \"Author Three\"]
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (= "Author One" (:author metadata)) "Should extract first string from author array"))))

(deftest test-multiple-json-ld-scripts
  (testing "Handle multiple JSON-LD scripts (use first)"
    (let [json-ld1 "{\"@type\": \"Article\", \"headline\": \"First Title\", \"author\": {\"name\": \"First Author\"}}"
          json-ld2 "{\"@type\": \"Article\", \"headline\": \"Second Title\", \"author\": {\"name\": \"Second Author\"}}"
          html-str (str "<html><head>"
                        "<script type='application/ld+json'>" json-ld1 "</script>"
                        "<script type='application/ld+json'>" json-ld2 "</script>"
                        "</head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (= "First Title" (:title metadata)) "Should use first JSON-LD script")
      (is (= "First Author" (:author metadata)) "Should use first JSON-LD script"))))

(deftest test-graph-array
  (testing "Handle @graph array in JSON-LD"
    (let [json-ld "{
        \"@context\": \"https://schema.org\",
        \"@graph\": [
          {
            \"@type\": \"WebSite\",
            \"name\": \"Example Site\"
          },
          {
            \"@type\": \"Article\",
            \"headline\": \"Article in Graph\",
            \"author\": {\"name\": \"Graph Author\"},
            \"datePublished\": \"2025-01-15\"
          }
        ]
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      ;; Note: Current implementation may not handle @graph - this tests current behavior
      (is (map? metadata) "Should return metadata map even with @graph"))))

;; Test field name variations

(deftest test-headline-vs-name
  (testing "Prefer headline over name for title"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"Headline Title\",
        \"name\": \"Name Title\"
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (= "Headline Title" (:title metadata)) "Should prefer headline over name"))))

(deftest test-datepublished-vs-datecreated
  (testing "Prefer datePublished over dateCreated"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"Test\",
        \"datePublished\": \"2025-01-15T10:00:00Z\",
        \"dateCreated\": \"2025-01-10T08:00:00Z\"
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (= "2025-01-15T10:00:00Z" (:date metadata)) "Should prefer datePublished"))))

(deftest test-only-datecreated
  (testing "Use dateCreated if datePublished is not present"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"Test\",
        \"dateCreated\": \"2025-01-10T08:00:00Z\"
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (= "2025-01-10T08:00:00Z" (:date metadata)) "Should use dateCreated when datePublished absent"))))

;; Test malformed and edge case JSON-LD

(deftest test-empty-json-ld
  (testing "Handle empty JSON-LD gracefully"
    (let [json-ld "{}"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (map? metadata) "Should return metadata map")
      (is (= "https://example.com/test" (:url metadata)) "Should still extract URL from base"))))

(deftest test-null-values-in-json-ld
  (testing "Handle null values in JSON-LD"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"Test\",
        \"author\": null,
        \"datePublished\": null,
        \"description\": \"Valid description\"
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (= "Test" (:title metadata)) "Should extract non-null values")
      (is (= "Valid description" (:description metadata)) "Should extract valid description")
      (is (or (nil? (:author metadata)) (= "" (:author metadata))) "Null author should be nil or empty"))))

(deftest test-malformed-json-syntax
  (testing "Handle malformed JSON syntax"
    (let [json-ld "{ invalid json syntax }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (map? metadata) "Should return metadata map even with invalid JSON")
      (is (= "https://example.com/test" (:url metadata)) "Should still extract URL"))))

(deftest test-json-ld-with-comments
  (testing "Handle JSON-LD with comments (invalid JSON)"
    (let [json-ld "{
        // This is a comment
        \"@type\": \"Article\",
        \"headline\": \"Test\"
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      ;; Comments make JSON invalid, so this should fail gracefully
      (is (map? metadata) "Should return metadata map"))))

(deftest test-string-author-instead-of-object
  (testing "Handle author as string instead of object"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"Test\",
        \"author\": \"String Author Name\"
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (= "String Author Name" (:author metadata)) "Should handle author as string"))))

(deftest test-missing-author-name
  (testing "Handle author object without name field"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"Test\",
        \"author\": {
          \"@type\": \"Person\",
          \"email\": \"author@example.com\"
        }
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (or (nil? (:author metadata)) (= "" (:author metadata)))
          "Should handle missing name in author object"))))

(deftest test-empty-strings
  (testing "Handle empty string values"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"\",
        \"author\": {\"name\": \"\"},
        \"description\": \"\"
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (map? metadata) "Should return metadata map")
      ;; Empty strings might be filtered out by first-non-blank
      (is (or (= "" (:title metadata)) (nil? (:title metadata))) "Empty headline should result in empty or nil"))))

(deftest test-date-formats
  (testing "Handle various date formats"
    (let [test-dates [["2025-01-15T10:00:00Z" "ISO 8601 with Z"]
                      ["2025-01-15T10:00:00+00:00" "ISO 8601 with timezone"]
                      ["2025-01-15" "Date only"]
                      ["2025-01-15T10:00:00" "ISO 8601 without timezone"]]]
      (doseq [[date-str description] test-dates]
        (let [json-ld (str "{\"@type\": \"Article\", \"headline\": \"Test\", \"datePublished\": \"" date-str "\"}")
              html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
              doc (Jsoup/parse html-str)
              metadata (html/extract-metadata doc "https://example.com/test")]
          (is (= date-str (:date metadata)) (str "Should handle " description)))))))

(deftest test-unicode-in-json-ld
  (testing "Handle Unicode characters in JSON-LD"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"测试文章 - مقالة اختبار\",
        \"author\": {\"name\": \"Владимир Петров\"},
        \"description\": \"🎉 Unicode test 你好\"
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (= "测试文章 - مقالة اختبار" (:title metadata)) "Should handle Unicode in title")
      (is (= "Владимир Петров" (:author metadata)) "Should handle Cyrillic in author")
      (is (= "🎉 Unicode test 你好" (:description metadata)) "Should handle emoji and Unicode"))))

(deftest test-escaped-characters-in-json
  (testing "Handle escaped characters in JSON"
    (let [json-ld "{
        \"@type\": \"Article\",
        \"headline\": \"Title with \\\"quotes\\\" and \\\\ backslash\",
        \"description\": \"Line 1\\nLine 2\"
      }"
          html-str (str "<html><head><script type='application/ld+json'>" json-ld "</script></head><body><p>Content</p></body></html>")
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/test")]
      (is (re-find #"quotes" (:title metadata)) "Should handle escaped quotes")
      (is (re-find #"Line 1" (:description metadata)) "Should handle escaped newlines"))))
