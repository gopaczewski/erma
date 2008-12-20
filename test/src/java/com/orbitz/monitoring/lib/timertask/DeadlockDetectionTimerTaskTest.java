package com.orbitz.monitoring.lib.timertask;

import com.orbitz.monitoring.api.Monitor;
import com.orbitz.monitoring.api.MonitoringEngine;
import com.orbitz.monitoring.lib.BaseMonitoringEngineManager;
import com.orbitz.monitoring.test.MockDecomposer;
import com.orbitz.monitoring.test.MockMonitorProcessor;
import com.orbitz.monitoring.test.MockMonitorProcessorFactory;
import junit.framework.TestCase;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.lang.management.ManagementFactory;

/**
 * Created by IntelliJ IDEA.
 * User: mkemp
 * Date: Dec 2, 2008
 * Time: 10:56:46 AM
 * To change this template use File | Settings | File Templates.
 */
public class DeadlockDetectionTimerTaskTest extends TestCase {

    private DeadlockDetectionTimerTask task;
    private MockMonitorProcessor processor;

    protected void setUp() throws Exception {
        super.setUp();

        task = new DeadlockDetectionTimerTask();
        processor = new MockMonitorProcessor();

        MockMonitorProcessorFactory mockMonitorProcessorFactory =
                new MockMonitorProcessorFactory(processor);
        MockDecomposer mockDecomposer = new MockDecomposer();
        BaseMonitoringEngineManager monitoringEngineManager =
                new BaseMonitoringEngineManager(mockMonitorProcessorFactory, mockDecomposer);
        monitoringEngineManager.startup();
    }

    public void testNoDeadlock() {
        task.run();
        Monitor[] monitors = processor.extractProcessObjects();
        for (Monitor monitor: monitors) {
            if("JvmStats".equals(monitor.get(Monitor.NAME))) {
                if("Thread.Deadlock".equals(monitor.get("type"))) {
                    fail();
                }
            }
        }
    }

    public void testDeadlock() throws Exception {
        if (! ManagementFactory.getThreadMXBean().isThreadContentionMonitoringSupported()) {
            System.out.println("Skipping test, thread contention monitoring not supported in this vm");
            return;
        }

        DeadlockController controller = new DeadlockController();
        Object obj1 = new Object();
        Object obj2 = new Object();

        Thread thread1 = new Thread(new Deadlocker(controller, obj1, obj2));
        Thread thread2 = new Thread(new Deadlocker(controller, obj2, obj1));

        thread1.start();
        thread2.start();

        controller.waitForTwo();
        while(! thread1.getState().equals(Thread.State.BLOCKED)) {
            Thread.sleep(10);
        }

        while(! thread2.getState().equals(Thread.State.BLOCKED)) {
            Thread.sleep(10);
        }

        task.run();

        Monitor[] monitors = processor.extractProcessObjects();
        boolean deadlockFound = false;
        for (Monitor monitor: monitors) {
            if("JvmStats".equals(monitor.get(Monitor.NAME))) {
                if("Thread.Deadlock".equals(monitor.get("type"))) {
                    assertEquals("Didn't find a count attribute", 1, monitor.getAsInt("count"));
                    deadlockFound = true;
                }
            }
        }
        assertTrue("No deadlock was detected", deadlockFound);
    }

    protected void tearDown() throws Exception {
        MonitoringEngine.getInstance().shutdown();
        processor = null;
        task = null;
        super.tearDown();
    }

    class Deadlocker implements Runnable {

        private Object a;
        private Object b;
        private DeadlockController controller;

        public Deadlocker(DeadlockController controller, Object a, Object b) {
            super();
            this.controller = controller;
            this.a = a;
            this.b = b;
        }

        public void run() {
            synchronized (a) {
                try {
                    controller.iHaveALock();
                    controller.waitForTwo();
                } catch (Exception doNothing) { }
                synchronized (b) {
                    ; // never gonna get here...
                }
            }
        }
    }

    class DeadlockController {
        private CountDownLatch latch = new CountDownLatch(2);

        public void iHaveALock() { latch.countDown(); }

        public void waitForTwo() throws Exception {
            latch.await();
        }
    }
}
