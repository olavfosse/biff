(ns com.example.app
  (:require [com.biffweb :as biff :refer [q]]
            [com.example.middleware :as mid]
            [com.example.ui :as ui]
            [com.example.settings :as settings]
            [rum.core :as rum]
            [xtdb.api :as xt]
            [ring.adapter.jetty9 :as jetty]
            [cheshire.core :as cheshire]))

(defn app [{:keys [session biff/db user] :as ctx}]
  (let [{:user/keys [email]} user]
    (ui/page
     {}
     [:div "Signed in as " email ". "
      (biff/form
       {:action "/auth/signout"
        :class "inline"}
       [:button.text-blue-500.hover:text-blue-800 {:type "submit"}
        "Sign out"])
      "."])))

(def plugin
  {:routes ["/app" {:middleware [mid/wrap-require-user]}
            ["" {:get app}]]})
