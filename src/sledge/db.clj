(ns sledge.db
  (:import [java.util Date])
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pp pprint]]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str])  )


;; adjunct indexes map from tokenized attribute values
;; (["orbitall"], ["little","fluffy", "clouds"] or whatever)
;; to pathnames.

(defn add-tokens [token-map val tokens]
  (merge-with clojure.set/union
      	      token-map
              (into {} (map vector tokens (repeat #{val})))))

(assert (= (add-tokens {} "/hell.mp3" ["Foo" "Fighters"])
           {"Fighters" #{"/hell.mp3"}, "Foo" #{"/hell.mp3"}}))

(defn assoc-adjunct-index [index name-map tokenize]
  (reduce (fn [m [filename tags]]
            (add-tokens m filename (tokenize tags)))
          index
          name-map))

(defn make-adjunct-index [name-map tokenize]
  (assoc-adjunct-index {} name-map tokenize))

(let [name-map {"/l.mp3" {:author "Foo Fighters"}
                "/pretenders.mp3" {:author "Foo Fighters"}
                "/box.mp3" {:author "Orbital" :title "The Box"}}]
  (make-adjunct-index name-map
                      #(set (str/split (:author %) #" "))))


(defn lower-words [x]
  (set/difference
   (set (remove str/blank? (str/split (.toLowerCase (or x "")) #"[ ,.()-]")))
   #{"the"}))

(defn safe-read-integer [s]
  (try (Integer/parseInt s)
       (catch NumberFormatException e nil)))

(def adjuncts-schema
  {:artist {:tokenize-tags (comp #'lower-words :artist)
            :tokenize-query lower-words}
   :album {:tokenize-tags (comp #'lower-words :album)
           :tokenize-query lower-words}
   :title {:tokenize-tags (comp #'lower-words :title)
           :tokenize-query lower-words}
   ;; I have the feeling genre is in practice something useless like
   ;; a number inside parens.  Sort that out before adding this index
   #_ #_
   :genre {:tokenize-tags (comp #'lower-words :genre)
           :tokenize-query lower-words}
   :_content {:tokenize-tags (fn [r]
                               (lower-words
                                (str/join " "
                                          ((juxt :artist :album :title :genre)
                                           r))))
              :tokenize-query lower-words}
   ;; numeric field support is a bit rudimentary yet, it doesn't
   ;; do anything useful with "like".
   :year {:empty-map (sorted-map)
          :tokenize-tags #(if-let [y (safe-read-integer (get % :year ""))]
                            [y] [])
          :tokenize-query #(vector (safe-read-integer %))}
   })

;; for each entry [k v] in adjuncts-schema, create map entry
;; from k to (make-adjunct-index name-map (:tokenize-tags v))
;; and have the end result attached to the primary index
;; somehow

(defn adjunctivize [name-map]
  (println "reindexing ...")
  (reduce
   (fn [m [attr tok]]
     (let [empty (get tok :empty-map {})]
       (assoc m attr (assoc-adjunct-index empty name-map (:tokenize-tags tok)))))
   {}
   adjuncts-schema))


(defn query-adjunct [index adjunct-name string]
  (let [kw (keyword adjunct-name)
        tokenizer (:tokenize-query
                   (or (get adjuncts-schema kw)
                       (throw (Exception. "no index for attribute"))))
        tokens (tokenizer string)
        adjunct-map (get @(:adjuncts index) kw)
        matches (map #(get adjunct-map %) tokens)]
    ;; return a collection of pathnames ordered by the number
    ;; of elements of matches that each appears within
    (let [relevance (fn [file]
                      (/ (count (filter #(contains? % file) matches))
                         (count tokens)))]
      (sort-by second >
               (map (fn [f] [f (relevance f)])
                    (apply set/union matches))))))

(defmulti where (fn [index [op & args]] op))

(defmethod where "like" [index [_ attr string]]
  (query-adjunct index attr string))

(defmethod where "=" [index [_ attr string]]
  (let [likes (query-adjunct index attr string)
        names (:data index)]
    (filter (fn [[path rel]]
              (and (>= rel 1)
                   (let [tags (get names path)]
                     (= (get tags (keyword attr)) string))))
	    likes)))

(defn write-log [name-map filename]
  (with-open [f (io/writer filename)]
    (binding [*out* f]
      (dorun (map prn name-map)))))

(defn entries-where [index query]
  (let [data (:data index)]
    (map #(if-let [r (get data (first %))]
            (assoc r :_score (second %)))
         (where index query))))

;; We have a bunch of [pathname relevance] for each constituent term
;; We want the pathnames that appear in all of the lists, with
;; relevance computed as the product of that pathname's relevance
;; in each constituent list

(defmethod where "and" [index [_ & terms]]
  (if terms
    (let [results (map #(into {} (where index %)) terms)
          paths (apply set/intersection (map #(set (keys %)) results))]
      (map (fn [path]
             [path (reduce * (map #(get % path) results))])
           paths))
    (map #(vector % 1) (keys (:data index)))))

(defmethod where "or" [index [_ & terms]]
  (if terms
    (let [results (map #(into {} (where index %)) terms)
          paths (apply set/union (map #(set (keys %)) results))]
      (map (fn [path]
             [path (reduce max (map #(get % path 0) results))])
           paths))
    []))


(defn by-pathname [index pathname]
  (get (:data index) pathname))

;; the main index maps from pathname to hash of tags

(defn last-modified [index]
  (let [log (:log index)]
    (if (and log (.exists log))
      (.lastModified log)
      0)))

(defn open-index [name]
  (let [folder (io/file name)
        logfile (io/file folder "log.edn")
        name-map
        (if (.exists logfile)
          (with-open [f (java.io.PushbackReader. (clojure.java.io/reader logfile))]
            (let [forms (repeatedly (partial edn/read {:eof nil} f))]
              (reduce (fn [m [k v]] (assoc m k v))
                      {}
                      (take-while identity forms))))
          {})
        adjunct-maps (delay (adjunctivize name-map))
        ]
    (or (.isDirectory folder) (.mkdir folder))
    {:folder folder :log logfile :data name-map :adjuncts adjunct-maps
     :dirty false}
    ))

(defn save-index [index]
  (when (:dirty index)
    (println "Saving...")
    (let [tmpname (io/file (:folder index) "tmplog.edn")]
      (write-log (:data index) tmpname)
      (.renameTo tmpname (:log index))))
  (assoc index :dirty false))

(defn update-entry [db k v]
  (let [data (assoc (:data db) k v)]
    (assoc db
      :dirty true
      :data data
      :adjuncts (delay (adjunctivize data)))))
