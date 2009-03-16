package com.orbitz.monitoring.lib.timertask;

import com.orbitz.monitoring.api.monitor.EventMonitor;
import org.apache.log4j.Logger;

import javax.management.JMException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.RuntimeMBeanException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimerTask;
import java.util.Date;
import java.lang.management.ManagementFactory;

/**
 * <p>
 * A generic mbean polling timer task. Fires ERMA events by querying an mbean server
 * for a list of object names. Each mbean has all attributes with simple types converted
 * to attributes on the erma monitor.
 * </p>
 *
 * @author Greg Opaczewski
 */
public class MBeanPollerTimerTask extends TimerTask {

    private static final Logger log = Logger.getLogger(MBeanPollerTimerTask.class);

    private MBeanServer mbeanServer;
    private List<ObjectName> objectNames;

    /**
     * Default constructor.  Uses the platform mbean server.
     */
    public MBeanPollerTimerTask() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    /**
     * Constructor.
     *
     * @param mbeanServer jmx MBeanServer to query for mbeans
     */
    public MBeanPollerTimerTask(MBeanServer mbeanServer) {
        if (mbeanServer == null) {
            throw new NullPointerException("mbeanServer cannot be null");
        }
        this.mbeanServer = mbeanServer;

        if (log.isDebugEnabled()) {
            log.debug("starting with mbeanServer: " + mbeanServer);
        }

        // default to querying all mbeans unless an explicit list is wired in
        objectNames = new ArrayList<ObjectName>();
        try {
            objectNames.add(new ObjectName("*:*"));
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);    
        }
    }

    public void run() {
        Set<ObjectName> mbeans = new HashSet<ObjectName>();
        for (ObjectName objectName : objectNames) {
            mbeans.addAll(mbeanServer.queryNames(objectName, null));
        }

        for (ObjectName mbean : mbeans) {
            MBeanInfo mbeanInfo;
            try {
                mbeanInfo = mbeanServer.getMBeanInfo(mbean);
            } catch (JMException e) {
                if (log.isDebugEnabled()) {
                    log.debug("failed to fire monitor for: " + mbean, e);
                }
                continue;
            } catch (RuntimeMBeanException e) {
                if (log.isDebugEnabled()) {
                    log.debug("failed to fire monitor for: " + mbean, e);
                }
                continue;
            }

            // commas will be silently stripped from monitor names, so replace with an
            // allowable delimiter before creating the monitor
            String monitorName = mbean.toString().replace(',', '+');
            EventMonitor monitor = new EventMonitor(monitorName);

            extractMbeanAttributes(mbean, mbeanInfo, monitor);

            monitor.fire();
        }
    }

    private void extractMbeanAttributes(ObjectName mbean, MBeanInfo mbeanInfo, EventMonitor monitor) {
        for (MBeanAttributeInfo attributeInfo : mbeanInfo.getAttributes()) {
            String attributeName = attributeInfo.getName();

            try {
                Object attributeValue = mbeanServer.getAttribute(mbean, attributeName);
                if (attributeValue != null &&
                        isAllowedAttributeType(attributeValue.getClass())) {
                    monitor.set(attributeName, attributeValue);
                }
            } catch (JMException e) {
                if (log.isDebugEnabled()) {
                    log.debug("failed to get mbean attribute: " + mbean + "." +
                            attributeName, e);
                }
            } catch (RuntimeMBeanException e) {
                if (log.isDebugEnabled()) {
                    log.debug("failed to get mbean attribute: " + mbean + "." +
                            attributeName, e);
                }
            }
        }
    }

    public void setObjectNames(List<ObjectName> objectNames) {
        this.objectNames = objectNames;
    }

    private boolean isAllowedAttributeType(Class clazz) {
        return (Number.class.isAssignableFrom(clazz) ||
                Character.class.isAssignableFrom(clazz) ||
                Boolean.class.isAssignableFrom(clazz) ||
                String.class.isAssignableFrom(clazz) ||
                Byte.class.isAssignableFrom(clazz) ||
                Date.class.isAssignableFrom(clazz));
    }
}
