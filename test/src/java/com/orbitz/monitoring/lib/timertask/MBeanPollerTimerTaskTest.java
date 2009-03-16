package com.orbitz.monitoring.lib.timertask;

import com.orbitz.monitoring.api.Monitor;
import com.orbitz.monitoring.lib.BaseMonitoringEngineManager;
import com.orbitz.monitoring.lib.processor.MonitorProcessorAdapter;
import com.orbitz.monitoring.test.MockDecomposer;
import com.orbitz.monitoring.test.MockMonitorProcessorFactory;
import junit.framework.TestCase;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.MBeanServerFactory;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimerTask;
import java.math.BigDecimal;

/**
 * Test cases for MBeanPollerTimerTask
 *
 * @author Greg Opaczewski
 */
public class MBeanPollerTimerTaskTest extends TestCase {

    BrowsableMonitorProcessor processor;

    @Override
    public void setUp() {
        processor = new BrowsableMonitorProcessor();

        MockMonitorProcessorFactory mockMonitorProcessorFactory =
                new MockMonitorProcessorFactory(processor);
        MockDecomposer mockDecomposer = new MockDecomposer();
        BaseMonitoringEngineManager monitoringEngineManager =
                new BaseMonitoringEngineManager(mockMonitorProcessorFactory, mockDecomposer);
        monitoringEngineManager.startup();
        
        processor.clear();
    }

    public void testNullMBeanServerFailsFast() {
        try {
            new MBeanPollerTimerTask(null);
            fail("Should have thrown NPE");
        } catch (NullPointerException e) {
            // expected
        }
    }

    public void testPlatformMBeans() {
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        TimerTask timerTask = new MBeanPollerTimerTask(mbeanServer);
        timerTask.run();

        assertMonitorFired("java.lang:type=Runtime",
                new String[] {"ClassPath"});
    }

    public void testStandardMBeans() throws Exception {
        MBeanServer mbeanServer = MBeanServerFactory.createMBeanServer(
                "MBeanPollerTimerTaskTest.testStandardMBeans");
        MBeanPollerTimerTask timerTask = new MBeanPollerTimerTask(mbeanServer);

        // register one mbean and validate one returned
        mbeanServer.registerMBean(new MyImpl(101),
                new ObjectName("MBeanPollerTimerTaskTest:type=myStandardMBean"));

        timerTask.setObjectNames(Arrays.asList(new ObjectName("MBeanPollerTimerTaskTest:*")));
        timerTask.run();

        assertEquals(1, processor.getProcessedMonitors().size());
        assertMonitorFired("MBeanPollerTimerTaskTest:type=myStandardMBean",
                new String[] {"Value"},
                new Integer[] {101});

        // register a second mbean and assert both returned for default wildcard query
        mbeanServer.registerMBean(new MyImpl(202),
                new ObjectName("MBeanPollerTimerTaskTest:type=myStandardMBean,foo=bar"));

        processor.clear();
        timerTask.run();

        assertEquals(2, processor.getProcessedMonitors().size());
        assertMonitorFired("MBeanPollerTimerTaskTest:type=myStandardMBean",
                new String[] {"Value"},
                new Integer[] {101});
        assertMonitorFired("MBeanPollerTimerTaskTest:type=myStandardMBean+foo=bar",
                new String[] {"Value"},
                new Integer[] {202});

        // change object name query so that only one mbean should be returned
        processor.clear();
        timerTask.setObjectNames(Arrays.asList(new ObjectName("MBeanPollerTimerTaskTest:type=myStandardMBean")));
        timerTask.run();
        assertEquals(1, processor.getProcessedMonitors().size());

        // query for both object names explicitly
        processor.clear();
        timerTask.setObjectNames(Arrays.asList(new ObjectName("MBeanPollerTimerTaskTest:type=myStandardMBean"),
                    new ObjectName("MBeanPollerTimerTaskTest:type=myStandardMBean,foo=bar")));
        timerTask.run();
        assertEquals(2, processor.getProcessedMonitors().size());
    }

    public void testNoDuplicateMonitorsForOverlappingQueries() throws Exception {
        MBeanServer mbeanServer = MBeanServerFactory.createMBeanServer(
                "MBeanPollerTimerTaskTest.testNoDuplicateMonitorsForOverlappingQueries");
        MBeanPollerTimerTask timerTask = new MBeanPollerTimerTask(mbeanServer);

        mbeanServer.registerMBean(new MyImpl(101),
                new ObjectName("MBeanPollerTimerTaskTest:type=myStandardMBean,foo=bar"));

        timerTask.setObjectNames(Arrays.asList(
                new ObjectName("MBeanPollerTimerTaskTest:*"),
                new ObjectName("MBeanPollerTimerTaskTest:type=myStandardMBean,foo=bar")));
        timerTask.run();
        assertEquals(1, processor.getProcessedMonitors().size());
    }

    public void testFindNoMBeans() throws Exception {
        MBeanServer mbeanServer = ManagementFactory.getPlatformMBeanServer();
        MBeanPollerTimerTask timerTask = new MBeanPollerTimerTask(mbeanServer);

        timerTask.setObjectNames(Arrays.asList(
                new ObjectName("notthere:*"),
                new ObjectName("*:type=xxx,*")));
        timerTask.run();
        assertEquals(0, processor.getProcessedMonitors().size());
    }

    public void testAllowedAttributeTypes() throws Exception {
        MBeanServer mbeanServer = MBeanServerFactory.createMBeanServer(
                "MBeanPollerTimerTaskTest.testDisallowedAttributes");
        MBeanPollerTimerTask timerTask = new MBeanPollerTimerTask(mbeanServer);

        mbeanServer.registerMBean(new MyImpl(101),
                new ObjectName("MBeanPollerTimerTaskTest:type=myStandardMBean,foo=bar"));

        timerTask.setObjectNames(Arrays.asList(new ObjectName("MBeanPollerTimerTaskTest:*")));
        timerTask.run();
        assertEquals(1, processor.getProcessedMonitors().size());

        Monitor monitor = processor.getProcessedMonitors().get(0);
        assertTrue(monitor.hasAttribute("Number"));
        assertTrue(monitor.hasAttribute("Boolean"));
        assertFalse(monitor.hasAttribute("Object"));
    }

    private void assertMonitorFired(String monitorName, String[] attributes) {
        assertMonitorFired(monitorName, attributes, null);
    }

    private void assertMonitorFired(String monitorName, String[] attributes, Object[] values) {
        List<Monitor> monitors = processor.getProcessedMonitors();
        Monitor match = null;
        for (Monitor monitor : monitors) {
            String name = monitor.getAsString(Monitor.NAME);
            if (monitorName.equals(name)) {
                match = monitor;
                break;
            }
        }

        if (match == null) {
            fail("No monitor fired with name: " + monitorName);
        }

        for (int i = 0; i < attributes.length; i++) {
            String attribute = attributes[i];
            assertTrue(match.hasAttribute(attribute));
            if (values != null) {
                assertEquals(values[i], match.get(attribute));
            }
        }
    }

    public interface MyImplMBean {
        int getValue();
        int getRTE();
        Object getObject();
        Number getNumber();
        boolean getBoolean();
    }

    public class MyImpl implements MyImplMBean {
        public int value;
        
        public MyImpl(int value) throws Exception {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        public int getRTE() {
            throw new RuntimeException("should be wrapped by RuntimeMBeanException");
        }

        public Object getObject() {
            return new Object();
        }

        public Number getNumber() {
            return new BigDecimal(0);
        }

        public boolean getBoolean() {
            return false;
        }
    }

    private class BrowsableMonitorProcessor extends MonitorProcessorAdapter {
        private List<Monitor> processedMonitors = new ArrayList<Monitor>();

        public void process(Monitor monitor) {
            processedMonitors.add(monitor);
        }

        public List<Monitor> getProcessedMonitors() {
            return processedMonitors;
        }

        public void clear() {
            processedMonitors.clear();
        }
    }
}


