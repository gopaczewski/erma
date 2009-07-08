package com.orbitz.monitoring.api;

/**
 * Description of Interface.
 * <p/>
 * <p>(c) 2000-06 Orbitz, LLC. All Rights Reserved.</p>
 */
public interface MonitorThrottle {

    public static final String THROTTLE_MONITOR_NAME = MonitorThrottle.class.getName();
    public static final String TOO_MANY_UNIQUE_NAMES = "alarm.monitoring.api.TooManyUniqueNames";

    boolean shouldAllow(Monitor monitor);

    void enable();

    void disable();

    void reset();

    int getTotalThrottledCount();

    boolean isIdempotent();
}
