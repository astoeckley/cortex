(ns cortex.spiral-test
  (:require
    [clojure.test :refer [deftest is are]]
    [cortex.optimise :as opt]
    [clojure.core.matrix :as m]
    [clojure.core.matrix.random :as randm]
    [cortex.util :as util]
    [cortex.network :as net]
    [cortex.core :as core]
    [cortex.layers :as layers]))



(def num-points 100)
(def num-classes 2)
(m/set-current-implementation :vectorz)

(defn create-spiral
  [start-theta end-theta start-radius end-radius num-items]
  (let [theta-increments (/ (- end-theta start-theta) num-items)
        radius-increments (/ (- end-radius start-radius) num-items)]
    (mapv (fn [idx]
            (let [theta (+ start-theta (* theta-increments idx))
                  radius (+ start-radius (* radius-increments idx))]
              (m/mul! (m/array [(Math/sin theta) (Math/cos theta)]) radius)))
          (range num-items))))


(defn create-spiral-from-index
  [idx]
  (let [start-theta (/ idx Math/PI)
        end-theta (+ start-theta 4)
        start-radius 0.1
        end-radius 2.0]
    (create-spiral start-theta end-theta start-radius end-radius num-points)))


(def all-data (into [] (mapcat create-spiral-from-index (range num-classes))))
(def all-labels (into [] (mapcat #(repeat num-points (m/array (assoc (into [] (repeat num-classes 0)) % 1))) (range num-classes))))
(def loss-fn (opt/mse-loss))
(def hidden-layer-size 5)


(defn softmax-network
  []
  (core/stack-module [(layers/linear-layer 2 num-classes)
                      (layers/softmax [num-classes])
                      ]))

(defn create-optimizer
  [network]
  (opt/adadelta-optimiser (core/parameter-count network)))


(defn train-and-evaluate
  []
  (let [network (softmax-network)
        optimizer (create-optimizer network)
        network (net/train network optimizer loss-fn all-data all-labels 100 1)]
    (println (format "Network score: %g" (net/evaluate-softmax network all-data all-labels)))
    network))