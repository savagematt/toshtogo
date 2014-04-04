(ns toshtogo.test.server.api.sql-contracts-test
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [toshtogo.server.util.middleware :refer [sql-deps]]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.server.api.util :refer [job-req]]
            [toshtogo.server.core :refer [dev-db]]
            [toshtogo.client.util :as util]
            [toshtogo.server.agents.protocol :refer :all]
            [toshtogo.server.persistence.protocol :refer :all]
            [toshtogo.server.api :refer :all]))

(def agent-details (util/agent-details "savagematt" "toshtogo"))

(defn given-job-exists [persistence id job-type & deps]
  (new-job! persistence agent-details (job-req id {:some-data (uuid)} job-type :dependencies deps)))

(defn given-job-succeeded [persistence job-id]
  (let [job      (get-job persistence job-id)
        contract (request-work! persistence (uuid) {:job_type (job :job_type)} agent-details)]
    (when (not= (contract :job_id) job-id)
      (throw (RuntimeException. "More than one job exists for tags")))

    (complete-work! persistence (contract :commitment_id) (success {:response "success"}) agent-details)))

(fact "Gets contracts by filters "
  (sql/with-db-transaction
   [cnxn dev-db]
   (let [id-one                         (uuid)
         id-two                         (uuid)
         job-type-one                        (uuid-str) ;so we can run against a dirty database
         job-type-two                        (uuid-str)
         {:keys [agents persistence]} (sql-deps cnxn)]

     (given-job-exists persistence id-two job-type-two)
     (given-job-exists persistence id-one job-type-one)

     (get-contracts persistence {:outcome :waiting :job_type job-type-one})
     => (contains (contains {:job_id id-one})))))

(facts "New contracts check for the state of old contracts"
  (sql/with-db-transaction
   [cnxn dev-db]
   (let [job-id    (uuid)
         job-type   (uuid-str)        ;so we can run against a dirty database
         {:keys [agents persistence]} (sql-deps cnxn)]

     (given-job-exists persistence job-id job-type)

     (fact "Can't create contract when job is in progress"
       (new-contract! persistence (contract-req job-id))
       => (throws IllegalStateException
                  (str "Job " job-id " has an unfinished contract. Can't create a new one.")))

     (fact "Can't create contract when job has succeeded"
       (given-job-succeeded persistence job-id)

       (new-contract! persistence (contract-req job-id))
       => (throws IllegalStateException
                  (str "Job " job-id " has been completed. Can't create further contracts"))))))

(future-fact "If there is no work to do, no work is handed out")
