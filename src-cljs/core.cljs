;;; -*- Clojure -*- mode
(ns sledge.core
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:import [goog.net XhrIo])
  (:require [goog.events :as events]
            [clojure.string :as string]
            [clojure.set :as set]
            [cljs.core.async :as async :refer [>! <! put! chan]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            ))

(enable-console-print!)

(defn detect-platform []
  (let [w (.-innerWidth js/window)]
    (condp > w
      480 :phone
      600 :tablet
      :desktop)))

(defn mobile? []
  (= (detect-platform) :phone))

(def app-state
  (atom
    {:search {
              :term #{}
              :results []
              }
     :player-queue []
     :tab-on-view [:search]
     }))

(defn search-results []
  (om/ref-cursor (:results (:search (om/root-cursor app-state)))))

(defn player-queue []
  (om/ref-cursor (:player-queue (om/root-cursor app-state))))

(defn tab-on-view []
  (om/ref-cursor (:tab-on-view (om/root-cursor app-state))))

(defn enqueue-track [track]
  (om/transact! (player-queue) #(conj % track)))

(defn dequeue-track [index]
  (om/transact! (player-queue)
                (fn [v] (vec (concat (subvec v 0 index)
                                     (subvec v (inc index)))))))

(defn dequeue-all []
  (om/transact! (player-queue) (fn [v] [])))

(defn mmss [seconds]
  (let [m (quot seconds 60)
        s (- seconds (* 60 m))]
    (str m ":" (.substr (str "000" s) -2))))


#_(println (mmss 20) (mmss 40) (mmss 60) (mmss 80)
         (mmss 200) (mmss 4000) (mmss 6000) (mmss 381))

(defn results-track-view [track owner]
  (reify
    om/IRender
    (render [this]
      (let [search-chan (om/get-shared owner :search-channel)
            artist (dom/span
                    #js {:className "artist"
                         :onClick #(put! search-chan
                                         [:add
                                          [[:artist (get @track "artist")]]])}
                    (get track "artist"))
            album (dom/span
                   #js {:className "album"
                        :onClick #(put! search-chan
                                        [:add
                                         [[:artist (get @track "artist")]
                                          [:album (get @track "album")]]])}
                   (get track "album"))
            title (dom/span #js {:className "title"}
                            (str (get track "track") " - " (get track "title")))
            duration (dom/span #js {:className "duration"} (mmss (get track "length")))
            button (dom/button #js {:onClick #(enqueue-track @track)} "+")]
        (apply dom/div #js {:className "track"}
               (if (mobile?)
                 [title artist album duration button]
                 [artist album title duration button]))))))


(defn xhr-search [term]
  (let [term (filter second term)
        channel (chan)]
    (.send XhrIo "/tracks.json"
           (fn [e]
             (let [xhr (.-target e)
                   code (.getStatus xhr)
                   o (and (< code 400) (js->clj (.getResponseJson xhr)))]
               (put! channel (or o []))))
           "POST"
           (string/join
            " AND "
            (map (fn [[k v]] (str (name k) ": " (pr-str v))) term))
           {"Content-Type" "text/plain"}
           )
    channel))

(defn sorted-tracks [tracks]
  (sort-by
   #(vector (get % "artist")
            (get % "album")
            (js/parseInt (get % "track")))
   tracks))

(defn results-view [tracks owner]
  (reify
    om/IRender
    (render [this]
      (let [button (dom/button
                    #js {:onClick
                         (fn [e] (doall (map #(enqueue-track %)
                                             tracks)))}
                    "+")
            track-components
            (om/build-all results-track-view tracks)]
        (if (mobile?)
          (apply dom/div #js {:className "results tracks" }
                 (dom/div #js {:className "track"}
                          (dom/span {:id "queue-all-tracks"}
                                    "Queue all tracks")
                          button)
                 track-components)
          (apply dom/div #js {:className "results tracks" }
                 (dom/div #js {:className "track header"}
                          (dom/span #js {:className "artist"} "Artist")
                          (dom/span #js {:className "album"} "Album" )
                          (dom/span #js {:className "title"} "Title")
                          (dom/span #js {:className "duration"} "Length")
                          button)
                 track-components))))
    ))


(defn queue-track-view [track owner]
  (reify
    om/IRenderState
    (render-state [this {:keys [index]}]
      (dom/div #js {:className "track"}
               (dom/span #js {:className "artist"} (get track "artist"))
               (dom/span #js {:className "album"} (get track "album" ))
               (dom/span #js {:className "title"} (get track "title"))
               (dom/span #js {:className "duration"} (mmss (get track "length")))
               (dom/button #js {:onClick #(dequeue-track index)}
                           "-")))))

(defn queue-view [app owner]
  (reify
    om/IRender
    (render [this]
      (let [queue (om/observe owner (player-queue))]
        (apply dom/div #js {:className "queue tracks"}
               (dom/div #js {:className "track header"}
                        (dom/span #js {:className "artist"} "Artist")
                        (dom/span #js {:className "album"} "Album" )
                        (dom/span #js {:className "title"} "Title")
                        (dom/span #js {:className "duration"} "Length")
                        (dom/button #js {:onClick #(dequeue-all)} "-"))
               (map #(om/build queue-track-view
                               %1
                               {:state {:index %2}})
                    queue (range 0 999))
               )))))


(defn search-entry-view [term owner]
  (reify
    om/IRenderState
    (render-state [this state]
      (let [search-chan (om/get-shared owner :search-channel)
            send-search (fn [e]
                          (let [str (.. e -target -value)]
                            (if-not (empty? str)
                              (put! search-chan [:add [[:_content str]]]))))]
        (dom/div
         #js {:className "search-box"}
         (apply dom/span #js {:className "filters" }
                (map #(dom/span #js {:className "filter"
                                     :onClick
                                     (fn [e] (put! search-chan
                                                   [:drop [%]]))}
                                (str (name (first %)) ": " (second  %)))
                     (filter second term)))
         (dom/div #js {:id "bodge"}
                  (dom/input #js {:ref "search-term"
                                  :id "search-term"
                                  :type "text"
                                  :size "10"
                                  :placeholder "Search artist/album/title"
                                  :value (:string state)
                                  :onChange
                                  #(om/set-state! owner :string
                                                  (.. % -target -value))
                                  :onKeyUp
                                  #(when (= 13 (.-which %))
                                     (send-search %)
                                     (om/set-state! owner :string ""))
                                  :onBlur send-search
                                  })))
        ))))

(defn best-media-url [r]
  (let [urls (get r "_links")]
    (get
     (or (get urls "ogg") (get urls "mp3"))
     "href")))

(defn player-view [app owner]
  (reify
    om/IDidMount
    (did-mount [this]
      (let [el (om/get-node owner)]
        ;; last arg "true" is cos audio events don't bubble
        ;; http://stackoverflow.com/questions/11291651/why-dont-audio-and-video-events-bubble
        (.addEventListener el "ended" #(dequeue-track 0) true)))
    om/IRender
    (render [this]
      (let [queue (om/observe owner (player-queue))
            on-view (om/observe owner (tab-on-view))
            bits (best-media-url (first queue))
            player (dom/audio #js {:controls "controls"
                                 :autoPlay "true"
                                 :ref "player"
                                 :src bits
                                 })]
        (if (= (first on-view) :player-queue)
          (dom/div nil
                   (dom/h2 nil "player")
                   (dom/h3 nil "queued")
                   (om/build queue-view app)
                   player)
          (dom/div nil
                   player))))))



(defn update-term [[command new-terms] previous]
  (case command
    :add (set/union previous (set new-terms))
    :drop (set/difference previous new-terms)))


(defn search-view [search owner]
  (reify
    om/IWillMount
    (will-mount [_]
      (let [channel (om/get-shared owner :search-channel)]
        (go (loop []
              (let [search-for (update-term (<! channel) (:term @search))
                    _ (om/update! search [:term] search-for)
                    tracks (<! (xhr-search search-for))
                    sorted (sorted-tracks tracks)]
                (om/update! search [:results] (vec sorted))
                (recur))))))
    om/IRender
    (render [this]
      (let [on-view (om/observe owner (tab-on-view))]
        (if (= (first on-view) :search)
          (dom/div nil
                   (dom/h2 nil "library")
                   (om/build search-entry-view (:term search)
                             {:init-state {:string ""}})
                   (om/build results-view (:results search))))))))

(defn show-tab [cursor tab-name]
  (om/update! cursor [:tab-on-view] [tab-name]))

(defn tab-selector-view [app owner]
  (reify
    om/IRender
    (render [this]
      (dom/nav nil
               (dom/ul nil
                       (dom/li #js {:onClick #(show-tab app :search)}
                               "library")
                       (dom/li  #js {:onClick #(show-tab app :player-queue)}
                                "player"))))))



(defn app-view [app owner]
  (reify
    om/IRender
    (render [this]
      (dom/div nil
               (dom/header
                #js {:id "sledge"}
                "sledge"
                (om/build tab-selector-view app))
               (om/build search-view (:search app))
               (om/build player-view app)
               ))))

(defn init []
  (let [el (. js/document (getElementById "om-app"))
        search (chan)]
    (om/root app-view app-state
             {:target el
              :shared {:search-channel search}})))

(.addEventListener js/window "load" init)
