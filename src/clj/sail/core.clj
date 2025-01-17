(ns sail.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.string :as s]
            [juxt.dirwatch :as dw]
            [sail.accessibility :refer [accessibility]]
   ;; [clojure.tools.logging.impl]
            [sail.base :refer [base]]
            [sail.components :refer [components]]
            [sail.normalize :refer [normalize]]
            [sail.transition :refer [transition]]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as log-appenders])
  (:import (java.io PushbackReader)))

(log/merge-config!
 {:appenders {:println (log-appenders/println-appender
                        {:stream (java.io.OutputStreamWriter. System/out)})}})

(defn prefix
  "Include . for class names, ignore for reserved words like 'html'."
  [n]
  ;; TODO generate these definitions based on the normalize array map,
  ;; this is PRETTY long and you will need to maintain it otherwise.
  (if (contains? #{:html :body :main :h1 :h2 :h3 :h4 :h5 :h6
                   :section :nav :header :footer :hr :pre :a
                   :b :strong :code :kbd :samp :small :sub :sup :img
                   :svg :video :canvas :audio :iframe :embed :object
                   :button :input :optgroup :select :textarea
                   :fieldSet :legend :progress :details :summary
                   :template :* :blockquote :dl :dd :tr :td :thead
                   :tbody :table :figure :p :button:focus :ol :ul :li
                   (keyword "::before")
                   (keyword "::after")
                   (keyword "[type=\"checkbox\"]")
                   (keyword "[type=\"radio\"]")
                   (keyword "[type=\"button\"]")
                   (keyword "[role=\"button\"]")
                   (keyword "[type=\"reset\"]")
                   (keyword "[type=\"submit\"]")
                   (keyword "[type=\"search\"]")
                   (keyword "[hidden]")
                   (keyword "::-webkit-file-upload-button")
                   (keyword "[type=\"search\"]::-webkit-search-decoration")
                   (keyword "button::-moz-focus-inner")
                   (keyword "[type=\"button\"]::-moz-focus-inner")
                   (keyword "[type=\"reset\"]::-moz-focus-inner")
                   (keyword "[type=\"submit\"]::-moz-focus-inner")
                   (keyword "button:-moz-focusring")
                   (keyword "[type=\"button\"]:-moz-focusring")
                   (keyword "[type=\"reset\"]:-moz-focusring")
                   (keyword "[type=\"submit\"]:-moz-focusring")
                   (keyword "[type=\"number\"]::-webkit-inner-spin-button")
                   (keyword "[type=\"number\"]::-webkit-outer-spin-button")
                   (keyword "input::-webkit-input-placeholder")
                   (keyword "textarea::-webkit-input-placeholder")
                   (keyword "input::-moz-placeholder")
                   (keyword "textarea::-moz-placeholder")
                   (keyword "input:-ms-input-placeholder")
                   (keyword "textarea:-ms-input-placeholder")
                   (keyword "input::-ms-input-placeholder")
                   (keyword "textarea::-ms-input-placeholder")
                   (keyword "input::placeholder")
                   (keyword "textarea::placeholder")
                   (keyword "abbr[title]")} (keyword n))
    n (str "." n)))

(defn ->selector [k]
  "Write key as CSS selectors, can be single or a sequence."
  (let [x (fn [n] (-> n name prefix))]
    (-> (if (vector? k)
          (s/join "," (map x k))
          (x k)))))

(defn style->string [smap]
  (reduce
   (fn [output-string [k v]]
     (if (or (map? v) (vector? v))
       (str output-string (->selector k) "{" (style->string v) "}")
       (str output-string (name k) ":" (if (keyword? v)
                                         (name v) v) ";")))
   "" (if (map? smap) smap (partition 2 smap))))

(defn with-responsive-prefix
  "Apply a min-width media query an class prefix to styles e.g md:text-gray-700"
  [smap prefix screen-width]
  (str "@media (min-width: " screen-width "){"
       (style->string
        (reduce (fn [coll [k v]]
                  (into coll [(str prefix "\\" k) v]))
                [] (partition 2 smap))) "}"))

(def all (reduce into [normalize base components accessibility transition]))

(defn internal-generate-styles [css-styles css-components-styles]
  (str (style->string css-styles)
       (with-responsive-prefix css-components-styles "sm" "640px")
       (with-responsive-prefix css-components-styles "md" "768px")
       (with-responsive-prefix css-components-styles "lg" "1024px")
       (with-responsive-prefix css-components-styles "xl" "1024px")))

;; TODO deprecated, purge should be an option
(defn generate-styles [path]
  (spit path (internal-generate-styles all components)))

;; TODO deprecated, purge should be an option
(defn generate-styles-with
  "Generate Tailwind CSS and append the provided css-file on the end."
  [path css-file]
  (spit path (str (internal-generate-styles all components) (slurp css-file))))

(defn split-tags-and-classes [tags]
  (reduce
   (fn [coll tag]
     (into coll
           (if (keyword? tag)
             (-> tag
                 name
                 (clojure.string/split #"\.")
                 (#(map keyword %)))
             [tag])))
   [] tags))

(defn convert-pseudo-classes
  "Converts hiccup class reference to css or what we expect in sail's lookup table e.g :hover:red-400 -> :hover\\:red-400:hover"
  [classes]
  (map (fn [c]
         (if (keyword? c)
           (cond
             (s/includes? c "hover") (keyword (str (s/replace (name c) ":" "\\:") ":hover"))
             (s/includes? c "active") (keyword (str (s/replace (name c) ":" "\\:") ":active"))
             (s/includes? c "focus") (keyword (str (s/replace (name c) ":" "\\:") ":focus"))
             :else c)
           c)
         ) classes))

;; used-css-classes aren't just classes but 'keywords' like body/html, psedo tags etc
(defn purge-styles [css-styles used-css-classes]
  (let [split-used-css-classes (-> (split-tags-and-classes used-css-classes)
                                   convert-pseudo-classes)]
    (reduce (fn [coll [k v]]
              (if (some #{k} split-used-css-classes)
                (into coll [k v])
                coll))
            [] (partition 2 css-styles))))

(defn all-keywords-in-file [filepath]
  (let [file-str (slurp filepath)
        normalized (string/replace file-str "::" ":")
        reader (PushbackReader. (io/reader (char-array normalized)))
        eof (Object.)]
    (try
      (->> (loop [acc []
                  form (read reader false eof)]
             (if (identical? form eof)
               acc
               (let [next (try (read reader false eof)
                               (catch Exception e
                                 (read reader false eof)))]
                 (recur (conj acc form) next))))
           (flatten)
           (filter simple-keyword?))
      (catch Exception e
        (log/info (str "Exception while reading keywords in file: " filepath ", got: " (.getMessage e)))))))

(def default-keywords
  [:html :body :* [:* (keyword "::before") (keyword "::after")]])

(defn clojure-file? [file-path]
  (some #(clojure.string/ends-with? file-path %)
        [;; ".cljc" ".edn" ;; I get errors reading these but it would
         ;; be useful to consume them.
         ".cljs" ".clj"]))

(defn all-project-keywords
  "Pulls out all the keywords for the given path (but not split e.g :div.rounded.p-4 not [:rounded :p-4])"
  [path]
  (->> (file-seq (clojure.java.io/file (or path "src")))
       (filter #(.isFile %))
       (filter #(clojure-file? (.getName %)))
       (#(mapcat all-keywords-in-file %))
       (into default-keywords)))

;; TODO generate-styles is currently over-zealous and will include used styles for ALL media queries even if they
;; aren't used.
;; E.g bg-red-400 is included in project code so xl:bg-red-400, lg:bg-red-400 etc are all included.
(defn purge-and-generate-styles [output {:keys [paths css-file] :as opts}]
  (log/info (str "purge-and-generate-styles to: " output) opts)
  (let [kws (mapcat all-project-keywords paths)
        out-all (if (:purge opts)
                  (purge-styles all kws)
                  all)
        out-components (if (:purge opts)
                         (purge-styles components kws)
                         components)
        generated-content (internal-generate-styles out-all out-components)]
    (spit output (if css-file
                   (str generated-content (slurp css-file))
                   generated-content))))

(defonce
 ^{:doc "Contains the dirwatch process to control sail's watching for compilation process"}
 css-watcher (atom nil))

(def default-opts
  {:purge true})

(defn build
  ([output]
   (build output {}))
  ([output {:keys [paths] :as opts}]
   (let [dirs (or paths [(System/getProperty "user.dir")])]
     (purge-and-generate-styles output (merge default-opts opts {:paths dirs})))))

(defn watcher-running? []
  (not (nil? @css-watcher)))

(defn- watch-fn [output opts]
  (fn [{:keys [file]}]
    (let [path (.getAbsolutePath file)]
      (when-not (s/includes? path output)
        (log/info (str "watcher requested style generation: " path))
        (build output opts)))))

;; TODO watch/build fns share a lot of code, generalise the setup for both of these!
(defn watch
  "Watch & rebuild styles on file modified, useful when developing to view sites with purged code as you would use in production.
  Also useful for providing the feedback when manipulating classes in Clojure e.g splitting (str \"bg-green-\" v).

  Takes optional args:
  :path directory to watch for new uses of css classes (e.g your project code)
  "
  ([output]
   (watch output {}))
  ([output {:keys [paths] :as opts}]
   (let [dirs (or paths [(System/getProperty "user.dir")])]
     (io/make-parents output)
     (log/info output opts)
     (build output opts)
     (reset! css-watcher
             (apply (partial dw/watch-dir (watch-fn output opts))
                    (map io/file dirs))
             ;; (apply dw/watch-dir (into [(watch-fn output opts)] (io/file dirs)))
             ;; (dw/watch-dir (watch-fn output opts) (io/file (first dirs)))
             )
     (log/info (str "sail watcher started, monitoring file changes under " dirs)))))

(defn stop-watch []
  (if @css-watcher
    (do
      (dw/close-watcher @css-watcher)
      (reset! css-watcher nil)
      (log/info "shutting down sail css watcher"))
    (log/info "sail css watcher is not currently running")))
