package com.github.keenon.lense.human_server.payments;

import com.github.keenon.lense.human_server.HumanSourceServer;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Created by keenon on 10/14/15.
 *
 * This manages the payments system, once created, by taking care of the logistics of synchronizing payments with the
 * HistoricalDatabase.
 */
public class PaymentsThread implements Runnable {
    public static long minimumStayingTime = 30*60*1000; // 30 minutes
    public static double initialBonus = 3.00;
    public static double perUnitBonus = 0.002;

    BiFunction<HistoricalDatabase.JobRecord,Double,Boolean> makePayment;
    boolean running = true;
    Object barrier = new Object();

    Set<HistoricalDatabase.JobRecord> inFlightPaymentAttempts = new HashSet<>();

    public PaymentsThread(BiFunction<HistoricalDatabase.JobRecord,Double,Boolean> makePayment) {
        this.makePayment = makePayment;
    }

    @Override
    public void run() {

        // Make a pass every 1s, while running

        while (running) {
            synchronized (HumanSourceServer.currentInstance) {
                for (HistoricalDatabase.HumanRecord hr : HistoricalDatabase.getAllHumanRecords()) {
                    for (HistoricalDatabase.JobRecord jr : hr.jobs) {
                        // If we haven't been paid yet for this and the job is complete, attempt a payment
                        if (jr.endTime != -1 && !hr.hasBeenPaid(jr)) {
                            if (!inFlightPaymentAttempts.contains(jr)) {
                                // Round to the nearest penny
                                double unroundedAmountOwed = perUnitBonus*jr.queryReturnTimes.size();
                                double amountOwed = (double)Math.round(unroundedAmountOwed*100)/100;
                                // Launch a payment attempt
                                inFlightPaymentAttempts.add(jr);
                                new Thread(() -> {
                                    boolean paymentSuccessful = makePayment.apply(jr, amountOwed);
                                    // Make sure updates to this structure are locked properly
                                    synchronized (HumanSourceServer.currentInstance) {
                                        if (paymentSuccessful) {
                                            hr.createPaymentRecord(jr, amountOwed);
                                        }
                                        inFlightPaymentAttempts.remove(jr);
                                    }
                                }).start();
                            }
                        }
                    }
                }
            }

            // Make an interuptable wait

            synchronized (barrier) {
                try {
                    barrier.wait(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void stop() {
        running = false;
        synchronized (barrier) {
            barrier.notifyAll();
        }
    }
}
