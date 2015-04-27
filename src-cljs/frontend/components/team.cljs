(ns frontend.components.team
  (:require [datascript :as d]
            [frontend.components.permissions :as permissions]
            [frontend.datascript :as ds]
            [frontend.sente :as sente]
            [frontend.urls :as urls]
            [frontend.utils :as utils]
            [om.core :as om]
            [taoensso.sente])
  (:require-macros [sablono.core :refer (html)])
  (:import [goog.ui IdGenerator]))

(defn team-settings [app owner]
  (reify
    om/IDisplayName (display-name [_] "Team settings")
    om/IInitState
    (init-state [_]
      {:permission-grant-email ""
       :listener-key (.getNextUniqueId (.getInstance IdGenerator))})
    om/IDidMount
    (did-mount [_]
      (d/listen! (om/get-shared owner :team-db)
                 (om/get-state owner :listener-key)
                 (fn [tx-report]
                   ;; TODO: better way to check if state changed
                   (when (seq (filter #(or (= :permission/team (:a %))
                                           (= :access-grant/team (:a %))
                                           (= :access-request/team (:a %))
                                           (= :access-request/status (:a %)))
                                      (:tx-data tx-report)))
                     (om/refresh! owner)))))
    om/IWillUnmount
    (will-unmount [_]
      (d/unlisten! (om/get-shared owner :team-db) (om/get-state owner :listener-key)))

    om/IRenderState
    (render-state [_ {:keys [permission-grant-email]}]
      (let [team (:team app)
            db (om/get-shared owner :team-db)
            permissions (ds/touch-all '[:find ?t :in $ ?team-uuid :where [?t :permission/team ?team-uuid]] @db (:team/uuid team))
            access-grants (ds/touch-all '[:find ?t :in $ ?team-uuid :where [?t :access-grant/team ?team-uuid]] @db (:team/uuid team))
            access-requests (ds/touch-all '[:find ?t :in $ ?team-uuid :where [?t :access-request/team ?team-uuid]] @db (:team/uuid team))
            cast! (om/get-shared owner :cast!)
            submit-form (fn [e]
                          (cast! :team-permission-grant-submitted {:email permission-grant-email})
                          (om/set-state! owner :permission-grant-email "")
                          (utils/stop-event e))]
        (html
         [:div.menu-view
          [:div.content
           [:p.make
            "Any docs you create in the " (:team/subdomain team)
            " subdomain will be private to your team by default."
            " Add your teammate's email to add them to your team."]
           [:form.menu-invite-form.make
            {:on-submit submit-form
             :on-key-down #(when (= "Enter" (.-key %))
                             (submit-form %))}
            [:input
             {:type "text"
              :required "true"
              :data-adaptive ""
              :value (or permission-grant-email "")
              :on-change #(om/set-state! owner :permission-grant-email (.. % -target -value))}]
            [:label
             {:data-placeholder "Teammate's email"
              :data-placeholder-nil "What's your teammate's email?"
              :data-placeholder-forgot "Don't forget to submit!"}]]
           (for [access-entity (sort-by (comp - :db/id) (concat permissions access-grants access-requests))]
             (permissions/render-access-entity access-entity cast!))]])))))

(defn your-teams [app owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (sente/send-msg (om/get-shared owner :sente) [:cust/fetch-teams]
                      20000
                      (fn [res]
                        (if (taoensso.sente/cb-success? res)
                          (om/set-state! owner :teams (:teams res))
                          (comment "do something about errors")))))
    om/IRenderState
    (render-state [_ {:keys [teams]}]
      (html
       [:div.menu-view
        [:div.content.make
         (if (nil? teams)
           [:div.loading "Loading..."]
           (if (empty? teams)
             [:p.make "You don't have any teams, yet"]
             [:div.make
              (for [team (sort-by :team/subdomain teams)]
                [:p.make {:key (:team/subdomain team)}
                 [:a {:href (urls/absolute-doc-url (:team/intro-doc team)
                                                   :subdomain (:team/subdomain team))}
                  (:team/subdomain team)]])]))
         [:div.calls-to-action.content.make
          [:a.bubble-button {:role "button"
                             :href "/pricing"}
           "Create a new team"]]]]))))
