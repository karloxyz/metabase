(ns metabase.api.activity-test
  "Tests for /api/activity endpoints."
  (:require [expectations :refer :all]
            [metabase.db :as db]
            (metabase.models [activity :refer [Activity]]
                             [card :refer [Card]]
                             [dashboard :refer [Dashboard]]
                             [view-log :refer [ViewLog]])
            [metabase.test.data :refer :all]
            [metabase.test.data.users :refer :all]
            [metabase.test.util :refer [match-$ expect-with-temp resolve-private-fns], :as tu]
            [metabase.util :as u]))

;; GET /

;; Things we are testing for:
;;  1. ordered by timestamp DESC
;;  2. :user and :model_exists are hydrated

;; NOTE: timestamp matching was being a real PITA so I cheated a bit.  ideally we'd fix that
(tu/expect-with-temp [Activity [activity1 {:topic     "install"
                                           :details   {}
                                           :timestamp (u/->Timestamp "2015-09-09T12:13:14.888Z")}]
                      Activity [activity2 {:topic     "dashboard-create"
                                           :user_id   (user->id :crowberto)
                                           :model     "dashboard"
                                           :model_id  1234
                                           :details   {:description  "Because I can!"
                                                       :name         "Bwahahaha"
                                                       :public_perms 2}
                                           :timestamp (u/->Timestamp "2015-09-10T18:53:01.632Z")}]
                      Activity [activity3 {:topic     "user-joined"
                                           :user_id   (user->id :rasta)
                                           :model     "user"
                                           :details   {}
                                           :timestamp (u/->Timestamp "2015-09-10T05:33:43.641Z")}]]
  [(match-$ (Activity (:id activity2))
     {:id           $
      :topic        "dashboard-create"
      :user_id      $
      :user         (match-$ (fetch-user :crowberto)
                      {:id           (user->id :crowberto)
                       :email        $
                       :date_joined  $
                       :first_name   $
                       :last_name    $
                       :last_login   $
                       :is_superuser $
                       :is_qbnewb    $
                       :common_name  $})
      :model        $
      :model_id     $
      :model_exists false
      :database_id  nil
      :database     nil
      :table_id     nil
      :table        nil
      :custom_id    nil
      :details      $})
   (match-$ (Activity (:id activity3))
     {:id           $
      :topic        "user-joined"
      :user_id      $
      :user         (match-$ (fetch-user :rasta)
                      {:id           (user->id :rasta)
                       :email        $
                       :date_joined  $
                       :first_name   $
                       :last_name    $
                       :last_login   $
                       :is_superuser $
                       :is_qbnewb    $
                       :common_name  $})
      :model        $
      :model_id     $
      :model_exists false
      :database_id  nil
      :database     nil
      :table_id     nil
      :table        nil
      :custom_id    nil
      :details      $})
   (match-$ (Activity (:id activity1))
     {:id           $
      :topic        "install"
      :user_id      nil
      :user         nil
      :model        $
      :model_id     $
      :model_exists false
      :database_id  nil
      :database     nil
      :table_id     nil
      :table        nil
      :custom_id    nil
      :details      $})]
  ;; clear any other activities from the DB just in case; not sure this step is needed any more
  (do (db/cascade-delete! Activity :id [:not-in #{(:id activity1)
                                                  (:id activity2)
                                                  (:id activity3)}])
      (for [activity ((user->client :crowberto) :get 200 "activity")]
        (dissoc activity :timestamp))))


;;; GET /recent_views

;; Things we are testing for:
;;  1. ordering is sorted by most recent
;;  2. results are filtered to current user
;;  3. `:model_object` is hydrated in each result
;;  4. we filter out entries where `:model_object` is nil (object doesn't exist)

(defn- create-view! [user model model-id]
  (db/insert! ViewLog
    :user_id  user
    :model    model
    :model_id model-id
    :timestamp (u/new-sql-timestamp))
  ;; we sleep a bit to ensure no events have the same timestamp
  ;; sadly, MySQL doesn't support milliseconds so we have to wait a second
  ;; otherwise our records are out of order and this test fails :(
  (Thread/sleep 1000))

(expect-with-temp [Card      [card1 {:name                   "rand-name"
                                     :creator_id             (user->id :crowberto)
                                     :public_perms           2
                                     :display                "table"
                                     :dataset_query          {}
                                     :visualization_settings {}}]
                   Dashboard [dash1 {:name         "rand-name"
                                     :description  "rand-name"
                                     :creator_id   (user->id :crowberto)
                                     :public_perms 2}]
                   Card      [card2 {:name                   "rand-name"
                                     :creator_id             (user->id :crowberto)
                                     :public_perms           2
                                     :display                "table"
                                     :dataset_query          {}
                                     :visualization_settings {}}]]
  [{:cnt          1
    :user_id      (user->id :crowberto)
    :model        "card"
    :model_id     (:id card1)
    :model_object {:id          (:id card1)
                   :name        (:name card1)
                   :description (:description card1)
                   :display     (name (:display card1))}}
   {:cnt          1
    :user_id      (user->id :crowberto)
    :model        "dashboard"
    :model_id     (:id dash1)
    :model_object {:id          (:id dash1)
                   :name        (:name dash1)
                   :description (:description dash1)}}
   {:cnt          1
    :user_id      (user->id :crowberto)
    :model        "card"
    :model_id     (:id card2)
    :model_object {:id          (:id card2)
                   :name        (:name card2)
                   :description (:description card2)
                   :display     (name (:display card2))}}]
  (do
    (create-view! (user->id :crowberto) "card"      (:id card2))
    (create-view! (user->id :crowberto) "dashboard" (:id dash1))
    (create-view! (user->id :crowberto) "card"      (:id card1))
    (create-view! (user->id :crowberto) "card"      36478)
    (create-view! (user->id :rasta)     "card"      (:id card1))
    (for [recent-view ((user->client :crowberto) :get 200 "activity/recent_views")]
      (dissoc recent-view :max_ts))))


;;; activities->referenced-objects, referenced-objects->existing-objects, add-model-exists-info

(resolve-private-fns metabase.api.activity activities->referenced-objects referenced-objects->existing-objects add-model-exists-info)

(def ^:private ^:const fake-activities
  [{:model "dashboard", :model_id  43, :topic :dashboard-create,    :details {}}
   {:model "dashboard", :model_id  42, :topic :dashboard-create,    :details {}}
   {:model "card",      :model_id 114, :topic :card-create,         :details {}}
   {:model "card",      :model_id 113, :topic :card-create,         :details {}}
   {:model "card",      :model_id 112, :topic :card-create,         :details {}}
   {:model "card",      :model_id 111, :topic :card-create,         :details {}}
   {:model "dashboard", :model_id  41, :topic :dashboard-add-cards, :details {:dashcards [{:card_id 109}]}}
   {:model "card",      :model_id 109, :topic :card-create,         :details {}}
   {:model "dashboard", :model_id  41, :topic :dashboard-add-cards, :details {:dashcards [{:card_id 108}]}}
   {:model "dashboard", :model_id  41, :topic :dashboard-create,    :details {}}
   {:model "card",      :model_id 108, :topic :card-create,         :details {}}
   {:model "user",      :model_id  90, :topic :user-joined,         :details {}}
   {:model nil,         :model_id nil, :topic :install,             :details {}}])

(expect
  {"dashboard" #{41 43 42}
   "card"      #{113 108 109 111 112 114}
   "user"      #{90}}
  (activities->referenced-objects fake-activities))


(expect-with-temp [Dashboard [{dashboard-id :id}]]
  {"dashboard" #{dashboard-id}, "card" nil}
  (referenced-objects->existing-objects {"dashboard" #{dashboard-id 0}
                                         "card"      #{0}}))


(expect-with-temp [Dashboard [{dashboard-id :id}]
                   Card      [{card-id :id}]]
  [{:model "dashboard", :model_id dashboard-id, :model_exists true}
   {:model "card",      :model_id 0,            :model_exists false}
   {:model "dashboard", :model_id 0,            :model_exists false, :topic :dashboard-remove-cards, :details {:dashcards [{:card_id card-id, :exists true}
                                                                                                                           {:card_id 0,       :exists false}]}}]
  (add-model-exists-info [{:model "dashboard", :model_id dashboard-id}
                          {:model "card",      :model_id 0}
                          {:model "dashboard", :model_id 0, :topic :dashboard-remove-cards, :details {:dashcards [{:card_id card-id}
                                                                                                                  {:card_id 0}]}}]))
