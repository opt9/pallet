(ns pallet.core.group
  "Pallet Group functions for adjusting nodes

Provides the node-spec, service-spec and group-spec abstractions.

Provides the lift and converge operations.

Uses a TargetMap to describe a node with its group-spec info.

Uses :system-targets to track all nodes to be considered.
TODO: put :system-targets into plan-state?"
  (:require
   [clojure.core.async :as async :refer [<! <!! alts!! chan close! timeout]]
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> for> letfn> loop>
            inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.set :refer [union]]
   [clojure.string :as string :refer [blank?]]
   [clojure.tools.logging :as logging :refer [debugf tracef]]
   [pallet.async
    :refer [concat-chans from-chan go-logged go-tuple map-async map-thread
            reduce-results]]
   [pallet.compute :refer [create-nodes destroy-nodes nodes service-properties]]
   [pallet.contracts
    :refer [check-converge-options
            check-group-spec
            check-lift-options
            check-node-spec
            check-server-spec]]
   [pallet.core.api :as api :refer [errors plan-fn]]
   [pallet.core.async :refer [action-errors?]]
   [pallet.core.executor.ssh :as ssh]
   [pallet.core.middleware :as middleware]
   [pallet.core.phase :as phase :refer [phases-with-meta]]
   [pallet.core.plan-state :refer :all]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.session :as session
    :refer [admin-user add-system-targets plan-state recorder
            remove-system-targets]]
   [pallet.core.user :as user :refer [obfuscated-passwords]]
   [pallet.crate.os :refer [os]]
   [pallet.environment :refer [merge-environments]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.node :as node :refer [group-name node? node-map terminated?]]
   [pallet.thread-expr :refer [when->]]
   [pallet.utils
    :refer [combine-exceptions maybe-update-in total-order-merge]]))

(def-alias KeyAlgorithms (Map Keyword Keyword))
(ann ^:no-check pallet.map-merge/merge-keys
     [KeyAlgorithms (Map Any Any) * -> (Map Any Any)])

(ann merge-spec-algorithm KeyAlgorithms)
(def
  ^{:doc "Map from key to merge algorithm. Specifies how specs are merged."}
  merge-spec-algorithm
  {:phases :merge-phases
   :roles :union
   :group-names :union
   :default-phases :total-ordering})

;; TODO remove :no-check
(ann ^:no-check merge-specs
     [KeyAlgorithms GroupSpec GroupSpec -> GroupSpec])
(defn merge-specs
  "Merge specs using the specified algorithms."
  [algorithms a b]
  (merge-keys algorithms a b))

;;; #### Phase Extension

;;; ## Domain Model

;;; ### Node Spec
(def ^{:doc "Vector of keywords recognised by node-spec"
       :private true}
  node-spec-keys [:image :hardware :location :network])

(defn node-spec
  "Create a node-spec.

   Defines the compute image and hardware selector template.

   This is used to filter a cloud provider's image and hardware list to select
   an image and hardware for nodes created for this node-spec.

   :image     a map describing a predicate for matching an image:
              os-family os-name-matches os-version-matches
              os-description-matches os-64-bit
              image-version-matches image-name-matches
              image-description-matches image-id

   :location  a map describing a predicate for matching location:
              location-id
   :hardware  a map describing a predicate for matching hardware:
              min-cores min-ram smallest fastest biggest architecture
              hardware-id
   :network   a map for network connectivity options:
              inbound-ports
   :qos       a map for quality of service options:
              spot-price enable-monitoring"
  [& {:keys [image hardware location network qos] :as options}]
  {:pre [(or (nil? image) (map? image))]}
  (check-node-spec (vary-meta (or options {}) assoc :type ::node-spec)))


(def ^{:doc "Executes on non bootstrapped nodes, with image credentials."}
  unbootstrapped-meta
  {:middleware (-> api/execute
                   middleware/image-user-middleware
                   (middleware/execute-on-unflagged :bootstrapped))})

(def ^{:doc "Executes on bootstrapped nodes, with admin user credentials."}
  bootstrapped-meta
  {:middleware (-> api/execute
                   (middleware/execute-on-flagged :bootstrapped))})

(def ^{:doc "The bootstrap phase is executed with the image credentials, and
only not flagged with a :bootstrapped keyword."}
  default-phase-meta
  {:bootstrap {:middleware (->
                            api/execute
                            middleware/image-user-middleware
                            (middleware/execute-one-shot-flag :bootstrapped))}})

;;; ### Server Spec

(defn extend-specs
  "Merge in the inherited specs"
  ([spec inherits algorithms]
     (if inherits
       (merge-specs
        algorithms
        (if (map? inherits)
          inherits
          (reduce #(merge-specs algorithms %1 %2) {} inherits))
        spec)
       spec))
  ([spec inherits]
     (extend-specs spec inherits merge-spec-algorithm)))

;;; #### Server-spec

(defn server-spec
  "Create a server-spec.

   - :phases         a hash-map used to define phases. Phases are inherited by
                     anything that :extends the server-spec.
                     Standard phases are:
     - :bootstrap    run on first boot of a new node
     - :configure    defines the configuration of the node
   - :default-phases a sequence specifying the default phases
   - :phases-meta    metadata to add to the phases
   - :extends        takes a server-spec, or sequence thereof, and is used to
                     inherit phases, etc.
   - :roles          defines a sequence of roles for the server-spec. Inherited
                     by anything that :extends the server-spec.
   - :node-spec      default node-spec for this server-spec
   - :packager       override the choice of packager to use

For a given phase, inherited phase functions are run first, in the order
specified in the `:extends` argument."
  [& {:keys [phases phases-meta default-phases packager node-spec extends roles]
      :as options}]
  (check-server-spec
   (->
    (or node-spec {})                    ; ensure we have a map and not nil
    (merge options)
    (when-> roles
            (update-in [:roles] #(if (keyword? %) #{%} (into #{} %))))
    (extend-specs extends)
    (maybe-update-in [:phases] phases-with-meta phases-meta default-phase-meta)
    (update-in [:default-phases] #(or default-phases % [:configure]))
    (dissoc :extends :node-spec :phases-meta)
    (vary-meta assoc :type ::server-spec))))

;;; ### Group-spec

(defn group-spec
  "Create a group-spec.

   `name` is used for the group name, which is set on each node and links a node
   to its node-spec

   - :extends        specify a server-spec, a group-spec, or sequence thereof
                     and is used to inherit phases, etc.

   - :phases         used to define phases. Standard phases are:
   - :phases-meta    metadata to add to the phases
   - :default-phases a sequence specifying the default phases
   - :bootstrap      run on first boot of a new node
   - :configure      defines the configuration of the node.

   - :count          specify the target number of nodes for this node-spec
   - :packager       override the choice of packager to use
   - :node-spec      default node-spec for this group-spec
   - :node-filter    a predicate that tests if a node is a member of this
                     group."
  ;; Note that the node-filter is not set here for the default group-name based
  ;; membership, so that it does not need to be updated by functions that modify
  ;; a group's group-name.
  [name
   & {:keys [extends count image phases phases-meta default-phases packager
             node-spec roles node-filter]
      :as options}]
  {:pre [(or (nil? image) (map? image))]}
  (let [group-name (keyword (clojure.core/name name))]
    (check-group-spec
     (->
      (or node-spec {})
      (merge options)
      (when-> roles
              (update-in [:roles] #(if (keyword? %) #{%} (into #{} %))))
      (extend-specs extends)
      (maybe-update-in [:phases] phases-with-meta phases-meta default-phase-meta)
      (update-in [:default-phases] #(or default-phases % [:configure]))
      (dissoc :extends :node-spec :phases-meta)
      (assoc :group-name group-name)
      (vary-meta assoc :type ::group-spec)))))

(defn expand-cluster-groups
  "Expand a node-set into its groups"
  [node-set]
  (cond
   (sequential? node-set) (mapcat expand-cluster-groups node-set)
   (map? node-set) (if-let [groups (:groups node-set)]
                     (mapcat expand-cluster-groups groups)
                     [node-set])
   :else [node-set]))

(defn expand-group-spec-with-counts
  "Expand a converge node spec into its groups"
  ([node-set spec-count]
     (letfn [(*1 [x y] (* (or x 1) y))
             (scale-spec [spec factor]
               (update-in spec [:count] *1 factor))
             (set-spec [node-spec]
               (mapcat
                (fn [[node-spec spec-count]]
                  (if-let [groups (:groups node-spec)]
                    (expand-group-spec-with-counts groups spec-count)
                    [(assoc node-spec :count spec-count)]))
                node-set))]
       (cond
        (sequential? node-set) (mapcat
                                #(expand-group-spec-with-counts % spec-count)
                                node-set)
        (map? node-set) (if-let [groups (:groups node-set)]
                          (let [spec (scale-spec node-set spec-count)]
                            (mapcat
                             #(expand-group-spec-with-counts % (:count spec))
                             groups))
                          (if (:group-name node-set)
                            [(scale-spec node-set spec-count)]
                            (set-spec node-spec)))
        :else [(scale-spec node-set spec-count)])))
  ([node-set] (expand-group-spec-with-counts node-set 1)))

(defn cluster-spec
  "Create a cluster-spec.

   `name` is used as a prefix for all groups in the cluster.

   - :groups    specify a sequence of groups that define the cluster

   - :extends   specify a server-spec, a group-spec, or sequence thereof
                for all groups in the cluster

   - :phases    define phases on all groups.

   - :node-spec default node-spec for the nodes in the cluster

   - :roles     roles for all group-specs in the cluster"
  [cluster-name
   & {:keys [extends groups phases node-spec roles] :as options}]
  (let [cluster-name (name cluster-name)
        group-prefix (if (blank? cluster-name) "" (str cluster-name "-"))]
    (->
     options
     (update-in [:groups]
                (fn [group-specs]
                  (map
                   (fn [group-spec]
                     (->
                      node-spec
                      (merge (dissoc group-spec :phases))
                      (update-in
                       [:group-name]
                       #(keyword (str group-prefix (name %))))
                      (update-in [:roles] union roles)
                      (extend-specs extends)
                      (extend-specs [{:phases phases}])
                      (extend-specs [(select-keys group-spec [:phases])])))
                   (expand-group-spec-with-counts group-specs 1))))
     (dissoc :extends :node-spec)
     (assoc :cluster-name (keyword cluster-name))
     (vary-meta assoc :type ::cluster-spec))))


(ann node-has-group-name? [GroupName -> [Node -> boolean]])
(defn node-has-group-name?
  "Returns a predicate to check if a node has the specified group name."
  {:internal true}
  [group-name]
  (fn> has-group-name? [node :- Node]
    (when-let [node-group (node/group-name node)]
      (= group-name node-group))))

(ann node-in-group? [Node GroupSpec -> boolean])
(defn node-in-group?
  "Check if a node satisfies a group's node-filter."
  {:internal true}
  [node group]
  {:pre [(check-group-spec group)]}
  ((:node-filter group (node-has-group-name? (:group-name group)))
   node))

;; TODO remove :no-check
(ann ^:no-check node->node-map [(Nilable (NonEmptySeqable GroupSpec))
                                -> [Node -> IncompleteGroupTargetMap]])
(defn node->node-map
  "Build a map entry from a node and a list of groups."
  {:internal true}
  [groups]
  (fn> [node :- Node]
    (when-let [groups (seq (->>
                            groups
                            (filter (fn> [group :- GroupSpec]
                                      (check-group-spec group)
                                      (node-in-group? node group)))
                            (map (fn> [group :- GroupSpec]
                                   (check-group-spec group)
                                   (assoc-in
                                    group [:group-names]
                                    (set [(:group-name group)]))))))]
      (let [group
            (reduce
             (fn> [target :- GroupSpec group :- GroupSpec]
               (merge-specs merge-spec-algorithm target group))
             groups)]
        (assoc group :node node)))))

(ann service-state [ComputeService (Nilable (NonEmptySeqable GroupSpec))
                    -> IncompleteGroupTargetMapSeq])
(defn service-state
  "Query the available nodes in a `compute-service`, filtering for nodes in the
  specified `groups`. Returns a sequence that contains a node-map for each
  matching node."
  [compute-service groups]
  {:pre [(every? #(check-group-spec %) groups)]}
  (let [nodes (remove terminated? (nodes compute-service))
        _ (tracef "service-state %s" (vec nodes))
        targets (seq (remove nil? (map (node->node-map groups) nodes)))]
    targets))

(ann ^:no-check service-groups [ComputeService -> (Seqable GroupSpec)])
(defn service-groups
  "Query the available nodes in a `compute-service`, returning a group-spec
  for each group found."
  [compute-service]
  (->> (nodes compute-service)
       (remove terminated?)
       (map group-name)
       (map (fn> [gn :- GroupName] {:group-name gn}))
       (map (fn> [m :- (HMap :mandatory {:group-name GroupName})]
              ((inst vary-meta
                     (HMap :mandatory {:group-name GroupName})
                     Keyword Keyword)
               m assoc :type :pallet.api/group-spec)))))



(def ^{:doc "node-specific environment keys"}
  node-keys [:image :phases])

(defn group-with-environment
  "Add the environment to a group."
  [environment group]
  (merge-environments
   (maybe-update-in (select-keys environment node-keys)
                    [:phases] phases-with-meta {} default-phase-meta)
   group
   (maybe-update-in (get-in environment [:groups group])
                    [:phases] phases-with-meta {} default-phase-meta)))


(ann ^:no-check execute
     [BaseSession TargetMap PlanFn -> (ReadOnlyPort PhaseResult)])
(defn execute
  "Execute a plan function on a target.

  Ensures that the session target is set, and that the script
  environment is set up for the target.

  Returns a channel, which will yield a result for plan-fn, a map
  with `:target`, `:return-value` and `:action-results` keys."
  [session target plan-fn]
  (go-logged
   ;; (let [r (in-memory-recorder)     ; a recorder for just this plan-fn
   ;;       session (-> session
   ;;                   (set-target target)
   ;;                   (set-recorder (juxt-recorder [r (recorder session)])))]
   ;;   (with-script-for-node target (plan-state session)
   ;;     (let [rv (plan-fn session)]
   ;;       {:action-results (results r)
   ;;        :return-value rv
   ;;        :target target})))
   ))

;;; ## Calculation of node count adjustments
(def-alias NodeDeltaMap (HMap :mandatory {:actual AnyInteger
                                          :target AnyInteger
                                          :delta AnyInteger}))
(def-alias GroupDelta '[GroupSpec NodeDeltaMap])
(def-alias GroupDeltaSeq (Seqable GroupDelta))

(ann group-delta [TargetMapSeq GroupSpec -> NodeDeltaMap])
(defn group-delta
  "Calculate actual and required counts for a group"
  [targets group]
  (let [existing-count (count targets)
        target-count (:count group ::not-specified)]
    (when (= target-count ::not-specified)
      (throw
       (ex-info
        (format "Node :count not specified for group: %s" group)
        {:reason :target-count-not-specified
         :group group}) (:group-name group)))
    {:actual existing-count
     :target target-count
     :delta (- target-count existing-count)}))

(ann group-deltas [TargetMapSeq (Nilable (Seqable GroupSpec)) -> GroupDeltaSeq])
(defn group-deltas
  "Calculate actual and required counts for a sequence of groups. Returns a map
  from group to a map with :actual and :target counts."
  [targets groups]
  ((inst map GroupDelta GroupSpec)
   (juxt
    (inst identity GroupSpec)
    (fn> [group :- GroupSpec]
      (group-delta (filter
                    (fn> [t :- TargetMap]
                      (node-in-group? (get t :node) group))
                    targets)
                   group)))
   groups))

;; TODO remove no-check when core.typed can handle first, second on Vector*
(ann ^:no-check groups-to-create [GroupDeltaSeq -> (Seq GroupSpec)])
(defn groups-to-create
  "Return a sequence of groups that currently have no nodes, but will have nodes
  added."
  [group-deltas]
  (letfn> [new-group? :- [NodeDeltaMap -> boolean]
           ;; TODO revert to destructuring when supported by core.typed
           (new-group? [delta]
            (and (zero? (get delta :actual)) (pos? (get delta :target))))]
    (->>
     group-deltas
     (filter (fn> [delta :- GroupDelta]
                  (new-group? ((inst second NodeDeltaMap) delta))))
     ((inst map GroupSpec GroupDelta) (inst first GroupSpec))
     ((inst map GroupSpec GroupSpec)
      (fn> [group-spec :- GroupSpec]
           (assoc group-spec :target-type :group))))))

;; TODO remove no-check when core.typed can handle first, second on Vector*
(ann ^:no-check groups-to-remove [GroupDeltaSeq -> (Seq GroupSpec)])
(defn groups-to-remove
  "Return a sequence of groups that have nodes, but will have all nodes
  removed."
  [group-deltas]
  (letfn> [remove-group? :- [NodeDeltaMap -> boolean]
           ;; TODO revert to destructuring when supported by core.typed
           (remove-group? [delta]
            (and (zero? (get delta :target)) (pos? (get delta :actual))))]
    (->>
     group-deltas
     (filter (fn> [delta :- GroupDelta]
                  (remove-group? ((inst second NodeDeltaMap) delta))))
     ((inst map GroupSpec GroupDelta)
      (fn> [group-delta :- GroupDelta]
           (assoc (first group-delta) :target-type :group))))))

;; TODO remove no-check when core.typed can handle first, second on Vector*
(def-alias GroupNodesForRemoval
  (HMap :mandatory
        {:targets (NonEmptySeqable TargetMap)
         :all boolean}))

(ann ^:no-check nodes-to-remove
     [TargetMapSeq GroupDeltaSeq
      -> (Seqable '[GroupSpec GroupNodesForRemoval])])
(defn nodes-to-remove
  "Finds the specified number of nodes to be removed from the given groups.
  Nodes are selected at random. Returns a map from group to a map with
  :servers and :all, where :servers is a sequence of severs to remove, and :all
  is a boolean that is true if all nodes are being removed."
  [targets group-deltas]
  (letfn> [pick-servers :- [GroupDelta
                            -> '[GroupSpec
                                 (HMap :mandatory
                                       {:targets (NonEmptySeqable TargetMap)
                                        :all boolean})]]
           ;; TODO revert to destructuring when supported by core.typed
           (pick-servers [group-delta]
             (let
                 [group ((inst first GroupSpec) group-delta)
                  dm ((inst second NodeDeltaMap) group-delta)
                  target (get dm :target)
                  delta (get dm :delta)]
               (vector
                group
                {:targets (take (- delta)
                              (filter
                               (fn> [target :- TargetMap]
                                    (node-in-group? (get target :node) group))
                               targets))
                 :all (zero? target)})))]
    (into {}
          (->>
           group-deltas
           (filter (fn> [group-delta :- GroupDelta]
                        (when (neg? (:delta (second group-delta)))
                          group-delta)))
           (map pick-servers)))))

;; TODO remove no-check when core.typed can handle first, second on Vector*
(ann ^:no-check nodes-to-add
     [GroupDeltaSeq -> (Seqable '[GroupSpec AnyInteger])])
(defn nodes-to-add
  "Finds the specified number of nodes to be added to the given groups.
  Returns a map from group to a count of servers to add"
  [group-deltas]
  (->>
   group-deltas
   (filter (fn> [group-delta :- GroupDelta]
                (when (pos? (get (second group-delta) :delta))
                  [(first group-delta)
                   (get (second group-delta) :delta)])))))

;;; ## Node creation and removal
;; TODO remove :no-check when core.type understands assoc

(ann ^:no-check add-group-nodes
     [Session ComputeService GroupSpec AnyInteger -> (Seq TargetMap)])
(defn add-group-nodes
  "Create `count` nodes for a `group`."
  [session compute-service group count ch]
  (debugf "add-group-nodes %s %s" group count)
  (go-tuple ch
    (debugf "add-group-nodes in go-tuple")
    (debugf "add-group-nodes %s" (admin-user session))
    (let [c (chan)
          _ (create-nodes
             compute-service
             group
             (admin-user session)
             count
             {:node-name (name (:group-name group))}
             c)
          _ (debugf "add-group-nodes dispatched")
          [targets e] (<! c)
          _ (debugf "add-group-nodes %s %s" targets e)
          targets ((inst map TargetMap Node)
                   (fn> [node :- Node] (assoc group :node node))
                   targets)]
      ;; (add-system-targets session targets)
      (debugf "add-group-nodes %s %s" targets e)
      [targets e])))

(ann remove-nodes [Session ComputeService GroupSpec GroupNodesForRemoval
                   -> nil])
(defn remove-nodes
  "Removes `nodes` from `group`. If `all` is true, then all nodes for the group
  are being removed."
  [session compute-service group removals ch]
  (go-tuple ch
   (let [all (:all removals)
         targets (:targets removals)]
     (debugf "remove-nodes all %s targets %s" all (vector targets))
     ;; (remove-system-targets session targets)
     (let [c (chan)]
       ;; if all
       ;;  (destroy-nodes-in-group compute-service (:group-name group))
       (destroy-nodes compute-service (map :node targets) c)
       (<! c)))))

(ann create-group-nodes
  [Session ComputeService (Seqable '[GroupSpec NodeDeltaMap]) ->
   (Seqable (ReadOnlyPort '[(Nilable (Seqable TargetMap))
                            (Nilable (ErrorMap '[GroupSpec NodeDeltaMap]))]))])
(defn create-group-nodes
  "Create nodes for groups."
  [session compute-service group-counts ch]
  (debugf "create-group-nodes %s %s" compute-service (vec group-counts))
  (go-tuple ch
    ;; We use a buffered channel so as not blocked when we read the
    ;; channel returned by add-group-nodes
    (let [c (chan 1)
          cs (for [[group-spec delta-map] group-counts]
               (add-group-nodes
                session compute-service group-spec (:delta delta-map) c))]
      (doseq [csc cs]
        (<! csc))              ; sync on completion of add-group-nodes
      (close! c)
      (let [[targets e] (reduce-results c)]
        [(apply concat targets) e]))))

(ann remove-group-nodes
  [Session ComputeService (Seqable '[GroupSpec GroupNodesForRemoval]) -> Any])
(defn remove-group-nodes
  "Removes nodes from groups. `group-nodes` is a map from group to a sequence of
  nodes.  The result is written to the channel, ch."
  [session compute-service group-nodes ch]
  (debugf "remove-group-nodes %s" group-nodes)
  (go-tuple ch
    (let [c (chan)]
      (doseq [[g n] group-nodes]
        (remove-nodes session compute-service g n c))
      (loop [n (count group-nodes)
             res []
             exceptions []]
        (if-let [[r e] (and (pos? n) (<! c))]
          (recur (dec n) (conj res r) (if e (conj exceptions e) exceptions))
          [res (combine-exceptions exceptions)])))))

(ann default-phases [TargetMapSeq -> (Seqable Keword)])
(defn default-phases
  "Return a sequence with the default phases for `targets`."
  [targets]
  (->> targets
       (map :default-phases)
       distinct
       (apply total-order-merge)))

;; ;;; # Execution helpers
;; (ann execute-plan-fns [BaseSession TargetMapSeq (Seqable PlanFn)
;;                        -> (Seqable (ReadOnlyPort PlanResult))])
;; (defn execute-plan-fns
;;   "Apply plan functions to targets.  Returns a sequence of channels that
;;   will yield phase result maps."
;;   [session targets plan-fns]
;;   (for> :- (ReadOnlyPort PlanResult)
;;         [target :- TargetMap targets
;;          plan :- PlanFn plan-fns]
;;     (phase/execute-phase session target plan)))


;; (ann execute-plan-fns [BaseSession TargetMapSeq (Seqable Phase)
;;                        -> (Seqable (ReadOnlyPort PlanResult))])
;; (defn execute-phases
;;   "Execute the specified `phases` on `targets`."
;;   [session targets phases]
;;   (go-logged
;;    (loop> [phases :- Phase phases]
;;      (if-let [p (first phases)]
;;        (if (action-errors? (phase/execute-phase session targets p))
;;          (results (recorder session))
;;          (recur (rest phases)))
;;        (results (recorder session))))))


(defn execute-target-phase
  "Using the session, execute phase on target."
  [session phase {:keys [node phases target-type] :as target}]
  {:pre [(or node (= target-type :group))]}
  (phase/execute-phase session node phases phase))

(defn lift-phase
  "Execute phase on all targets, returning a sequence of channels."
  [session phase targets]
  (map-thread #(execute-target-phase session phase %) targets))

(defn lift-phase-with-middleware
  "Execute phase on all targets, using phase middleware.  Return a channel
  with the results."
  [session phase targets]
  (let [mw-targets (group-by (comp :phase-middleware meta) targets)
        c (chan)]
    (->
     (apply concat
            (for [[mw targets] mw-targets]
              (if mw
                (mw session phase targets)
                (lift-phase session phase targets))))
     doall
     (concat-chans c))
    c))


(defn async-result->action-result
  "Convert an async result tuple to an action result map."
  [[r e]]
  (if r
    r
    {:error e}))

(defn execute-phase
  [session phase targets ch]
  {:pre [(every? (some-fn :node #(= :group (:target-type %))) targets)]}
  (go-tuple ch
    (let [c (lift-phase-with-middleware session phase targets)
          [results exception] (reduce-results c)
          phase-name (phase/phase-kw phase)
          res (->> results (mapv #(assoc % :phase phase-name)))]
      [res exception])))

(defn async-lift-op
  "Execute phases on targets.  Returns a channel containing a tuple of
 a sequence of the results and a sequence of any exceptions thrown.

## Options

`:phase-execution-f`
: specifies the function used to execute a phase on the targets.  Defaults
  to `pallet.core.primitives/execute-phase`.

`:post-phase-f`
: specifies an optional function that is run after a phase is applied.  It is
  passed `targets`, `phase` and `results` arguments, and is called before any
  error checking is done.  The return value is ignored, so this is for side
  affect only.

`:execution-settings-f`
: specifies a function that will be called with a node argument, and which
  should return a map with `:user`, `:executor` and `:executor-status-fn` keys."
  [session phases targets {:keys [execution-settings-f phase-execution-f
                                  post-phase-f]
                           :or {targets service-state
                                ;; TODO
                                ;; phase-execution-f #'primitives/execute-phase
                                ;; execution-settings-f (api/environment-execution-settings)
                                }}
   ch]
  (logging/debugf "async-lift-op :phases %s :targets %s"
                  (vec phases) (vec (map :group-name targets)))
  ;; TODO support post-phase, partitioning middleware, etc
  (go-tuple ch
   (loop [phases phases
          res []]
     (if-let [phase (first phases)]
       (let [c (chan)
             _ (execute-phase session phase targets c)
             [results exception] (<! c)
             res (concat res results)]
         (if (or (some #(some :error (:action-results %)) results)
                 exception)
           [res exception]
           (recur (rest phases) res)))
       [res nil]))))

(defn lift-op
  "Execute phases on targets.  Returns a sequence of results.

## Options

`:targets`
: used to restrict the nodes on which the phases are run to a subset of
  `service-state`.  Defaults to `service-state`.

`:phase-execution-f`
: specifies the function used to execute a phase on the targets.  Defaults
  to `pallet.core.primitives/execute-phase`.

`:post-phase-f`
: specifies an optional function that is run after a phase is applied.  It is
  passed `targets`, `phase` and `results` arguments, and is called before any
  error checking is done.  The return value is ignored, so this is for side
  affect only.

`:post-phase-fsm`
: specifies an optional fsm returning function that is run after a phase is
  applied.  It is passed `targets`, `phase` and `results` arguments, and is
  called before any error checking is done.  The return value is ignored, so
  this is for side affect only.

`:execution-settings-f`
: specifies a function that will be called with a node argument, and which
  should return a map with `:user`, `:executor` and `:executor-status-fn` keys."
  [session phases targets {:keys [execution-settings-f phase-execution-f
                                  post-phase-f]
                           :or {targets service-state
                                ;; TODO
                                ;; phase-execution-f #'primitives/execute-phase
                                ;; execution-settings-f (api/environment-execution-settings)
                                }
                           :as options}]
  (logging/debugf "lift-op :phases %s :targets %s"
                  (vec phases) (vec (map :group-name targets)))
  (let [c (chan)
        _ (async-lift-op session phases targets options c)
        [results exceptions] (<!! c)]
    (when (seq exceptions)
      (throw (ex-info "Exception in phase"
                      {:results results
                       :execptions exceptions}
                      (first exceptions))))
    results))



;;; TODO add converge and lift here

;;; ## Execution modifiers
(defn apply-target
  "Apply a plan-fn to a target.

  Examines the metadata on plan-fn to see if it is has a
  :phase-execution-f, in which case it is called.  Otherwise it calls
  `execute`."
  [session target plan-fn]
  (let [{:keys [phase-execution-f]} (meta plan-fn)]
    ((or phase-execution-f execute) session target plan-fn)))

(defn guard-execute
  [session target plan-fn]
  (let [{:keys [guard-fn]} (meta plan-fn)]
    (if (or (nil? guard-fn) (guard-fn session target))
      (execute session target plan-fn))))

;; (defn execute-phase
;;   "Execute the specified phase on target.
;;   Return a channel for the result."
;;   [target phase]
;;   (execute target (target-phase-fn target phase)))


;;; Node count adjuster
(defn node-count-adjuster
  "Adjusts node counts. Groups are expected to have node counts on them.
Return a map.  The :new-targets key will contain a sequence of new
targets; the :old-node-ids a sequence of removed node-ids,
and :results a sequence of phase results from
the :destroy-server, :destroy-group, and :create-group phases."
  [session compute-service groups targets ch]
  {:pre [compute-service
         (every? :count groups)
         (every? (some-fn :node :group) targets)]}
  (go-tuple ch
    (let [group-deltas (group-deltas targets groups)
          nodes-to-remove (nodes-to-remove targets group-deltas)
          nodes-to-add (nodes-to-add group-deltas)
          fail (fn fail [msg errs]
                 (ex-info msg {:errors errs}))
          add-results (fn add-results [result results]
                        (update-in result [:results] concat results))
          c (chan)
          _ (execute-phase
             session :destroy-server
             (mapcat :targets (vals nodes-to-remove)) c)
          [res-ds es] (<! c)
          result {:results res-ds}]

      (if es
        [result es]
        (do
          (if-let [errs (errors res-ds)]
            [result (fail "destroy-server phase failed" errs)]
            (do
              (remove-group-nodes session compute-service nodes-to-remove c)
              (let [[res-rg es] (<! c)
                    result (assoc result :old-node-ids res-rg)]
                (if es
                  [result es]
                  (do
                    (execute-phase
                     session :destroy-group (groups-to-remove group-deltas) c)
                    (let [[res-dg es] (<! c)
                          result (add-results result res-dg)]
                      (if es
                        [result es]
                        (if-let [errs (errors res-dg)]
                          [result (fail "destroy-group phase failed" errs)]
                          (do
                            (execute-phase
                             session :create-group
                             (groups-to-create group-deltas) c)
                            (let [[res-cg es] (<! c)
                                  result (add-results result res-cg)]
                              (if es
                                [result es]
                                (if-let [errs (errors res-cg)]
                                  [result
                                   (fail "create-group phase failed" errs)]
                                  (do
                                    (create-group-nodes
                                     session compute-service nodes-to-add c)
                                    (let [[res-cn es] (<! c)
                                          result (assoc result :new-targets res-cn)]
                                      (if es
                                        [result es]
                                        [result]))))))))))))))))))))


;;; ## Operations
;;;

(defn- process-phases
  "Process phases. Returns a phase list and a phase-map. Functions specified in
  `phases` are identified with a keyword and a map from keyword to function.
  The return vector contains a sequence of phase keywords and the map
  identifying the anonymous phases."
  [phases]
  (let [phases (if (or (keyword? phases) (fn? phases)) [phases] phases)]
    (reduce
     (fn [[phase-kws phase-map] phase]
       (if (or (keyword? phase)
               (and (or (vector? phase) (seq? phase)) (keyword? (first phase))))
         [(conj phase-kws phase) phase-map]
         (let [phase-kw (-> (gensym "phase")
                            name keyword)]
           [(conj phase-kws phase-kw)
            (assoc phase-map phase-kw phase)])))
     [[] {}] phases)))

(defn- groups-with-phases
  "Adds the phases from phase-map into each group in the sequence `groups`."
  [groups phase-map]
  (letfn [(add-phases [group]
            (update-in group [:phases] merge phase-map))]
    (map add-phases groups)))

(defn expand-cluster-groups
  "Expand a node-set into its groups"
  [node-set]
  (cond
   (sequential? node-set) (mapcat expand-cluster-groups node-set)
   (map? node-set) (if-let [groups (:groups node-set)]
                     (mapcat expand-cluster-groups groups)
                     [node-set])
   :else [node-set]))

(defn split-groups-and-targets
  "Split a node-set into groups and targets. Returns a map with
:groups and :targets keys"
  [node-set]
  (logging/tracef "split-groups-and-targets %s" (vec node-set))
  (->
   (group-by
    #(if (and (map? %)
              (every? map? (keys %))
              (every?
               (fn node-or-nodes? [x] (or (node? x) (sequential? x)))
               (vals %)))
       :targets
       :groups)
    node-set)
   (update-in
    [:targets]
    #(mapcat
      (fn [m]
        (reduce
         (fn [result [group nodes]]
           (if (sequential? nodes)
             (concat result (map (partial assoc group :node) nodes))
             (conj result (assoc group :node nodes))))
         []
         m))
      %))))

(defn all-group-nodes
  "Returns the service state for the specified groups"
  [compute groups all-node-set]
  (service-state
   compute
   (concat groups (map
                   (fn [g] (update-in g [:phases] select-keys [:settings]))
                   all-node-set))))

(def ^{:doc "Arguments that are forwarded to be part of the environment"}
  environment-args [:compute :blobstore :provider-options])

(defn group-node-maps
  "Return the nodes for the specified groups."
  [compute groups & {:keys [async timeout-ms timeout-val] :as options}]
  (all-group-nodes compute groups nil) options)

(defn exec-operation
  [chan {:keys [async operation status-chan close-status-chan?
                timeout-ms timeout-val]}]
  (cond
   async chan
   timeout-ms (let [[[res e] _] (alts!! [chan (timeout timeout-ms)])]
                (when e (throw e))
                res)
   :else (let [[res e] (<!! chan)]
           (when e (throw e))
           res)))


(def ^{:doc "A sequence of keywords, listing the lift-options"}
  lift-options
  [:targets :phase-execution-f :execution-settings-f
   :post-phase-f :post-phase-fsm :partition-f])

(defn converge-op
  "Converge the `groups`, using the specified service-state to provide the
existing nodes.  The `:bootstrap` phase is run on new nodes.  When tagging is
supported the `:bootstrap` phase is run on those nodes without a :bootstrapped
flag.

## Options

`:targets`
: used to restrict the nodes on which the phases are run to a subset of
  `service-state`.  Defaults to `service-state`."
  [session compute groups service-state phases
   {:keys [targets execution-settings-f]
    :or {targets service-state
         ;; execution-settings-f (api/environment-execution-settings)
         }
    :as options}
   ch]
  (logging/debugf
   "converge :phase %s :groups %s :settings-groups %s"
   (vec phases)
   (vec (map :group-name groups))
   (vec (map :group-name targets)))
  (go-tuple ch
    (let [c (chan)]
      (node-count-adjuster session compute groups targets c)
      (<! c))))


;; (defn ^:internal partition-targets
;;   "Partition targets using the, possibly nil, default partitioning function f.

;; There are three sources of partitioning applied.  The default passed to the
;; function, a partioning based on the partitioning and post-phase functions in
;; the target's metadata, and the target's partitioning function from the metadata.

;; The partitioning by metadata is applied so that post-phase functions are applied
;; to the correct targets in lift."
;;   [targets phase f]
;;   (let [fns (comp
;;              (juxt :partition-f :post-phase-f :post-phase-fsm)
;;              meta #(phase/target-phase % phase))]
;;     (->>
;;      targets
;;      (clojure.core/partition-by fns)
;;      (mapcat
;;       #(let [[pf & _] (fns (first %))]
;;          (if pf
;;            (pf %)
;;            [%]))))))

;; (defn lift-partitions
;;   "Lift targets by phase, applying partitions for each phase.

;; To apply phases at finer than a group granularity (so for example, a
;; `:post-phase-f` function is applied to nodes rather than a whole group), we can
;; use partitioning.

;; The partitioning function takes a sequence of targets, and returns a sequence of
;; sequences of targets.  The function can filter targets as required.

;; For example, this can be used to implement a rolling restart, or a blue/green
;; deploy.

;; ## Options

;; Options are as for `lift`, with the addition of:

;; `:partition-f`
;; : a function that takes a sequence of targets, and returns a sequence of
;;   sequences of targets.  Used to partition or filter the targets.  Defaults to
;;   any :partition metadata on the phase, or no partitioning otherwise.

;; Other options as taken by `lift`."
;;   [operation service-state plan-state environment phases
;;    {:keys [targets partition-f]
;;     :or {targets service-state}
;;     :as options}]
;;   {:pre [(:user environment)]}
;;   (logging/debugf
;;    "lift-partitions :phases %s :targets %s"
;;    (vec phases) (vec (map :group-name targets)))
;;   (let [[outer-results plan-state]
;;         (reduce
;;          (fn phase-reducer [[acc-results plan-state] phase]
;;            (let [[lift-results plan-state]
;;                  (reduce
;;                   (fn target-reducer [[r plan-state] targets]
;;                     (let
;;                         [{:keys [results plan-state]}
;;                          (lift-op
;;                           operation
;;                           service-state plan-state environment [phase]
;;                           (assoc options :targets targets))]
;;                       (do
;;                         (logging/tracef "back from lift")
;;                         (logging/tracef
;;                          "lift-partitions (count r) %s (count results) %s"
;;                          (count r) (count results))
;;                         [(concat r results) plan-state])))
;;                   [acc-results plan-state]
;;                   (let [fns (comp
;;                              (juxt :partition-f :post-phase-f :post-phase-fsm
;;                                    :phase-execution-f)
;;                              meta #(phase/target-phase % phase))]
;;                     (partition-targets targets phase partition-f)))]
;;              (do
;;                (logging/tracef "back from phase loop")
;;                (logging/tracef "(count lift-results) %s" (count lift-results))
;;                [lift-results plan-state])))
;;          [[] plan-state]
;;          phases)]
;;     (do
;;       (logging/tracef "back from partitions")
;;       (logging/tracef "(count outer-results) %s" (count outer-results))
;;       {:results outer-results
;;        :targets targets
;;        :plan-state plan-state})))

(defn converge*
  "Converge the existing compute resources with the counts
   specified in `group-spec->count`.  Options are as for `converge`.
   The result is written to the channel, ch."
  [group-spec->count ch {:keys [compute blobstore user phase
                                all-nodes all-node-set environment
                                plan-state debug os-detect]
                         :or {os-detect true}
                         :as options}]
  (check-converge-options options)
  (logging/tracef "environment %s" environment)
  (let [[phases phase-map] (process-phases phase)
        phase-map (if os-detect
                    (assoc phase-map
                      :pallet/os (vary-meta
                                  (plan-fn [session] (os session))
                                  merge unbootstrapped-meta)
                      :pallet/os-bs (vary-meta
                                     (plan-fn [session] (os session))
                                     merge bootstrapped-meta))
                    phase-map)
        _ (debugf "phase-map %s" phase-map)
        groups (if (map? group-spec->count)
                 [group-spec->count]
                 group-spec->count)
        groups (expand-group-spec-with-counts group-spec->count)
        {:keys [groups targets]} (-> groups
                                     expand-cluster-groups
                                     split-groups-and-targets)
        _ (logging/tracef "groups %s" (vec groups))
        _ (logging/tracef "targets %s" (vec targets))
        _ (logging/tracef "environment keys %s"
                          (select-keys options environment-args))
        environment (merge-environments
                     (and compute (pallet.environment/environment compute))
                     environment
                     (select-keys options environment-args))
        groups (groups-with-phases groups phase-map)
        targets (groups-with-phases targets phase-map)
        groups (map (partial group-with-environment environment) groups)
        targets (map (partial group-with-environment environment) targets)
        lift-options (select-keys options lift-options)
        initial-plan-state (or plan-state {})
        ;; initial-plan-state (assoc (or plan-state {})
        ;;                      action-options-key
        ;;                      (select-keys debug
        ;;                                   [:script-comments :script-trace]))
        phases (or (seq phases)
                   (apply total-order-merge
                          (map :default-phases (concat groups targets))))]
    (doseq [group groups] (check-group-spec group))
    (go-tuple ch
      (let [session (session/create
                     {:executor (ssh/ssh-executor)
                      :plan-state (in-memory-plan-state initial-plan-state)
                      :user user/*admin-user*})
            nodes-set (all-group-nodes compute groups all-node-set)
            nodes-set (concat nodes-set targets)
            _ (when-not (or compute (seq nodes-set))
                (throw (ex-info
                        "No source of nodes"
                        {:error :no-nodes-and-no-compute-service})))
            c (chan)
            _ (converge-op
               session compute groups nodes-set phases lift-options c)
            [converge-result e] (<! c)]
        (debugf "running nodes-set %s" (vec nodes-set))
        (if e
          [converge-result e]
          (do
            (debugf "running phases %s" phases)
            (async-lift-op session
                           (concat (when os-detect [:pallet/os-bs :pallet/os])
                                   [:settings :bootstrap] phases)
                           (concat (:new-targets converge-result) nodes-set)
                           lift-options c)
            (let [[result e] (<! c)]
              [(-> converge-result
                   (update-in [:results] concat result))
               e])))))))

(defn converge
  "Converge the existing compute resources with the counts specified in
`group-spec->count`. New nodes are started, or nodes are destroyed to obtain the
specified node counts.

`group-spec->count` can be a map from group-spec to node count, or can be a
sequence of group-specs containing a :count key.

This applies the `:bootstrap` phase to all new nodes and, by default,
the :configure phase to all running nodes whose group-name matches a key in the
node map.  Phases can also be specified with the `:phase` option, and will be
applied to all matching nodes.  The :configure phase is the default phase
applied.

## Options

`:compute`
: a compute service.

`:blobstore`
: a blobstore service.

`:phase`
: a phase keyword, phase function, or sequence of these.

`:user`
the admin-user on the nodes.

### Partitioning

`:partition-f`
: a function that takes a sequence of targets, and returns a sequence of
  sequences of targets.  Used to partition or filter the targets.  Defaults to
  any :partition metadata on the phase, or no partitioning otherwise.

## Post phase options

`:post-phase-f`
: specifies an optional function that is run after a phase is applied.  It is
  passed `targets`, `phase` and `results` arguments, and is called before any
  error checking is done.  The return value is ignored, so this is for side
  affect only.

`:post-phase-fsm`
: specifies an optional fsm returning function that is run after a phase is
  applied.  It is passed `targets`, `phase` and `results` arguments, and is
  called before any error checking is done.  The return value is ignored, so
  this is for side affect only.

### Asynchronous and Timeouts

`:async`
: a flag to control whether the function executes asynchronously.  When truthy,
  the function returns an Operation that can be deref'd like a future.  When not
  truthy, `:timeout-ms` may be used to specify a timeout.  Defaults to nil.

`:timeout-ms`
: an integral number of milliseconds to wait for completion before timeout.
  Only applies if `:async` is not truthy (the default).

`:timeout-val`
: a value to be returned should the operation time out.

### Algorithm options

`:phase-execution-f`
: specifies the function used to execute a phase on the targets.  Defaults
  to `pallet.core.primitives/build-and-execute-phase`.

`:execution-settings-f`
: specifies a function that will be called with a node argument, and which
  should return a map with `:user`, `:executor` and `:executor-status-fn` keys.

### OS detection

`:os-detect`
: controls detection of nodes' os (default true)."
  [group-spec->count & {:keys [compute blobstore user phase
                               all-nodes all-node-set environment
                               async timeout-ms timeout-val
                               debug plan-state]
                        :as options}]
  ;; TODO  (load-plugins)
  (let [ch (chan)]
    (converge* group-spec->count ch options)
    (exec-operation
     ch
     (select-keys
      options [:async :operation :status-chan :close-status-chan?
               :timeout-ms :timeout-val]))))

(defn lift*
  "Asynchronously execute a lift of phases on the node-set.  Options
  as specified in `lift`."
  [node-set ch {:keys [compute phase all-node-set environment debug plan-state
                       os-detect]
                :or {os-detect true}
                :as options}]
  (logging/trace "Lift*")
  (check-lift-options options)
  (let [[phases phase-map] (process-phases phase)
        phase-map (if os-detect
                    (assoc phase-map
                      :pallet/os (vary-meta
                                  (plan-fn [session] (os session))
                                  merge unbootstrapped-meta)
                      :pallet/os-bs (vary-meta
                                     (plan-fn [session] (os session))
                                     merge bootstrapped-meta))
                    phase-map)
        {:keys [groups targets]} (-> node-set
                                     expand-cluster-groups
                                     split-groups-and-targets)
        _ (logging/tracef "groups %s" (vec groups))
        _ (logging/tracef "targets %s" (vec targets))
        _ (logging/tracef "environment keys %s"
                          (select-keys options environment-args))
        _ (logging/tracef "options %s" options)
        environment (merge-environments
                     (and compute (pallet.environment/environment compute))
                     environment
                     (select-keys options environment-args))
        groups (groups-with-phases groups phase-map)
        targets (groups-with-phases targets phase-map)
        groups (map (partial group-with-environment environment) groups)
        targets (map (partial group-with-environment environment) targets)
        initial-plan-state (or plan-state {})
        ;; TODO
        ;; initial-plan-state (assoc (or plan-state {})
        ;;                      action-options-key
        ;;                      (select-keys debug
        ;;                                   [:script-comments :script-trace]))
        lift-options (select-keys options lift-options)
        phases (or (seq phases)
                   (apply total-order-merge
                          (map :default-phases (concat groups targets))))]
    (doseq [group groups] (check-group-spec group))
    (logging/trace "Lift ready to start")
    (go-tuple ch
      (let [session (session/create
                     {:executor (ssh/ssh-executor)
                      :plan-state (in-memory-plan-state initial-plan-state)
                      :user user/*admin-user*})
            nodes-set (all-group-nodes compute groups all-node-set)
            nodes-set (concat nodes-set targets)
            _ (when-not (or compute (seq nodes-set))
                (throw (ex-info "No source of nodes"
                                {:error :no-nodes-and-no-compute-service})))
            _ (logging/trace "Retrieved nodes")
            c (chan)
            _ (async-lift-op
               session
               (concat
                (when os-detect [:pallet/os])
                [:settings])
               nodes-set
               {}
               c)
            [settings-results e] (<! c)
            errs (errors settings-results)
            result {:results settings-results
                    :session session}]
        (cond
         e [result e]
         errs [result (ex-info "settings phase failed" {:errors errs})]
         :else (do
                 (async-lift-op session phases nodes-set lift-options c)
                 (let [[lift-results e] (<! c)
                       errs (errors lift-results)
                       result (update-in result [:results] concat lift-results)]
                   [result (or e
                               (if errs
                                 (ex-info "phase failed" {:errors errs})))])))))))

(defn lift
  "Lift the running nodes in the specified node-set by applying the specified
phases.  The compute service may be supplied as an option, otherwise the
bound compute-service is used.  The configure phase is applied by default
unless other phases are specified.

node-set can be a group spec, a sequence of group specs, or a map
of group specs to nodes. Examples:

    [group-spec1 group-spec2 {group-spec #{node1 node2}}]
    group-spec
    {group-spec #{node1 node2}}

## Options:

`:compute`
: a compute service.

`:blobstore`
: a blobstore service.

`:phase`
: a phase keyword, phase function, or sequence of these.

`:user`
the admin-user on the nodes.

### Partitioning

`:partition-f`
: a function that takes a sequence of targets, and returns a sequence of
  sequences of targets.  Used to partition or filter the targets.  Defaults to
  any :partition metadata on the phase, or no partitioning otherwise.

## Post phase options

`:post-phase-f`
: specifies an optional function that is run after a phase is applied.  It is
  passed `targets`, `phase` and `results` arguments, and is called before any
  error checking is done.  The return value is ignored, so this is for side
  affect only.

`:post-phase-fsm`
: specifies an optional fsm returning function that is run after a phase is
  applied.  It is passed `targets`, `phase` and `results` arguments, and is
  called before any error checking is done.  The return value is ignored, so
  this is for side affect only.

### Asynchronous and Timeouts

`:async`
: a flag to control whether the function executes asynchronously.  When truthy,
  the function returns an Operation that can be deref'd like a future.  When not
  truthy, `:timeout-ms` may be used to specify a timeout.  Defaults to nil.

`:timeout-ms`
: an integral number of milliseconds to wait for completion before timeout.
  Only applies if `:async` is not truthy (the default).

`:timeout-val`
: a value to be returned should the operation time out.

### Algorithm options

`:phase-execution-f`
: specifies the function used to execute a phase on the targets.  Defaults
  to `pallet.core.primitives/build-and-execute-phase`.

`:execution-settings-f`
: specifies a function that will be called with a node argument, and which
  should return a map with `:user`, `:executor` and `:executor-status-fn` keys.

### OS detection

`:os-detect`
: controls detection of nodes' os (default true)."
  [node-set & {:keys [compute phase user all-node-set environment
                      async timeout-ms timeout-val
                      partition-f post-phase-f post-phase-fsm
                      phase-execution-f execution-settings-f
                      debug plan-state]
               :as options}]
  (logging/trace "Lift")
  ;; TODO (load-plugins)
  (let [ch (chan)]
    (lift* node-set ch options)
    (exec-operation
     ch
     (select-keys
      options [:async :operation :status-chan :close-status-chan?
               :timeout-ms :timeout-val]))))

(defn lift-nodes
  "Lift `targets`, a sequence of node-maps, using the specified `phases`.  This
provides a way of lifting phases, which doesn't tie you to working with all
nodes in a group.  Consider using this only if the functionality in `lift` is
insufficient.

`phases`
: a sequence of phase keywords (identifying phases) or plan functions, that
  should be applied to the target nodes.  Note that there are no default phases.

## Options:

`:user`
: the admin-user to use for operations on the target nodes.

`:environment`
: an environment map, to be merged into the environment.

`:plan-state`
: an state map, which can be used to passing settings across multiple lift-nodes
  invocations.

`:async`
: a flag to control whether the function executes asynchronously.  When truthy,
  the function returns an Operation that can be deref'd like a future.  When not
  truthy, `:timeout-ms` may be used to specify a timeout.  Defaults to nil.

`:timeout-ms`
: an integral number of milliseconds to wait for completion before timeout.
  Only applies if `:async` is not truthy (the default).

`:timeout-val`
: a value to be returned should the operation time out."
  [targets phases
   & {:keys [user environment plan-state async timeout-ms timeout-val]
      :or {environment {} plan-state {}}
      :as options}]
  (let [[phases phase-map] (process-phases phases)
        targets (groups-with-phases targets phase-map)
        environment (merge-environments
                     environment
                     (select-keys options environment-args))]
    (letfn [(lift-nodes* [operation]
              ;; (lift-partitions
              ;;  operation
              ;;  targets plan-state environment phases
              ;;  (dissoc options
              ;;          :environment :plan-state :async
              ;;          :timeout-val :timeout-ms))
              )]
      (exec-operation lift-nodes* options))))

(defn group-nodes
  "Return a sequence of node-maps for each node in the specified group-specs.

## Options:

`:async`
: a flag to control whether the function executes asynchronously.  When truthy,
  the function returns an Operation that can be deref'd like a future.  When not
  truthy, `:timeout-ms` may be used to specify a timeout.  Defaults to nil.

`:timeout-ms`
: an integral number of milliseconds to wait for completion before timeout.
  Only applies if `:async` is not truthy (the default).

`:timeout-val`
: a value to be returned should the operation time out."
  [compute groups & {:keys [async timeout-ms timeout-val] :as options}]
  (letfn [(group-nodes* [operation] (group-nodes operation compute groups))]
    ;; TODO
    ;; (exec-operation group-nodes* options)
    ))
