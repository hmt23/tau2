/*
 * TauOutputSession.java
 * 
 * Title: ParaProf Author: Robert Bell Description:
 */

/*
 * To do: 1) Add some sanity checks to make sure that multiple metrics really do
 * belong together. For example, wrap the creation of nodes, contexts, threads,
 * global mapping elements, and the like so that they do not occur after the
 * first metric has been loaded. This will not of course ensure 100% that the
 * data is consistent, but it will at least prevent the worst cases.
 */

package edu.uoregon.tau.dms.dss;

import java.io.*;
import java.util.*;

public class TauDataSource extends DataSource {

    public TauDataSource(Object initializeObject) {
        super();
        this.initializeObject = initializeObject;
    }

    private Object initializeObject;

    public void cancelLoad() {
        abort = true;
        return;
    }

    private boolean abort = false;
    int totalFiles = 0;
    int filesRead = 0;

    public int getProgress() {
        if (totalFiles != 0)
            return (int) ((float) filesRead / (float) totalFiles * 100);
        return 0;
    }

    public void load() throws FileNotFoundException, IOException, DataSourceException {
        long time = System.currentTimeMillis();

        Vector v = (Vector) initializeObject;

        // first count the files (for progressbar)
        for (Enumeration e = v.elements(); e.hasMoreElements();) {
            File[] files = (File[]) e.nextElement();
            for (int i = 0; i < files.length; i++) {
                totalFiles++;
            }
        }

        int metric = 0;

        // A flag is needed to test whether we have processed the metric
        // name rather than just checking whether this is the first file set. This is because we
        // might skip that first file (for example if the name were profile.-1.0.0) and thus skip
        // setting the metric name.
        //Reference bug08.

        boolean metricNameProcessed = false;

        Function func = null;
        FunctionProfile functionProfile = null;

        UserEvent userEvent = null;
        UserEventProfile userEventProfile = null;

  
        int nodeID = -1;
        int contextID = -1;
        int threadID = -1;

        String inputString = null;
        String s1 = null;
        String s2 = null;

        String tokenString;
        String groupNamesString = null;
        StringTokenizer genericTokenizer;

        // iterate through the vector of File arrays (each directory)
        for (Enumeration e = v.elements(); e.hasMoreElements();) {

            //Reset metricNameProcessed flag.
            metricNameProcessed = false;

            //Only need to call addDefaultToVectors() if not the first run.
            if (metric != 0) { // If this isn't the first metric, call i

                for (Iterator it = this.getFunctions(); it.hasNext();) {
                    Function function = (Function) it.next();
                    function.incrementStorage();
                }

                for (Iterator it = this.getNodes(); it.hasNext();) {
                    Node node = (Node) it.next();
                    for (Iterator it2 = node.getContexts(); it2.hasNext();) {
                        Context context = (Context) it2.next();
                        for (Iterator it3 = context.getThreads(); it3.hasNext();) {
                            Thread thread = (Thread) it3.next();
                            thread.incrementStorage();
                            for (Enumeration e6 = thread.getFunctionProfiles().elements(); e6.hasMoreElements();) {
                                FunctionProfile fp = (FunctionProfile) e6.nextElement();
                                if (fp != null)  // fp == null would mean this thread didn't call this function
                                    fp.incrementStorage();
                            }
                        }
                    }
                }

            }

            File[] files = (File[]) e.nextElement();
            for (int i = 0; i < files.length; i++) {
                filesRead++;

                if (abort)
                    return;

                int[] nct = this.getNCT(files[i].getName());
                if (nct != null) {

                    FileInputStream fileIn = new FileInputStream(files[i]);
                    InputStreamReader inReader = new InputStreamReader(fileIn);
                    BufferedReader br = new BufferedReader(inReader);

                    nodeID = nct[0];
                    contextID = nct[1];
                    threadID = nct[2];

                    Node node = this.addNode(nodeID);
                    Context context = node.addContext(contextID);
                    Thread thread = context.getThread(threadID);
                    if (thread == null) {
                        thread = context.addThread(threadID);
                    }

                    // First Line (e.g. "601 templated_functions")
                    inputString = br.readLine();
                    if (inputString == null) {
                        throw new DataSourceException("Unexpected end of file: " + files[i].getName()
                                + "\nLooking for 'templated_functions' line");
                    }
                    genericTokenizer = new StringTokenizer(inputString, " \t\n\r");

                    // the first token is the number of functions
                    tokenString = genericTokenizer.nextToken();
                    int numFunctions = Integer.parseInt(tokenString);

                    if (metricNameProcessed == false) {
                        //Set the metric name.
                        String metricName = getMetricName(inputString);
                        if (metricName == null)
                            metricName = new String("Time");
                        this.addMetric(metricName);
                        metricNameProcessed = true;
                    }

                    // Second Line (e.g. "# Name Calls Subrs Excl Incl ProfileCalls")
                    inputString = br.readLine();
                    if (inputString == null) {
                        throw new DataSourceException("Unexpected end of file: " + files[i].getName()
                                + "\nLooking for '# Name Calls ...' line");
                    }
                    if (i == 0) {
                        //Determine if profile stats or profile calls data is present.
                        if (inputString.indexOf("SumExclSqr") != -1)
                            this.setProfileStatsPresent(true);
                    }

                    for (int j = 0; j < numFunctions; j++) {

                        inputString = br.readLine();
                        if (inputString == null) {
                            throw new DataSourceException("Unexpected end of file: " + files[i].getName()
                                    + "\nOnly found " + (j - 2) + " of " + numFunctions + " Function Lines");
                        }

                        this.getFunctionDataLine(inputString);
                        String groupNames = this.getGroupNames(inputString);

                        //Calculate inclusive/call
                        double inclusivePerCall = functionDataLine.d1 / functionDataLine.i0;

                        if (functionDataLine.i0 != 0) {
                            func = this.addFunction(functionDataLine.s0, 1);

                            functionProfile = thread.getFunctionProfile(func);

                            if (functionProfile == null) {
                                functionProfile = new FunctionProfile(func);
                                thread.addFunctionProfile(functionProfile);
                            }

                            //When we encounter duplicate names in the profile.x.x.x file, treat as additional
                            //data for the name (that is, don't just overwrite what was there before).
                            //See todo item 7 in the ParaProf docs directory.
                            functionProfile.setExclusive(metric, functionProfile.getExclusive(metric)
                                    + functionDataLine.d0);
                            functionProfile.setInclusive(metric, functionProfile.getInclusive(metric)
                                    + functionDataLine.d1);
                            if (metric == 0) {
                                functionProfile.setNumCalls(functionProfile.getNumCalls() + functionDataLine.i0);
                                functionProfile.setNumSubr(functionProfile.getNumSubr() + functionDataLine.i1);
                            }
                            functionProfile.setInclusivePerCall(metric,
                                    functionProfile.getInclusivePerCall(metric) + inclusivePerCall);

                            //Set the max values (thread max values are calculated in the
                            // edu.uoregon.tau.dms.dss.Thread class).
                            if ((func.getMaxExclusive(metric)) < functionDataLine.d0)
                                func.setMaxExclusive(metric, functionDataLine.d0);
                            if ((func.getMaxInclusive(metric)) < functionDataLine.d1)
                                func.setMaxInclusive(metric, functionDataLine.d1);
                            if (func.getMaxNumCalls() < functionDataLine.i0)
                                func.setMaxNumCalls(functionDataLine.i0);
                            if (func.getMaxNumSubr() < functionDataLine.i1)
                                func.setMaxNumSubr(functionDataLine.i1);
                            if (func.getMaxInclusivePerCall(metric) < inclusivePerCall)
                                func.setMaxInclusivePerCall(metric, inclusivePerCall);

                            if (metric == 0 && groupNames != null) {
                                StringTokenizer st = new StringTokenizer(groupNames, " |");
                                while (st.hasMoreTokens()) {
                                    String groupName = st.nextToken();
                                    if (groupName != null) {
                                        // The potential new group is added here. If the group is already present,
                                        // then the addGroup function will just return the
                                        // already existing group id. See the TrialData
                                        // class for more details.
                                        Group group = this.addGroup(groupName);
                                        func.addGroup(group);
                                    }
                                }
                            }

                        }

                        // unused profile calls

//                        //Process the appropriate number of profile call lines.
//                        for (int k = 0; k < functionDataLine.i2; k++) {
//                            //this.setProfileCallsPresent(true);
//                            inputString = br.readLine();
//                            genericTokenizer = new StringTokenizer(inputString, " \t\n\r");
//                            //Arguments are evaluated left to right.
//                            functionProfile.addCall(Double.parseDouble(genericTokenizer.nextToken()),
//                                    Double.parseDouble(genericTokenizer.nextToken()));
//                        }
                    }

                    //Process the appropriate number of aggregate lines.
                    inputString = br.readLine();

                    //A valid profile.*.*.* will always contain this line.
                    if (inputString == null) {
                        throw new DataSourceException("Unexpected end of file: " + files[i].getName()
                                + "\nLooking for 'aggregates' line");
                    }
                    genericTokenizer = new StringTokenizer(inputString, " \t\n\r");
                    //It's first token will be the number of aggregates.
                    tokenString = genericTokenizer.nextToken();

                    numFunctions = Integer.parseInt(tokenString);
                    for (int j = 0; j < numFunctions; j++) {
                        //this.setAggregatesPresent(true);
                        inputString = br.readLine();
                    }

                    if (metric == 0) {
                        //Process the appropriate number of userevent lines.
                        inputString = br.readLine();
                        if (inputString != null) {
                            genericTokenizer = new StringTokenizer(inputString, " \t\n\r");
                            //It's first token will be the number of userEvents
                            tokenString = genericTokenizer.nextToken();
                            int numUserEvents = Integer.parseInt(tokenString);

                            //Skip the heading (e.g. "# eventname numevents max min mean sumsqr")
                            br.readLine();
                            for (int j = 0; j < numUserEvents; j++) {
                                if (j == 0) {
                                    setUserEventsPresent(true);
                                }

                                inputString = br.readLine();
                                if (inputString == null) {
                                    throw new DataSourceException("Unexpected end of file: "
                                            + files[i].getName() + "\nOnly found " + (j - 2) + " of "
                                            + numUserEvents + " User Event Lines");
                                }

                                this.getUserEventData(inputString);

                                // User events
                                if (usereventDataLine.i0 != 0) {

                                    userEvent = this.addUserEvent(usereventDataLine.s0);
                                    userEventProfile = thread.getUserEventProfile(userEvent);

                                    if (userEventProfile == null) {
                                        userEventProfile = new UserEventProfile(userEvent);
                                        thread.addUserEvent(userEventProfile);
                                    }

                                    userEventProfile.setUserEventNumberValue(usereventDataLine.i0);
                                    userEventProfile.setUserEventMaxValue(usereventDataLine.d0);
                                    userEventProfile.setUserEventMinValue(usereventDataLine.d1);
                                    userEventProfile.setUserEventMeanValue(usereventDataLine.d2);
                                    userEventProfile.setUserEventSumSquared(usereventDataLine.d3);

                                    userEventProfile.updateMax();

                                }
                            }
                        }
                    }

                    br.close();
                    inReader.close();
                    fileIn.close();
                }
            }
            metric++;
        }

       

//        time = (System.currentTimeMillis()) - time;
//        System.out.println("Time to process (in milliseconds): " + time);
//        time = System.currentTimeMillis();

        
        //Generate derived data.
        this.generateDerivedData();

        if (CallPathUtilFuncs.checkCallPathsPresent(this.getFunctions())) {
            setCallPathDataPresent(true);
            CallPathUtilFuncs.buildRelations(this);
        }

//        time = (System.currentTimeMillis()) - time;
//        System.out.println("Time to process (in milliseconds): " + time);
    }

    public String toString() {
        return this.getClass().getName();
    }

    //profile.*.*.* string processing methods.
    private int[] getNCT(String string) {
        try {
            int[] nct = new int[3];
            StringTokenizer st = new StringTokenizer(string, ".\t\n\r");
            st.nextToken();
            nct[0] = Integer.parseInt(st.nextToken());
            nct[1] = Integer.parseInt(st.nextToken());
            nct[2] = Integer.parseInt(st.nextToken());

            if (nct[0] < 0 || nct[1] < 0 || nct[2] < 0) {
                // I'm 99% sure that this doesn't happen anymore
                return null;
            }
            return nct;
        } catch (Exception e) {
            // I'm 99% sure that this doesn't happen anymore
            return null;
        }
    }

    private String getMetricName(String inString) {
        String tmpString = null;
        int tmpInt = inString.indexOf("_MULTI_");

        if (tmpInt > 0) {
            //We are reading data from a multiple counter run.
            //Grab the counter name.
            tmpString = inString.substring(tmpInt + 7);
            return tmpString;
        }

        tmpInt = inString.indexOf("hw_counters");
        if (tmpInt > 0) {
            //We are reading data from a hardware counter run.
            return "Hardware Counter";
        }

        //We are not reading data from a multiple counter run or hardware
        // counter run.
        return tmpString;

    }

    private void getFunctionDataLine(String string) throws DataSourceException {

        // first, count the number of double-quotes to determine if the
        // function contains a double-quote
        int quoteCount = 0;
        for (int i = 0; i < string.length(); i++) {
            if (string.charAt(i) == '"')
                quoteCount++;
        }

        if (quoteCount == 0) {
            throw new DataSourceException("Looking for function line, found '" + string + "' instead");
        }

        StringTokenizer st2;

        if (quoteCount == 2 || quoteCount == 4) { // assume all is well
            StringTokenizer st1 = new StringTokenizer(string, "\"");
            functionDataLine.s0 = st1.nextToken(); //Name

            st2 = new StringTokenizer(st1.nextToken(), " \t\n\r");
        } else {

            // there is a quote in the name of the timer/function
            // we assume that TAU_GROUP="..." is there, so the end of the name
            // must be at (quoteCount - 2)
            int count = 0;
            int i = 0;
            while (count < quoteCount - 2 && i < string.length()) {
                if (string.charAt(i) == '"')
                    count++;
                i++;
            }

            functionDataLine.s0 = string.substring(1, i - 1);
            st2 = new StringTokenizer(string.substring(i + 1), " \t\n\r");
        }

        functionDataLine.i0 = Integer.parseInt(st2.nextToken()); //Calls
        functionDataLine.i1 = Integer.parseInt(st2.nextToken()); //Subroutines
        functionDataLine.d0 = Double.parseDouble(st2.nextToken()); //Exclusive
        functionDataLine.d1 = Double.parseDouble(st2.nextToken()); //Inclusive
        if (this.getProfileStatsPresent())
            functionDataLine.d2 = Double.parseDouble(st2.nextToken()); //SumExclSqr
        functionDataLine.i2 = Integer.parseInt(st2.nextToken()); //ProfileCalls

    }

    private String getGroupNames(String string) {

        // first, count the number of double-quotes to determine if the
        // function contains a double-quote
        int quoteCount = 0;
        for (int i = 0; i < string.length(); i++) {
            if (string.charAt(i) == '"')
                quoteCount++;
        }

        // there is a quote in the name of the timer/function
        // we assume that TAU_GROUP="..." is there, so the end of the name
        // must be (at quoteCount - 2)
        int count = 0;
        int i = 0;
        while (count < quoteCount - 2 && i < string.length()) {
            if (string.charAt(i) == '"')
                count++;
            i++;
        }

        StringTokenizer getMappingNameTokenizer = new StringTokenizer(string.substring(i + 1), "\"");
        String str = getMappingNameTokenizer.nextToken();

        //Just do the group check once.
        if (!(this.getGroupCheck())) {
            //If present, "GROUP=" will be in this token.
            int tmpInt = str.indexOf("GROUP=");
            if (tmpInt > 0) {
                this.setGroupNamesPresent(true);
            }
            this.setGroupCheck(true);
        }

        if (getGroupNamesPresent()) {
            str = getMappingNameTokenizer.nextToken();
            return str;
        }
        //If here, this profile file does not track the group names.
        return null;
    }

    private void getUserEventData(String string) {

        // first, count the number of double-quotes to determine if the
        // user event contains a double-quote
        int quoteCount = 0;
        for (int i = 0; i < string.length(); i++) {
            if (string.charAt(i) == '"')
                quoteCount++;
        }

        StringTokenizer st2;

        if (quoteCount == 2) { // proceed as usual
            StringTokenizer st1 = new StringTokenizer(string, "\"");
            usereventDataLine.s0 = st1.nextToken();
            st2 = new StringTokenizer(st1.nextToken(), " \t\n\r");
        } else {

            // there is a quote in the name of the user event
            int count = 0;
            int i = 0;
            while (count < quoteCount && i < string.length()) {
                if (string.charAt(i) == '"')
                    count++;
                i++;
            }

            usereventDataLine.s0 = string.substring(1, i - 1);
            st2 = new StringTokenizer(string.substring(i + 1), " \t\n\r");
        }

        usereventDataLine.i0 = (int) Double.parseDouble(st2.nextToken()); //Number of calls
        usereventDataLine.d0 = Double.parseDouble(st2.nextToken()); //Max
        usereventDataLine.d1 = Double.parseDouble(st2.nextToken()); //Min
        usereventDataLine.d2 = Double.parseDouble(st2.nextToken()); //Mean
        usereventDataLine.d3 = Double.parseDouble(st2.nextToken()); //Standard Deviation
    }

    
    
    
    protected void setProfileStatsPresent(boolean profileStatsPresent) {
        this.profileStatsPresent = profileStatsPresent;
    }

    public boolean getProfileStatsPresent() {
        return profileStatsPresent;
    }

    private boolean profileStatsPresent = false;

    

    protected boolean groupCheck = false;

    protected void setGroupCheck(boolean groupCheck) {
        this.groupCheck = groupCheck;
    }
    protected boolean getGroupCheck() {
        return groupCheck;
    }
    
    
    //Instance data.
    private LineData functionDataLine = new LineData();
    private LineData usereventDataLine = new LineData();
}