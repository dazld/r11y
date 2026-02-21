(ns r11y.lib.html
  (:require [org.httpkit.client :as client]
            [clojure.string :as str]
            [clojure.data.json :as json])
  (:import (java.net URI)
           (java.io ByteArrayInputStream)
           [org.jsoup Jsoup]
           [org.jsoup.nodes Document Element Node TextNode]
           [java.util.regex Pattern]))


(def PRESERVE_START_TOKEN "__PRESERVE_ae0d3c51_START__")
(def PRESERVE_END_TOKEN "__PRESERVE_ae0d3c51_END__")

(defn- parse-html-bytes
  "Parse HTML from bytes, letting JSoup detect the charset"
  [^bytes body-bytes base-url]
  (Jsoup/parse (ByteArrayInputStream. body-bytes) nil (or base-url "")))

(defn- bytes->utf8
  "Convert bytes to UTF-8 string"
  [^bytes body-bytes]
  (String. body-bytes "UTF-8"))

;; Configuration
(def ^:const default-link-density-threshold 0.5)

;; Regex patterns for cleaning (based on trafilatura/readability)
(def unlikely-candidates-pattern
  (Pattern/compile
    "combx|comment|community|disqus|extra|foot|header|menu|remark|rss|shoutbox|sidebar|sponsor|ad-break|agegate|pagination|pager|popup|tweet|twitter|newsletter|social|share"
    Pattern/CASE_INSENSITIVE))

(def positive-patterns
  (Pattern/compile "article|body|content|entry|hentry|main|page|post|text|blog|story" Pattern/CASE_INSENSITIVE))

(def display-none (Pattern/compile "display:\\s*none" Pattern/CASE_INSENSITIVE))

;; Helper functions

(defn get-inner-text [^Element e] (str/trim (.text e)))

(defn get-link-density
  "Calculate link density, only counting external links"
  [^Element e]
  (let [text-length (count (get-inner-text e))
        external-links (.select e "a[--mnp-cleaning--data-link-type=external]")
        external-link-length (reduce + 0 (map #(count (.text %)) external-links))]
    (if (zero? text-length) 0 (float (/ external-link-length text-length)))))

(defn resolve-and-classify-links
  "Resolve all URLs against base URL and mark internal vs. external links"
  [^Document doc base-url]
  (let [base-uri (URI. base-url)
        base-host (.getHost base-uri)]
    ;; Process all href attributes (links, stylesheets, etc.)
    (doseq [^Element elem (.select doc "[href]")]
      (let [href (-> (.attr elem "href") (str/trim))
            doc-local-link? (str/starts-with? href "#")
            ;; converts anchors & relative to fully qualified
            resolved-uri (try (.resolve base-uri href) (catch Exception _ nil))
            is-internal? (and resolved-uri (= base-host (.getHost resolved-uri)))]
        (when resolved-uri
          (.attr elem "href" ^String (.toString resolved-uri)))
        ;; Mark internal/external for <a> tags (used for link density)
        ;; Internal links are ignored for link density calculation
        (when (= "a" (.tagName elem))
          (cond-> elem
            doc-local-link? (.attr "--mnp-doc-local-link" "true")
            true (.attr "--mnp-cleaning--data-link-type" (if is-internal? "internal" "external"))))))
    ;; Process all src attributes (images, scripts, iframes, etc)
    (doseq [elem (.select doc "[src]")]
      (let [src (.attr elem "src")
            resolved-uri (try (.resolve base-uri src) (catch Exception _ nil))
            resolved-url (when resolved-uri (.toString resolved-uri))]
        (when resolved-url
          (.attr elem "src" resolved-url))))
    doc))

;; Cleaning pipeline functions
(defn clean-document
  "Initial cleaning of the document."
  [^Document doc]
  (doseq [elem (.select
                 doc
                 "script, style, noscript, iframe, object, embed, footer, header, nav, head link, aside, svg, canvas, applet, input, button, select, textarea, label, fieldset, legend, dialog")]
    (.remove elem))
  (doseq [elem (.select doc "form")]
    (when (< (count (.text elem)) 200)
      (.remove elem)))
  (doseq [elem (.select doc "[role=navigation], [role=banner], [role=complementary], [role=search], [role=none], [role=presentation], [aria-hidden=true]")]
    (.remove elem))
  (doseq [el (.select doc "[style*='display'][style*='none']")]
    (when-let [style (.attr el "style")]
      (when (.find (.matcher display-none style)) (.remove el))))
  doc)

(defn prune-unwanted-nodes
  "Remove elements that are unlikely to be part of the main content."
  [^Document doc]
  (doseq [^Element elem (.select doc "*")]
    (let [class-id (str (.className elem) " " (.id elem))]
      (when
        (and (re-find unlikely-candidates-pattern class-id)
             (not (re-find positive-patterns class-id))
             (< (count (.text elem)) 200))
        (.remove elem))))
  doc)

(defn clean-by-link-density
  "Remove elements with high link density."
  [^Document doc threshold]
  (doseq [elem (.select doc "div, p, li, td, section")]
    (when (> (get-link-density elem) threshold) (.remove elem)))
  doc)

(defn clean-empty-elements
  "Remove empty elements."
  [^Document doc]
  (doseq [^Element elem (.select doc "p, div, li, td, section, ul, ol, h1, h2, h3, h4, h5, h6")]
    (when (and (str/blank? (get-inner-text elem))
               (empty? (.children elem)))
      (.remove elem)))
  doc)

(defn get-main-content-element
  "Try to find the main content element."
  [^Document doc]
  (or (.selectFirst doc "article")
      (.selectFirst doc "[itemprop=articleBody]")
      (.selectFirst doc "[role=main]")
      (.selectFirst doc "main")
      (.selectFirst doc ".post-content")
      (.selectFirst doc ".entry-content")
      (.selectFirst doc ".article-body")
      (.selectFirst doc ".content")
      (.selectFirst doc "#content")
      (.body doc)))

;; Markdown conversion
(declare element-to-markdown)

(defn- node-to-markdown
  "Convert a JSoup node to Markdown recursively"
  ([^Node node depth]
   (node-to-markdown node depth false))
  ([^Node node depth preserve-whitespace?]
   (cond (instance? TextNode node) (let [text (.getWholeText ^TextNode node)]
                                     (if preserve-whitespace?
                                       text
                                       (-> text
                                           (str/replace #"\s+" " ")
                                           str/trim)))
         (instance? Element node) (element-to-markdown node depth preserve-whitespace?)
         :else "")))

(defn- table-to-markdown
  "Convert an HTML table to Markdown table format"
  [^Element table]
  (let [thead (.selectFirst table "thead")
        tbody (.selectFirst table "tbody")
        process-row (fn [^Element row]
                      (let [cells (concat (.select row "th") (.select row "td"))
                            cell-texts (map #(-> (.text %)
                                                 str/trim
                                                 (str/replace #"\|" "\\|")
                                                 (str/replace #"\n" " "))
                                            cells)]
                        (str "| " (str/join " | " cell-texts) " |")))
        ;; Get header rows from thead or first tr with th elements
        header-rows (if thead
                      (.select thead "tr")
                      (let [first-row (first (.select table "tr"))]
                        (when (and first-row (seq (.select first-row "th"))) [first-row])))
        ;; Get body rows from tbody or remaining tr elements
        body-rows (if tbody
                    (.select tbody "tr")
                    (let [all-rows (.select table "tr")]
                      (if (seq header-rows) (rest all-rows) all-rows)))
        header (when
                 (seq header-rows)
                 (process-row (first header-rows)))
        num-cols (when
                   header
                   (count (concat (.select (first header-rows) "th") (.select (first header-rows) "td"))))
        separator (when num-cols (str "|" (str/join "|" (repeat num-cols " --- ")) "|"))
        body (map process-row body-rows)]
    (str/join "\n"
              (concat (when
                        header
                        [header separator])
                      body
                      ["\n"]))))



(defn- element-to-markdown
  ([^Element element] (element-to-markdown element 0 false))
  ([^Element element depth preserve-whitespace?]
   (let [tag-name (.tagName element)
         ;; Check if we're entering a whitespace-preserving context
         is-pre? (= tag-name "pre")
         contains-code? (when is-pre? (seq (.select element "code")))
         preserve? (or preserve-whitespace? (= tag-name "pre") (= tag-name "code"))
         contains-new-lines? (str/includes? (.wholeText ^Element element) "\n")
         pad-start (if contains-code? "" (if contains-new-lines? "```\n" "`"))
         pad-end (if contains-code? "" (if contains-new-lines? "\n```\n" "`"))
         children (.childNodes element)
         content (str/join (map #(node-to-markdown % (inc depth) preserve?) children))
         href (.attr element "href")
         src (.attr element "src")
         alt (.attr element "alt")]
     (case tag-name
       "h1" (str "# " content "\n\n")
       "h2" (str "## " content "\n\n")
       "h3" (str "### " content "\n\n")
       "h4" (str "#### " content "\n\n")
       "h5" (str "##### " content "\n\n")
       "h6" (str "###### " content "\n\n")
       "p" (str content "\n\n")
       "br" "\n"
       "hr" "\n---\n\n"
       "strong" (str "**" content "**")
       "b" (str "**" content "**")
       "em" (str "*" content "*")
       "i" (str "*" content "*")
       "code" (str pad-start PRESERVE_START_TOKEN content PRESERVE_END_TOKEN pad-end)
       "pre" (str pad-start
                  (when-not contains-code? PRESERVE_START_TOKEN)
                  content
                  (when-not contains-code? PRESERVE_END_TOKEN)
                  pad-end)
       "a" (if (str/blank? href) content (str " [" content "](" href ") "))
       "img" (str "![" alt "](" src ")\n\n")
       "figure" (str content "\n\n")
       "figcaption" (str "*" content "*\n\n")
       "ul" (str content "\n")
       "ol" (str content "\n")
       "li" (str (apply str (repeat depth "  ")) "- " content "\n")
       "blockquote" (str "> " (str/replace content #"\n" "\n> ") "\n\n")
       "table" (table-to-markdown element)
       "div" content
       "span" content
       "article" content
       "section" content
       "main" content
       content))))


(defn html-to-markdown
  "Convert HTML string to markdown"
  [html]
  (let [doc (Jsoup/parse ^String html)
        body (.body doc)
        preserved (atom [])
        result (str/join (map #(node-to-markdown % 0) (.childNodes body)))
        ;; Extract and replace preserved sections
        result-with-placeholders (str/replace result
                                              (Pattern/compile (str "(?s)" PRESERVE_START_TOKEN
                                                                    "(.*?)" PRESERVE_END_TOKEN))
                                              (fn [[_ content]]
                                                (let [idx (count @preserved)]
                                                  (swap! preserved conj content)
                                                  (str "__PRESERVED_" idx "__"))))
        ;; Clean up non-preserved content
        cleaned (-> result-with-placeholders
                    (str/replace #"\n{3,}" "\n\n")
                    (str/replace #" +\n" "\n")
                    (str/replace #"\n +" "\n")
                    (str/replace #"^\s+" "")
                    (str/replace #"\s+$" ""))
        ;; Restore preserved sections
        final (reduce-kv (fn [s idx content]
                           (str/replace s (str "__PRESERVED_" idx "__") content))
                         cleaned
                         (vec @preserved))]
    final))

;; Metadata extraction
(defn- safe-attr
  "Safely get attribute from element, returning empty string if element is null"
  [^Element elem attr]
  (if elem (.attr elem attr) ""))

(defn- safe-text
  "Safely get text from element, returning empty string if element is null"
  [^Element elem]
  (if elem (.text elem) ""))

(defn- extract-json-ld
  "Extract and parse JSON-LD structured data"
  [^Document doc]
  (try (let [json-ld-scripts (.select doc "script[type='application/ld+json']")]
         (when
           (seq json-ld-scripts)
           (let [json-text (.html (.first json-ld-scripts))]
             (when-not (str/blank? json-text)
               (try (json/read-str json-text :key-fn keyword) (catch Exception _ nil))))))
       (catch Exception _ nil)))

(defn- get-json-ld-value
  "Safely extract value from JSON-LD data"
  [json-ld & keys]
  (when
    json-ld
    (let [val (get-in json-ld keys)]
      (cond
        (string? val) val
        (map? val) (or (:name val)
                       (get val (keyword "@value"))
                       "")
        (sequential? val) (let [first-val (first val)]
                            (cond
                              (string? first-val) first-val
                              (map? first-val) (or (:name first-val)
                                                   (get first-val (keyword "@value"))
                                                   "")
                              :else ""))
        :else ""))))

(defn- first-non-blank
  "Return first non-blank value"
  [& values]
  (first (filter (complement str/blank?) values)))

(defn- distinct-by
  [f coll]
  (let [seen (atom #{})]
    (filter (fn [x]
              (let [k (f x)]
                (if (contains? @seen k)
                  false
                  (do (swap! seen conj k)
                      true))))
            coll)))

(defn- extract-date-from-url
  "Extract date from URL patterns like /2024/03/15/"
  [url]
  (when
    url
    (let [patterns [#"/(\d{4})/(\d{2})/(\d{2})/" ; /2024/03/15/
                    #"/(\d{4})-(\d{2})-(\d{2})"  ; /2024-03-15
                    #"/(\d{4})(\d{2})(\d{2})"]]  ; /20240315
      (some
        (fn [pattern]
          (when-let [match (re-find pattern url)]
            (let [[_ year month day] match]
              (str year "-" month "-" day))))
        patterns))))

(defn- extract-site-icon
  "Extract the best available site icon URL"
  [^Document doc base-url]
  (let [icons (->> (.select doc "link[rel*='icon']")
                   (map (fn [^Element el]
                          {:href (.attr el "href")
                           :rel (str/lower-case (.attr el "rel"))
                           :sizes (str/lower-case (.attr el "sizes"))}))
                   (sort-by (fn [{:keys [rel sizes]}]
                              (let [is-apple (str/includes? rel "apple")
                                    size (if (or (str/blank? sizes) (= sizes "any"))
                                           0
                                           (try (Integer/parseInt (first (str/split sizes #"x")))
                                                (catch Exception _ 0)))]
                                (+ (if is-apple 1000 0) size)))
                            >))
        best-icon (first icons)
        icon-url (or (:href best-icon) "/favicon.ico")]
    (if (and base-url (not (str/blank? icon-url)))
      (try
        (str (.resolve (URI. base-url) icon-url))
        (catch Exception _ icon-url))
      icon-url)))

(defn- resolve-canonical-url
  "Resolve a canonical URL against a base URL"
  [base-url canonical-url]
  (when (and base-url (not (str/blank? canonical-url)))
    (try
      (str (.resolve (URI. base-url) canonical-url))
      (catch Exception _ nil))))

(defn extract-metadata
  "Extract metadata from document"
  [^Document doc base-url]
  (let [json-ld (extract-json-ld doc)
        title (first-non-blank (get-json-ld-value json-ld :name)
                               (get-json-ld-value json-ld :headline)
                               (safe-text (.selectFirst doc "title"))
                               (safe-attr (.selectFirst doc "meta[property=og:title]") "content"))
        author (first-non-blank (get-json-ld-value json-ld :author :name)
                                (get-json-ld-value json-ld :author)
                                (safe-attr (.selectFirst doc "meta[name=author]") "content")
                                (safe-attr (.selectFirst doc "meta[property=article:author]") "content"))
        description (first-non-blank (get-json-ld-value json-ld :description)
                                     (safe-attr (.selectFirst doc "meta[name=description]") "content")
                                     (safe-attr (.selectFirst doc "meta[property=og:description]") "content"))
        sitename (first-non-blank (get-json-ld-value json-ld :publisher :name)
                                  (safe-attr (.selectFirst doc "meta[property=og:site_name]") "content"))
        date (first-non-blank (get-json-ld-value json-ld :datePublished)
                              (get-json-ld-value json-ld :dateCreated)
                              (safe-attr (.selectFirst doc "meta[property=article:published_time]") "content")
                              (safe-attr (.selectFirst doc "meta[name=date]") "content")
                              (safe-attr (.selectFirst doc "time[datetime]") "datetime")
                              (extract-date-from-url base-url))
        hostname (when base-url (try (.getHost (URI. base-url)) (catch Exception _ "")))
        raw-canonical-url (safe-attr (.selectFirst doc "link[rel=canonical]") "href")
        canonical-url (resolve-canonical-url base-url raw-canonical-url)
        is-canonical (or (str/blank? canonical-url) (= canonical-url base-url))
        icon (extract-site-icon doc base-url)]
    {:title (or title "")
     :author (or author "")
     :url base-url
     :hostname (or hostname "")
     :description (or description "")
     :sitename (or sitename "")
     :date (or date "")
     :canonical-url (or canonical-url "")
     :is-canonical is-canonical
     :icon (or icon "")}))

(defn metadata-to-frontmatter
  "Convert metadata map to YAML frontmatter string"
  [metadata]
  (let [fields [[:title (:title metadata)]
                [:author (:author metadata)]
                [:url (:url metadata)]
                [:canonical-url (:canonical-url metadata)]
                [:is-canonical (:is-canonical metadata)]
                [:hostname (:hostname metadata)]
                [:description (:description metadata)]
                [:sitename (:sitename metadata)]
                [:date (:date metadata)]
                [:icon (:icon metadata)]]
        non-empty-fields (filter #(not (str/blank? (str (second %)))) fields)
        yaml-lines (map (fn [[k v]]
                          (str (name k) ": " v))
                        non-empty-fields)]
    (if (seq yaml-lines) (str "---\n" (str/join "\n" yaml-lines) "\n---\n\n") "")))

(defn- clean-links [link-elements]
  (->> link-elements
       (keep (fn [^Element elem]
               (let [url (-> (.attr elem "href") (str/trim))
                     text (-> (.text elem) (str/trim))]
                 (when (and (not= "true" (.attr elem "--mnp-doc-local-link"))
                            (not (str/blank? url))
                            (not (str/starts-with? url "#")))
                   {:text text
                    :url url}))))
       (distinct-by :url)
       (sort-by :url)
       vec))

;; Main API
(defn extract-content
  "Extract content from HTML. Input can be a String or byte array.
   When passing bytes, JSoup will auto-detect the charset."
  [html-input & {:keys [format link-density-threshold base-url with-metadata] :or {format :html link-density-threshold default-link-density-threshold with-metadata false}}]
  (let [doc (if (bytes? html-input)
              (parse-html-bytes html-input base-url)
              (Jsoup/parse ^String html-input))
        metadata (when with-metadata (extract-metadata doc base-url))
        frontmatter (when with-metadata (metadata-to-frontmatter metadata))
        ;; First pass: aggressive cleaning
        cleaned-doc-pass1 (-> (.clone doc)
                              (cond-> base-url (resolve-and-classify-links base-url))
                              clean-document
                              prune-unwanted-nodes
                              (clean-by-link-density link-density-threshold)
                              clean-empty-elements)
        main-element-pass1 (get-main-content-element cleaned-doc-pass1)
        ;; Second pass (conservative) if first pass fails
        main-element (if (< (count (.text main-element-pass1)) 200)
                       (let [cleaned-doc-pass2 (-> (.clone doc)
                                                   (cond-> base-url (resolve-and-classify-links base-url))
                                                   clean-document
                                                   ;; Skip prune-unwanted-nodes
                                                   (clean-by-link-density link-density-threshold)
                                                   clean-empty-elements)]
                         (get-main-content-element cleaned-doc-pass2))
                       main-element-pass1)

        main-content-html (.html main-element)
        ;; Extract links from main content
        link-elements (.select main-element "a[href]")
        links (clean-links link-elements)
        ;; Extract images from main content
        image-elements (.select main-element "img[src]")
        images (->> image-elements
                    (map (fn [^Element elem]
                           {:alt (.attr elem "alt")
                            :url (.attr elem "src")}))
                    (filter #(not (str/blank? (:url %))))
                    (distinct-by :url)
                    (sort-by :url)
                    vec)
        markdown-content (str frontmatter (html-to-markdown main-content-html))]
    (with-meta {:markdown markdown-content
                ;:html main-content-html
                :links    links
                :images   images
                :metadata (or metadata {})}
               {:html main-content-html
                :main-element main-element})))

(defn normalize-github-url
  "Convert GitHub blob URLs to raw URLs for better content extraction"
  [url]
  (if (and (str/includes? url "github.com")
           (str/includes? url "/blob/"))
    (str url "?raw=true")
    url))

(defn extract-github-readme
  "Extract README content from GitHub repo pages"
  [^String html-string]
  (try (let [doc (Jsoup/parse html-string)
             ;; GitHub embeds README in an article tag with class markdown-body
             readme-article (.select doc "article.markdown-body")
             readme-html (when (pos? (.size readme-article)) (.html (.first readme-article)))]
         readme-html)
       (catch Exception _ nil)))

(defn- fetch-url
  "Fetch URL with common headers and return http-kit response."
  [url]
  @(client/request {:method :get
                    :url url
                    :as :byte-array
                    :headers {"User-Agent" "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15"
                              "Accept" "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                              "Accept-Encoding" "gzip, deflate"
                              "Accept-Language" "en-GB,en;q=0.9"
                              "Priority" "u=0, i"
                              "Sec-Fetch-Dest" "document"
                              "Sec-Fetch-Mode" "navigate"
                              "Sec-Fetch-Site" "none"}}))

(defn extract-content-from-url
  "Extract main content from a URL. Returns clean HTML by default.
   Options:
   :format - :html (default) or :markdown
   :link-density-threshold - float between 0-1 (default 0.5)
   :with-metadata - include YAML frontmatter with metadata (default false)"
  [url & {:keys [format link-density-threshold with-metadata] :or {format :html link-density-threshold default-link-density-threshold with-metadata false}}]
  (let
    [is-github-blob? (and (str/includes? url "github.com") (str/includes? url "/blob/"))
     ;; Fetch original URL for metadata if it's a GitHub blob
     metadata-response (when (and with-metadata is-github-blob?)
                         (fetch-url url))
     metadata (when
                metadata-response
                (let [doc (parse-html-bytes (:body metadata-response) url)]
                  ;; Extract metadata using original URL, not normalized
                  (extract-metadata doc url)))
     ;; Now fetch content URL (possibly raw for GitHub)
     normalized-url (normalize-github-url url)
     response (fetch-url normalized-url)
     content-type (get-in response
                          [:headers :content-type]
                          "")
     body (:body response)
     frontmatter (when metadata (metadata-to-frontmatter metadata))]
    (cond
      ;; For non-HTML content (like raw text), return as-is with metadata
      (not (str/includes? (str/lower-case content-type) "text/html"))
      (let [text-body (bytes->utf8 body)]
        {:markdown (str frontmatter text-body)
         :html text-body
         :links []
         :images []
         :metadata (or metadata {})})

      ;; For GitHub repo pages, extract README directly
      (and (str/includes? normalized-url "github.com")
           (not (str/includes? normalized-url "/blob/"))
           (not (str/includes? normalized-url "/issues"))
           (not (str/includes? normalized-url "/pull")))
      (if-let [^String readme-html (extract-github-readme (bytes->utf8 body))]
        (let [readme-markdown (html-to-markdown readme-html)
              doc (Jsoup/parse readme-html)
              ;; Extract links and images from README
              link-elements (.select doc "a[href]")
              links (clean-links link-elements)
              image-elements (.select doc "img[src]")
              images (->> image-elements
                          (map (fn [^Element elem]
                                 {:alt (.attr elem "alt")
                                  :url (.attr elem "src")}))
                          (filter #(not (str/blank? (:url %))))
                          (distinct)
                          (sort-by :url)
                          vec)]
          {:markdown readme-markdown
           :html readme-html
           :links links
           :images images
           :metadata (or metadata {})})
        ;; Fallback to normal extraction if README extraction fails
        (extract-content body
                         :base-url normalized-url
                         :format format
                         :link-density-threshold link-density-threshold
                         :with-metadata with-metadata))

      ;; Default: perform normal HTML extraction
      :else (extract-content body
                             :base-url normalized-url
                             :format format
                             :link-density-threshold link-density-threshold
                             :with-metadata with-metadata))))
