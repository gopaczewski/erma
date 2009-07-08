package com.orbitz.monitoring.lib;

import com.orbitz.monitoring.api.monitor.EventMonitor;
import com.orbitz.monitoring.api.MonitorThrottle;
import com.orbitz.monitoring.api.Monitor;
import com.orbitz.monitoring.api.MonitoringLevel;
import edu.emory.mathcs.backport.java.util.concurrent.ConcurrentHashMap;
import edu.emory.mathcs.backport.java.util.concurrent.ScheduledThreadPoolExecutor;
import edu.emory.mathcs.backport.java.util.concurrent.TimeUnit;
//import edu.emory.mathcs.backport.java.util.concurrent.atomic.AtomicInteger;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.log4j.Logger;

/**
 * Implements monitor throttling by applying a limit on the number of monitor instances
 * that can be processed per configured time window.  All monitor instances are counted
 * per unique monitor name, regardless of the throttling result.
 *
 * @author gopaczewski
 */
public class TimeWindowMonitorThrottle implements MonitorThrottle {
    private static final Logger log = Logger.getLogger(TimeWindowMonitorThrottle.class);

    private static final int DEFAULT_TIME_WINDOW_SECONDS = 60;
    private static final int DEFAULT_UNIQUE_MONITOR_NAME_LIMIT = 10000;
    private static final int DEFAULT_UNIQUE_MONITOR_NAME_PER_WINDOW_LIMIT = 600;

    private final String THROTTLE_RESULT_ATTRIBUTE =
            "TimeWindowMonitorThrottle_" + this.hashCode() + "_allowed";

    private boolean enabled = true;
    private boolean allowEssentialLevelOnly = false;
    private AtomicInteger uniqueOverflowCount = new AtomicInteger(0);
    private AtomicInteger totalThrottledCount = new AtomicInteger(0);

    private int timeWindowSizeSeconds;
    private int uniqueMonitorNameLimit;
    private int uniqueMonitorNamePerWindowLimit;

    private Map monitorMap;
    private ScheduledThreadPoolExecutor windowExecutor;

    /**
     * Default constructor.
     */
    public TimeWindowMonitorThrottle() {
        this(DEFAULT_TIME_WINDOW_SECONDS,
                DEFAULT_UNIQUE_MONITOR_NAME_LIMIT,
                DEFAULT_UNIQUE_MONITOR_NAME_PER_WINDOW_LIMIT);
    }

    /**
     * Constructor.
     *
     * @param timeWindowSizeSeconds size of time window in seconds
     * @param uniqueMonitorNameLimit number of keys allowed in monitor name map
     * @param uniqueMonitorNamePerWindowLimit throttle limit for each monitor name
     */
    public TimeWindowMonitorThrottle(int timeWindowSizeSeconds, int uniqueMonitorNameLimit,
                int uniqueMonitorNamePerWindowLimit) {
        if (timeWindowSizeSeconds < 1) {
            throw new IllegalArgumentException("timeWindowSizeSeconds=" + timeWindowSizeSeconds +
                    ": window size must be at least 1 second");
        }

        if (uniqueMonitorNameLimit < 1) {
            throw new IllegalArgumentException("uniqueMonitorNameLimit=" + uniqueMonitorNameLimit +
                    ": must allow at least 1 unique monitor name");
        }

        if (uniqueMonitorNamePerWindowLimit < 1) {
            throw new IllegalArgumentException("uniqueMonitorNamePerWindowLimit=" +
                    uniqueMonitorNamePerWindowLimit + ": invalid limit, must be at least 1");
        }

        this.timeWindowSizeSeconds = timeWindowSizeSeconds;
        this.uniqueMonitorNameLimit = uniqueMonitorNameLimit;
        this.uniqueMonitorNamePerWindowLimit = uniqueMonitorNamePerWindowLimit;

        this.monitorMap = new ConcurrentHashMap(uniqueMonitorNameLimit);

        startWindowManager();
    }

    /**
     * Returns false if the given monitor should be throttled.  Each time a <b>unique</b>
     * monitor instance is throttle-checked, internal counters are updated for tracking purposes.
     * This method is idempotent - all invocations for the same monitor instance are guaranteed
     * to return the same result.  (Note this is true only for the same instance of this object
     * and monitor instance.
     *
     * A monitor is throttled if the following are true:
     * <ol>
     * <li>The enabled flag is set to true.</li>
     * AND one or more of the following are also true:
     * <li>The monitor instance was previously throttled by this MonitorThrottle instance.</li>
     * OR
     * <li>The monitor level is lower priority than a set monitoring level</li>
     * OR
     * <li>The monitor name exceeds the uniqueMonitorNameLimit</li>
     * OR
     * <li>The monitor name has been seen more than uniqueMonitorNamePerWindowLimit times
     *          in the current window</li>
     *
     * @param monitor instance to throttle check
     * @return false if the monitor instance should be throttled
     */
    public boolean shouldAllow(Monitor monitor) {
        if (! enabled) return true;

        if (throttleHasTagged(monitor)) {
            return previousThrottleResultFor(monitor);
        }

        boolean allow = allowCheckWithSideEffects(monitor);
        postThrottleCheck(monitor, allow);

        return allow;
    }

    private void postThrottleCheck(Monitor monitor, boolean allow) {
        if (! allow) {
            totalThrottledCount.incrementAndGet();
        }
        monitor.set(THROTTLE_RESULT_ATTRIBUTE, allow);
    }

    private boolean throttleHasTagged(Monitor monitor) {
        return monitor.hasAttribute(this.THROTTLE_RESULT_ATTRIBUTE);
    }

    private boolean previousThrottleResultFor(Monitor monitor) {
        return monitor.getAsBoolean(this.THROTTLE_RESULT_ATTRIBUTE);
    }

    private boolean allowCheckWithSideEffects(Monitor monitor) {

        if (disallowByMonitorLevel(monitor)) return false;

        String name = monitor.getAsString(Monitor.NAME);
        name = (name == null) ? "<null>" : name;

        boolean allow;
        if (monitorMap.containsKey(name)) {
            AtomicInteger aCount = (AtomicInteger) monitorMap.get(name);
            int count = aCount.incrementAndGet();
            allow = (count <= uniqueMonitorNamePerWindowLimit);
        } else {
            allow = allowNewMonitor(name);
            if (allow) {
                monitorMap.put(name, new AtomicInteger(1));
            }
        }

        return allow;
    }

    private boolean disallowByMonitorLevel(Monitor monitor) {
        boolean disallow = (allowEssentialLevelOnly &&
                (! monitor.getLevel().hasHigherOrEqualPriorityThan(MonitoringLevel.ESSENTIAL)));

        if (disallow && log.isDebugEnabled()) {
            log.debug("Monitor discarded due to level: " + monitor.get(Monitor.NAME));
        }

        return disallow;
    }

    private boolean allowNewMonitor(String name) {
        int snapshotMapSize = monitorMap.size();
        boolean tooManyUniqueNames = (snapshotMapSize >= uniqueMonitorNameLimit);
        if (tooManyUniqueNames) {
            if (log.isDebugEnabled()) {
                log.debug("Unique count=" + snapshotMapSize + ".  Dropping monitor name: " + name);
            }
            uniqueOverflowCount.incrementAndGet();
        }

        return (! tooManyUniqueNames);
    }

    /**
     * Enable throttling logic.
     */
    public void enable() {
        enabled = true;
    }

    /**
     * Disable throttling logic (allow all).
     */
    public void disable() {
        enabled = false;
    }

    /**
     * Passing the same monitor instance to shouldAllow(monitor) more than once may produce
     * a different result each time.
     *
     * @return false
     */
    public boolean isIdempotent() {
        return false;
    }

    /**
     * Resets all internal state used to make throttling decisions.
     */
    public void reset() {
        clearAllCounts();
        allowEssentialLevelOnly = false;
        enabled = true;
    }

    private void clearAllCounts() {
        monitorMap.clear();
        uniqueOverflowCount.set(0);
        totalThrottledCount.set(0);
    }

    public void shutdown() {
        windowExecutor.shutdownNow();
        windowExecutor = null;
    }

    public int getTimeWindowSizeSeconds() {
        return timeWindowSizeSeconds;
    }

    public void setTimeWindowSizeSeconds(int timeWindowSizeSeconds) {
        this.timeWindowSizeSeconds = timeWindowSizeSeconds;
    }

    public int getUniqueMonitorNameLimit() {
        return uniqueMonitorNameLimit;
    }

    public void setUniqueMonitorNameLimit(int uniqueMonitorNameLimit) {
        this.uniqueMonitorNameLimit = uniqueMonitorNameLimit;
    }

    public int getUniqueMonitorNamePerWindowLimit() {
        return uniqueMonitorNamePerWindowLimit;
    }

    public void setUniqueMonitorNamePerWindowLimit(int uniqueMonitorNamePerWindowLimit) {
        this.uniqueMonitorNamePerWindowLimit = uniqueMonitorNamePerWindowLimit;
    }

    public int getTotalThrottledCount() {
        return totalThrottledCount.get();
    }

    private void startWindowManager() {
        windowExecutor = new ScheduledThreadPoolExecutor(1);
        windowExecutor.scheduleAtFixedRate(new WindowManagerRunnable(), 0, timeWindowSizeSeconds,
                TimeUnit.SECONDS);
    }

    private class WindowManagerRunnable implements Runnable {

        public void run() {
            resetMonitorCounts();
            checkUniqueCount();
        }

        private void resetMonitorCounts() {
            Iterator entries = monitorMap.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry entry = (Map.Entry) entries.next();
                String monitorName = (String) entry.getKey();

                // get current count and reset to zero atomically
                int count = ((AtomicInteger) entry.getValue()).getAndSet(0);

                if (count > uniqueMonitorNamePerWindowLimit) {
                    if (log.isDebugEnabled()) {
                        log.debug("Reached limit of " + uniqueMonitorNamePerWindowLimit +
                                " for monitor name: " + monitorName);
                    }

                    EventMonitor m = new EventMonitor(MonitorThrottle.THROTTLE_MONITOR_NAME);
                    m.set("throttledMonitorName", monitorName);
                    m.set("throttledCount", (count - uniqueMonitorNamePerWindowLimit));
                    m.set("totalThrottledCount", totalThrottledCount.get());
                    m.fire();
                }
            }
        }

        private void checkUniqueCount() {
            int count = uniqueOverflowCount.get();
            if (count != 0) {
                EventMonitor m = new EventMonitor(MonitorThrottle.TOO_MANY_UNIQUE_NAMES);
                m.set("uniqueOverflowCount", count);
                m.set("essentialOnly", allowEssentialLevelOnly);
                m.fire();

                // reset all counters and raise the monitoring level we will throttle to, iff
                // it hasn't already been attempted once
                if (! allowEssentialLevelOnly) {
                    allowEssentialLevelOnly = true;
                    clearAllCounts();
                    log.warn("ERMA MonitoringLevel raised to ESSENTIAL - too many unique monitors.");
                } else {
                    log.warn("ERMA throttling to ESSENTIAL - too many unique.  Instrumentation patch needed.");
                }
            }
        }
    }
}
