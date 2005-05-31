package edu.uoregon.tau.dms.dss;

import java.util.*;
import java.io.*;

/**
 * This class represents a Thread.  It contains an array of FunctionProfiles and 
 * UserEventProfiles as well as maximum data (e.g. max exclusive value for all functions on 
 * this thread). 
 *  
 * <P>CVS $Id: Thread.java,v 1.16 2005/05/31 23:21:02 amorris Exp $</P>
 * @author	Robert Bell, Alan Morris
 * @version	$Revision: 1.16 $
 * @see		Node
 * @see		Context
 * @see		FunctionProfile
 * @see		UserEventProfile
 */
public class Thread implements Comparable {

    private int nodeID = -1;
    private int contextID = -1;
    private int threadID = -1;
    private List functionProfiles = new ArrayList();
    private Vector userEventProfiles = new Vector();
    private double[] doubleList;
    private double maxNumCalls = 0;
    private double maxNumSubr = 0;
    private boolean trimmed = false;
    private boolean relationsBuilt = false;
    private int numMetrics = 0;
    private static final int METRIC_SIZE = 6;
    
    public Thread(int nodeID, int contextID, int threadID) {
        this(nodeID, contextID, threadID, 1);
    }

    public Thread(int nodeID, int contextID, int threadID, int numMetrics) {
        this.nodeID = nodeID;
        this.contextID = contextID;
        this.threadID = threadID;
        doubleList = new double[numMetrics * METRIC_SIZE];
        this.numMetrics = numMetrics;
    }

    public int getNodeID() {
        return nodeID;
    }

    public int getContextID() {
        return contextID;
    }

    public int getThreadID() {
        return threadID;
    }

    public int getNumMetrics() {
        return this.numMetrics;
    }

    public void incrementStorage() {
        int currentLength = doubleList.length;
        double[] newArray = new double[currentLength + METRIC_SIZE];

        for (int i = 0; i < currentLength; i++) {
            newArray[i] = doubleList[i];
        }
        doubleList = newArray;
        this.numMetrics++;
    }

    public void addFunctionProfile(FunctionProfile fp) {
        int id = fp.getFunction().getID();
        // increase the size of the functionProfiles list if necessary
        while (id >= functionProfiles.size()) {
            functionProfiles.add(null);
        }
        
        functionProfiles.set(id, fp);
    }

    public void addUserEvent(UserEventProfile uep) {
        int id = uep.getUserEvent().getID();
        // increase the userEventProfiles vector size if necessary
        if (id >= userEventProfiles.size()) {
            userEventProfiles.setSize(id + 1);
        }
        userEventProfiles.set(id, uep);
    }

    public FunctionProfile getFunctionProfile(Function function) {
        if ((functionProfiles != null) && (function.getID() < functionProfiles.size()))
            return (FunctionProfile) functionProfiles.get(function.getID());
        return null;
    }

    public List getFunctionProfiles() {
        return functionProfiles;
    }

    public Iterator getFunctionProfileIterator() {
        return functionProfiles.iterator();
    }

    public UserEventProfile getUserEventProfile(UserEvent userEvent) {
        if ((userEventProfiles != null) && (userEvent.getID() < userEventProfiles.size()))
            return (UserEventProfile) userEventProfiles.elementAt(userEvent.getID());
        return null;
    }

    public List getUserEventProfiles() {
        return userEventProfiles;
    }

    private void setMaxInclusive(int metric, double inDouble) {
        this.insertDouble(metric, 0, inDouble);
    }

    public double getMaxInclusive(int metric) {
        return this.getDouble(metric, 0);
    }

    private void setMaxExclusive(int metric, double inDouble) {
        this.insertDouble(metric, 1, inDouble);
    }

    public double getMaxExclusive(int metric) {
        return this.getDouble(metric, 1);
    }

    private void setMaxInclusivePercent(int metric, double inDouble) {
        this.insertDouble(metric, 2, inDouble);
    }

    public double getMaxInclusivePercent(int metric) {
        return this.getDouble(metric, 2);
    }

    private void setMaxExclusivePercent(int metric, double inDouble) {
        this.insertDouble(metric, 3, inDouble);
    }

    public double getMaxExclusivePercent(int metric) {
        return this.getDouble(metric, 3);
    }

    private void setMaxInclusivePerCall(int metric, double inDouble) {
        this.insertDouble(metric, 4, inDouble);
    }

    public double getMaxInclusivePerCall(int metric) {
        return this.getDouble(metric, 4);
    }

    private void setMaxExclusivePerCall(int metric, double inDouble) {
        this.insertDouble(metric, 5, inDouble);
    }

    public double getMaxExclusivePerCall(int metric) {
        return this.getDouble(metric, 5);
    }

    private void setMaxNumCalls(double inDouble) {
        maxNumCalls = inDouble;
    }

    public double getMaxNumCalls() {
        return maxNumCalls;
    }

    private void setMaxNumSubr(double inDouble) {
        maxNumSubr = inDouble;
    }

    public double getMaxNumSubr() {
        return maxNumSubr;
    }

    // Since per thread callpath relations are build on demand, the following four functions tell whether this
    // thread's callpath information has been set yet.  This way, we only compute it once.
    public void setTrimmed(boolean b) {
        trimmed = b;
    }

    public boolean trimmed() {
        return trimmed;
    }

    public void setRelationsBuilt(boolean b) {
        relationsBuilt = b;
    }

    public boolean relationsBuilt() {
        return relationsBuilt;
    }

    public int compareTo(Object obj) {
        return threadID - ((Thread) obj).getThreadID();
    }

    public String toString() {
        return this.getClass().getName() + ": " + this.getNodeID() + "," + this.getContextID() + ","
                + this.getThreadID();
    }

    public void setThreadData(int metric) {
        setThreadValues(metric, metric);
    }

    public void setThreadDataAllMetrics() {
        setThreadValues(0, this.getNumMetrics() - 1);
    }

    private void insertDouble(int metric, int offset, double inDouble) {
        int actualLocation = (metric * METRIC_SIZE) + offset;
        doubleList[actualLocation] = inDouble;
    }

    private double getDouble(int metric, int offset) {
        int actualLocation = (metric * METRIC_SIZE) + offset;
        return doubleList[actualLocation];
    }

    // compute max values and percentages for threads (not mean/total)
    private void setThreadValues(int startMetric, int endMetric) {
        for (int metric = startMetric; metric <= endMetric; metric++) {
            double maxInclusive = 0.0;
            double maxExclusive = 0.0;
            double maxInclusivePerCall = 0.0;
            double maxExclusivePerCall = 0.0;
            double maxNumCalls = 0;
            double maxNumSubr = 0;

            for (Iterator it = this.getFunctionProfileIterator(); it.hasNext();) {
                FunctionProfile fp = (FunctionProfile) it.next();
                if (fp != null) {
                    maxInclusive = Math.max(maxInclusive, fp.getInclusive(metric));
                    maxExclusive = Math.max(maxExclusive, fp.getExclusive(metric));
                    maxInclusivePerCall = Math.max(maxInclusivePerCall, fp.getInclusivePerCall(metric));
                    maxExclusivePerCall = Math.max(maxExclusivePerCall, fp.getExclusivePerCall(metric));
                    maxNumCalls = Math.max(maxNumCalls, fp.getNumCalls());
                    maxNumSubr = Math.max(maxNumSubr, fp.getNumSubr());
                }
            }

            this.setMaxInclusive(metric, maxInclusive);
            this.setMaxExclusive(metric, maxExclusive);
            this.setMaxInclusivePerCall(metric, maxInclusivePerCall);
            this.setMaxExclusivePerCall(metric, maxExclusivePerCall);
            this.setMaxNumCalls(maxNumCalls);
            this.setMaxNumSubr(maxNumSubr);

            double maxInclusivePercent = 0.0;
            double maxExclusivePercent = 0.0;

            for (Iterator it = this.getFunctionProfileIterator(); it.hasNext();) {
                FunctionProfile fp = (FunctionProfile) it.next();
                if (fp != null) {

                    // Note: Assumption is made that the max inclusive value is the value required to calculate
                    // percentage (ie, divide by). Thus, we are assuming that the sum of the exclusive
                    // values is equal to the max inclusive value. This is a reasonable assuption. This also gets
                    // us out of sticky situations when call path data is present (this skews attempts to calculate
                    // the total exclusive value unless checks are made to ensure that we do not include call path objects).

                    Function function = fp.getFunction();
                    if (this.getNodeID() > -1) { // don't do this for mean/total
                        double inclusiveMax = this.getMaxInclusive(metric);

                        if (inclusiveMax != 0) {
                            double exclusivePercent = (fp.getExclusive(metric) / inclusiveMax) * 100.00;
                            double inclusivePercent = (fp.getInclusive(metric) / inclusiveMax) * 100;

                            fp.setExclusivePercent(metric, exclusivePercent);
                            fp.setInclusivePercent(metric, inclusivePercent);
                            //function.setMaxExclusivePercent(metric, Math.max(function.getMaxExclusivePercent(metric), exclusivePercent));
                            //function.setMaxInclusivePercent(metric, Math.max(function.getMaxInclusivePercent(metric), inclusivePercent));
                        }
                    }

                    maxExclusivePercent = Math.max(maxExclusivePercent, fp.getExclusivePercent(metric));
                    maxInclusivePercent = Math.max(maxInclusivePercent, fp.getInclusivePercent(metric));
                }
            }

            this.setMaxInclusivePercent(metric, maxInclusivePercent);
            this.setMaxExclusivePercent(metric, maxExclusivePercent);
        }
    }

    
}