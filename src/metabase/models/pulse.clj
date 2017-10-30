(ns metabase.models.pulse
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [medley.core :as m]
            [metabase
             [db :as mdb]
             [events :as events]
             [util :as u]]
            [metabase.api.common :refer [*current-user*]]
            [metabase.models
             [card :refer [Card]]
             [interface :as i]
             [pulse-card :refer [PulseCard]]
             [pulse-channel :as pulse-channel :refer [PulseChannel]]
             [pulse-channel-recipient :refer [PulseChannelRecipient]]]
            [toucan
             [db :as db]
             [hydrate :refer [hydrate]]
             [models :as models]]))

;;; ------------------------------------------------------------ Perms Checking ------------------------------------------------------------

(defn- perms-objects-set [pulse read-or-write]
  (set (when-let [card-ids (db/select-field :card_id PulseCard, :pulse_id (u/get-id pulse))]
         (apply set/union (for [card (db/select [Card :dataset_query], :id [:in card-ids])]
                            (i/perms-objects-set card read-or-write))))))

(defn- channels-with-recipients
  "Get the 'channels' associated with this PULSE, including recipients of those 'channels'.
   If `:channels` is already hydrated, as it will be when using `retrieve-pulses`, this doesn't need to make any DB calls."
  [pulse]
  (or (:channels pulse)
      (-> (db/select PulseChannel, :pulse_id (u/get-id pulse))
          (hydrate :recipients))))

(defn- emails
  "Get the set of emails this PULSE will be sent to."
  [pulse]
  (set (for [channel   (channels-with-recipients pulse)
             recipient (:recipients channel)]
         (:email recipient))))

(defn- can-read? [pulse]
  (or (i/current-user-has-full-permissions? :read pulse)
      (contains? (emails pulse) (:email @*current-user*))))


;;; ------------------------------------------------------------ Entity & Lifecycle ------------------------------------------------------------

(models/defmodel Pulse :pulse)

(defn- pre-delete [{:keys [id]}]
  (db/delete! PulseCard :pulse_id id)
  (db/delete! PulseChannel :pulse_id id))

(u/strict-extend (class Pulse)
  models/IModel
  (merge models/IModelDefaults
         {:hydration-keys (constantly [:pulse])
          :properties     (constantly {:timestamped? true})
          :pre-delete     pre-delete})
  i/IObjectPermissions
  (merge i/IObjectPermissionsDefaults
         {:perms-objects-set  perms-objects-set
          ;; I'm not 100% sure this covers everything. If a user is subscribed to a pulse they're still allowed to know it exists, right?
          :can-read?          can-read?
          :can-write?         (partial i/current-user-has-full-permissions? :write)}))


;;; ------------------------------------------------------------ Hydration ------------------------------------------------------------

(defn ^:hydrate channels
  "Return the `PulseChannels` associated with this PULSE."
  [{:keys [id]}]
  (db/select PulseChannel, :pulse_id id))


(defn ^:hydrate cards
  "Return the `Cards` associated with this PULSE."
  [{:keys [id]}]
  (db/select [Card :id :name :description :display]
    :archived false
    (mdb/join [Card :id] [PulseCard :card_id])
    (db/qualify PulseCard :pulse_id) id
    {:order-by [[(db/qualify PulseCard :position) :asc]]}))


;;; ------------------------------------------------------------ Pulse Fetching Helper Fns ------------------------------------------------------------

(defn- hydrate-pulse [pulse]
  (hydrate pulse :creator :cards [:channels :recipients]))

(defn- remove-alert-fields [pulse]
  (dissoc pulse :alert_condition :alert_description :alert_above_goal :alert_first_only))

(defn retrieve-pulse
  "Fetch a single `Pulse` by its ID value."
  [id]
  {:pre [(integer? id)]}
  (-> (db/select-one Pulse {:where [:and
                                    [:= :id id]
                                    [:= :alert_condition nil]]})
      hydrate-pulse
      remove-alert-fields
      (m/dissoc-in [:details :emails])))

(defn retrieve-pulse-or-alert
  "Fetch a single `Pulse` by its ID value."
  [id]
  {:pre [(integer? id)]}
  (-> (db/select-one Pulse {:where [:= :id id]})
      hydrate-pulse
      (m/dissoc-in [:details :emails])))

(defn pulse->alert
  "Convert a pulse to an alert"
  [pulse]
  (-> pulse
      (assoc :card (first (:cards pulse)))
      (dissoc :cards)))

(defn retrieve-alert
  "Fetch a single `Alert` by its ID value."
  [id]
  {:pre [(integer? id)]}
  (-> (db/select-one Pulse {:where [:and
                                    [:= :id id]
                                    [:not= :alert_condition nil]]})
      hydrate-pulse
      pulse->alert
      (m/dissoc-in [:details :emails])))

(defn retrieve-alerts
  "Fetch a single `Alert` by its ID value."
  []
  (for [pulse (hydrate-pulse (db/select Pulse, {:where [:not= :alert_condition nil]
                                                :order-by [[:name :asc]]}))]

    (-> pulse
        pulse->alert
        (m/dissoc-in [:details :emails]))))

(defn retrieve-pulses
  "Fetch all `Pulses`."
  []
  (for [pulse (hydrate-pulse (db/select Pulse, {:where [:= :alert_condition nil]
                                                :order-by [[:name :asc]]} ))]
    (-> pulse
        remove-alert-fields
        (m/dissoc-in [:details :emails]))))

(defn retrieve-alerts-for-card
  [card-id user-id]
  (map (comp pulse->alert hydrate-pulse #(into (PulseInstance.) %))
       (db/query {:select    [:p.*]
                  :from      [[Pulse :p]]
                  :join      [[PulseCard :pc] [:= :p.id :pc.pulse_id]
                              [PulseChannel :pchan] [:= :p.id :pc.pulse_id]
                              [PulseChannelRecipient :pcr] [:= :pchan.id :pcr.pulse_channel_id]]
                  :where     [:and
                              [:not= :p.alert_condition nil]
                              [:= :pc.card_id card-id]
                              [:or [:= :p.creator_id user-id]
                               [:= :pcr.user_id user-id]]]})))

;;; ------------------------------------------------------------ Other Persistence Functions ------------------------------------------------------------

(defn update-pulse-cards!
  "Update the `PulseCards` for a given PULSE.
   CARD-IDS should be a definitive collection of *all* IDs of cards for the pulse in the desired order.

   *  If an ID in CARD-IDS has no corresponding existing `PulseCard` object, one will be created.
   *  If an existing `PulseCard` has no corresponding ID in CARD-IDs, it will be deleted.
   *  All cards will be updated with a `position` according to their place in the collection of CARD-IDS"
  {:arglists '([pulse card-ids])}
  [{:keys [id]} card-ids]
  {:pre [(integer? id)
         (sequential? card-ids)
         (every? integer? card-ids)]}
  ;; first off, just delete any cards associated with this pulse (we add them again below)
  (db/delete! PulseCard :pulse_id id)
  ;; now just insert all of the cards that were given to us
  (when (seq card-ids)
    (let [cards (map-indexed (fn [i card-id] {:pulse_id id, :card_id card-id, :position i}) card-ids)]
      (db/insert-many! PulseCard cards))))


(defn- create-update-delete-channel!
  "Utility function which determines how to properly update a single pulse channel."
  [pulse-id new-channel existing-channel]
  ;; NOTE that we force the :id of the channel being updated to the :id we *know* from our
  ;;      existing list of `PulseChannels` pulled from the db to ensure we affect the right record
  (let [channel (when new-channel (assoc new-channel
                                    :pulse_id       pulse-id
                                    :id             (:id existing-channel)
                                    :channel_type   (keyword (:channel_type new-channel))
                                    :schedule_type  (keyword (:schedule_type new-channel))
                                    :schedule_frame (keyword (:schedule_frame new-channel))))]
    (cond
      ;; 1. in channels, NOT in db-channels = CREATE
      (and channel (not existing-channel))  (pulse-channel/create-pulse-channel! channel)
      ;; 2. NOT in channels, in db-channels = DELETE
      (and (nil? channel) existing-channel) (db/delete! PulseChannel :id (:id existing-channel))
      ;; 3. in channels, in db-channels = UPDATE
      (and channel existing-channel)        (pulse-channel/update-pulse-channel! channel)
      ;; 4. NOT in channels, NOT in db-channels = NO-OP
      :else nil)))

(defn update-pulse-channels!
  "Update the `PulseChannels` for a given PULSE.
   CHANNELS should be a definitive collection of *all* of the channels for the the pulse.

   * If a channel in the list has no existing `PulseChannel` object, one will be created.
   * If an existing `PulseChannel` has no corresponding entry in CHANNELS, it will be deleted.
   * All previously existing channels will be updated with their most recent information."
  {:arglists '([pulse channels])}
  [{:keys [id]} channels]
  {:pre [(integer? id)
         (coll? channels)
         (every? map? channels)]}
  (let [new-channels   (group-by (comp keyword :channel_type) channels)
        old-channels   (group-by (comp keyword :channel_type) (db/select PulseChannel :pulse_id id))
        handle-channel #(create-update-delete-channel! id (first (get new-channels %)) (first (get old-channels %)))]
    (assert (zero? (count (get new-channels nil)))
      "Cannot have channels without a :channel_type attribute")
    ;; for each of our possible channel types call our handler function
    (doseq [[channel-type] pulse-channel/channel-types]
      (handle-channel channel-type))))

(defn- create-notification [pulse card-ids channels alert? ]
  (db/transaction
    (let [{:keys [id] :as pulse} (db/insert! Pulse pulse)]
      ;; add card-ids to the Pulse
      (update-pulse-cards! pulse card-ids)
      ;; add channels to the Pulse
      (update-pulse-channels! pulse channels)
      ;; return the full Pulse (and record our create event)
      (events/publish-event! :pulse-create (if alert?
                                             (retrieve-alert id)
                                             (retrieve-pulse id))))))


(defn create-pulse!
  "Create a new `Pulse` by inserting it into the database along with all associated pieces of data such as:
  `PulseCards`, `PulseChannels`, and `PulseChannelRecipients`.

   Returns the newly created `Pulse` or throws an Exception."
  [pulse-name creator-id card-ids channels skip-if-empty?]
  {:pre [(string? pulse-name)
         (integer? creator-id)
         (sequential? card-ids)
         (seq card-ids)
         (every? integer? card-ids)
         (coll? channels)
         (every? map? channels)]}
  (create-notification {:creator_id    creator-id
                        :name          pulse-name
                        :skip_if_empty skip-if-empty?}
                       card-ids channels false))

(defn create-alert!
  "Creates a pulse with the correct fields specified for an alert"
  [alert creator-id card-id channels]
  (-> alert
      (assoc :skip_if_empty true :creator_id creator-id)
      (create-notification [card-id] channels true)))

(defn update-notification!
  "Updates the pulse/alert and updates the related channels"
  [{:keys [id name cards channels skip-if-empty?] :as pulse}]
  (db/transaction
    ;; update the pulse itself
    (db/update! Pulse id, :name name, :skip_if_empty skip-if-empty?)
    ;; update cards (only if they changed). Order for the cards is important which is why we're not using select-field
    (when (not= cards (map :card_id (db/select [PulseCard :card_id], :pulse_id id, {:order-by [[:position :asc]]})))
      (update-pulse-cards! pulse cards))
    ;; update channels
    (update-pulse-channels! pulse channels)))

(defn update-pulse!
  "Update an existing `Pulse`, including all associated data such as: `PulseCards`, `PulseChannels`, and `PulseChannelRecipients`.

   Returns the updated `Pulse` or throws an Exception."
  [{:keys [id name cards channels skip-if-empty?] :as pulse}]
  {:pre [(integer? id)
         (string? name)
         (sequential? cards)
         (> (count cards) 0)
         (every? integer? cards)
         (coll? channels)
         (every? map? channels)]}
  (update-notification! pulse)
  ;; fetch the fully updated pulse and return it (and fire off an event)
  (->> (retrieve-pulse id)
       (events/publish-event! :pulse-update)))

(defn update-alert!
  "Updates the given `ALERT` and returns it"
  [{:keys [id card] :as alert}]
  (-> alert
      (assoc :skip-if-empty? true :cards [card])
      (dissoc :card)
      update-notification!)
  ;; fetch the fully updated pulse and return it (and fire off an event)
  (->> (retrieve-alert id)
       (events/publish-event! :pulse-update)))

(defn unsubscribe-from-alert
  "Removes `USER-ID` from `PULSE-ID`"
  [pulse-id user-id]
  (let [[result] (db/execute! {:delete-from PulseChannelRecipient
                               :where [:= :id {:select [:pcr.id]
                                               :from [[PulseChannelRecipient :pcr]]
                                               :join [[PulseChannel :pchan] [:= :pchan.id :pcr.pulse_channel_id]
                                                      [Pulse :p] [:= :p.id :pchan.pulse_id]]
                                               :where [:and
                                                       [:= :p.id pulse-id]
                                                       [:not= :p.alert_condition nil]
                                                       [:= :pcr.user_id user-id]]}]})]
    (when (zero? result)
      (log/warnf "Failed to remove user-id '%s' from pulse-id '%s'" user-id pulse-id))

    result))
