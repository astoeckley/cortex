(ns cortex.impl.layers
  (:require [cortex.protocols :as cp]
            [cortex.util :as util :refer [error EMPTY-VECTOR]]
            [clojure.core.matrix :as m]
            [core.blas.protocols :as blas]
            [cortex.backends :as b]
            [cortex.impl.vectorz-blas]
            #?(:clj [cortex.registry :refer [register-module]]
               :cljs [cortex.registry :refer-macros [register-module]]))
  #?(:clj (:import [java.util PriorityQueue])))

#?(:clj (do
          (set! *warn-on-reflection* true)
          (set! *unchecked-math* :warn-on-boxed)))

;; LOGISTIC
;; Module implementing a Logistic activation function over a numerical array
#?(:cljs (register-module cortex.impl.layers.Logistic))
(defrecord Logistic [output input-gradient]
  cp/PModule
    (calc [this input]
      (m/assign! output input)
      (m/logistic! output)
      this)

    (output [this]
      (:output this))

  cp/PNeuralTraining
    (forward [this input]
      (cp/calc this input))

    (backward [this input output-gradient]
      (let []
        ;; input gradient = output * (1 - output) * output-gradient
        (m/assign! input-gradient 1.0)
        (m/sub! input-gradient output)
        (m/mul! input-gradient output output-gradient)

        ;; finally return this, input-gradient has been updated in-place
        this))

    (input-gradient [this]
      input-gradient))


;; DROPOUT
;; Module implementing "dropout" functionality when training
;; Works as a identity function otherwise
#?(:cljs (register-module cortex.impl.layers.Dropout))
(defrecord Dropout [output input-gradient ^double probability dropout]
  cp/PModule
    (calc [this input]
      (m/assign! output input)
       this)

    (output [this]
      (:output this))

  cp/PNeuralTraining
    (forward [this input]
      (m/emap! (fn ^double [^double _] (if (< (Math/random) probability) 1.0 0.0)) dropout)
      (m/assign! output input)
      (m/mul! output dropout)
      (m/scale! output (/ 1.0 probability))
      this)

    (backward [this input output-gradient]
      (let []
        (m/assign! input-gradient output-gradient)
        (m/mul! input-gradient dropout)
        (m/scale! input-gradient (/ 1.0 probability))
        this))

    (input-gradient [this]
      input-gradient))


;; SCALE
;; Module implementing simple scaling functionality and addition with a constant
;; - factor of nil works as identity
;; - constant of nil works as identity
#?(:cljs (register-module cortex.impl.layers.Scale))
(defrecord Scale [output input-gradient factor constant]
  cp/PModule
    (calc [this input]
      (m/assign! output input)
      (when factor (m/mul! output factor))
      (when constant (m/add! output constant))
      this)

    (output [this]
      (:output this))

  cp/PNeuralTraining
    (forward [this input]
      (m/assign! output input)
      (when factor (m/mul! output factor))
      (when constant (m/add! output constant))
      this)

    (backward [this input output-gradient]
      (let []
        (m/assign! input-gradient output-gradient)
        (when factor (m/mul! input-gradient factor))
        this))

    (input-gradient [this]
      input-gradient))


;;There is an option that torch uses which is if the input is less than 0
;;then multiply it by a special value (negval).
;;https://github.com/torch/nn/blob/master/lib/THNN/generic/LeakyReLU.c
#?(:cljs (register-module cortex.impl.layers.RectifiedLinear))
(defrecord RectifiedLinear [output input-gradient dotvec negval]
  cp/PModule
  (calc [this input]
    (m/emap! (fn ^double [^double _ ^double in] (if (neg? in) negval 1.0)) dotvec input)
    (m/assign! output input)
    (m/mul! output dotvec)
    this)

  (output [this]
    (:output this))

  cp/PNeuralTraining
  (forward [this input]
    (cp/calc this input)
    this)


  (backward [this input output-gradient]
    (m/assign! input-gradient output-gradient)
    (m/mul! input-gradient dotvec)
    this)

  (input-gradient [this]
    input-gradient))

#?(:cljs (register-module cortex.impl.layers.Tanh))
(defrecord Tanh [output input-gradient]
  cp/PModule
  (calc [this input]
    (m/assign! output input)
    (m/tanh! output)
    this)

  (output [this]
    (:output this))

  cp/PNeuralTraining
  (forward [this input]
    (cp/calc this input)
    this)

  (backward [this input output-gradient]
    (m/assign! input-gradient output-gradient)
    (m/emul! input-gradient (util/tanh' output))
    this)

  (input-gradient [this]
    input-gradient))


(defn softmax-forward!
  "Runs softmax on input and places result in output"
  [input output]
  (let [max-val (m/emax input)]
    ;;From caffe, we subtract the max for numerical stability
    ;;and then run the textbook softmax
    (m/assign! output input)
    (m/sub! output max-val)
    (m/exp! output)
    (m/div! output (m/esum output))))


(defn softmax-backward!
  ""
  [input-gradient output output-gradient input]
  (m/assign! input-gradient output-gradient))

#?(:cljs (register-module cortex.impl.layers.Softmax))
(defrecord Softmax [output input-gradient]
  cp/PModule
  (calc [this input]
    (softmax-forward! input output))

  (output [this]
    (:output this))

  cp/PNeuralTraining
  (forward [this input]
    (cp/calc this input)
    this)


  (backward [this input output-gradient]
    (softmax-backward! input-gradient (:output this) output-gradient input)
    this)

  (input-gradient [this]
    input-gradient))

;;Found through perf testing...probably needs to be
;;specified somehow through the backend
(defn blas-gemv-cutoff ^long [] 500000)

(defn linear-forward!
  [this input]
  (let [weights (:weights this)
        bias (:bias this)
        elem-count (long (m/ecount weights))]
    (if (and (blas/supports-blas? input)
             (blas/supports-blas? weights)
             (blas/supports-blas? bias)
             (= (count (m/shape weights)) 2))
      (let [output (or (:output this)
                       (b/new-array (m/shape bias)))]
        (m/assign! output bias)
        (blas/gemv! output false 1.0 weights input 1.0)
        (assoc this :output output))
      (let [output (m/inner-product weights input)]
        (m/add! output bias)
        (assoc this :output output)))))


(defn linear-backward!
  "linear backward pass.  Returns a new input gradient."
  [this input output-gradient]
  (let [weights (:weights this)
        bias (:bias this)
        weight-gradient (:weight-gradient this)
        bias-gradient (:bias-gradient this)
        wg (m/outer-product output-gradient input)
        elem-count (long (m/ecount weights))]
    (m/add! (m/as-vector weight-gradient) (m/as-vector wg))
    (m/add! bias-gradient output-gradient)
    (if (and (blas/supports-blas? weights)
             (blas/supports-blas? output-gradient)
             (= 2 (count (m/shape weights))))
     (let [input-gradient (or (:input-gradient this)
                              (b/new-array (m/shape input)))]
       (blas/gemv! input-gradient true 1.0 weights output-gradient 0.0)
       (assoc this :input-gradient input-gradient))
     (do
       (assoc this :input-gradient (m/inner-product (m/transpose weights) output-gradient))))))

;; LINEAR
;; function that implements a linear transformation (weights + bias)
;; has mutable parameters and accumlators for gradient
#?(:cljs (register-module cortex.impl.layers.Linear))
(defrecord Linear [weights bias]
  cp/PModule
  (calc [this input]
    (linear-forward! this input))

    (output [this]
      (:output this))

  cp/PNeuralTraining
    (forward [this input]
      (-> this
        (cp/calc input)
        (assoc :input input)))

    (backward [this input output-gradient]
      (linear-backward! this input output-gradient))

    (input-gradient [this]
      (:input-gradient this))

  cp/PParameters
    (parameters [this]
      (m/join (m/as-vector (:weights this)) (m/as-vector (:bias this))))

    (update-parameters [this parameters]
      (let [param-view (cp/parameters this)]
        #?(:clj
            (m/assign! param-view parameters)
           :cljs
            (do
              (let [w (:weights this)
                    w-update (m/array (take (m/ecount (:weights this)) (m/eseq parameters)))
                    b (:bias this)
                    b-update (m/array (drop (m/ecount (:weights this)) (m/eseq parameters)))]
              (m/assign! (m/as-vector w) (m/as-vector w-update))
              (m/assign! (m/as-vector b) (m/as-vector b-update))))))

      #?(:clj
          (let [gradient-view (cp/gradient this)]
            (m/assign! gradient-view 0.0))
         :cljs
          (do
            (m/assign! (:weight-gradient this) 0.0)
            (m/assign! (:bias-gradient this) 0.0)))
      this)

  cp/PGradient
    (gradient [this]
      (m/join (m/as-vector (:weight-gradient this)) (m/as-vector (:bias-gradient this)))))

;; NORMALISER
;; Module which normalises outputs towards mean 0.0, sd 1.0
;; accumulates observed mean and variance of data, recalibrates during update-parameters
(def DEFAULT-NORMALISER-LEARN-RATE 0.001)
(def DEFAULT-NORMALISER-FACTOR 0.001)

#?(:cljs (register-module cortex.impl.layers.Normaliser))
(defrecord Normaliser [output input-gradient sd mean acc-ss acc-mean tmp]
  cp/PModule
    (calc [this input]
      (let []
        (m/assign! output input)
        (m/sub! output mean)
        (m/div! output sd)
        this))

    (output [this]
      (:output this))

  cp/PNeuralTraining
    (forward [this input]
      (let [lr (double (or (:learn-rate this) DEFAULT-NORMALISER-LEARN-RATE ))]
        (when (> lr 0)
          (let [decay (- 1.0 lr)]
            (m/scale! acc-mean decay)
            (m/add-scaled! acc-mean input lr)
            (m/scale! acc-ss decay)
            (m/add-scaled-product! acc-ss input input lr)))
        (cp/calc this input)))

    (backward [this input output-gradient]
      (let []
        ;; input gradient = output / s.d.
        (m/assign! input-gradient output-gradient)
        (m/div! input-gradient sd)

        ;; add gradient for normalisation adjustment
        (let [nf (double (or (:normaliser-factor this) DEFAULT-NORMALISER-FACTOR))]
          (when (> nf 0)
            ;; mean adjustment - gradient towards mean
            (m/assign! tmp input)
            (m/sub! tmp mean)
            (m/add-scaled! input-gradient tmp nf)

            ;; sd adjustment - gradient scales towards sd 1.0
            (m/assign! tmp sd)
            (m/sub! tmp 1.0)
            (m/add-scaled-product! input-gradient input tmp nf)
            ))

        ;; finally return this, input-gradient has been updated in-place
        this))

    (input-gradient [this]
      input-gradient)

  cp/PParameters
  (parameters
      [this]
        ;; no external parameters to optimise
        EMPTY-VECTOR)
    (update-parameters
      [this parameters]
        (m/assign! mean acc-mean)
        (m/assign! sd acc-ss)
        (m/add-scaled-product! sd mean mean -1.0)
        (m/sqrt! sd)
        this))

;; DENOISING AUTOENCODER
(defn noise-fn ^double [^double x]
  (if (< 0.2 (util/rand-normal))
    (util/rand-gaussian)
    x))

#?(:cljs (register-module cortex.impl.layers.DenoisingAutoencoder))
(defrecord DenoisingAutoencoder
  [up down input-tmp output-tmp ]
  cp/PModule
    (cp/calc [m input]
      (let [up (cp/calc up input)]
        (DenoisingAutoencoder. up down input-tmp output-tmp)))

    (cp/output [m]
      (cp/output up))

  cp/PNeuralTraining
    (forward [this input]
      (m/assign! input-tmp input)
      (m/emap! noise-fn input-tmp) ;; input-tmp contains input with noise
      (let [noise-up (cp/calc up input-tmp)
            _ (m/assign! output-tmp (cp/output noise-up)) ;; output-tmp contains noisy output from up
            up (cp/forward up input)
            down (cp/forward down output-tmp)
            ]
        (DenoisingAutoencoder. up down input-tmp output-tmp)))

    (backward [this input output-gradient]
      (let [down (cp/backward down output-tmp (m/sub input (cp/output down)))
            _ (m/assign! output-tmp output-gradient)
            _ (m/add! output-tmp (cp/input-gradient down)) ;; output-tmp contains gradient
            up (cp/backward up input output-tmp)
            ]
        (DenoisingAutoencoder. up down input-tmp output-tmp)))

    (input-gradient [this]
      (cp/input-gradient up))

  cp/PGradient
    (gradient [this]
      (m/join (cp/gradient up) (cp/gradient down)))

  cp/PParameters
    (parameters [this]
      (m/join (cp/parameters up) (cp/parameters down)))

    (update-parameters [this parameters]
      (let [nup (cp/parameter-count up)
            ndown (cp/parameter-count down)
            up (cp/update-parameters up (m/subvector parameters 0 nup))
            down (cp/update-parameters down (m/subvector parameters nup ndown))]
        (DenoisingAutoencoder. up down input-tmp output-tmp)))

    cp/PModuleClone
      (clone [this]
        (DenoisingAutoencoder. (cp/clone up)
                               (cp/clone down)
                               (m/clone input-tmp)
                               (m/clone output-tmp))))


(defn get-padded-strided-dimension
  "http://caffe.berkeleyvision.org/tutorial/layers.html.  Returns the dimensions
of the output of a conv-net ignoring channels."
  ^long [^long input-dim ^long pad ^long kernel-size ^long stride]
  (long (+ (quot (- (+ input-dim (* 2 pad))  kernel-size)
                 stride)
           1)))

(defn columnar-sum
  "Sum the columns of the matrix so we produce in essence a new row
which contains the columnar sums.  Produces a vector and leaves input unchanged"
  [m]
  (let [rows (m/rows m)
        accumulator (m/clone (first rows))]
    (reduce m/add! accumulator (rest rows))))



(defn create-conv-layer-config
  [width height kernel-width kernel-height padx pady stride-w stride-h num-channels]
  (let [width (long width)
        height (long height)
        kernel-width (long kernel-width)
        kernel-height (long kernel-height)
        padx (long padx)
        pady (long pady)
        stride-w (long stride-w)
        stride-h (long stride-h)
        num-channels (long num-channels)]
    { :width width
     :height height
     :k-width kernel-width
     :k-height kernel-height
     :padx padx
     :pady pady
     :stride-w stride-w
     :stride-h stride-h
     :num-channels num-channels}))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Convolution layer forward pass utility functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-padded-matrix
  "We need to force vectorz to create a dense matrix.  Hence the superfluous lines"
  [{:keys [^long width ^long height ^long padx ^long pady
           ^long num-channels] :as conv-layer-config}]
  (m/array :vectorz (repeat (+ height (* 2 pady))
                            (repeat (* (+ width (* 2 padx))
                                       num-channels)
                                    0.0))))


(defn sub-vector-assignment!
  [padx pady image-width num-channels backing-matrix input-vector]
  (let [^long padx padx
        ^long pady pady
        ^long image-width image-width
        ^long num-channels num-channels
        input-row-stride (* image-width num-channels)
        input-row-count (quot (long (first (m/shape input-vector))) input-row-stride)]
    (loop [row 0]
      (when (< row input-row-count)
        (let [backing-row-idx (+ pady row)
              backing-row (m/get-row backing-matrix backing-row-idx)
              input-row (m/subvector input-vector (* row input-row-stride) input-row-stride)
              backing-row (m/subvector backing-row (* padx num-channels) input-row-stride)]
          (m/assign! backing-row input-row))
        (recur (inc row))))))


(defn create-padded-input-matrix
  "Remove padding from the equation by creating an input vector that includes it
and then copying the input data (if necessary) into the input vector.  Note this operation
is done for every input *and* it is done for the max pooling layers.  If you want to avoid
the perf hit of a copy then don't use padding."
  [interleaved-input-vector {:keys [^long width ^long height ^long padx ^long pady
                                    ^long num-channels] :as conv-layer-config}]
  (let [has-padding (or (> padx 0) (> pady 0))]
    (if has-padding
      (let [padded-input-matrix (create-padded-matrix conv-layer-config)]
        (sub-vector-assignment! padx pady width num-channels padded-input-matrix interleaved-input-vector)
        padded-input-matrix)
      (m/reshape interleaved-input-vector [height (* width num-channels)]))))


(def convolution-operation-sequence
  (memoize
   (fn [{:keys [^long width ^long height
                ^long k-width ^long k-height
                ^long padx ^long pady
                ^long stride-w ^long stride-h
                ^long num-channels] :as conv-layer-config}]
     (let [output-width (get-padded-strided-dimension width padx k-width stride-w)
           output-height (get-padded-strided-dimension height pady k-height stride-w)
           ;;A 'row' for each output pixel
           num-rows (* output-width output-height)]
       (flatten
        (for [^long idx (range num-rows)]
          (let [output-x (rem idx output-width)
                output-y (quot idx output-width)
                input-left (- (* output-x stride-w) padx)
                input-top (- (* output-y stride-h) pady)]
            (for [^long conv-y (range k-height)]
              (let [input-y (+ input-top conv-y)
                    input-x input-left]
                {:input-x input-x :input-y input-y :conv-x 0 :conv-y conv-y
                 :output-x output-x :output-y output-y})))))))))


(def convolution-input-sequence
  (memoize
   (fn [{:keys [^long width ^long height
                ^long k-width ^long k-height
                ^long padx ^long pady
                ^long stride-w ^long stride-h
                ^long num-channels] :as conv-layer-config}]
     ;;Trim out rows that are completely invalid w/r/t the input non-padded matrix
     (let [valid-rows (filter #(let [{:keys [^long input-y ^long input-x]} %]
                                 (and (>= input-y 0)
                                      (< input-y height)
                                      (>= (+ input-x k-width) 0)
                                      (< input-x width)))
                              (convolution-operation-sequence conv-layer-config))
           output-width (get-padded-strided-dimension width padx k-width stride-w)
           output-height (get-padded-strided-dimension height pady k-height stride-w)]
       ;;Transform each entry such that the x-op stays in bounds.  Also, change output-x,output-y
       ;;into an output-row-index that indicates which row in the convolution matrix this item corresponds to.
       (map (fn [{:keys [^long input-x ^long input-y ^long conv-x ^long conv-y ^long output-x ^long output-y]
                  :as conv-window}]
              (let [input-offset-x (max input-x 0)
                    conv-x (- input-offset-x input-x)
                    k-width (- k-width conv-x)
                    input-end (min width (+ input-offset-x k-width))
                    write-len (- input-end input-offset-x)
                    conv-row-index (+ (* output-y output-width) output-x)]
                (assoc conv-window :conv-x conv-x :input-x input-offset-x
                       :write-len write-len :conv-row-index conv-row-index)))
            valid-rows)))))


(defn convolution-to-input-vector!
  [conv-matrix input-vector {:keys [^long width ^long height
                                    ^long k-width ^long k-height
                                    ^long padx ^long pady
                                    ^long stride-w ^long stride-h
                                    ^long num-channels] :as conv-layer-config}]
  (let [kernel-stride (* k-width num-channels)
        input-stride (* width num-channels)]
    (doseq [input-item (convolution-input-sequence conv-layer-config)]
      (let [{:keys [^long input-x ^long input-y
                    ^long conv-x ^long conv-y
                    ^long write-len ^long conv-row-index]} input-item
            input-row (m/get-row conv-matrix conv-row-index)
            write-idx 0
            conv-offset (+ (* conv-y kernel-stride)
                           (* (+ conv-x write-idx) num-channels))
            input-offset (+ (* input-y input-stride)
                            (* (+ input-x write-idx) num-channels))
            write-num-elems (* write-len num-channels)]
        (m/add! (m/subvector input-vector input-offset write-num-elems)
                (m/subvector input-row conv-offset write-num-elems))))
    input-vector))


(defn input-vector-to-convolution!
  [input-vector conv-matrix {:keys [^long width ^long height
                                    ^long k-width ^long k-height
                                    ^long padx ^long pady
                                    ^long stride-w ^long stride-h
                                    ^long num-channels] :as conv-layer-config}]
  (let [kernel-stride (* k-width num-channels)
        input-stride (* width num-channels)
        num-rows (m/row-count conv-matrix)]
    (doseq [input-item (convolution-input-sequence conv-layer-config)]
      (let [{:keys [^long input-x ^long input-y
                    ^long conv-x ^long conv-y
                    ^long write-len ^long conv-row-index]} input-item
            conv-row (m/get-row conv-matrix conv-row-index)
            write-idx 0
            conv-offset (+ (* conv-y kernel-stride)
                           (* (+ conv-x write-idx) num-channels))
            input-offset (+ (* input-y input-stride)
                            (* (+ input-x write-idx) num-channels))
            write-num-elems (* write-len num-channels)]
        (m/assign! (m/subvector conv-row conv-offset write-num-elems)
                   (m/subvector input-vector input-offset write-num-elems))))
    conv-matrix))


(defn convolution-sequence
  "Produce a sequence of views that performs a convolution over an input matrix"
  [input-matrix output-width output-height {:keys [^long k-width ^long k-height
                                                   ^long stride-w ^long stride-h
                                                   ^long num-channels]
                                            :as conv-layer-config}]
  (let [kernel-stride (* k-width num-channels)]
    (for [^long output-y (range output-height)
          ^long output-x (range output-width)]
      (m/as-vector (m/submatrix input-matrix [[(* output-y stride-h) k-height]
                                              [(* output-x stride-w num-channels) kernel-stride]])))))


(defn create-convolution-rows
  "Given an image flattened into an interleaved vector
create a sequence where each item is the input to the convolution filter row
meaning the convolution is just a dotproduct across the rows.
Should be output-width*output-height rows.  Padding is applied as zeros across channels."
  [interleaved-input-vector {:keys [^long width ^long height ^long k-width ^long k-height
                                    ^long padx ^long pady ^long stride-w ^long stride-h
                                    ^long num-channels] :as conv-layer-config }]
  (let [input-matrix (create-padded-input-matrix interleaved-input-vector
                                                 conv-layer-config)
        output-width (get-padded-strided-dimension width padx k-width stride-w)
        output-height (get-padded-strided-dimension height pady k-height stride-h)
        input-mat-stride (* (+ width (* 2 padx)) num-channels)
        kernel-stride (* k-width num-channels)]
    ;;I go ahead and create a contiguous matrix here because we will iterate over this many times
    (convolution-sequence input-matrix output-width output-height
                          conv-layer-config)))


(defn convolution-forward
  [this weights bias input-convolved-rows]
  (if (and (blas/supports-blas? weights)
           (blas/supports-blas? input-convolved-rows))
    (let [output (or (:output-mat this)
                     (b/new-array [(m/row-count input-convolved-rows)
                                   (m/row-count weights)]))]
      (m/assign! output bias)
      (blas/gemm! output false true 1.0
                  input-convolved-rows weights
                  1.0)
      (assoc this :output-mat output
             :output (m/as-vector output)))
    (let [weights-t (m/transpose weights)
          result (m/inner-product input-convolved-rows weights-t)]
      (doseq [row (m/rows result)]
        (m/add! row bias))
      (assoc this :output (m/as-vector result)))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Convolution backward pass utility functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-gradient-convolution-sequence
  "returns [conv-sequence input-mat-view] for the backpass steps of nn layers
using convolutional steps"
  [{:keys [^long width ^long height ^long k-width ^long k-height
           ^long padx ^long pady ^long stride-w ^long stride-h
           ^long num-channels] :as conv-layer-config}]
  (let [input-matrix (create-padded-matrix conv-layer-config)
        input-mat-view (m/submatrix input-matrix
                                    [[pady height]
                                     [(* padx num-channels) (* width num-channels)]])
        output-width (get-padded-strided-dimension width padx k-width stride-w)
        output-height (get-padded-strided-dimension height pady k-height stride-h)
        kernel-stride (* k-width num-channels)]
    [(convolution-sequence input-matrix output-width output-height
                           conv-layer-config)
     input-mat-view
     input-matrix]))

(defn convolution-copying-roll
  [packed-gradient gradient-view-rows gradient-view]
  (doall (map m/assign! gradient-view-rows packed-gradient))
  (m/array :vectorz (m/as-vector gradient-view)))

(defn convolution-backward!
  ([output-gradient input-conv-sequence weights
    weight-gradient bias-gradient input-gradient-conv-sequence
    {:keys [^long width ^long height
            ^long k-width ^long k-height
            ^long padx ^long pady
            ^long stride-w ^long stride-h
            ^long num-channels] :as conv-layer-config}]
   ;;We use an accumulator here because the input-gradient-conv-sequence
   ;;could be a set of view-on-views or some other deep abstraction.
   ;;Using an accumulator is a significant performance helper during the tight
   ;;per-kernel gradient accumulation
   (let [^long kernel-count (first (m/shape weights))
         output-row-count (quot (long (first (m/shape output-gradient))) kernel-count)
         accum (m/zero-array :vectorz [(* k-width k-height num-channels)])]
     (loop [idx 0]
       (when (< idx output-row-count)
         (let [input-gradient-row (m/get-row input-gradient-conv-sequence idx)
               input-row (m/get-row input-conv-sequence idx)]
           (m/assign! accum input-gradient-row)
           (loop [kern-idx 0]
             (when (< kern-idx kernel-count)
               (let [weight-row (m/get-row weights kern-idx)
                     weight-gradient-row (m/get-row weight-gradient kern-idx)
                     output-gradient-offset (+ (* idx kernel-count)
                                               kern-idx)
                     ^double gradient (m/mget output-gradient output-gradient-offset)
                     ^double bias-gradient-val (m/mget bias-gradient kern-idx)]
                 (m/add-scaled! accum weight-row gradient)
                 (m/add-scaled! weight-gradient-row input-row gradient)
                 (m/mset! bias-gradient kern-idx (+ gradient bias-gradient-val)))
               (recur (inc kern-idx))))
           (m/assign! input-gradient-row accum))
         (recur (inc idx))))))
  ;;Easier access for testing
  ([output-gradient input weights weight-gradient bias-gradient conv-layer-config]
   (let [input-conv-sequence (create-convolution-rows input conv-layer-config)
         [gradient-conv-sequence gradient-mat-view padded-backing-matrix]
         (get-gradient-convolution-sequence conv-layer-config)
         gradient-conv-sequence (into [] gradient-conv-sequence)]
     (convolution-backward! output-gradient input-conv-sequence weights weight-gradient
                            bias-gradient gradient-conv-sequence conv-layer-config)
     (m/as-vector gradient-mat-view))))



;; Explanation of the the conv-layer algorithm:
;;
;; Forward: Take the input which is expected to be a single interleaved vector of data
;; and create a matrix that contains a row for each convolution.  So for example if you have
;; 2x2 kernels and you have a 3x3 matrix of monotonically incrementing indexes we produce
;; a new matrix
;; input: [[1 2 3]
;;         [4 5 6]
;;         [7 8 9]]
;;
;;
;; convolved rows:
;;
;; [[1.0,2.0,4.0,5.0],
;;  [2.0,3.0,5.0,6.0],
;;  [4.0,5.0,7.0,8.0],
;;  [5.0,6.0,8.0,9.0]].
;;
;; You can see how each convolution is represented by a row in the matrix.
;;
;; Now we expect our weights and bias in the same format as what the linear layer
;; uses so each convolution kernel has a row of weights and a bias entry in the bias
;; vector.
;;
;; This means the convolution step is:
;; (m/matrix-multiply convolved-rows (m/transpose weights))
;; The result is an interleaved matrix that looks like:
;; [[cr1k1 cr1k2 cr1k3 cr1k4] (repeats for num convolved rows...)].
;; Note that simply calling as-vector means our output is in the exact same format
;; as our input where each convolution kernel output plane is interleaved.  It also means
;; we can use our linear layer with no changes directly after a convolution layer.
;;


#?(:cljs (register-module cortex.impl.layers.Convolutional))
(defrecord Convolutional [weights bias weight-gradient bias-gradient
                          conv-layer-config]
    cp/PModule
    (cp/calc [this input]
      ;;It turns out that creating a new matrix with padding and such is faster than
      ;;copying single channel resultsg
      (let [input-data (or (:input-data this)
                           (let [[conv-rows input-view padded-backing-matrix]
                                 (get-gradient-convolution-sequence conv-layer-config)]
                             [(into [] conv-rows) input-view padded-backing-matrix]))
            [conv-rows input-view padded-backing-matrix] input-data
            _ (sub-vector-assignment! (:padx conv-layer-config)
                                      (:pady conv-layer-config)
                                      (:width conv-layer-config)
                                      (:num-channels conv-layer-config)
                                      padded-backing-matrix
                                      input)
            packed-conv-matrix (if-let [conv-matrix (:packed-conv-matrix this)]
                                 (do (doall (map m/assign! conv-matrix conv-rows))
                                     conv-matrix)
                                 (m/array :vectorz (seq conv-rows)))]
        (convolution-forward
         (assoc this
                :input-data input-data
                :packed-conv-matrix packed-conv-matrix)
         weights bias packed-conv-matrix)))

    (cp/output [m]
      (:output m))

    cp/PNeuralTraining
    (forward [this input]
      (cp/calc this input))

    (backward [this input output-gradient]
      (let [packed-conv-matrix (:packed-conv-matrix this)
            [conv-rows input-view padded-backing-matrix] (:input-data this)
            input-gradient (or (:input-gradient this)
                               (m/array :vectorz (repeat (first (m/shape input)) 0.0)))]
        ;;Note that at this point packed-conv-matrix holds the input exploded into convolutions
        (m/mset! padded-backing-matrix 0.0)
        ;;the backward pass is optimized to only minimally access conv-rows.  This is because they
        ;;are views of views (m/as-vector (m/submatrix ...)) which implies a significant
        ;;performance hit at this time.
        (convolution-backward! output-gradient packed-conv-matrix weights weight-gradient
                               bias-gradient conv-rows conv-layer-config)
        (m/assign! input-gradient (m/as-vector input-view))
        (assoc this :input-gradient input-gradient)))

    (input-gradient [this]
      (:input-gradient this))

    cp/PParameters
    (parameters [this]
      (m/join (m/as-vector (:weights this)) (m/as-vector (:bias this))))

    (update-parameters [this parameters]
      (let [param-view (cp/parameters this)]
        (m/assign! param-view parameters))
      (let [gradient-view (cp/gradient this)]
        (m/assign! gradient-view 0.0))
      this)

    cp/PGradient
    (gradient [this]
      (m/join (m/as-vector (:weight-gradient this)) (m/as-vector (:bias-gradient this)))))


(defn max-pooling-forward!
  "The forward pass writes to two things; output and output-indexes.  The indexes
record which item from the input row we actually looked at.  Returns
[output output-indexes].  Note that this does not change the number of channels
so it needs to remember the max per-channel."
  [input output output-indexes
   {:keys [^long height ^long width
           ^long padx ^long pady
           ^long k-width ^long k-height
           ^long stride-w ^long stride-h
           ^long num-channels] :as conv-layer-config}]
  ;;Each input row has k-width*num-channels*k-height in it.
  ;;Each output index gets num-channels written to it.
  (let [conv-sequence (convolution-operation-sequence conv-layer-config)]
    (doseq [conv-item conv-sequence]
      (let [{:keys [^long input-x ^long input-y ^long conv-x ^long conv-y
                    ^long output-x ^long output-y]} conv-item
            output-width (get-padded-strided-dimension width padx k-width stride-w)
            output-offset (* num-channels (+ (* output-y output-width) output-x))]
        (loop [conv-x conv-x]
          (when (< conv-x k-width)
            (let [input-offset-x (+ input-x conv-x)
                  valid-input? (and (>= input-y 0)
                                    (< input-y height)
                                    (>= input-x 0)
                                    (< input-x width))
                  kernel-index (+ (* conv-y k-width) conv-x)
                  input-offset (* num-channels (+ (* input-y width) input-offset-x))]
              (loop [chan 0]
                (when (< chan num-channels)
                  (let [^double input-val (if valid-input?
                                            (m/mget input (+ input-offset chan))
                                            0.0)
                        output-offset (+ output-offset chan)
                        ^double existing-value (m/mget output output-offset)]
                    (when (or (= kernel-index 0)
                              (> input-val existing-value))
                      (m/mset! output output-offset input-val)
                      (m/mset! output-indexes output-offset kernel-index)))
                  (recur (inc chan)))))
            (recur (inc conv-x))))))
    [output output-indexes]))


(defn max-pooling-backward!
  "Calculates the input gradient using the inverse of the convolution step
combined with the output gradient and the output indexes which tell you which kernel
index the output came from."
  [output-gradient output-indexes input-gradient
   {:keys [^long width ^long height
           ^long k-width ^long k-height
           ^long padx ^long pady
           ^long stride-w ^long stride-h
           ^long num-channels] :as conv-layer-config}]
  (m/mset! input-gradient 0.0)
  (let [output-width (get-padded-strided-dimension width padx k-width stride-w)
        output-height (get-padded-strided-dimension height pady k-height stride-h)
        num-pixels (* output-width output-height)]
    (loop [pixel 0]
      (when (< pixel num-pixels)
        (let [output-offset (* pixel num-channels)
              output-x (rem pixel output-width)
              output-y (quot pixel output-width)]
          (loop [chan 0]
            (when (< chan num-channels)
              (let [output-offset (+ output-offset chan)
                    kernel-index (long (m/mget output-indexes output-offset))
                    conv-x (rem kernel-index k-width)
                    conv-y (quot kernel-index k-width)
                    input-x (- (+ (* output-x stride-w) conv-x) padx)
                    input-y (- (+ (* output-y stride-h) conv-y) pady)]
                (when (and (>= input-y 0)
                           (< input-y height)
                           (>= input-x 0)
                           (< input-x width))
                  (let [^double output-gradient-value (m/mget output-gradient output-offset)
                        input-offset (+ (* num-channels (+ (* input-y width)
                                                           input-x))
                                        chan)
                        ^double input-gradient-value (m/mget input-gradient input-offset)]
                    (m/mset! input-gradient input-offset (+ input-gradient-value
                                                            output-gradient-value)))))
              (recur (inc chan)))))
        (recur (inc pixel))))))


;;Max pooling layer.  There are other pooling layer types (average,a sochiastic)
;;that may be implemented later but for now we only need max pooling.
#?(:cljs (register-module cortex.impl.layers.Pooling))
(defrecord Pooling [output output-indexes input-gradient conv-layer-config]
  cp/PModule
  (cp/calc [this input]
    (max-pooling-forward! input output output-indexes conv-layer-config))

  (cp/output [m]
    (:output m))

  cp/PNeuralTraining
  (forward [this input]
    (cp/calc this input)
    this)

  (backward [this input output-gradient]
    (max-pooling-backward! output-gradient output-indexes
                           input-gradient conv-layer-config)
    this)

  (input-gradient [this]
    (:input-gradient this)))


#?(:clj
   (defrecord KSparse [^long k]
     cp/PModule
     (cp/calc [this input]
       (let [^PriorityQueue queue (or (:queue this)
                                      (PriorityQueue. k (reify java.util.Comparator
                                                          (compare ^int [this o1 o2]
                                                            (int (- (double (o1 0)) (double (o2 0))))))))
             output (or (:output this)
                        (m/new-array :vectorz (m/shape input)))
             dotvec (or (:dotvec this)
                        (m/new-array :vectorz (m/shape input)))]
         (m/assign! dotvec 0.0)
         (m/assign! output input)
         (.clear queue)
         (doall (m/emap-indexed! (fn [idx value]
                                   (.add queue [value (first idx)])
                                   (when (> (.size queue) k)
                                     (.remove queue (.peek queue)))
                                   value)
                                 output))
         (doseq [[value idx] (seq queue)]
           (m/mset! dotvec idx 1.0))
         (m/mul! output dotvec)
         (assoc this :queue queue
                :output output
                :dotvec dotvec)))


     (cp/output [m]
       (:output m))

     cp/PNeuralTraining
     (forward [this input]
       (cp/calc this input))

     (backward [this input output-gradient]
       (let [input-gradient (or (:input-gradient this)
                                (m/new-array :vectorz (m/shape output-gradient)))]
         (m/assign! input-gradient output-gradient)
         (m/mul! input-gradient (:dotvec this))
         (assoc this :input-gradient input-gradient)))

     (input-gradient [this]
       (:input-gradient this))))