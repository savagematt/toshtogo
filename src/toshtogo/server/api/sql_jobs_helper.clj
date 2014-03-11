(ns toshtogo.server.api.sql-jobs-helper
  (:require [flatland.useful.map :refer [update update-each]]
            [pallet.map-merge :refer [merge-keys]]
            [clj-time.core :refer [now]]
            [clj-time.coerce :as tc]
            [cheshire.core :as json]
            [toshtogo.server.agents.protocol :refer [agent!]]
            [toshtogo.server.api.protocol :refer :all]
            [toshtogo.util.core :refer [uuid debug]]
            [toshtogo.util.sql :as tsql])
  (:import [toshtogo.util OptimisticLockingException]))

(defn job-record [id job-type agent-id body notes]
  {:job_id            id
   :job_type          job-type
   :requesting_agent  agent-id
   :job_created       (now)
   :request_body      (json/generate-string body)
   :notes             notes})

(defn job-sql [params]
  (cond->
    "select
       *

     from
       jobs

     left join
       job_results
       on jobs.job_id = job_results.job_id

     left join
       contracts
       on jobs.job_id = contracts.job_id
       and contracts.contract_number = (
         select max(contract_number)
         from contracts c
         where jobs.job_id = c.job_id)

     left join
       agent_commitments commitments
       on contracts.contract_id = commitments.commitment_contract

     left join
       commitment_outcomes
       on commitments.commitment_id = commitment_outcomes.outcome_id"
    (:get-tags params) (str "   left join job_tags on jobs.job_id = job_tags.job_id
")))

(def depends-on-sql
  "jobs.job_id in (
     select parent_job_id
     from job_dependencies
     where child_job_id = :depends_on_job_id)")

(def dependency-of-sql
  "jobs.job_id in (
     select child_job_id
     from job_dependencies
     where parent_job_id = :dependency_of_job_id)")


(defn collect-tags [job row]
  (if job
    (-> job
        (update :tags #(conj % (row :tag) )))
    (assoc row :tags #{(row :tag)})))

(defn job-outcome [job]
  (if (:outcome job)
    (:outcome job)
    (if (:contract_claimed job)
      :running
      (if (:contract_id job)
          :waiting
          :no-contract))))

(defn fix-job-outcome [job]
  (assoc job :outcome (job-outcome job)))

(defn normalise-job [job]
  (-> job
      (dissoc :tag)
      (dissoc :job_id_2 :job_id_3 :job_id_4 :commitment_contract :outcome_id)
      (update :outcome keyword)
      (fix-job-outcome)
      (update :last_heartbeat #(when % (tc/from-sql-time %)))
      (update-each [:request_body :result_body] #(json/parse-string % keyword))))

(defn fold-in-tags [job-with-tags]
  (reduce collect-tags
          nil
          job-with-tags))

(defn normalise-job-rows
  "job-rows might include joins in to the tags table"
  [job-rows]
  (map (comp normalise-job fold-in-tags)
       (partition-by :job_id job-rows)))

(defn jobs-where-fn [params]
  (reduce
   (fn [[out-params clauses] [k v]]
     (case k
       :job_id
       [(assoc out-params :job_id v)
        (cons "jobs.job_id = :job_id" clauses)]

       :depends_on_job_id
       [(assoc out-params :depends_on_job_id v)
        (cons depends-on-sql clauses)]

       :dependency_of_job_id
       [(assoc out-params :dependency_of_job_id v)
        (cons dependency-of-sql clauses)]

       [(assoc out-params k v) clauses]))
   [{} []]
   params))

(defn incremement-succeeded-dependency-count!
  "This is sufficient to implement optimistic locking when using Postgres.

  We need to revisit if we ever support another database"
  [cnxn job-id]
  (let [prev-count  (:dependencies_succeeded (tsql/query-single
                                               cnxn
                                               "select dependencies_succeeded from jobs where job_id = :job_id"
                                               {:job_id job-id}))]
    (when-not (= 1 (first (tsql/update! cnxn :jobs
                                        {:dependencies_succeeded (inc prev-count)}
                                                 ["dependencies_succeeded = ? and job_id = ?"
                                                  prev-count  job-id])))
      ; In fact this never happens thanks to Postgres's minimum isolation level
      ; locking the job row on update
      (throw (OptimisticLockingException.
              (str "dependencies_succeeded updated in another transaction for job "
                   job-id))))))

(defn commitment-record [commitment-id contract-id agent]
  {:commitment_id       commitment-id
   :commitment_contract contract-id
   :commitment_agent    (agent :agent_id)
   :contract_claimed    (now)})
