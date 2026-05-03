(ns r11y.lib.html
  (:require [r11y.lib.http :as http]
            [r11y.lib.json :as json]
            [clojure.string :as str])
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
(defn standardize-semantic-divs
  "Convert role-based semantic divs to their proper HTML tags so the
   markdown converter sees them as block content. Modern React/Next.js
   sites emit <div role=paragraph>, <div role=list>, <div role=listitem>
   that JSoup serializes as plain divs without semantic meaning."
  [^Document doc]
  (doseq [^Element elem (.select doc "div[role=paragraph]")]
    (.tagName elem "p"))
  (doseq [^Element elem (.select doc "div[role=list]")]
    (.tagName elem "ul"))
  (doseq [^Element elem (.select doc "div[role=listitem]")]
    (.tagName elem "li"))
  doc)

(defn clean-document
  "Initial cleaning of the document."
  [^Document doc]
  (standardize-semantic-divs doc)
  (doseq [elem (.select
                doc
                "script, style, noscript, iframe, object, embed, footer, header, nav, head link, aside, svg, canvas, applet, input, button, select, textarea, label, fieldset, legend, dialog")]
    (.remove elem))
  (doseq [elem (.select doc "form")]
    (when (< (count (.text elem)) 200)
      (.remove elem)))
  (doseq [elem (.select doc "[role=navigation], [role=banner], [role=complementary], [role=search], [role=img], [role=none], [role=presentation], [aria-hidden=true]")]
    (.remove elem))
  (doseq [el (.select doc "[style*='display'][style*='none']")]
    (when-let [style (.attr el "style")]
      (when (.find (.matcher display-none style)) (.remove el))))
  ;; Remove image maps (navigation, not content)
  (doseq [elem (.select doc "map, area")] (.remove elem))
  ;; Remove images used as image map triggers
  (doseq [^Element elem (.select doc "img[usemap]")] (.remove elem))
  ;; Remove spacer/transparent images (width or height of 1)
  (doseq [^Element elem (.select doc "img")]
    (let [w (.attr elem "width")
          h (.attr elem "height")
          src (.attr elem "src")]
      (when (or (= w "1") (= h "1")
                (re-find #"(?i)trans[_-]|spacer|blank\." src))
        (.remove elem))))
  ;; Remove duplicate images — repeated images are decorative/UI chrome
  (let [imgs (.select doc "img[src]")
        freq (frequencies (map #(.attr ^Element % "src") imgs))]
    (doseq [^Element elem imgs]
      (when (> (get freq (.attr elem "src") 0) 2)
        (.remove elem))))
  doc)

(defn prune-unwanted-nodes
  "Remove elements that are unlikely to be part of the main content."
  [^Document doc]
  (doseq [^Element elem (.select doc "*")]
    (let [class-id (str (.className elem) " " (.id elem))
          role (.attr elem "role")]
      (when
       (or
          ;; Check role attribute for decorative/content-hidden roles
        (and role (re-find #"^(img|presentation|none|button)$" role))
          ;; Check class/id patterns
        (and (re-find unlikely-candidates-pattern class-id)
             (not (re-find positive-patterns class-id))
             (< (count (.text elem)) 200)))
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
  (doseq [^Element elem (.select doc "p, div, li, td, section, article, ul, ol, h1, h2, h3, h4, h5, h6")]
    (when (and (str/blank? (get-inner-text elem))
               (empty? (.children elem)))
      (.remove elem)))
  doc)

(defn- select-largest
  "Select the element with the most text content from a CSS selector.
   Returns nil if no elements match."
  [^Document doc ^String selector]
  (let [elements (.select doc selector)]
    (when (pos? (.size elements))
      (reduce (fn [best ^Element el]
                (if (> (count (.text el)) (count (.text ^Element best)))
                  el
                  best))
              (first elements)
              elements))))

(defn get-main-content-element
  "Try to find the main content element."
  [^Document doc]
  (or (select-largest doc "article")
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
                                       (let [collapsed (str/replace text #"\s+" " ")
                                             trimmed (str/trim collapsed)]
                                         (if (str/blank? trimmed)
                                           (if (str/blank? text) "" " ")
                                           (str (when (str/starts-with? collapsed " ") " ")
                                                trimmed
                                                (when (str/ends-with? collapsed " ") " "))))))
         (instance? Element node) (element-to-markdown node depth preserve-whitespace?)
         :else "")))

(defn- layout-table?
  "Detect tables used for layout rather than data.
   Heuristic: no <th> elements AND (border=0 or contains block-level content)."
  [^Element table]
  (let [has-th? (pos? (.size (.select table "th")))
        border-zero? (= "0" (.attr table "border"))
        has-block-content? (pos? (.size (.select table "table, p, br, font, img, map, form")))]
    (and (not has-th?)
         (or border-zero? has-block-content?))))

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

(defn- wrap-inline-marker
  "Wrap content with markdown markers, moving leading/trailing whitespace outside"
  [content marker]
  (let [trimmed (str/trim content)]
    (if (str/blank? trimmed)
      content
      (str (when (str/starts-with? content " ") " ")
           marker trimmed marker
           (when (str/ends-with? content " ") " ")))))

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
       "strong" (wrap-inline-marker content "**")
       "b" (wrap-inline-marker content "**")
       "em" (wrap-inline-marker content "*")
       "i" (wrap-inline-marker content "*")
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
       "table" (if (layout-table? element) content (table-to-markdown element))
       "tr" content
       "td" content
       "tbody" content
       "thead" content
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
(defn- valid-metadata-value?
  "Reject placeholder values that CMSes sometimes leak unresolved into
   meta tags or JSON-LD: template literals like {author.fullName},
   anchor-style references like #author.fullName, and strings without
   any letter or digit at all (e.g. dashes, dots, underscores)."
  [s]
  (and (string? s)
       (not (str/blank? s))
       (not (re-find #"\{[^{}]+\}" s))
       (not (re-find #"^#[\p{L}_][\p{L}\p{N}_.]*$" s))
       (boolean (re-find #"[\p{L}\p{N}]" s))))

(defn- safe-attr
  "Safely get attribute from element, returning empty string if element is null
   or the value is a placeholder."
  [^Element elem attr]
  (if elem
    (let [v (.attr elem attr)]
      (if (valid-metadata-value? v) v ""))
    ""))

(defn- safe-text
  "Safely get text from element, returning empty string if element is null
   or the value is a placeholder."
  [^Element elem]
  (if elem
    (let [v (.text elem)]
      (if (valid-metadata-value? v) v ""))
    ""))

(def ^:private json-ld-primary-types
  #{"Article" "NewsArticle" "BlogPosting" "ScholarlyArticle"
    "TechArticle" "Report" "WebPage" "AboutPage"})

(defn- decode-html-entities
  "Decode HTML entities commonly found in JSON-LD strings (&amp;, &#39;, etc.).
   Single-pass — each entity is decoded exactly once."
  [^String s]
  (let [named {"amp" "&" "lt" "<" "gt" ">" "quot" "\"" "apos" "'" "nbsp" " "}]
    (str/replace s #"&(?:#(\d+)|#[xX]([0-9a-fA-F]+)|([a-zA-Z][a-zA-Z0-9]*));"
                 (fn [[whole dec hex name]]
                   (cond
                     dec (try (str (char (Integer/parseInt dec))) (catch Exception _ whole))
                     hex (try (str (char (Integer/parseInt hex 16))) (catch Exception _ whole))
                     name (or (named name) whole)
                     :else whole)))))

(defn- decode-entities-deep
  "Recursively decode HTML entities in all string values of a parsed JSON structure."
  [val]
  (cond
    (string? val) (decode-html-entities val)
    (map? val) (into {} (map (fn [[k v]] [k (decode-entities-deep v)]) val))
    (sequential? val) (mapv decode-entities-deep val)
    :else val))

(defn- strip-json-comments
  "Strip /* */ block comments and // line comments. Sites occasionally serve
   non-strict JSON-LD with embedded comments; this is best-effort, not a
   full JSON parser."
  [^String s]
  (-> s
      (str/replace #"(?s)/\*.*?\*/" "")
      (str/replace #"(?m)^\s*//.*$" "")))

(defn- flatten-graph
  "Expand a JSON-LD value into a flat list of objects: a top-level @graph
   array becomes its items; a sequential becomes its elements (recursively)."
  [parsed]
  (cond
    (map? parsed) (if-let [g (get parsed (keyword "@graph"))]
                    (vec g)
                    [parsed])
    (sequential? parsed) (vec (mapcat flatten-graph parsed))
    :else []))

(defn- json-ld-types
  "Return the set of @type values for an item (handles string or array)."
  [item]
  (let [t (get item (keyword "@type"))]
    (cond
      (string? t) #{t}
      (sequential? t) (set t)
      :else #{})))

(defn- pick-primary-json-ld
  "Choose the best object from a flat list of JSON-LD items: prefer one
   whose @type is an article-like type; fall back to the first item."
  [items]
  (or (first (filter (fn [item]
                       (and (map? item)
                            (some json-ld-primary-types (json-ld-types item))))
                     items))
      (first (filter map? items))))

(defn- extract-json-ld
  "Extract and parse JSON-LD structured data. Reads all script tags,
   strips JSON comments, flattens @graph, prefers Article-typed objects,
   decodes HTML entities recursively in the chosen primary."
  [^Document doc]
  (try
    (let [scripts (.select doc "script[type='application/ld+json']")
          parsed (->> scripts
                      (keep (fn [^Element script]
                              (let [text (.html script)]
                                (when-not (str/blank? text)
                                  (try (json/parse (strip-json-comments text))
                                       (catch Exception _ nil)))))))
          flattened (vec (mapcat flatten-graph parsed))
          primary (pick-primary-json-ld flattened)]
      (when primary (decode-entities-deep primary)))
    (catch Exception _ nil)))

(defn- get-json-ld-value
  "Safely extract value from JSON-LD data. Rejects placeholder values."
  [json-ld & keys]
  (when
   json-ld
    (let [val (get-in json-ld keys)
          raw (cond
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
                :else "")]
      (if (valid-metadata-value? raw) raw ""))))

(defn- get-json-ld-image
  "Extract image URL from JSON-LD. Image can be a string, an ImageObject map
   with :url, or an array of either."
  [json-ld]
  (when json-ld
    (let [val (:image json-ld)
          raw (cond
                (string? val) val
                (map? val) (or (:url val) "")
                (sequential? val) (let [first-val (first val)]
                                    (cond
                                      (string? first-val) first-val
                                      (map? first-val) (or (:url first-val) "")
                                      :else ""))
                :else "")]
      (if (valid-metadata-value? raw) raw ""))))

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

(defn- guard-sitename
  "Reject overly long site names — sites sometimes put the article title
   in og:site_name. More than 6 words is almost certainly not a site name."
  [s]
  (if (and (not (str/blank? s))
           (> (count (re-seq #"\S+" s)) 6))
    ""
    s))

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
                                  (guard-sitename (safe-attr (.selectFirst doc "meta[property=og:site_name]") "content")))
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
        icon (extract-site-icon doc base-url)
        image (first-non-blank (get-json-ld-image json-ld)
                               (safe-attr (.selectFirst doc "meta[property=og:image]") "content")
                               (safe-attr (.selectFirst doc "meta[name=twitter:image]") "content")
                               (safe-attr (.selectFirst doc "meta[property=twitter:image]") "content"))]
    {:title (or title "")
     :author (or author "")
     :url base-url
     :hostname (or hostname "")
     :description (or description "")
     :sitename (or sitename "")
     :date (or date "")
     :canonical-url (or canonical-url "")
     :is-canonical is-canonical
     :icon (or icon "")
     :image (or image "")}))

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
                [:icon (:icon metadata)]
                [:image (:image metadata)]]
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
        ;; Measure raw body text before cleaning for coverage heuristic
        raw-body-text-len (count (.text (.body doc)))
        ;; First pass: aggressive cleaning
        cleaned-doc-pass1 (-> (.clone doc)
                              (cond-> base-url (resolve-and-classify-links base-url))
                              clean-document
                              prune-unwanted-nodes
                              (clean-by-link-density link-density-threshold)
                              clean-empty-elements)
        main-element-pass1 (get-main-content-element cleaned-doc-pass1)
        ;; Second pass (conservative) if first pass fails
        main-element-pass2 (when (< (count (.text main-element-pass1)) 200)
                             (let [cleaned-doc-pass2 (-> (.clone doc)
                                                         (cond-> base-url (resolve-and-classify-links base-url))
                                                         clean-document
                                                         ;; Skip prune-unwanted-nodes
                                                         (clean-by-link-density link-density-threshold)
                                                         clean-empty-elements)]
                               (get-main-content-element cleaned-doc-pass2)))
        main-element (or main-element-pass2 main-element-pass1)
        ;; Coverage heuristic: if we captured less than 30% of the raw body text,
        ;; we likely missed content — fall back to the cleaned body
        main-element (if (and (pos? raw-body-text-len)
                              (< (/ (double (count (.text main-element)))
                                    raw-body-text-len)
                                 0.3))
                       (let [cleaned-body (-> (.clone doc)
                                              (cond-> base-url (resolve-and-classify-links base-url))
                                              clean-document
                                              clean-empty-elements)]
                         (.body cleaned-body))
                       main-element)

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
             readme-html (when (pos? (.size readme-article)) (.html (first readme-article)))]
         readme-html)
       (catch Exception _ nil)))

(defn- parse-yaml-frontmatter
  "Parse YAML frontmatter from a markdown string.
   Returns {:frontmatter {key val ...} :body \"rest of markdown\"}.
   If no frontmatter, returns {:frontmatter nil :body original-string}."
  [^String text]
  (if (str/starts-with? (str/trim text) "---")
    (let [trimmed (str/trim text)
          close-idx (str/index-of trimmed "\n---" 3)]
      (if close-idx
        (let [yaml-block (if (> close-idx 4) (subs trimmed 4 close-idx) "")
              body (str/trim (subs trimmed (+ close-idx 4)))
              pairs (->> (str/split-lines yaml-block)
                         (keep (fn [line]
                                 (when-let [colon-idx (str/index-of line ":")]
                                   (let [k (str/trim (subs line 0 colon-idx))
                                         v (str/trim (subs line (inc colon-idx)))]
                                     (when-not (str/blank? k)
                                       [k v])))))
                         (into {}))]
          {:frontmatter pairs :body body})
        {:frontmatter nil :body text}))
    {:frontmatter nil :body text}))

(defn- upstream-frontmatter->metadata
  "Map upstream YAML frontmatter fields into our metadata format.
   Merges with URL-derived fields."
  [fm url]
  (let [hostname (when url (try (.getHost (URI. url)) (catch Exception _ "")))]
    {:title (or (get fm "title") "")
     :author (or (get fm "author") "")
     :url url
     :hostname (or hostname "")
     :description (or (get fm "description") "")
     :sitename (or (get fm "sitename") (get fm "site_name") "")
     :date (or (get fm "date") (get fm "published") "")
     :canonical-url ""
     :is-canonical false
     :icon (or (get fm "icon") "")
     :image (or (get fm "image") "")}))

(defn looks-like-markdown?
  "Sniff body bytes/string to detect markdown content.
   Some servers (e.g. Cloudflare) return markdown but set content-type: text/html.
   Uses markdown-specific patterns to avoid false positives with plain text/JSON/CSV."
  [body]
  (let [s (str/trim (if (bytes? body)
                      (let [len (min (alength ^bytes body) 512)]
                        (String. ^bytes body 0 len "UTF-8"))
                      (subs (str body) 0 (min (count body) 512))))]
    (if (or (str/blank? s)
            (re-find #"\x00" s))
      false
      (or (str/starts-with? s "---")
          (re-find #"(?m)^#{1,6}\s+\S" s)
          (re-find #"\*\*[^\s*][^*]*\*\*" s)
          (re-find #"(?<!_)__[^\s_][^_]*__(?!_)" s)
          (re-find #"(?m)^[-*+]\s+\S" s)
          (re-find #"(?m)^\d+\.\s+\S" s)))))

(defn- fetch-url
  "Fetch URL with common headers and return response map."
  [url]
  (http/get-url url {:as :byte-array
                     :headers {"User-Agent" "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15"
                               "Accept" "text/markdown,text/html;q=0.9,application/xhtml+xml;q=0.9,application/xml;q=0.8,*/*;q=0.7"
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
   :with-metadata - include YAML frontmatter with metadata (default false)
   :content - pre-fetched HTML content (String or bytes), skips initial fetch
   :content-type - content-type of pre-fetched content
   :fetch-fn - custom fetch function returning http-kit style response map"
  [url & {:keys [format link-density-threshold with-metadata
                 content content-type fetch-fn]
          :or {format :html
               link-density-threshold default-link-density-threshold
               with-metadata false}}]
  (let [do-fetch (or fetch-fn fetch-url)
        normalized-url (normalize-github-url url)
        urls-differ? (not= normalized-url url)
        ;; Step 1: Resolve original content
        ;; Fetch original URL when no pre-fetched content, unless URLs differ
        ;; and we don't need metadata (optimization: skip unneeded fetch)
        original-response (when (and (not content)
                                     (or with-metadata (not urls-differ?)))
                            (do-fetch url))
        original-body (or content (:body original-response))
        ;; Step 2: Extract metadata from original content (skip if body is markdown)
        metadata (when (and with-metadata original-body
                            (not (looks-like-markdown? original-body)))
                   (let [doc (if (bytes? original-body)
                               (parse-html-bytes original-body url)
                               (Jsoup/parse ^String original-body))]
                     (extract-metadata doc url)))
        ;; Step 3: Fetch normalized URL for extraction if URLs differ
        extraction-response (when urls-differ? (do-fetch normalized-url))
        extraction-body (if urls-differ?
                          (:body extraction-response)
                          original-body)
        header-content-type (cond
                              extraction-response (get-in extraction-response [:headers :content-type] "")
                              content-type content-type
                              original-response (get-in original-response [:headers :content-type] "")
                              :else "text/html")
        ;; Some servers (e.g. Cloudflare) return markdown body but claim text/html.
        ;; Sniff the body to detect this when we requested text/markdown.
        resolved-content-type (if (and (str/includes? (str/lower-case header-content-type) "text/html")
                                       extraction-body
                                       (looks-like-markdown? extraction-body))
                                "text/markdown"
                                header-content-type)
        frontmatter (when metadata (metadata-to-frontmatter metadata))
        fallback-extract (fn []
                           (if metadata
                             (let [result (extract-content extraction-body
                                                           :base-url normalized-url
                                                           :format format
                                                           :link-density-threshold link-density-threshold
                                                           :with-metadata false)]
                               (assoc result
                                      :markdown (str frontmatter (:markdown result))
                                      :metadata metadata))
                             (extract-content extraction-body
                                              :base-url normalized-url
                                              :format format
                                              :link-density-threshold link-density-threshold
                                              :with-metadata with-metadata)))]
    (cond
      ;; For non-HTML content (like markdown or raw text), return as-is
      ;; Strip any upstream YAML frontmatter; rebuild in our format if requested
      (not (str/includes? (str/lower-case resolved-content-type) "text/html"))
      (let [text-body (if (bytes? extraction-body) (bytes->utf8 extraction-body) extraction-body)
            {upstream-fm :frontmatter body :body} (parse-yaml-frontmatter text-body)
            md-metadata (when (and with-metadata upstream-fm)
                          (upstream-frontmatter->metadata upstream-fm url))
            md-frontmatter (when md-metadata (metadata-to-frontmatter md-metadata))]
        {:markdown (str md-frontmatter body)
         :links []
         :images []
         :metadata (or md-metadata {})})

      ;; For GitHub repo pages, extract README directly
      (and (str/includes? normalized-url "github.com")
           (not (str/includes? normalized-url "/blob/"))
           (not (str/includes? normalized-url "/issues"))
           (not (str/includes? normalized-url "/pull")))
      (let [body-str (if (bytes? extraction-body) (bytes->utf8 extraction-body) extraction-body)]
        (if-let [^String readme-html (extract-github-readme body-str)]
          (let [readme-markdown (html-to-markdown readme-html)
                doc (Jsoup/parse readme-html)
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
            {:markdown (str frontmatter readme-markdown)
             :html readme-html
             :links links
             :images images
             :metadata (or metadata {})})
          (fallback-extract)))

      ;; Default: perform normal HTML extraction
      :else (fallback-extract))))
