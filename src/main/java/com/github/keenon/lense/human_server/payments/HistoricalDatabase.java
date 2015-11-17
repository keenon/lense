package com.github.keenon.lense.human_server.payments;

import com.github.keenon.lense.human_server.HumanSourceServer;

import java.io.Serializable;
import java.util.*;

/**
 * Created by keenon on 10/13/15.
 *
 * This provides a nice, synchronous, locked database that handles recording the behavior of workers. This can then be
 * used as the basis of payment or bonus systems.
 */
public class HistoricalDatabase {
    private static Map<String,HumanRecord> humans = new HashMap<>();

    public static class HumanRecord implements Serializable {
        List<JobRecord> jobs = new ArrayList<>();
        List<PaymentRecord> payments = new ArrayList<>();

        /**
         * This will find or create a job record that this human could be currently working on
         * @return a job record that the human is currently working on
         */
        public JobRecord getCurrentJobRecord() {
            // This means there is no currently open job record, so we need to create one
            if (jobs.size() == 0 || jobs.get(jobs.size()-1).endTime != -1) {
                JobRecord jr = new JobRecord();
                jobs.add(jr);
                return jr;
            }
            // Otherwise we can just return this one
            else {
                return jobs.get(jobs.size()-1);
            }
        }

        /**
         * This is a convenient shorthand for returning the last time we paid someone.
         * @return the last time we paid this person
         */
        public long lastPaymentTime() {
            if (payments.size() == 0) {
                return -1;
            }
            else {
                return payments.get(payments.size()-1).time;
            }
        }

        /**
         * If a job has been paid, find the corresponding PaymentRecord, and return true. Otherwise false.
         *
         * @param jobRecord the job record to inquire about
         * @return whether it's been paid
         */
        public boolean hasBeenPaid(JobRecord jobRecord) {
            for (PaymentRecord payment : payments) {
                if (payment.jobRecord == jobRecord) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Creates a record that the given job got paid just now.
         *
         * @param jobRecord the job record that was just paid off
         * @param amount the amount that the job record received
         */
        public void createPaymentRecord(JobRecord jobRecord, double amount) {
            PaymentRecord pr = new PaymentRecord();
            pr.jobRecord = jobRecord;
            pr.quantity = amount;
            pr.time = System.currentTimeMillis();
            payments.add(pr);
        }
    }

    public static class PaymentRecord implements Serializable {
        public long time = -1;
        public double quantity = 0.0;
        public JobRecord jobRecord = null;
    }

    public static class JobRecord implements Serializable {
        public long startTime = -1;
        public long endTime = -1;
        public String workerID = "";
        public String assignmentID = "";
        public List<Long> queryReturnTimes = new ArrayList<>();
    }

    public static Collection<HumanRecord> getAllHumanRecords() {
        return humans.values();
    }

    /**
     * Gets or creates a record for a worker matching this ID
     * @param workerID the unique ID of the worker
     * @return the record for that worker
     */
    private static HumanRecord getHumanRecord(String workerID) {
        if (!humans.containsKey(workerID)) {
            humans.put(workerID, new HumanRecord());
        }
        return humans.get(workerID);
    }

    /**
     * This records that a worker started work at the current system clock time.
     *
     * @param workerID the unique identifier for the worker
     * @param assignmentID the unique identifier for the worker's session, so that it can be recovered later for payments, etc
     */
    public static void startedWork(String workerID, String assignmentID) {
        synchronized (HumanSourceServer.currentInstance) {
            JobRecord jr = getHumanRecord(workerID).getCurrentJobRecord();
            assert(jr.startTime == -1);
            jr.startTime = System.currentTimeMillis();
            jr.workerID = workerID;
            jr.assignmentID = assignmentID;
        }
    }

    /**
     * This records that a worker ended work at the current system clock time.
     *
     * @param workerID the unique identifier for the worker
     */
    public static void endedWork(String workerID) {
        synchronized (HumanSourceServer.currentInstance) {
            JobRecord jr = getHumanRecord(workerID).getCurrentJobRecord();
            assert(jr.startTime != -1);
            assert(jr.endTime == -1);
            jr.endTime = System.currentTimeMillis();
        }
    }

    /**
     * This records the given worker did a single task.
     *
     * @param workerID the unique identifier for the worker
     */
    public static void didTask(String workerID) {
        synchronized (HumanSourceServer.currentInstance) {
            getHumanRecord(workerID).getCurrentJobRecord().queryReturnTimes.add(System.currentTimeMillis());
        }
    }

    /**
     * Gets the number of tasks performed in this session.
     *
     * @param workerID the unique identifier for the worker
     */
    public static int numberOfTasksThisWorkUnit(String workerID) {
        synchronized (HumanSourceServer.currentInstance) {
            return getHumanRecord(workerID).jobs.stream().mapToInt(jobRecord -> jobRecord.queryReturnTimes.size()).sum();
        }
    }
}
