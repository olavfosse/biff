(ns com.example
  (:require [babashka.curl :as curl]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.stacktrace :as st]
            [com.biffweb :as biff]
            [babashka.curl :as curl]
            [babashka.fs :as fs]
            [com.example.email :as email]
            [com.example.app :as app]
            [com.example.home :as home]
            [com.example.middleware :as mid]
            [com.example.ui :as ui]
            [com.example.worker :as worker]
            [com.example.schema :as schema]
            [clojure.test :as test]
            [clojure.tools.logging :as log]
            [clojure.tools.namespace.repl :as tn-repl]
            [malli.core :as malc]
            [malli.registry :as malr]
            [nrepl.cmdline :as nrepl-cmd]
            [clojure.tools.build.api :as b])
  (:gen-class))

(def plugins
  [app/plugin
   (biff/authentication-plugin {})
   home/plugin
   schema/plugin
   worker/plugin])

(def routes [["" {:middleware [mid/wrap-site-defaults]}
              (keep :routes plugins)]
             ["" {:middleware [mid/wrap-api-defaults]}
              (keep :api-routes plugins)]])

(def handler (-> (biff/reitit-handler {:routes routes})
                 mid/wrap-base-defaults))

(def static-pages (apply biff/safe-merge (map :static plugins)))

(defn generate-assets!
  ([ctx prefix]
   (biff/export-rum static-pages (str prefix "resources/public"))
   (biff/delete-old-files {:dir (str prefix "resources/public")
                           :exts [".html"]}))
  ([ctx]
   (generate-assets! ctx "target/")))

(defn on-save [ctx]
  (biff/add-libs)
  (biff/eval-files! ctx)
  (generate-assets! ctx)
  (test/run-all-tests #"com.example.test.*"))

(def malli-opts
  {:registry (malr/composite-registry
              malc/default-registry
              (apply biff/safe-merge
                     (keep :schema plugins)))})

(def initial-system
  {:biff/plugins #'plugins
   :biff/send-email #'email/send-email
   :biff/handler #'handler
   :biff/malli-opts #'malli-opts
   :biff.beholder/on-save #'on-save
   :biff.middleware/on-error #'ui/on-error
   :biff.xtdb/tx-fns biff/tx-fns
   :com.example/chat-clients (atom #{})})

(defonce system (atom {}))

(def components
  [biff/use-config
   biff/use-secrets
   biff/use-xt
   biff/use-queues
   biff/use-tx-listener
   biff/use-jetty
   biff/use-chime
   biff/use-beholder])

(defn start []
  (let [new-system (reduce (fn [system component]
                             (log/info "starting:" (str component))
                             (component system))
                           initial-system
                           components)]
    (reset! system new-system)
    (generate-assets! new-system)
    (log/info "System started.")
    (log/info "Go to" (:biff/base-url new-system))))

;; clojure pattern is to jsut use a future regardless of if you care
;; about result value, just cus it's convenient
(defmacro future-verbose [& body]
  `(future
     (try
       ~@body
       (catch Exception e#
         ;; st/print-stack-trace just prints Babashka's internal stack trace.
         (st/print-throwable e#)
         (println)))))

(defn tailwind-file []
  (let [os-name (str/lower-case (System/getProperty "os.name"))
        os-type (cond
                  (str/includes? os-name "windows") "windows"
                  (str/includes? os-name "linux") "linux"
                  :else "macos")
        arch (case (System/getProperty "os.arch")
               "amd64" "x64"
               "arm64")]
    (str "tailwindcss-" os-type "-" arch (when (= os-type "windows") ".exe"))))

(defn install-tailwind []
  (let [file (tailwind-file)
        url (str "https://github.com/tailwindlabs/tailwindcss/releases/latest/download/"
                 file)
        dest (io/file "bin/tailwindcss")]
    (io/make-parents dest)
    (println "Downloading the latest version of Tailwind CSS...")
    (println (str "Auto-detected build: " file "."))
    (println)
    (io/copy (:body (curl/get url {:compressed false :as :stream})) dest)
    (.setExecutable dest true)))

(defn shell [& args]
  (apply process/shell args))

(defn tailwind
  [action]
  (let [local-bin-installed (fs/exists? "bin/tailwindcss")]
    (when (not local-bin-installed) (install-tailwind))
    ;; This normally will be handled by install-tailwind, but we set it here
    ;; in case that function was interrupted. Assuming the download was
    ;; incomplete, the 139 exit code handler will be triggered below.
    (.setExecutable (io/file "bin/tailwindcss") true)
    (try
      (apply shell (concat ["bin/tailwindcss"]
                           ["-c" "resources/tailwind.config.js"
                            "-i" "resources/tailwind.css"
                            "-o" (case action
                                   :dev "target/resources/public/css/main.css"
                                   :build-prod "prod_build/resources/public/css/main.css")]
                           (case action
                             :dev "--watch"
                             :build-prod "--minify")))
      (catch Exception e
        (when (= 139 (:babashka/exit (ex-data e)))
          (binding [*out* *err*]
            (println "It looks like your Tailwind installation is corrupted. Try deleting it and running this command again:")
            (println)
            (println "  rm bin/tailwindcss")
            (println)))
        (throw e)))))

(defn -dev-main [& args]
  ;; args must be passed directly now... it's fine tbh.. methinks
  ;; TODO: tailwind watcher
  (future-verbose (tailwind :dev))
  ;; TODO: secrets/config
  
  (start)
  (apply nrepl-cmd/-main args))

(defn -prod-main [& args]
  (start)
  (apply nrepl-cmd/-main args))
(def -main -prod-main)

(defn -build-prod-main [& args]
  ;; stil some thinkin to do wrt getting the dictionaries right
  (let [main-ns 'com.example #_(ns-name *ns*) ;; ns is not right
        class-dir "prod_build/jar/classes"
        basis (b/create-basis {:project "deps.edn"})
        uber-file "prod_build/jar/app.jar"]
    (println "clearning old build...")
    (b/delete {:path "prod_build"})
    (println "tailwind...")
    (tailwind :build-prod)
    (println "assets...")
    (generate-assets! {} "prod_build/")
    (println "copy...")
    (b/copy-dir {:src-dirs ["src" "resources" "prod_build/resources"]
                 :target-dir class-dir})
    (println "comp...")
    (b/compile-clj {:basis basis          
                    :ns-compile [main-ns] 
                    :class-dir class-dir})
    (println "uber...")
    (b/uber {:class-dir class-dir
             :uber-file uber-file
             :basis basis        
             :main main-ns}))    
  )

(defn new-secret [length]
  (let [buffer (byte-array length)]
    (.nextBytes (java.security.SecureRandom/getInstanceStrong) buffer)
    (.encodeToString (java.util.Base64/getEncoder) buffer)))

(defn -generate-secrets-main
  [& args]
  (println "Put these in your secrets.env file:")
  (println)
  (println (str "export COOKIE_SECRET=" (new-secret 16)))
  (println (str "export JWT_SECRET=" (new-secret 32)))
  (println))

(defn refresh []
  (doseq [f (:biff/stop @system)]
    (log/info "stopping:" (str f))
    (f))
  (tn-repl/refresh :after `start))
