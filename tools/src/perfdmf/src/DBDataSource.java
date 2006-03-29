package edu.uoregon.tau.perfdmf;

import java.util.*;
import java.sql.*;

public class DBDataSource extends DataSource {

    private DatabaseAPI databaseAPI;
    private volatile boolean abort = false;
    private volatile int totalItems = 0;
    private volatile int itemsDone = 0;

    
    public DBDataSource(DatabaseAPI dbAPI) {
        super();
        this.setMetrics(new Vector());
        this.databaseAPI = dbAPI;
    }


    public int getProgress() {
        return 0;
        //return DatabaseAPI.getProgress();
    }

    public void cancelLoad() {
        abort = true;
        return;
    }

    public void load() throws SQLException {

        Function function = null;
        UserEvent userEvent = null;
        FunctionProfile functionProfile = null;
        UserEventProfile userEventProfile = null;

        Node node = null;
        Context context = null;
        edu.uoregon.tau.perfdmf.Thread thread = null;
        int nodeID = -1;
        int contextID = -1;
        int threadID = -1;

        // System.out.println("Processing data, please wait ......");
        long time = System.currentTimeMillis();

        int numberOfMetrics = databaseAPI.getNumberOfMetrics();
        for (int i = 0; i < numberOfMetrics; i++) {
            this.addMetric(databaseAPI.getMetricName(i));
        }

        //Add the functionProfiles.
        ListIterator l = databaseAPI.getIntervalEvents().listIterator();

        meanData = new Thread(-1, -1, -1, numberOfMetrics);
        totalData = new Thread(-2, -2, -2, numberOfMetrics);

        totalItems += this.getNumFunctions();

        while (l.hasNext()) {
            IntervalEvent ie = (IntervalEvent) l.next();

            function = this.addFunction(ie.getName(), numberOfMetrics);

            FunctionProfile meanProfile = new FunctionProfile(function, numberOfMetrics);
            function.setMeanProfile(meanProfile);
            meanData.addFunctionProfile(meanProfile);

            FunctionProfile totalProfile = new FunctionProfile(function, numberOfMetrics);
            function.setTotalProfile(totalProfile);
            totalData.addFunctionProfile(totalProfile);

            IntervalLocationProfile ilp = ie.getMeanSummary();

            if (ie.getGroup() != null) {
                
                String groupNames = ie.getGroup();
                StringTokenizer st = new StringTokenizer(groupNames, "|");
                while (st.hasMoreTokens()) {
                    String groupName = st.nextToken();
                    if (groupName != null) {
                        // The potential new group is added here. If the group is already present,
                        // then the addGroup function will just return the
                        // already existing group id. See the TrialData
                        // class for more details.
                        Group group = this.addGroup(groupName.trim());
                        function.addGroup(group);
                    }
                }
                
                //Group group = this.addGroup(ie.getGroup());
                //function.addGroup(group);
                this.setGroupNamesPresent(true);
            }

            for (int i = 0; i < numberOfMetrics; i++) {
                meanProfile.setExclusive(i, ilp.getExclusive(i));
                meanProfile.setExclusivePercent(i, ilp.getExclusivePercentage(i));
                meanProfile.setInclusive(i, ilp.getInclusive(i));
                meanProfile.setInclusivePercent(i, ilp.getInclusivePercentage(i));
                //meanProfile.setInclusivePerCall(i, ilp.getInclusivePerCall(i));
                meanProfile.setNumCalls(ilp.getNumCalls());
                meanProfile.setNumSubr(ilp.getNumSubroutines());

            }


            ilp = ie.getTotalSummary();
            for (int i = 0; i < numberOfMetrics; i++) {
                totalProfile.setExclusive(i, ilp.getExclusive(i));
                totalProfile.setExclusivePercent(i, ilp.getExclusivePercentage(i));
                totalProfile.setInclusive(i, ilp.getInclusive(i));
                totalProfile.setInclusivePercent(i, ilp.getInclusivePercentage(i));
                //totalProfile.setInclusivePerCall(i, ilp.getInclusivePerCall(i));
                totalProfile.setNumCalls(ilp.getNumCalls());
                totalProfile.setNumSubr(ilp.getNumSubroutines());
            }
        }

        l = databaseAPI.getIntervalEventData().listIterator();

        while (l.hasNext()) {
            IntervalLocationProfile fdo = (IntervalLocationProfile) l.next();
            node = this.getNode(fdo.getNode());
            if (node == null)
                node = this.addNode(fdo.getNode());
            context = node.getContext(fdo.getContext());
            if (context == null)
                context = node.addContext(fdo.getContext());
            thread = context.getThread(fdo.getThread());
            if (thread == null) {
                thread = context.addThread(fdo.getThread(), numberOfMetrics);
            }

            //Get Function and FunctionProfile.

            function = this.getFunction(databaseAPI.getIntervalEvent(fdo.getIntervalEventID()).getName());
            functionProfile = thread.getFunctionProfile(function);

            if (functionProfile == null) {
                functionProfile = new FunctionProfile(function, numberOfMetrics);
                thread.addFunctionProfile(functionProfile);
            }

            for (int i = 0; i < numberOfMetrics; i++) {
                functionProfile.setExclusive(i, fdo.getExclusive(i));
                functionProfile.setInclusive(i, fdo.getInclusive(i));
                functionProfile.setExclusivePercent(i, fdo.getExclusivePercentage(i));
                functionProfile.setInclusivePercent(i, fdo.getInclusivePercentage(i));
                //functionProfile.setInclusivePerCall(i, fdo.getInclusivePerCall(i));
                functionProfile.setNumCalls(fdo.getNumCalls());
                functionProfile.setNumSubr(fdo.getNumSubroutines());
            }
        }

        l = databaseAPI.getAtomicEvents().listIterator();
        while (l.hasNext()) {
            AtomicEvent ue = (AtomicEvent) l.next();
            this.addUserEvent(ue.getName());
            setUserEventsPresent(true);
        }

        l = databaseAPI.getAtomicEventData().listIterator();
        while (l.hasNext()) {
            AtomicLocationProfile alp = (AtomicLocationProfile) l.next();

            // do we need to do this?
            node = this.getNode(alp.getNode());
            if (node == null)
                node = this.addNode(alp.getNode());
            context = node.getContext(alp.getContext());
            if (context == null)
                context = node.addContext(alp.getContext());
            thread = context.getThread(alp.getThread());
            if (thread == null) {
                thread = context.addThread(alp.getThread(), numberOfMetrics);
            }

            userEvent = this.getUserEvent(databaseAPI.getAtomicEvent(alp.getAtomicEventID()).getName());

            userEventProfile = thread.getUserEventProfile(userEvent);

            if (userEventProfile == null) {
                userEventProfile = new UserEventProfile(userEvent);
                thread.addUserEventProfile(userEventProfile);
            }

            userEventProfile.setNumSamples(alp.getSampleCount());
            userEventProfile.setMaxValue(alp.getMaximumValue());
            userEventProfile.setMinValue(alp.getMinimumValue());
            userEventProfile.setMeanValue(alp.getMeanValue());
            userEventProfile.setSumSquared(alp.getSumSquared());
            userEventProfile.updateMax();

        }

        

        time = (System.currentTimeMillis()) - time;
//        System.out.println("Time to download file (in milliseconds): " + time);


        // yep, I'm going to do it anyway, I have other stats to compute, we're just discarding the
        // database values.
        generateDerivedData();
    }
}
