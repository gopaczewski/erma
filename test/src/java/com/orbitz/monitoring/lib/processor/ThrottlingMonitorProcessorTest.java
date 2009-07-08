package com.orbitz.monitoring.lib.processor;

import junit.framework.TestCase;
import com.orbitz.monitoring.test.MockMonitorProcessor;
import com.orbitz.monitoring.api.MonitorThrottle;
import com.orbitz.monitoring.api.Monitor;
import com.orbitz.monitoring.api.monitor.EventMonitor;
import com.orbitz.monitoring.lib.TimeWindowMonitorThrottle;

/**
 * ThrottlingMonitorProcessor test cases
 *
 * @author gopaczewski
 */
public class ThrottlingMonitorProcessorTest extends TestCase {

    public void testThrottle() {
        MonitorThrottle monitorThrottle = new TimeWindowMonitorThrottle(1, 100, 10);
        MockMonitorProcessor mockProcessor = new MockMonitorProcessor();
        ThrottlingMonitorProcessor processor = new ThrottlingMonitorProcessor(monitorThrottle,
                mockProcessor);

        Monitor testMonitor = new EventMonitor("foo");
        processor.process(testMonitor);
        assertEquals(mockProcessor.extractProcessObjects()[0], testMonitor);

        for (int i=0; i < 200; i++) {
            Monitor m = new EventMonitor("m" + i);
            processor.process(m);
        }

        Monitor[] allowedMonitors = mockProcessor.extractProcessObjects();
        assertTrue(allowedMonitors.length > 0);
        assertTrue(allowedMonitors.length < 200);
    }

    public void testDisableEnableThrottle() {
        MonitorThrottle monitorThrottle = new TimeWindowMonitorThrottle(5, 100, 10);
        MockMonitorProcessor mockProcessor = new MockMonitorProcessor();
        ThrottlingMonitorProcessor processor = new ThrottlingMonitorProcessor(monitorThrottle,
                mockProcessor);

        monitorThrottle.disable();

        // 101 monitors exceeds 10 per window and 100 total unique configured for throttle...
        for (int i=0; i < 101; i++) {
            Monitor m = new EventMonitor("m" + i);
            processor.process(m);
            assertEquals(m, mockProcessor.extractProcessObjects()[0]);
        }

        assertEquals(0, monitorThrottle.getTotalThrottledCount());

        monitorThrottle.enable();

        for (int i=0; i < 101; i++) {
            Monitor m = new EventMonitor("m" + i);
            processor.process(m);
        }

        assertTrue(monitorThrottle.getTotalThrottledCount() > 0);
    }
}
