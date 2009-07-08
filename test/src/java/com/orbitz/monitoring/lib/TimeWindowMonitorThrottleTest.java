package com.orbitz.monitoring.lib;

import junit.framework.TestCase;
import com.orbitz.monitoring.api.monitor.EventMonitor;
import com.orbitz.monitoring.api.MonitoringEngine;
import com.orbitz.monitoring.api.MonitorProcessorFactory;
import com.orbitz.monitoring.api.MonitorProcessor;
import com.orbitz.monitoring.api.MonitorThrottle;
import com.orbitz.monitoring.api.Monitor;
import com.orbitz.monitoring.api.MonitoringLevel;
import com.orbitz.monitoring.test.MockMonitorProcessor;
import com.orbitz.monitoring.test.MockMonitorProcessorFactory;
import com.orbitz.monitoring.test.MockDecomposer;
import com.orbitz.monitoring.lib.TimeWindowMonitorThrottle;

/**
 * Test cases for TimeWindowMonitorThrottle
 */
public class TimeWindowMonitorThrottleTest extends TestCase {

    private MonitoringEngine engine;
    private MockMonitorProcessor processor;
    private MonitorProcessorFactory factory;

    protected void setUp()
            throws Exception {
        super.setUp();

        processor = new MockMonitorProcessor();

        factory = new MockMonitorProcessorFactory(
                new MonitorProcessor[]{processor});

        engine = MonitoringEngine.getInstance();
        engine.setProcessorFactory(factory);
        engine.setDecomposer(new MockDecomposer());

        engine.restart();
    }

    public void testInvalidWindowSize() {
        try {
            new TimeWindowMonitorThrottle(0, 1, 1);
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void testInvalidUniqueMonitorNameLimit() {
        try {
            new TimeWindowMonitorThrottle(1, 0, 1);
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void testInvalidUniqueMonitorNamePerWindowLimit() {
        try {
            new TimeWindowMonitorThrottle(1, 1, 0);
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void testThrottleNullMonitorName() {
        MonitorThrottle throttle = new TimeWindowMonitorThrottle();
        assertTrue(throttle.shouldAllow(new EventMonitor(null)));
    }

    public void testThrottleMonitorBeforeProcessing() {
        MonitorThrottle throttle = new TimeWindowMonitorThrottle();
        assertTrue(throttle.shouldAllow(new EventMonitor("foo")));
    }

    public void testTooManyUniqueNames() throws Exception {
        // 1 second window, 2 unique monitor names, 100 monitor instances per window
        MonitorThrottle throttle = new TimeWindowMonitorThrottle(1, 2, 100);

        throttle.shouldAllow(new EventMonitor("one"));
        throttle.shouldAllow(new EventMonitor("two"));

        throttle.shouldAllow(new EventMonitor("three"));
        assertEquals(1, throttle.getTotalThrottledCount());

        // poll for indication background thread in throttle has run
        int pollCount = 0;
        while (throttle.getTotalThrottledCount() != 0) {
            Thread.sleep(100);
            if (++pollCount > 20) {
                fail("waited too long for throttle reset");
            }
        }

        // throttling level now set to ESSENTIAL and counters reset...

        Monitor[] monitors = processor.extractProcessObjects();

        Monitor m = monitors[0];
        assertEquals(MonitorThrottle.TOO_MANY_UNIQUE_NAMES, m.get(Monitor.NAME));
        assertEquals(1, m.getAsInt("uniqueOverflowCount"));

        // these two throttled by level
        assertFalse(throttle.shouldAllow(new EventMonitor("four")));
        assertFalse(throttle.shouldAllow(new EventMonitor("five")));

        // now throttle check only ESSENTIAL monitors...

        assertTrue(throttle.shouldAllow(new EventMonitor("six", MonitoringLevel.ESSENTIAL)));
        assertTrue(throttle.shouldAllow(new EventMonitor("seven", MonitoringLevel.ESSENTIAL)));

        // now will exceed max of 2 unique names at ESSENTIAL only level
        assertFalse(throttle.shouldAllow(new EventMonitor("eight", MonitoringLevel.ESSENTIAL)));
        assertFalse(throttle.shouldAllow(new EventMonitor("nine", MonitoringLevel.ESSENTIAL)));
        assertFalse(throttle.shouldAllow(new EventMonitor("ten", MonitoringLevel.INFO)));

        // throttled "four", "five", "eight", "nine", "ten"
        assertEquals(5, throttle.getTotalThrottledCount());

        Thread.sleep(1000);

        monitors = processor.extractProcessObjects();
        m = monitors[0];
        
        assertEquals(MonitorThrottle.TOO_MANY_UNIQUE_NAMES, m.get(Monitor.NAME));
        assertEquals(2, m.getAsInt("uniqueOverflowCount"));
    }

    public void testThrottleThreeSecondWindow() throws Exception {
        // 1 second window, 1 unique monitor name, 100 monitor instances per window
        MonitorThrottle throttle = new TimeWindowMonitorThrottle(1, 1, 100);

        int allowed = 0;
        // push to the edge of the first time window boundary and throttle check 1000 monitors
        Thread.sleep(990);
        for (int i=0; i < 1000; i++) {
            EventMonitor m = new EventMonitor("foo");
            if (throttle.shouldAllow(m))
                allowed++;
        }

        // make sure we wait until window closes to get feedback on number of monitors throttled
        // in the form of an EventMonitor
        Thread.sleep(1000);

        Monitor[] monitors = processor.extractProcessObjects();

        // 1000 monitors fired, at most 100 allowed in first window, 100 in second window
        int throttledSum = sumThrottledCount(monitors);

        assertEquals(throttledSum, throttle.getTotalThrottledCount());
        assertTrue(throttledSum >= 800);
        assertTrue(throttledSum <= 900);

        assertEquals((1000-throttledSum), allowed);
    }

    private int sumThrottledCount(Monitor[] monitors) {
        int count = 0;
        for (Monitor m : monitors) {
            if (EventMonitor.class.isAssignableFrom(m.getClass())) {
                if (MonitorThrottle.THROTTLE_MONITOR_NAME.equals(m.get(Monitor.NAME))) {
                    count += m.getAsInt("throttledCount");
                }
            }
        }
        return count;
    }
}
