(ns onyx.log.zookeeper
  (:require [clojure.core.async :refer [go >! <! chan >!! <!! close! thread alts!! offer!]]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :refer [fatal warn info trace]]
            [onyx.log.curator :as zk]
            [onyx.extensions :as extensions]
            [onyx.compression.nippy :refer [zookeeper-compress zookeeper-decompress]]
            [onyx.log.replica :as replica]
            [onyx.peer.log-version]
            [onyx.monitoring.measurements :refer [measure-latency]]
            [onyx.log.entry :refer [create-log-entry]]
            [onyx.schema :as os]
            [schema.core :as s])
  (:import [java.util.concurrent TimeUnit]
           [org.apache.curator.test TestingServer]
           [org.apache.curator.framework CuratorFrameworkFactory CuratorFramework]
           [org.apache.log4j BasicConfigurator]
           [org.apache.curator.framework.state ConnectionStateListener ConnectionState]
           [org.apache.zookeeper KeeperException$NoNodeException KeeperException$NodeExistsException]))

(def root-path "/onyx")

(defn prefix-path [prefix]
  (assert prefix "Prefix must be supplied. Has :onyx/tenancy-id been supplied?")
  (str root-path "/" prefix))

(defn pulse-path [prefix]
  (str (prefix-path prefix) "/pulse"))

(defn log-path [prefix]
  (str (prefix-path prefix) "/log"))

(defn job-hash-path [prefix]
  (str (prefix-path prefix) "/job-hash"))

(defn catalog-path [prefix]
  (str (prefix-path prefix) "/catalog"))

(defn workflow-path [prefix]
  (str (prefix-path prefix) "/workflow"))

(defn flow-path [prefix]
  (str (prefix-path prefix) "/flow"))

(defn lifecycles-path [prefix]
  (str (prefix-path prefix) "/lifecycles"))

(defn windows-path [prefix]
  (str (prefix-path prefix) "/windows"))

(defn triggers-path [prefix]
  (str (prefix-path prefix) "/triggers"))

(defn job-metadata-path [prefix]
  (str (prefix-path prefix) "/job-metadata"))

(defn task-path [prefix]
  (str (prefix-path prefix) "/task"))

(defn sentinel-path [prefix]
  (str (prefix-path prefix) "/sentinel"))

(defn chunk-path [prefix]
  (str (prefix-path prefix) "/chunk"))

(defn origin-path [prefix]
  (str (prefix-path prefix) "/origin"))

(defn log-parameters-path [prefix]
  (str (prefix-path prefix) "/log-parameters"))

(defn exception-path [prefix]
  (str (prefix-path prefix) "/exception"))

(defn throw-subscriber-closed []
  (throw (ex-info "Log subscriber closed due to disconnection from ZooKeeper" {})))

(defn clean-up-broken-connections [f]
  (try
    (f)
    (catch org.apache.zookeeper.KeeperException$ConnectionLossException e
      (trace e)
      (throw-subscriber-closed))
    (catch org.apache.zookeeper.KeeperException$SessionExpiredException e
      (trace e)
      (throw-subscriber-closed))))

(defn initialize-origin! [conn config prefix]
  (clean-up-broken-connections
   (fn []
     (let [node (str (origin-path prefix) "/origin")
           bytes (zookeeper-compress {:message-id -1 :replica replica/base-replica})]
       (zk/create conn node :data bytes :persistent? true)))))

(defn try-connect [^CuratorFramework zk-client]
  (info "Trying connect ZK 5s ...")
  (.. zk-client
      (blockUntilConnected 5 TimeUnit/SECONDS)))

(defn block-until-connected [^CuratorFramework zk-client]
  (loop []
    (or (try-connect zk-client)
        (recur))))

; conn reconnect
(defn until-connected [^CuratorFramework zk-client restart-ch]
  (future (when-let [v (<!! restart-ch)]
            ; exclude null when channel closed
            (when v
                  (block-until-connected zk-client)))))

(defn as-connection-listener [f]
  (reify ConnectionStateListener
    (stateChanged [_ zk-client newState]
      (f newState))))

(defn add-conn-watcher [^CuratorFramework zk-client listener-fn]
  (let [listener (as-connection-listener listener-fn)]
    (.. zk-client
        getConnectionStateListenable
        (addListener listener))
    listener))

(defn remove-conn-watcher [^CuratorFramework zk-client listener]
  (.. zk-client
      getConnectionStateListenable
      (removeListener listener)))

; needs restart on conn lost
(defn notify-restarter [^CuratorFramework zk-client restart-ch]
  (add-conn-watcher zk-client
                    (fn [newState]
                      (info "ZK connection state:" (str newState))

                      ; try connect in bg when connection lost
                      (when (= ConnectionState/LOST newState)
                        (println "Start trying reconnect for each 5s ... (each Zookeeper client component separately)")
                        (go (>! restart-ch true))))))


(defn write-onyx-paths [conn config onyx-id]
  (taoensso.timbre/info "Write Onyx paths into ZK")
  (zk/create conn root-path :persistent? true)
  (zk/create conn (prefix-path onyx-id) :persistent? true)
  (zk/create conn (pulse-path onyx-id) :persistent? true)
  (zk/create conn (log-path onyx-id) :persistent? true)
  (zk/create conn (job-hash-path onyx-id) :persistent? true)
  (zk/create conn (catalog-path onyx-id) :persistent? true)
  (zk/create conn (workflow-path onyx-id) :persistent? true)
  (zk/create conn (flow-path onyx-id) :persistent? true)
  (zk/create conn (lifecycles-path onyx-id) :persistent? true)
  (zk/create conn (windows-path onyx-id) :persistent? true)
  (zk/create conn (triggers-path onyx-id) :persistent? true)
  (zk/create conn (job-metadata-path onyx-id) :persistent? true)
  (zk/create conn (task-path onyx-id) :persistent? true)
  (zk/create conn (sentinel-path onyx-id) :persistent? true)
  (zk/create conn (chunk-path onyx-id) :persistent? true)
  (zk/create conn (origin-path onyx-id) :persistent? true)
  (zk/create conn (log-parameters-path onyx-id) :persistent? true)
  (zk/create conn (exception-path onyx-id) :persistent? true)

  (initialize-origin! conn config onyx-id))

(defrecord ZooKeeper [config]
  component/Lifecycle

  (start [component]
    (s/validate os/PeerClientConfig config)
    (taoensso.timbre/info "Starting ZooKeeper" (if (:zookeeper/server? config) "server" "client connection. If Onyx hangs here it may indicate a difficulty connecting to ZooKeeper."))
    (BasicConfigurator/configure)
    (let [kill-ch      (chan)
          restarter-ch (chan 1)
          onyx-id      (-> config :onyx/tenancy-id)
          as-server    (-> config :zookeeper/server?)
          server       (when as-server (TestingServer. (int (:zookeeper.server/port config))))
          conn         (zk/connect-1-retry (-> config :zookeeper/address ))
          nr           (notify-restarter conn restarter-ch)
          restarter    (until-connected  conn restarter-ch)
          _ (do (block-until-connected conn)
                (write-onyx-paths conn config onyx-id))]

      (assoc component :kill-ch      kill-ch
                       :restarter-ch restarter-ch
                       :server       server
                       :conn         conn
                       :nr           nr
                       :restarter    restarter
                       :prefix       onyx-id
                       )))

  (stop [{:keys [kill-ch restarter-ch conn nr restarter server] :as component}]
    (taoensso.timbre/info "Stopping ZooKeeper" (if (-> config :zookeeper/server?) "server" "client connection"))
    (close! kill-ch)
    (close! restarter-ch)

    (when restarter (future-cancel restarter))
    (when nr (remove-conn-watcher conn nr))

    (when (.. ^CuratorFramework conn isStarted)
          (zk/close conn))

    (when server
      (.close ^TestingServer server))

    component))

(defmethod clojure.core/print-method ZooKeeper
  [system ^java.io.Writer writer]
  (.write writer "#<ZooKeeper Component>"))

(defn zookeeper [config]
  (map->ZooKeeper {:config config}))

(defn pad-sequential-id
  "ZooKeeper sequential IDs are at least 10 digits.
   If this node's id is less, pad it. Otherwise returns the str'ed id"
  [id]
  (let [padding 10
        id-len (count (str id))]
    (if (< id-len padding)
      (str (apply str (take (- padding id-len) (repeat "0"))) id)
      (str id))))

(defmethod extensions/write-log-entry ZooKeeper
  [{:keys [conn opts prefix monitoring] :as log} data]
  (let [bytes (zookeeper-compress data)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (log-path prefix) "/entry-")]
           (zk/create conn node :data bytes :persistent? true :sequential? true))))
     #(let [args {:event :zookeeper-write-log-entry
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/read-log-entry ZooKeeper
  [{:keys [conn opts prefix monitoring] :as log} position]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (log-path prefix) "/entry-" (pad-sequential-id position))
             data (zk/data conn node)
             content (zookeeper-decompress (:data data))]
         (assoc content :message-id position :created-at (:ctime (:stat data))))))
   #(let [args {:event :zookeeper-read-log-entry :position position :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/register-pulse ZooKeeper
  [{:keys [conn opts prefix] :as log} id]
  (clean-up-broken-connections
   (fn []
     (let [node (str (pulse-path prefix) "/" id)]
       (zk/create conn node :persistent? false)))))

(defmethod extensions/on-delete ZooKeeper
  [{:keys [conn opts prefix] :as log} id ch]
  (let [f (fn [event]
            (when (= (:event-type event) :NodeDeleted)
              (offer! ch true)))]
    (try
      (when-not (zk/exists conn (str (pulse-path prefix) "/" id) :watcher f)
        (offer! ch true))
      (catch Throwable e
        (trace e)
        ;; Node doesn't exist.
        (offer! ch true)))))

(defmethod extensions/group-exists? ZooKeeper
  [{:keys [conn opts prefix] :as log} id]
  (zk/exists conn (str (pulse-path prefix) "/" id)))

(defn find-log-parameters [log]
  (loop []
    (if-let [chunk
             (try
               (extensions/read-chunk log :log-parameters nil)
               (catch Throwable e
                 (warn e)
                 (warn "Log parameters couldn't be discovered. Backing off 500ms and trying again...")
                 nil))]
      chunk
      (do (Thread/sleep 500)
          (recur)))))

(defn seek-to-new-origin! [log ch]
  (let [origin (extensions/read-chunk log :origin nil)
        starting-position (inc (:message-id origin))
        entry (create-log-entry :set-replica! {:replica origin})]
    (>!! ch entry)
    starting-position))

(defn seek-and-put-entry! [log position ch]
  (try
    (let [entry (extensions/read-log-entry log position)]
      (>!! ch entry))
    (catch KeeperException$NoNodeException e
      (seek-to-new-origin! log ch))
    (catch KeeperException$NodeExistsException e
      (seek-to-new-origin! log ch))))


(defmethod extensions/subscribe-to-log ZooKeeper
  [{:keys [conn opts prefix kill-ch] :as log} ch]
  (let [rets (chan)]
    (thread
     (try
       (let [log-parameters (find-log-parameters log)
             origin (extensions/read-chunk log :origin nil)
             starting-position (inc (:message-id origin))]
         (onyx.peer.log-version/check-compatible-log-versions! (:log-version log-parameters))
         (>!! rets (merge (:replica origin) log-parameters))
         (close! rets)
         (loop [position starting-position]
           (let [path (str (log-path prefix) "/entry-" (pad-sequential-id position))]
             (if (zk/exists conn path)
               (seek-and-put-entry! log position ch)
               (loop []
                 (let [read-ch (chan 2)]
                   (zk/children conn (log-path prefix) :watcher (fn [_] (offer! read-ch true)))
                   ;; Log entry may have been added in between initial check and when we
                   ;; added the watch.
                   (when (zk/exists conn path)
                     (offer! read-ch true))
                   (let [[_ active-ch] (alts!! [read-ch kill-ch])]
                     (when (= active-ch read-ch)
                       (close! read-ch)
                       ;; Requires one more check. Watch may have been triggered by a delete
                       ;; from a GC call.
                       (if (zk/exists conn path)
                         (seek-and-put-entry! log position ch)
                         (recur)))))))
             (recur (inc position)))))
       (catch java.lang.IllegalStateException e
         (trace e)
         ;; Curator client has been shutdown, pass exception along
         (>!! ch e))
       (catch org.apache.zookeeper.KeeperException$ConnectionLossException e
         ;; ZooKeeper has been shutdown, pass exception along
         (trace e)
         (>!! ch e))
       (catch org.apache.zookeeper.KeeperException$SessionExpiredException e
         (trace e)
         (>!! ch e))
       (catch Throwable e
         (fatal e)
         (>!! ch e))))
    (<!! rets)))

(defmethod extensions/write-chunk [ZooKeeper :job-hash]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (job-hash-path prefix) "/" id)]
           (zk/create conn node :persistent? true :data bytes))))
     #(let [args {:event :zookeeper-write-job-hash :id id
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/write-chunk [ZooKeeper :catalog]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (catalog-path prefix) "/" id)]
           (zk/create conn node :persistent? true :data bytes))))
     #(let [args {:event :zookeeper-write-catalog :id id
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/write-chunk [ZooKeeper :workflow]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (workflow-path prefix) "/" id)]
           (zk/create conn node :persistent? true :data bytes))))
     #(let [args {:event :zookeeper-write-workflow :id id
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/write-chunk [ZooKeeper :flow-conditions]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (flow-path prefix) "/" id)]
           (zk/create conn node :persistent? true :data bytes))))
     #(let [args {:event :zookeeper-write-flow-conditions :id id
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/write-chunk [ZooKeeper :lifecycles]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (lifecycles-path prefix) "/" id)]
           (zk/create conn node :persistent? true :data bytes))))
     #(let [args {:event :zookeeper-write-lifecycles :id id
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/write-chunk [ZooKeeper :windows]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (windows-path prefix) "/" id)]
           (zk/create conn node :persistent? true :data bytes))))
     #(let [args {:event :zookeeper-write-windows :id id
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/write-chunk [ZooKeeper :triggers]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (triggers-path prefix) "/" id)]
           (zk/create conn node :persistent? true :data bytes))))
     #(let [args {:event :zookeeper-write-triggers :id id
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/write-chunk [ZooKeeper :job-metadata]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (job-metadata-path prefix) "/" id)]
           (zk/create conn node :persistent? true :data bytes))))
     #(let [args {:event :zookeeper-write-job-metadata :id id
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/write-chunk [ZooKeeper :task]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (task-path prefix) "/" id "/" (:id chunk))]
           (zk/create-all conn node :persistent? true :data bytes))))
     #(let [args {:event :zookeeper-write-task :id (:id chunk)
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/write-chunk [ZooKeeper :chunk]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (chunk-path prefix) "/" id "/chunk")]
           (zk/create-all conn node :persistent? true :data bytes)
           id)))
     #(let [args {:event :zookeeper-write-chunk :id id
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/write-chunk [ZooKeeper :log-parameters]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (log-parameters-path prefix) "/log-parameters")]
           (zk/create conn node :persistent? true :data bytes))))
     #(let [args {:event :zookeeper-write-log-parameters :id id
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/write-chunk [ZooKeeper :exception]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (exception-path prefix) "/" id)]
           (zk/create-all conn node :persistent? true :data bytes)
           id)))
     #(let [args {:event :zookeeper-write-exception :id id
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/force-write-chunk [ZooKeeper :chunk]
  [{:keys [conn opts prefix monitoring] :as log} kw chunk id]
  (let [bytes (zookeeper-compress chunk)]
    (measure-latency
     #(clean-up-broken-connections
       (fn []
         (let [node (str (chunk-path prefix) "/" id "/chunk")
               version (:version (zk/exists conn node))]
           (if (nil? version)
             (zk/create-all conn node :persistent? true :data bytes)
             (zk/set-data conn node bytes version)))))
     #(let [args {:event :zookeeper-force-write-chunk :id id
                  :latency % :bytes (count bytes)}]
        (extensions/emit monitoring args)))))

(defmethod extensions/read-chunk [ZooKeeper :job-hash]
  [{:keys [conn opts prefix monitoring] :as log} kw id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (job-hash-path prefix) "/" id)]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-job-hash :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/read-chunk [ZooKeeper :catalog]
  [{:keys [conn opts prefix monitoring] :as log} kw id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (catalog-path prefix) "/" id)]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-catalog :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/read-chunk [ZooKeeper :workflow]
  [{:keys [conn opts prefix monitoring] :as log} kw id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (workflow-path prefix) "/" id)]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-workflow :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/read-chunk [ZooKeeper :flow-conditions]
  [{:keys [conn opts prefix monitoring] :as log} kw id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (flow-path prefix) "/" id)]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-flow-conditions :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/read-chunk [ZooKeeper :lifecycles]
  [{:keys [conn opts prefix monitoring] :as log} kw id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (lifecycles-path prefix) "/" id)]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-lifecycles :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/read-chunk [ZooKeeper :windows]
  [{:keys [conn opts prefix monitoring] :as log} kw id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (windows-path prefix) "/" id)]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-windows :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/read-chunk [ZooKeeper :triggers]
  [{:keys [conn opts prefix monitoring] :as log} kw id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (triggers-path prefix) "/" id)]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-triggers :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/read-chunk [ZooKeeper :job-metadata]
  [{:keys [conn opts prefix monitoring] :as log} kw id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (job-metadata-path prefix) "/" id)]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-job-metadata :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/read-chunk [ZooKeeper :task]
  [{:keys [conn opts prefix monitoring] :as log} kw job-id id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (task-path prefix) "/" job-id "/" id)]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-task :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/read-chunk [ZooKeeper :chunk]
  [{:keys [conn opts prefix monitoring] :as log} kw id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (chunk-path prefix) "/" id "/chunk")]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-chunk :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/read-chunk [ZooKeeper :origin]
  [{:keys [conn opts prefix monitoring] :as log} kw id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (origin-path prefix) "/origin")]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-origin :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/read-chunk [ZooKeeper :log-parameters]
  [{:keys [conn opts prefix monitoring] :as log} kw id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (log-parameters-path prefix) "/log-parameters")]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-log-parameters :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/read-chunk [ZooKeeper :exception]
  [{:keys [conn opts prefix monitoring] :as log} kw id & _]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (exception-path prefix) "/" id)]
         (zookeeper-decompress (:data (zk/data conn node))))))
   #(let [args {:event :zookeeper-read-exception :id id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/update-origin! ZooKeeper
  [{:keys [conn opts prefix monitoring] :as log} replica message-id]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (origin-path prefix) "/origin")
             version (:version (zk/exists conn node))
             content (zookeeper-decompress (:data (zk/data conn node)))]
         (when (< (:message-id content) message-id)
           (let [new-content {:message-id message-id :replica replica}]
             (zk/set-data conn node (zookeeper-compress new-content) version))))))
   #(let [args {:event :zookeeper-write-origin :message-id message-id :latency %}]
      (extensions/emit monitoring args))))

(defmethod extensions/gc-log-entry ZooKeeper
  [{:keys [conn opts prefix monitoring] :as log} position]
  (measure-latency
   #(clean-up-broken-connections
     (fn []
       (let [node (str (log-path prefix) "/entry-" (pad-sequential-id position))]
         (zk/delete conn node))))
   #(let [args {:event :zookeeper-gc-log-entry :position position :latency %}]
      (extensions/emit monitoring args))))
