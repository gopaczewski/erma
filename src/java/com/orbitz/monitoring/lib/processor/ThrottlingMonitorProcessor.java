package com.orbitz.monitoring.lib.processor;

import com.orbitz.monitoring.api.Monitor;
import com.orbitz.monitoring.api.MonitorProcessor;
import com.orbitz.monitoring.api.MonitorThrottle;
import org.apache.log4j.Logger;

/**
 * Wraps another MonitorProcessor to perform throttling of monitors passed through
 * to wrapped processor.
 *
 * This processor by default only applies the MonitorThrottle logic to the process step
 * of the Monitor lifecycle - all invocations of monitorCreated and monitorStarted will
 * pass through all monitors by default.
 *
 * @author gopaczewski
 */
public class ThrottlingMonitorProcessor extends MonitorProcessorAdapter {
    private static final Logger log = Logger.getLogger(ThrottlingMonitorProcessor.class);

    private MonitorThrottle throttle;
    private MonitorProcessor delegateProcessor;

    private boolean applyThrottlingAtCreate = false;
    private boolean applyThrottlingAtStart = false;

    /**
     * Default constructor.
     *
     * @param throttle monitor throttle impl
     * @param processor delegate processor
     */
    public ThrottlingMonitorProcessor(MonitorThrottle throttle, MonitorProcessor processor) {
        this.throttle = throttle;
        this.delegateProcessor = processor;
    }

    /**
     * This is a method that a monitor can use to notify the processor that it
     * is completed and should be processed. All monitors must call this method
     * in order to have themselves processed.
     *
     * @param monitor monitor that is a candidate for processing by the delegate processor
     */
    public void process(Monitor monitor) {
        if (throttle.shouldAllow(monitor)) {
            delegateProcessor.process(monitor);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Throttling monitor : " + monitor);
            }
        }
    }

    /**
     * This is a method that all monitors will call when they are first created.
     *
     * @param monitor
     */
    public void monitorCreated(Monitor monitor) {
        if ((!applyThrottlingAtCreate) || throttle.shouldAllow(monitor)) {
            delegateProcessor.monitorCreated(monitor);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Throttling monitor : " + monitor);
            }
        }
    }

    /**
     * This is a method that monitors that wrap a unit of work will call when
     * they are started.
     *
     * @param monitor
     */
    public void monitorStarted(Monitor monitor) {
        if ((!applyThrottlingAtStart) || throttle.shouldAllow(monitor)) {
            delegateProcessor.monitorStarted(monitor);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Throttling monitor : " + monitor);
            }
        }
    }

    public void setApplyThrottlingAtCreate(boolean applyThrottlingAtCreate) {
        this.applyThrottlingAtCreate = applyThrottlingAtCreate;
    }

    public void setApplyThrottlingAtStart(boolean applyThrottlingAtStart) {
        this.applyThrottlingAtStart = applyThrottlingAtStart;
    }
}
