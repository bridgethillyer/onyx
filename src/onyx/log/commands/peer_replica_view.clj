(ns onyx.log.commands.peer-replica-view
  (:require [clojure.set :refer [union difference map-invert]]
            [clojure.data :refer [diff]]
            [onyx.log.commands.common :as common]
            [onyx.extensions :as extensions]
            [taoensso.timbre :refer [info warn]]
            [onyx.static.planning :as planning]
            [onyx.static.default-vals :refer [defaults arg-or-default]]))

(defn peer-site [peer-replica-view peer-id]
  (get (:peer-sites @peer-replica-view) peer-id))

(defrecord PeerReplicaView [backpressure? pick-peer-fns acker-candidates peer-sites job-id task-id catalog task])

(defn build-pick-peer-fn [task-id task-map egress-peers slot-id->peer-id]
  (let [out-peers (egress-peers task-id)] 
    (cond (empty? out-peers)
          (fn [_] nil)
          (and (planning/grouping-task? task-map) (#{:continue :kill} (:onyx/flux-policy task-map)))
          (fn [hash-group] 
            (nth out-peers
                 (mod hash-group
                      (count out-peers))))
          (and (planning/grouping-task? task-map) (= :recover (:onyx/flux-policy task-map)))
          (let [n-peers (or (:onyx/n-peers task-map)
                            (:onyx/max-peers task-map))] 
            (fn [hash-group] 
              (slot-id->peer-id (mod hash-group n-peers))))
          (planning/grouping-task? task-map) 
          (throw (ex-info "Unhandled grouping-task flux-policy." task-map))
          :else
          (fn [_]
            (rand-nth out-peers)))))

(defmethod extensions/peer-replica-view :default 
  [log entry old-replica new-replica diff old-view state opts]
  (let [peer-id (:id state)
        messenger (:messenger state)
        allocations (:allocations new-replica)
        allocated-job (common/peer->allocated-job allocations peer-id)
        task-id (:task allocated-job)
        job-id (:job allocated-job)]
    (if job-id
      (let [task (if (= task-id (:task-id old-view)) 
                   (:task old-view)
                   (extensions/read-chunk log :task task-id))
            catalog (if (= job-id (:job-id old-view)) 
                      (:catalog old-view)
                      (extensions/read-chunk log :catalog job-id))
            {:keys [peer-state ackers]} new-replica
            receivable-peers (common/job-receivable-peers peer-state allocations job-id)
            backpressure? (common/backpressure? new-replica job-id)
            slot-ids (get-in new-replica [:task-slot-ids job-id])
            pick-peer-fns (->> (:egress-ids task)
                               (map (fn [[task-name task-id]]
                                      (let [task-map (planning/find-task catalog task-name)
                                            slot-id->peer-id (map-invert (get slot-ids task-id))] 
                                        (vector task-id 
                                                (build-pick-peer-fn task-id task-map receivable-peers slot-id->peer-id)))))
                               (into {}))
            job-ackers (get ackers job-id)
            ;; Really should only use peers that are on egress tasks, and input tasks
            ;; all other tasks are non receivable from this peer
            peer-sites-peers (into (reduce into #{} (vals receivable-peers)) 
                                  job-ackers)
            peer-sites (zipmap peer-sites-peers
                               (map (fn [id]
                                      (let [peer-site (-> new-replica :peer-sites (get id))] 
                                        (extensions/connection-spec messenger id nil peer-site)))
                                    peer-sites-peers))]
        (->PeerReplicaView backpressure? pick-peer-fns job-ackers peer-sites job-id task-id catalog task))
      (->PeerReplicaView nil nil nil nil nil nil nil nil))))
