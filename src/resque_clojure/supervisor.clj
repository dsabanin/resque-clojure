(ns resque-clojure.supervisor
  (:require [resque-clojure.worker :as worker]
            [resque-clojure.resque :as resque]
            [resque-clojure.util :as util]))

(def run-loop? (ref true))
(def working-agents (ref #{}))
(def idle-agents (ref #{}))
(def watched-queues (atom []))
(def dispatching-thread (atom nil))

(def config (atom {:max-shutdown-wait (* 10 1000) ;; milliseconds
                   :poll-interval (* 5 1000)
                   :max-workers 1}))

(declare release-worker reserve-worker make-agent listen-to listen-loop handle-exceptions dispatch-jobs stop-dispatching-thread! create-dispatching-thread! default-dispatching-error-handler)

(def ^{:private true} default-opts
  {:dispatcher-error-handler default-dispatching-error-handler
   :job-lookup-fn worker/lookup-fn})

(defn configure [c]
  (swap! config merge c))

(defn stop []
  "stops polling queues. waits for all workers to complete current job"
  (dosync (ref-set run-loop? false))
  (apply await-for (:max-shutdown-wait @config) @working-agents)
  (resque/unregister @watched-queues)
  (stop-dispatching-thread!))

(defn default-dispatching-error-handler [e]
  (util/print-exception e)
  (throw e))

(defn start
  ([queues] (start queues {}))
  ([queues opts]
     "start listening for jobs on queues (vector)."
     (let [dispatch-opts (merge default-opts opts)]
       (dotimes [n (:max-workers @config)] (make-agent))
       (listen-to queues)
       (dosync (ref-set run-loop? true))
       (create-dispatching-thread! queues dispatch-opts)
       (.addShutdownHook (Runtime/getRuntime)
                         (Thread. stop)))))

(defn create-dispatching-thread! [queues {:keys [dispatcher-error-handler job-lookup-fn]}]
  (let [thread (Thread. (handle-exceptions (partial listen-loop job-lookup-fn) dispatcher-error-handler)
                        (str "Dispatching from " (prn-str queues)))]
    (reset! dispatching-thread thread)
    (.start thread)))

(defn stop-dispatching-thread! []
  (when @dispatching-thread
    (.interrupt @dispatching-thread)
    (reset! dispatching-thread nil)))

(defn worker-complete [key ref old-state new-state]
  (release-worker ref)
  (dispatch-jobs)
  (if (= :error (:result new-state))
    (resque/report-error new-state)))

(defn dispatch-jobs [job-lookup-fn]
  (when-let [worker-agent (reserve-worker)]
    (if-let [msg (resque/dequeue @watched-queues)]
      (send-off worker-agent (partial worker/work-on job-lookup-fn) msg)
      (release-worker worker-agent))))

(defn handle-exceptions [f handler]
  (fn [& args]
    (try
      (apply f args)
      (catch Exception e
        (handler e)))))

(defn listen-loop [job-lookup-fn]
  (when @run-loop?
    (dispatch-jobs job-lookup-fn)
    (Thread/sleep (:poll-interval @config))
    (recur job-lookup-fn)))

(defn make-agent []
  (let [worker-agent (agent {} :error-handler (fn [a e] (throw e)))]
    (add-watch worker-agent 'worker-complete worker-complete)
    (dosync (commute idle-agents conj worker-agent))
    worker-agent))

(defn reserve-worker []
  "either returns an idle worker or nil.
   marks the returned worker as working."

  (dosync
   (let [selected (first @idle-agents)]
     (if selected
       (do
         (alter idle-agents disj selected)
         (alter working-agents conj selected)))
     selected)))

(defn release-worker [w]
  (dosync (alter working-agents disj w)
          (alter idle-agents conj w)))

(defn listen-to [queues]
  (resque/register queues)
  (swap! watched-queues into queues))
