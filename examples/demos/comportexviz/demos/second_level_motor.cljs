(ns comportexviz.demos.second-level-motor
  (:require [org.nfrac.comportex.demos.second-level-motor :as demo]
            [org.nfrac.comportex.core :as core]
            [comportexviz.main :as main]
            [comportexviz.helpers :as helpers :refer [resizing-canvas]]
            [comportexviz.plots-canvas :as plt]
            [comportexviz.demos.sensorimotor-1d :refer [draw-eye]]
            [comportexviz.server.browser :as server]
            [comportexviz.util :as utilv]
            [monet.canvas :as c]
            [reagent.core :as reagent :refer [atom]]
            [reagent-forms.core :refer [bind-fields]]
            [goog.dom :as dom]
            [goog.dom.forms :as forms]
            [clojure.string :as str]
            [cljs.core.async :as async :refer [put!]])
  (:require-macros [comportexviz.macros :refer [with-ui-loading-message]]))

(def config
  (atom {:text demo/test-text}))

(def world-c (async/chan))

(def control-c (async/chan))

(def into-sim (atom nil))

(def model (atom nil))

(def raw-models-c
  (async/chan))

(defn feed-world!
  "Feed the world input channel continuously, selecting actions from
  state of model itself."
  []
  (demo/feed-world-c-with-actions! raw-models-c control-c world-c model))

(defn draw-world
  [ctx in-value]
  (let [{:keys [sentences position]} in-value
        [i j k] position
        sentence (get sentences i)
        word-n-letters (map (comp inc count) sentence)
        sentence-flat (concat (flatten (interpose \space sentence)) ["."])
        n-letters (reduce + word-n-letters)
        x-lim [0 1]
        y-lim [0 n-letters]
        width-px (.-width (.-canvas ctx))
        height-px (.-height (.-canvas ctx))
        plot-size {:w width-px
                   :h height-px}
        plot (plt/xy-plot ctx plot-size x-lim y-lim)
        x-scale (plt/scale-fn x-lim (:w plot-size))
        y-scale (plt/scale-fn y-lim (:h plot-size))]
    (c/clear-rect ctx {:x 0 :y 0 :w width-px :h height-px})
    (plt/frame! plot)
    (c/font-style ctx (str (int (/ height-px n-letters)) "px monospace"))
    (c/text-baseline ctx :middle)
    (c/fill-style ctx "black")
    (doseq [[y letter] (map-indexed vector sentence-flat)]
      (c/text ctx {:x 5 :y (y-scale (+ y 0.5)) :text (str letter)}))
    (let [curr-index (apply + k (take j word-n-letters))
          [ni nj nk] (demo/next-position in-value)
          sentence-sacc (:next-sentence-saccade in-value)
          next-index (cond
                       (neg? sentence-sacc) -1
                       (pos? sentence-sacc) (inc n-letters)
                       :else (apply + nk (take nj word-n-letters)))
          focus-x 10
          focus-y (y-scale (+ 0.5 curr-index))
          next-focus-y (y-scale (+ 0.5 next-index))
          eye-x (:w plot-size)
          eye-y (quot (:h plot-size) 2)]
      (doto ctx
        ;; draw line of next position (after next saccade)
        (c/begin-path)
        (c/move-to eye-x eye-y)
        (c/line-to focus-x next-focus-y)
        (c/stroke-style "lightgrey")
        (c/stroke)
        ;; draw line of current position
        (c/begin-path)
        (c/move-to eye-x eye-y)
        (c/line-to focus-x focus-y)
        (c/stroke-style "black")
        (c/stroke)
        ;; draw eye
        (draw-eye {:x eye-x
                   :y eye-y
                   :angle (Math/atan2 (- focus-y eye-y)
                                      (- focus-x eye-x))
                   :radius 30})))))

(defn signed-str [x] (str (if (neg? x) "" "+") x))

(defn sentence-string
  [sentence]
  (->> (concat (flatten (interpose \space sentence)) ["."])
       (apply str)))

(defn world-pane
  []
  (when-let [step (main/selected-step)]
    (let [in-value (first (:input-values step))
          {:keys [sentences position]} in-value
          [i j k] position
          letter-sacc (:next-letter-saccade in-value)
          word-sacc (:next-word-saccade in-value)
          sentence-sacc (:next-sentence-saccade in-value)]
      [:div
       [:p.muted [:small "Input on selected timestep."]]
       [:table.table
        [:tr [:th "value"]
         [:td (str (get-in sentences position))]]
        [:tr [:th "next move"]
         [:td (cond
                (not (zero? sentence-sacc)) "sentence"
                (not (zero? word-sacc)) "word"
                (not (zero? letter-sacc)) "letter")]]
        [:tr [:th "direction"]
         [:td (if (pos? (+ sentence-sacc word-sacc letter-sacc))
                "fwd" "back")]]]
       [:pre
        (->> (take i sentences)
             (map sentence-string)
             (str/join \newline))]
       [resizing-canvas {:style {:width "100%"
                                 :height "300px"}}
        [main/selection]
        (fn [ctx]
          (let [step (main/selected-step)
                in-value (first (:input-values step))]
            (draw-world ctx in-value)))
        nil]
       [:pre
        (->> (drop (inc i) sentences)
             (map sentence-string)
             (str/join \newline))]])))

(defn set-model!
  []
  (let [] ;; TODO: config
    (utilv/close-and-reset! into-sim (async/chan))
    (utilv/close-and-reset! main/into-journal (async/chan))

    (with-ui-loading-message
      (reset! model (demo/two-region-model))
      (server/init model
                   world-c
                   @main/into-journal
                   @into-sim
                   raw-models-c))))

(defn set-text!
  []
  (let [sentences (demo/parse-sentences (:text @config))]
    (when (seq sentences)
      (put! control-c #(assoc demo/initial-input-val
                              :sentences sentences)))))

(def config-template
  [:div
   [:h3 "Input " [:small "Letters in words in sentences"]]
   [:div.form-horizontal
    [:div.form-group
     [:div.col-sm-12
      [:textarea.form-control {:field :textarea
                               :id :text
                               :rows 8}]]]
    [:div.form-group
     [:div.col-sm-8
      [:button.btn.btn-primary
       {:on-click (fn [e]
                    (set-text!)
                    (.preventDefault e))}
       "Set sentences"]]]
    ]
   [:h3 "HTM model"]
   [:div.form-horizontal
    [:div.form-group
     [:div.col-sm-offset-5.col-sm-7
      [:button.btn.btn-default
       {:on-click (fn [e]
                    (set-model!)
                    (.preventDefault e))}
       "Restart with new model"]
      [:p.text-danger "This resets all parameters."]]]
    ]])

(defn model-tab
  []
  [:div
   [:p "A two-region example of temporal pooling over sensorimotor input."]
   [:p "The world is a string of letters divided into words and
   sentences. Only one letter is received as direct sensory input at
   any one time. Motor actions (saccades) shift the focus to a new
   letter. These motor actions are encoded in two separate inputs: "
    [:code "letter-motor"] " and " [:code "word-motor"]
    ". The former is distal input to the first level region, while the
    latter is distal input to the second-level region."]
   [:p "Within a word, letter saccades always move forward one
   letter. At the end of a word, we check whether the first region's
   columns are bursting (indicating it has not yet learned the word's
   letter sequence). If it is bursting, a letter saccade moves back to
   the start of the same word. Otherwise, a word saccade is
   generated."]
   [:p "Within a sentence, word saccades always move forward one
   word. At the end of a sentence, we check whether the second
   region's columns are bursting (indicating it has not yet learned
   the sentence's word sequence). If it is bursting, a word saccade
   moves back to the start of the same sentence."]
   [:p "And similarly for sentence saccades."]
   [bind-fields config-template config]
   ]
  )

(defn ^:export init
  []
  (reagent/render [main/comportexviz-app model-tab world-pane into-sim]
                  (dom/getElement "comportexviz-app"))

  (set-model!)
  (feed-world!))
