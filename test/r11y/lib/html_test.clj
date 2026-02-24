(ns r11y.lib.html-test
  (:require [clojure.test :refer [deftest is testing]]
            [r11y.lib.html :as html])
  (:import [org.jsoup Jsoup]))


(deftest test-link-and-image-deduplication
  (testing "Links and images are deduplicated by URL"
    (let [html-str "<html><body><a href='/page1'>Page 1</a><a href='/page1'>Page 1 again</a><img src='/img1.jpg' alt=''><img src='/img1.jpg' alt='again'></body></html>"
          result (html/extract-content html-str :base-url "https://example.com")]
      (is (= 1 (count (:links result))) "Links should be deduplicated by URL")
      (is (= 1 (count (:images result))) "Images should be deduplicated by URL"))))

(deftest test-anchor-link-filtering
  (testing "Anchor links are filtered from the links array"
    (let [html-str "<html><body><a href='/page1'>Page 1</a><a href='#section1'>Section 1</a></body></html>"
          result (html/extract-content html-str :base-url "https://example.com")]
      (is (= 1 (count (:links result))) "Anchor links should be filtered out")
      (is (= "https://example.com/page1" (:url (first (:links result)))) "Only non-anchor links should remain"))))


(deftest canonical-url
  (testing "Canonical URLs are found and shown, and not canonical if no match"
    (let [html-str "<html><head><link rel='canonical' href='https://example.com/foo'></head><body><a href='/page1'>Page 1</a><a href='#section1'>Section 1</a></body></html>"
          result (html/extract-content html-str :base-url "https://example.com" :with-metadata true)]

      (is (= "https://example.com/foo" (-> result :metadata :canonical-url)))
      (is (false? (-> result :metadata :is-canonical)))))
  (testing "When base url and canonical URL match, URL is canonical"
    (let [html-str "<html><head><link rel='canonical' href='https://example.com/foo'></head><body><a href='/page1'>Page 1</a><a href='#section1'>Section 1</a></body></html>"
          result (html/extract-content html-str :base-url "https://example.com/foo" :with-metadata true)]

      (is (= "https://example.com/foo" (-> result :metadata :canonical-url)))
      (is (true? (-> result :metadata :is-canonical))))))

(deftest test-url-resolution-in-images
  (testing "Image src attributes are resolved to absolute URLs"
    (let [html-str "<html><body><img src='/foo/bar.jpg' alt='Root relative'/><img src='relative.jpg' alt='Relative'/><img src='https://example.com/absolute.jpg' alt='Absolute'/></body></html>"
          base-url "https://bla.com/some/doc"
          result (:markdown (html/extract-content html-str :base-url base-url :format :markdown))]
      (is (re-find #"!\[Root relative\]\(https://bla\.com/foo/bar\.jpg\)" result)
          "Root-relative image path should resolve to absolute URL")
      (is (re-find #"!\[Relative\]\(https://bla\.com/some/relative\.jpg\)" result)
          "Relative image path should resolve to absolute URL")
      (is (re-find #"!\[Absolute\]\(https://example\.com/absolute\.jpg\)" result)
          "Absolute image path should remain unchanged"))))

(deftest test-url-resolution-in-links
  (testing "Link href attributes are resolved to absolute URLs"
    (let [html-str "<html><body><a href='/path/page'>Root</a><a href='../other'>Parent</a><a href='https://example.com/external'>External</a></body></html>"
          base-url "https://bla.com/some/doc"
          result (:markdown (html/extract-content html-str :base-url base-url :format :markdown))]
      (is (re-find #"\[Root\]\(https://bla\.com/path/page\)" result)
          "Root-relative link should resolve to absolute URL")
      (is (re-find #"\[Parent\]\(https://bla\.com/other\)" result)
          "Parent directory link should resolve correctly")
      (is (re-find #"\[External\]\(https://example\.com/external\)" result)
          "External absolute link should remain unchanged"))))

(deftest test-url-resolution-with-anchors
  (testing "Anchor links are resolved with base URL"
    (let [html-str "<html><body><a href='#section'>Section</a><a href='page#anchor'>Page anchor</a></body></html>"
          base-url "https://bla.com/some/doc"
          result (:markdown (html/extract-content html-str :base-url base-url :format :markdown))]
      (is (re-find #"\[Section\]\(https://bla\.com/some/doc#section\)" result)
          "Anchor-only link should resolve with base URL")
      (is (re-find #"\[Page anchor\]\(https://bla\.com/some/page#anchor\)" result)
          "Relative path with anchor should resolve correctly"))))

(deftest test-url-resolution-preserves-protocols
  (testing "Various URL protocols are handled correctly"
    (let [html-str "<html><body><a href='mailto:test@example.com'>Email</a><a href='javascript:void(0)'>JS</a><a href='data:text/html,test'>Data</a></body></html>"
          base-url "https://bla.com/some/doc"
          result (:markdown (html/extract-content html-str :base-url base-url :format :markdown))]
      (is (re-find #"mailto:test@example\.com" result)
          "Mailto links should be preserved")
      (is (re-find #"javascript:void\(0\)" result)
          "JavaScript URLs should be preserved")
      (is (re-find #"data:text/html,test" result)
          "Data URLs should be preserved"))))

(deftest test-url-resolution-with-query-params
  (testing "Query parameters and fragments are preserved"
    (let [html-str "<html><body><a href='/search?q=test'>Search</a><img src='/img.jpg?v=123' alt='Versioned'/></body></html>"
          base-url "https://bla.com/page"
          result (:markdown (html/extract-content html-str :base-url base-url :format :markdown))]
      (is (re-find #"\[Search\]\(https://bla\.com/search\?q=test\)" result)
          "Query parameters should be preserved in links")
      (is (re-find #"!\[Versioned\]\(https://bla\.com/img\.jpg\?v=123\)" result)
          "Query parameters should be preserved in images"))))

(deftest test-resolve-and-classify-links-function
  (testing "resolve-and-classify-links modifies document in place"
    (let [html-str "<html><body><a href='/internal'>Internal</a><a href='https://external.com/page'>External</a><img src='/img.jpg'/></body></html>"
          base-url "https://bla.com/page"
          doc (Jsoup/parse html-str)
          result-doc (html/resolve-and-classify-links doc base-url)]
      ;; Check that hrefs are resolved
      (is (= "https://bla.com/internal"
             (.attr (.selectFirst result-doc "a[href='/internal'], a[href*='bla.com/internal']") "href"))
          "Internal link should be resolved")
      (is (= "https://external.com/page"
             (.attr (.selectFirst result-doc "a[href*='external.com']") "href"))
          "External link should remain unchanged")
      ;; Check that src is resolved
      (is (= "https://bla.com/img.jpg"
             (.attr (.selectFirst result-doc "img") "src"))
          "Image src should be resolved")
      ;; Check internal/external classification
      (is (= "internal"
             (.attr (.selectFirst result-doc "a[href*='bla.com/internal']") "--mnp-cleaning--data-link-type"))
          "Internal link should be marked as internal")
      (is (= "external"
             (.attr (.selectFirst result-doc "a[href*='external.com']") "--mnp-cleaning--data-link-type"))
          "External link should be marked as external"))))

(deftest test-url-resolution-with-base-path
  (testing "Base URL path affects relative URL resolution"
    (let [html-str "<html><body><img src='image.jpg'/><a href='page.html'>Page</a></body></html>"]
      ;; Test with base URL ending in /
      (let [result1 (:markdown (html/extract-content html-str :base-url "https://example.com/dir/" :format :markdown))]
        (is (re-find #"https://example\.com/dir/image\.jpg" result1)
            "Relative URL should resolve within directory when base ends with /")
        (is (re-find #"https://example\.com/dir/page\.html" result1)
            "Relative link should resolve within directory when base ends with /"))
      ;; Test with base URL ending in filename
      (let [result2 (:markdown (html/extract-content html-str :base-url "https://example.com/dir/doc.html" :format :markdown))]
        (is (re-find #"https://example\.com/dir/image\.jpg" result2)
            "Relative URL should resolve in same directory as base document")
        (is (re-find #"https://example\.com/dir/page\.html" result2)
            "Relative link should resolve in same directory as base document")))))

(deftest test-url-resolution-without-base-url
  (testing "Content extraction works without base URL (no resolution)"
    (let [html-str "<html><body><p>Test content</p><img src='/img.jpg'/><a href='/page'>Link</a></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      ;; URLs should remain as-is when no base URL provided
      (is (re-find #"!\[\]\(/img\.jpg\)" result)
          "Image src should remain relative without base URL")
      (is (re-find #"\[Link\]\(/page\)" result)
          "Link href should remain relative without base URL"))))

;; HTML to Markdown conversion tests

(deftest test-headings-conversion
  (testing "HTML headings convert to Markdown headings"
    (let [html-str "<html><body><h1>Heading 1</h1><h2>Heading 2</h2><h3>Heading 3</h3><h4>Heading 4</h4><h5>Heading 5</h5><h6>Heading 6</h6></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"# Heading 1" result) "H1 should convert to #")
      (is (re-find #"## Heading 2" result) "H2 should convert to ##")
      (is (re-find #"### Heading 3" result) "H3 should convert to ###")
      (is (re-find #"#### Heading 4" result) "H4 should convert to ####")
      (is (re-find #"##### Heading 5" result) "H5 should convert to #####")
      (is (re-find #"###### Heading 6" result) "H6 should convert to ######"))))

(deftest test-text-formatting-conversion
  (testing "Text formatting converts correctly"
    (let [html-str "<html><body><p>This is <strong>bold</strong> and <em>italic</em> text.</p><p>Also <b>bold</b> and <i>italic</i>.</p></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"\*\*bold\*\*" result) "Strong should convert to **bold**")
      (is (re-find #"\*italic\*" result) "Em should convert to *italic*")
      (is (re-find #"Also \*\*bold\*\*" result) "B tag should convert to **bold**")
      (is (re-find #"and \*italic\*" result) "I tag should convert to *italic*"))))

(deftest test-lists-conversion
  (testing "Lists convert to Markdown lists"
    (let [html-str "<html><body><ul><li>Item 1</li><li>Item 2</li><li>Item 3</li></ul></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"- Item 1" result) "Unordered list items should start with -")
      (is (re-find #"- Item 2" result) "Second list item")
      (is (re-find #"- Item 3" result) "Third list item"))))

(deftest test-nested-lists-conversion
  (testing "Nested lists maintain proper indentation"
    (let [html-str "<html><body><ul><li>Parent 1<ul><li>Child 1</li><li>Child 2</li></ul></li><li>Parent 2</li></ul></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"- Parent 1" result) "Parent item should not be indented")
      (is (re-find #"- Child 1" result) "Child items should be present")
      (is (re-find #"- Child 2" result) "Second child item")
      (is (re-find #"- Parent 2" result) "Second parent item"))))

(deftest test-links-conversion
  (testing "Links convert to Markdown links"
    (let [html-str "<html><body><p>Check out <a href='https://example.com'>this link</a> for more info.</p></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"\[this link\]\(https://example\.com\)" result) "Links should convert to [text](url)"))))

(deftest test-images-conversion
  (testing "Images convert to Markdown images"
    (let [html-str "<html><body><img src='https://example.com/image.jpg' alt='A beautiful image'/></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"!\[A beautiful image\]\(https://example\.com/image\.jpg\)" result)
          "Images should convert to ![alt](src)"))))

(deftest test-code-blocks-conversion
  (testing "Code blocks preserve formatting"
    (let [html-str "<html><body><pre><code>function hello() {\n  console.log('world');\n}</code></pre></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"```" result) "Code blocks should be wrapped in ```")
      (is (re-find #"function hello\(\)" result) "Code content should be preserved")
      (is (re-find #"console\.log" result) "Code should maintain structure"))))

(deftest test-inline-code-conversion
  (testing "Inline code converts correctly"
    (let [html-str "<html><body><p>Use the <code>print()</code> function.</p></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"`print\(\)`" result) "Inline code should be wrapped in backticks"))))

(deftest test-blockquotes-conversion
  (testing "Blockquotes convert correctly"
    (let [html-str "<html><body><blockquote>This is a quote.</blockquote></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"> This is a quote\." result) "Blockquotes should start with >"))))

(deftest test-tables-conversion
  (testing "Tables convert to Markdown tables"
    (let [html-str "<html><body><table><thead><tr><th>Name</th><th>Age</th></tr></thead><tbody><tr><td>Alice</td><td>30</td></tr><tr><td>Bob</td><td>25</td></tr></tbody></table></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"\| Name \| Age \|" result) "Table header should convert")
      (is (re-find #"\| --- \| --- \|" result) "Table separator should be present")
      (is (re-find #"\| Alice \| 30 \|" result) "Table data should convert")
      (is (re-find #"\| Bob \| 25 \|" result) "Second row should convert"))))

(deftest test-horizontal-rule-conversion
  (testing "Horizontal rules convert correctly"
    (let [html-str "<html><body><p>Before</p><hr/><p>After</p></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"---" result) "HR should convert to ---"))))

(deftest test-line-breaks-conversion
  (testing "Line breaks convert correctly"
    (let [html-str "<html><body><p>Line 1<br/>Line 2</p></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"Line 1\nLine 2" result) "BR should convert to newline"))))

;; Metadata extraction tests

(deftest test-metadata-from-title-tag
  (testing "Extract title from title tag"
    (let [html-str "<html><head><title>Test Article Title</title></head><body><p>Content</p></body></html>"
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/page")]
      (is (= "Test Article Title" (:title metadata)) "Title should be extracted from title tag"))))

(deftest test-metadata-from-meta-tags
  (testing "Extract metadata from meta tags"
    (let [html-str "<html><head><meta name='author' content='John Doe'/><meta name='description' content='A great article'/></head><body><p>Content</p></body></html>"
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/page")]
      (is (= "John Doe" (:author metadata)) "Author should be extracted from meta tag")
      (is (= "A great article" (:description metadata)) "Description should be extracted"))))

(deftest test-metadata-from-opengraph
  (testing "Extract metadata from OpenGraph tags"
    (let [html-str "<html><head><meta property='og:title' content='OG Title'/><meta property='og:description' content='OG Description'/><meta property='og:site_name' content='Example Site'/></head><body><p>Content</p></body></html>"
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/page")]
      (is (= "OG Title" (:title metadata)) "OG title should be extracted")
      (is (= "OG Description" (:description metadata)) "OG description should be extracted")
      (is (= "Example Site" (:sitename metadata)) "OG site name should be extracted"))))

(deftest test-metadata-from-json-ld
  (testing "Extract metadata from JSON-LD"
    (let [html-str "<html><head><script type='application/ld+json'>{\"@type\":\"Article\",\"headline\":\"JSON-LD Title\",\"author\":{\"name\":\"Jane Smith\"},\"datePublished\":\"2025-01-15T10:00:00Z\"}</script></head><body><p>Content</p></body></html>"
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/page")]
      (is (= "JSON-LD Title" (:title metadata)) "Title should be extracted from JSON-LD")
      (is (= "Jane Smith" (:author metadata)) "Author should be extracted from JSON-LD")
      (is (= "2025-01-15T10:00:00Z" (:date metadata)) "Date should be extracted from JSON-LD"))))

(deftest test-metadata-hostname-extraction
  (testing "Hostname is extracted from URL"
    (let [html-str "<html><body><p>Content</p></body></html>"
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://www.example.com/path/to/page")]
      (is (= "www.example.com" (:hostname metadata)) "Hostname should be extracted from URL"))))

(deftest test-metadata-date-from-url
  (testing "Date extraction from URL patterns"
    (let [html-str "<html><body><p>Content</p></body></html>"
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/2025/01/15/article-title")]
      (is (= "2025-01-15" (:date metadata)) "Date should be extracted from URL pattern"))))

(deftest test-site-icon-extraction
  (testing "Site icon is extracted and prioritized"
    (let [html-str "<html><head>
                    <link rel='icon' href='/fav.ico' sizes='32x32'>
                    <link rel='apple-touch-icon' href='/apple.png' sizes='180x180'>
                    <link rel='icon' href='/large.png' sizes='192x192'>
                    </head><body></body></html>"
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/page")]
      (is (= "https://example.com/apple.png" (:icon metadata)) "Should prioritize apple-touch-icon")))
  (testing "Site icon falls back to large icon if no apple-touch-icon"
    (let [html-str "<html><head>
                    <link rel='icon' href='/fav.ico' sizes='32x32'>
                    <link rel='icon' href='/large.png' sizes='192x192'>
                    </head><body></body></html>"
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/page")]
      (is (= "https://example.com/large.png" (:icon metadata)) "Should pick largest icon")))
  (testing "Default favicon fallback"
    (let [html-str "<html><head></head><body></body></html>"
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/page")]
      (is (= "https://example.com/favicon.ico" (:icon metadata)) "Should fallback to /favicon.ico"))))

(deftest test-metadata-to-frontmatter
  (testing "Metadata converts to YAML frontmatter"
    (let [metadata {:title "Test Title"
                    :author "Test Author"
                    :url "https://example.com"
                    :hostname "example.com"
                    :description "Test description"
                    :sitename "Example Site"
                    :date "2025-01-15"}
          frontmatter (html/metadata-to-frontmatter metadata)]
      (is (re-find #"^---\n" frontmatter) "Frontmatter should start with ---")
      (is (re-find #"title: Test Title" frontmatter) "Title should be in frontmatter")
      (is (re-find #"author: Test Author" frontmatter) "Author should be in frontmatter")
      (is (re-find #"url: https://example\.com" frontmatter) "URL should be in frontmatter")
      (is (re-find #"\n---\n\n$" frontmatter) "Frontmatter should end with ---"))))

(deftest test-metadata-empty-fields-excluded
  (testing "Empty metadata fields are excluded from frontmatter"
    (let [metadata {:title "Test Title"
                    :author ""
                    :url "https://example.com"
                    :hostname "example.com"
                    :description ""
                    :sitename ""
                    :date ""}
          frontmatter (html/metadata-to-frontmatter metadata)]
      (is (re-find #"title: Test Title" frontmatter) "Non-empty title should be included")
      (is (re-find #"url: https://example\.com" frontmatter) "Non-empty URL should be included")
      (is (not (re-find #"author:" frontmatter)) "Empty author should be excluded")
      (is (not (re-find #"description:" frontmatter)) "Empty description should be excluded"))))

;; Content cleaning and filtering tests

(deftest test-script-removal
  (testing "Script tags are removed"
    (let [html-str "<html><body><p>Content</p><script>alert('bad');</script><p>More content</p></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (not (re-find #"alert" result)) "Script content should be removed")
      (is (re-find #"Content" result) "Regular content should remain")
      (is (re-find #"More content" result) "Content after script should remain"))))

(deftest test-style-removal
  (testing "Style tags are removed"
    (let [html-str "<html><body><style>.test { color: red; }</style><p>Content</p></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (not (re-find #"color: red" result)) "Style content should be removed")
      (is (re-find #"Content" result) "Regular content should remain"))))

(deftest test-nav-removal
  (testing "Navigation elements are removed"
    (let [html-str "<html><body><nav><a href='/home'>Home</a><a href='/about'>About</a></nav><article><p>Main content</p></article></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (not (re-find #"Home" result)) "Nav links should be removed")
      (is (not (re-find #"About" result)) "Nav links should be removed")
      (is (re-find #"Main content" result) "Article content should remain"))))

(deftest test-footer-header-removal
  (testing "Footer and header elements are removed"
    (let [html-str "<html><body><header><h1>Site Header</h1></header><main><p>Main content</p></main><footer><p>Copyright 2025</p></footer></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (not (re-find #"Site Header" result)) "Header should be removed")
      (is (not (re-find #"Copyright" result)) "Footer should be removed")
      (is (re-find #"Main content" result) "Main content should remain"))))

(deftest test-main-content-element-selection
  (testing "Main content is correctly identified"
    (let [html-str "<html><body><aside>Sidebar</aside><article><h1>Article Title</h1><p>Article content</p></article></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"Article Title" result) "Article content should be extracted")
      (is (re-find #"Article content" result) "Article content should be extracted"))))

(deftest test-link-density-filtering
  (testing "Elements with high link density are filtered"
    (let [html-str "<html><body><div><a href='https://ext.com/1'>Link 1</a> <a href='https://ext.com/2'>Link 2</a> <a href='https://ext.com/3'>Link 3</a> word</div><p>Real content with some <a href='https://ext.com/4'>links</a> but mostly text.</p></body></html>"
          result (:markdown (html/extract-content html-str :base-url "https://example.com" :format :markdown :link-density-threshold 0.5))]
      (is (re-find #"Real content" result) "Content with low link density should remain"))))

(deftest test-section-link-density-filtering
  (testing "Section elements with high link density are filtered"
    (let [html-str "<html><body><section><a href='https://ext.com/1'>Link 1</a> <a href='https://ext.com/2'>Link 2</a> <a href='https://ext.com/3'>Link 3</a></section><p>Real content</p></body></html>"
          result (:markdown (html/extract-content html-str :base-url "https://example.com" :format :markdown :link-density-threshold 0.5))]
      (is (not (re-find #"Link 1" result)) "High density section should be removed")
      (is (re-find #"Real content" result) "Real content should remain"))))

(deftest test-empty-list-cleanup
  (testing "Empty lists are removed after their items are filtered"
    (let [html-str "<html><body><ul><li><a href='https://ext.com/1'>Link 1</a></li><li><a href='https://ext.com/2'>Link 2</a></li></ul><p>Real content</p></body></html>"
          result (:markdown (html/extract-content html-str :base-url "https://example.com" :format :markdown :link-density-threshold 0.5))]
      (is (not (re-find #"- " result)) "Empty list should be removed")
      (is (re-find #"Real content" result) "Real content should remain"))))

(deftest test-extract-content-with-metadata
  (testing "Extract content with metadata frontmatter"
    (let [html-str "<html><head><title>Test Title</title><meta name='author' content='Test Author'/></head><body><article><p>Article content</p></article></body></html>"
          result (:markdown (html/extract-content html-str :base-url "https://example.com/test" :format :markdown :with-metadata true))]
      (is (re-find #"^---\n" result) "Should start with frontmatter")
      (is (re-find #"title: Test Title" result) "Should include title in frontmatter")
      (is (re-find #"author: Test Author" result) "Should include author in frontmatter")
      (is (re-find #"Article content" result) "Should include article content"))))

;; Edge cases and malformed HTML tests

(deftest test-empty-html
  (testing "Handle empty HTML gracefully"
    (let [html-str "<html><body></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (string? result) "Should return a string")
      (is (or (empty? (clojure.string/trim result))
              (= "" result)) "Empty HTML should produce empty or minimal output"))))

(deftest test-malformed-html
  (testing "Handle malformed HTML gracefully"
    (let [html-str "<html><body><p>Unclosed paragraph<div>Nested incorrectly</p></div></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (string? result) "Should return a string even with malformed HTML")
      (is (re-find #"Unclosed paragraph" result) "Content should still be extracted")
      (is (re-find #"Nested incorrectly" result) "Nested content should still be extracted"))))

(deftest test-html-with-no-body
  (testing "Handle HTML without body tag"
    (let [html-str "<html><head><title>Test</title></head></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (string? result) "Should handle HTML without body"))))

(deftest test-special-characters-in-content
  (testing "Handle special characters correctly"
    (let [html-str "<html><body><p>Special chars: &lt; &gt; &amp; &quot; &#39;</p></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"Special chars:" result) "Should preserve text with special chars")
      (is (re-find #"<" result) "HTML entities should be decoded")
      (is (re-find #">" result) "HTML entities should be decoded")
      (is (re-find #"&" result) "HTML entities should be decoded"))))

(deftest test-unicode-content
  (testing "Handle Unicode characters correctly"
    (let [html-str "<html><body><p>Unicode: 你好 مرحبا привет 🎉</p></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"你好" result) "Chinese characters should be preserved")
      (is (re-find #"مرحبا" result) "Arabic characters should be preserved")
      (is (re-find #"привет" result) "Cyrillic characters should be preserved")
      (is (re-find #"🎉" result) "Emoji should be preserved"))))

(deftest test-nested-formatting
  (testing "Handle nested formatting tags"
    (let [html-str "<html><body><p>This is <strong>bold with <em>nested italic</em> text</strong>.</p></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"\*\*bold with \*nested italic\* text\*\*" result) "Nested formatting should be preserved"))))

(deftest test-inline-element-spacing
  (testing "Inline elements on separate lines preserve spacing"
    (let [html-str "<p><em>Pretentious diction</em>
. Words like
<em>phenomenon</em>
,
<em>element</em>
,
<em>individual </em>
(as noun),
<em>objective</em>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"\*Pretentious diction\*" result) "Em content should be italic")
      (is (re-find #"Words like \*phenomenon\*" result) "Space before italic word")
      (is (re-find #"\*phenomenon\* ," result) "Space after italic word before comma")
      (is (re-find #", \*element\*" result) "Space after comma before italic word")
      (is (re-find #"\*individual\*" result) "Trailing space inside em should move outside markers")
      (is (re-find #"\(as noun\)" result) "Parenthetical text preserved")))
  (testing "Adjacent inline elements without whitespace"
    (let [result (:markdown (html/extract-content "<p><em>one</em><em>two</em></p>" :format :markdown))]
      (is (= "*one**two*" (clojure.string/trim result)) "No space injected when source has none")))
  (testing "Normal inline usage preserves spacing"
    (let [result (:markdown (html/extract-content "<p>This is <em>italic</em> text.</p>" :format :markdown))]
      (is (re-find #"This is \*italic\* text\." result) "Spaces around inline element preserved")))
  (testing "Bold inline spacing"
    (let [result (:markdown (html/extract-content "<p>A <strong>bold</strong> word.</p>" :format :markdown))]
      (is (re-find #"A \*\*bold\*\* word\." result) "Spaces around bold element preserved"))))

(deftest test-inline-marker-whitespace-movement
  (testing "Leading space inside em moves outside markers"
    (let [result (:markdown (html/extract-content "<p>word<em> italic</em> next</p>" :format :markdown))]
      (is (re-find #"word \*italic\*" result) "Leading space should move outside *")))
  (testing "Trailing space inside em moves outside markers"
    (let [result (:markdown (html/extract-content "<p>prev <em>italic </em>word</p>" :format :markdown))]
      (is (re-find #"\*italic\* word" result) "Trailing space should move outside *")))
  (testing "Leading space inside strong moves outside markers"
    (let [result (:markdown (html/extract-content "<p>word<strong> bold</strong> next</p>" :format :markdown))]
      (is (re-find #"word \*\*bold\*\*" result) "Leading space should move outside **")))
  (testing "Trailing space inside strong moves outside markers"
    (let [result (:markdown (html/extract-content "<p>prev <strong>bold </strong>word</p>" :format :markdown))]
      (is (re-find #"\*\*bold\*\* word" result) "Trailing space should move outside **"))))

(deftest test-whitespace-handling
  (testing "Handle excessive whitespace correctly"
    (let [html-str "<html><body><p>Text    with     multiple     spaces</p><p>Another    paragraph</p></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"Text with multiple spaces" result) "Multiple spaces should be normalized")
      (is (re-find #"Another paragraph" result) "Whitespace normalization should apply throughout"))))

(deftest test-empty-links
  (testing "Handle empty links gracefully"
    (let [html-str "<html><body><a href=''>Empty href</a><a>No href</a></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (string? result) "Should handle empty or missing hrefs")
      (is (re-find #"Empty href" result) "Link text should be preserved")
      (is (re-find #"No href" result) "Link text should be preserved even without href"))))

(deftest test-image-without-alt
  (testing "Handle images without alt text"
    (let [html-str "<html><body><img src='https://example.com/image.jpg'/></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"!\[\]" result) "Images without alt should have empty alt text in markdown"))))

(deftest test-tables-without-thead
  (testing "Handle tables without thead"
    (let [html-str "<html><body><table><tr><th>Header 1</th><th>Header 2</th></tr><tr><td>Data 1</td><td>Data 2</td></tr></table></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"\| Header 1 \| Header 2 \|" result) "Table without thead should still convert headers")
      (is (re-find #"\| Data 1 \| Data 2 \|" result) "Table data should convert"))))

(deftest test-tables-with-pipe-characters
  (testing "Handle pipe characters in table cells"
    (let [html-str "<html><body><table><tr><th>Col 1</th><th>Col 2</th></tr><tr><td>Data | with | pipes</td><td>Normal</td></tr></table></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"Data \\| with \\| pipes" result) "Pipe characters in cells should be escaped"))))

(deftest test-deeply-nested-elements
  (testing "Handle deeply nested elements"
    (let [html-str "<html><body><div><div><div><div><div><p>Deeply nested content</p></div></div></div></div></div></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"Deeply nested content" result) "Deeply nested content should be extracted"))))

(deftest test-mixed-content
  (testing "Handle mixed content types"
    (let [html-str "<html><body><h1>Title</h1><p>Paragraph with <a href='#'>link</a>.</p><ul><li>List item</li></ul><pre><code>code block</code></pre><blockquote>Quote</blockquote></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"# Title" result) "Heading should be present")
      (is (re-find #"Paragraph with" result) "Paragraph should be present")
      (is (re-find #"- List item" result) "List should be present")
      (is (re-find #"code block" result) "Code should be present")
      (is (re-find #"> Quote" result) "Blockquote should be present"))))

(deftest test-invalid-json-ld
  (testing "Handle invalid JSON-LD gracefully"
    (let [html-str "<html><head><script type='application/ld+json'>{ invalid json }</script></head><body><p>Content</p></body></html>"
          doc (Jsoup/parse html-str)
          metadata (html/extract-metadata doc "https://example.com/page")]
      (is (map? metadata) "Should return metadata map even with invalid JSON-LD")
      (is (= "https://example.com/page" (:url metadata)) "URL should still be extracted"))))

(deftest test-malformed-url-resolution
  (testing "Handle malformed URLs gracefully"
    (let [html-str "<html><body><a href='ht!tp://bad url'>Bad URL</a><img src='not a url'/></body></html>"
          result (:markdown (html/extract-content html-str :base-url "https://example.com" :format :markdown))]
      (is (string? result) "Should handle malformed URLs without crashing")
      (is (re-find #"Bad URL" result) "Link text should be preserved"))))

(deftest test-whitespace-preservation-in-pre
  (testing "Whitespace is preserved in pre tags"
    (let [html-str "<html><body><pre>Line 1\n  Line 2 with indent\n    Line 3 more indent</pre></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"Line 1\n  Line 2 with indent\n    Line 3 more indent" result)
          "Whitespace and indentation should be preserved in pre blocks"))))

(deftest test-multiple-paragraphs
  (testing "Multiple paragraphs are separated correctly"
    (let [html-str "<html><body><p>First paragraph.</p><p>Second paragraph.</p><p>Third paragraph.</p></body></html>"
          result (:markdown (html/extract-content html-str :format :markdown))]
      (is (re-find #"First paragraph\.\n\nSecond paragraph\." result)
          "Paragraphs should be separated by blank lines"))))

(deftest test-github-url-normalization
  (testing "GitHub URLs are normalized correctly"
    (let [url "https://github.com/user/repo/blob/main/README.md"
          normalized (html/normalize-github-url url)]
      (is (= "https://github.com/user/repo/blob/main/README.md?raw=true" normalized)
          "GitHub blob URLs should have ?raw=true appended"))
    (let [url "https://github.com/user/repo"
          normalized (html/normalize-github-url url)]
      (is (= url normalized)
          "Non-blob GitHub URLs should remain unchanged"))))

(deftest test-link-density-calculation
  (testing "Link density is calculated correctly"
    (let [html-str "<html><body><a href='https://external.com/1'>Link</a> <a href='https://external.com/2'>Link</a> text</body></html>"
          doc (Jsoup/parse html-str)
          _ (html/resolve-and-classify-links doc "https://example.com")
          body (.body doc)
          density (html/get-link-density body)]
      (is (> density 0.5) "High link density should be detected")
      (is (< density 1.0) "Link density should not exceed 1.0"))))
