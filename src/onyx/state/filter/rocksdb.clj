(ns onyx.state.filter.rocksdb
  (:import [org.rocksdb RocksDB Options BloomFilter BlockBasedTableConfig]
           [org.apache.commons.io FileUtils])
  (:require [onyx.state.state-extensions :as state-extensions]
            [onyx.state.rocksdb :as r]
            [onyx.static.default-vals :refer [arg-or-default defaults]]
            [onyx.compression.nippy :as nippy]
            [taoensso.timbre :refer [info error warn trace fatal] :as timbre]))

(defrecord RocksDbInstance [dir db id-counter bucket rotation-fut])

(defn new-bucket [v]
  (let [cyc (byte-array 1)]
    (aset cyc 0 (byte v))
    cyc))

(defn bucket-val [cyc]
  (aget cyc 0))

(defn next-bucket [cyc]
  (new-bucket (- (mod (+ (inc (bucket-val cyc)) 
                        128) 
                     256)
                128)))

(def start-bucket 0)

(defn clear-bucket! [db bucket]
  (let [bucket-val (byte bucket)
        iterator (.newIterator db)]
    (try
      (.seekToFirst iterator)
      (while (.isValid iterator)
        (when (= (aget (.value iterator) 0) 
                 bucket-val)
          (.remove db (.key iterator)))
        (.next iterator))
      (finally
        (.dispose iterator)))))

(defn rotate-bucket! 
  "Rotates to the next bucket, and then starts clearing the one after it"
  [db bucket]
  (swap! bucket next-bucket)
  (clear-bucket! db (bucket-val (next-bucket @bucket))))

(defn start-rotation-fut [peer-opts db id-counter bucket]
  (future
    (let [rotation-sleep (arg-or-default :onyx.rocksdb.filter/rotation-check-interval peer-opts)
          elements-per-bucket (arg-or-default :onyx.rocksdb.filter/rotate-filter-bucket-every-n peer-opts)] 
      (loop []
        (try
          (Thread/sleep rotation-sleep)
          (when (> @id-counter elements-per-bucket)
            (info "Rotating filter bucket after" elements-per-bucket "elements.")
            (rotate-bucket! db bucket)
            (reset! id-counter 0)) 
          (catch InterruptedException e
            (throw e))
          (catch Throwable e
            (fatal e))))
      (recur))))

(defmethod state-extensions/initialize-filter :rocksdb [_ {:keys [onyx.core/peer-opts onyx.core/id onyx.core/task-id] :as event}] 
  (let [_ (RocksDB/loadLibrary)
        compression-opt (arg-or-default :onyx.rocksdb.filter/compression peer-opts)
        block-size (arg-or-default :onyx.rocksdb.filter/block-size peer-opts)
        block-cache-size (arg-or-default :onyx.rocksdb.filter/peer-block-cache-size peer-opts)
        base-dir-path (arg-or-default :onyx.rocksdb.filter/base-dir peer-opts)
        bloom-filter-bits (arg-or-default :onyx.rocksdb.filter/bloom-filter-bits peer-opts)
        base-dir-path-file (java.io.File. base-dir-path)
        _ (when-not (.exists base-dir-path-file) (.mkdir base-dir-path-file))
        db-dir (str base-dir-path "/" id "_" task-id)
        bloom-filter (BloomFilter. bloom-filter-bits false)
        block-config (doto (BlockBasedTableConfig.)
                       (.setBlockSize block-size)
                       (.setBlockCacheSize block-size)
                       (.setFilter bloom-filter))
        options (doto (Options.)
                  (.setCompressionType (r/compression-option->type compression-opt))
                  (.setCreateIfMissing true)
                  (.setTableFormatConfig block-config))
        db (RocksDB/open options db-dir)
        bucket (atom (new-bucket start-bucket))
        id-counter (atom 0)
        rotation-fut (start-rotation-fut peer-opts db id-counter bucket)]
    (->RocksDbInstance db-dir db id-counter bucket rotation-fut)))

(defmethod state-extensions/apply-filter-id onyx.state.filter.rocksdb.RocksDbInstance [rocks-db _ id] 
  (let [k ^bytes (nippy/localdb-compress id)]
    (swap! (:id-counter rocks-db) inc)
    (.put ^RocksDB (:db rocks-db) k ^bytes @(:bucket rocks-db)))
  ;; Expects a filter back
  rocks-db)

(defmethod state-extensions/filter? onyx.state.filter.rocksdb.RocksDbInstance [rocks-db _ id] 
  (let [k (nippy/localdb-compress id)]
    (not (nil? (.get ^RocksDB (:db rocks-db) k)))))

(defmethod state-extensions/close-filter onyx.state.filter.rocksdb.RocksDbInstance [rocks-db _]
  (future-cancel (:rotation-fut rocks-db))
  (.close ^RocksDB (:db rocks-db))
  (FileUtils/deleteDirectory (java.io.File. (:dir rocks-db))))