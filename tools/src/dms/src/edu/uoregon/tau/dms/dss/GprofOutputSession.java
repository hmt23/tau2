/* 
   Name:        TauPprofOutputSession.java
   Author:      Robert Bell
   Description:  
*/

/*
  To do: 
  1) Add some sanity checks to make sure that multiple metrics really do belong together.
  For example, wrap the creation of nodes, contexts, threads, global mapping elements, and
  the like so that they do not occur after the first metric has been loaded.  This will
  not of course ensure 100% that the data is consistent, but it will at least prevent the
  worst cases.
*/

package edu.uoregon.tau.dms.dss;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

import java.io.*;
import java.util.*;

public class GprofOutputSession extends ParaProfDataSession{

    public GprofOutputSession(){
	super();
	this.setMetrics(new Vector());
    }

    public void run(){
	try{
	    //Record time.
	    long time = System.currentTimeMillis();

	    //######
	    //Frequently used items.
	    //######
	    GlobalMappingElement globalMappingElement = null;
	    GlobalThreadDataElement globalThreadDataElement = null;
	    
	    Node node = null;
	    Context context = null;
	    edu.uoregon.tau.dms.dss.Thread thread = null;
	    
	    String inputString = null;
	    String s1 = null;
	    String s2 = null;
	    
	    String tokenString;
	    StringTokenizer genericTokenizer;
	    
	    int mappingID = -1;
	    int callPathMappingID = -1;
	    GlobalMappingElement gme1 = null;
	    GlobalMappingElement gme2 = null;
	    
	    Vector v = null;
	    File[] files = null;
	    //######
	    //End - Frequently used items.
	    //######

	    System.out.println("In the GprofOutputSession");

	    v = (Vector) initializeObject;
	    for(Enumeration e = v.elements(); e.hasMoreElements() ;){
		files = (File[]) e.nextElement();
		System.out.println("Processing data file, please wait ......");

		FileInputStream fileIn = new FileInputStream(files[0]);
		InputStreamReader inReader = new InputStreamReader(fileIn);
		BufferedReader br = new BufferedReader(inReader);

		//Need to call increaseVectorStorage() on all objects that require it.
		this.getGlobalMapping().increaseVectorStorage();

		//Since this is gprof output, there will only be one node,context, and thread.
		node = this.getNCT().addNode(0);
		context = node.addContext(0);
		thread = context.addThread(0);
		thread.setDebug(this.debug());
		if(this.debug())
		    this.outputToFile("n,c,t: " + 0 + "," + 0 + "," + 0);
		thread.initializeFunctionList(this.getGlobalMapping().getNumberOfMappings(0));

		//Time is the only metric tracked with gprof.
		this.addMetric("Time");
		if(this.debug()){
		    System.out.println("metric name: Time");
		    this.outputToFile("metric name: Time");
		}
		

		boolean callPathSection = false;
		boolean parent = true;
		Vector parents = new Vector();
		LineData self = null;
		Vector children = new Vector();

		while((inputString = br.readLine()) != null){
		    int length = inputString.length();
		    if(length!=0){
			//The first time we see g, set the call path setion to be true,
			//and the second time, set it to be false.
			if(inputString.charAt(0)=='g'){
			    if(!callPathSection){
				System.out.println("###### Call path section ######");
				callPathSection = true;
			    }
			    else{
				System.out.println("###### Summary section ######");
				callPathSection = false;
			    }
			}
			
			if(callPathSection){
				if((inputString.indexOf("index") == 0) && 
				   (inputString.indexOf("time") >= 0) && 
				   (inputString.indexOf("self") >= 0) && 
				   (inputString.indexOf("called") >= 0) && 
				   (inputString.indexOf("name") >= 0)) {
				   // this line has the lengths of the fields.
				   // we need this.
				   getFieldLengths(inputString);
				} else if(inputString.charAt(0)=='['){
				self = getSelfLineData(inputString);
				parent=false;
			    }
			    else if(inputString.charAt(0)=='-'){
				//Add self to the global mapping.
				mappingID = this.getGlobalMapping().addGlobalMapping(self.s0, 0, 1);
				gme1 = this.getGlobalMapping().getGlobalMappingElement(mappingID, 0);

				System.out.println("SELF:"+"["+gme1.getMappingID()+ "]   " +self.s0);
				globalThreadDataElement = new GlobalThreadDataElement(this.getGlobalMapping().getGlobalMappingElement(gme1.getMappingID(), 0), false);
				thread.addFunction(globalThreadDataElement, gme1.getMappingID());
				globalThreadDataElement.setInclusiveValue(0,self.d1+self.d2);
				globalThreadDataElement.setExclusiveValue(0,self.d1);
				globalThreadDataElement.setNumberOfCalls(self.i0);
				globalThreadDataElement.setNumberOfSubRoutines(children.size());
				//globalThreadDataElement.setUserSecPerCall(0,self.d1/self.i0); //Check that this is done using inclusive.
				//Set the max values (thread max values are calculated in the edu.uoregon.tau.dms.dss.Thread class).
				
				int size = parents.size();
				for(int i=0;i<size;i++){
				    LineData lineDataParent = (LineData) parents.elementAt(i);
				    mappingID = this.getGlobalMapping().addGlobalMapping(lineDataParent.s0, 0, 1);
				    String s = lineDataParent.s0 + " => " + self.s0 + "  ";
				    callPathMappingID = this.getGlobalMapping().addGlobalMapping(lineDataParent.s0 + " => " + self.s0 + "  ", 0, 1);
				    System.out.println("call path name:"+this.getGlobalMapping().getGlobalMappingElement(callPathMappingID, 0).getMappingName());
				    this.getGlobalMapping().getGlobalMappingElement(callPathMappingID, 0).setCallPathObject(true);

				    System.out.println("PARENT:"+"["+mappingID+ "] "+lineDataParent.s0);
				    System.out.println("CALLPATH:"+"["+callPathMappingID+ "] "+s);

				    globalThreadDataElement = new GlobalThreadDataElement(this.getGlobalMapping().getGlobalMappingElement(callPathMappingID, 0), false);
				    thread.addFunction(globalThreadDataElement, callPathMappingID);
				    globalThreadDataElement.setInclusiveValue(0,lineDataParent.d0+lineDataParent.d1);
				    globalThreadDataElement.setExclusiveValue(0,lineDataParent.d0);
				    globalThreadDataElement.setNumberOfCalls(lineDataParent.i0);

				}
				parents.clear();
				
				size = children.size();
				for(int i=0;i<size;i++){
				    LineData lineDataChild = (LineData) children.elementAt(i);
				    mappingID = this.getGlobalMapping().addGlobalMapping(lineDataChild.s0, 0, 1);
				    String s = self.s0 + " => " + lineDataChild.s0 + "  ";
				    callPathMappingID = this.getGlobalMapping().addGlobalMapping(self.s0 + " => " + lineDataChild.s0 + "  ", 0, 1);
				    this.getGlobalMapping().getGlobalMappingElement(callPathMappingID, 0).setCallPathObject(true);

				    System.out.println("CHILD:"+"["+mappingID+"]  "+lineDataChild.s0);
				    System.out.println("CALLPATH:"+"["+callPathMappingID+ "] "+s);

				    globalThreadDataElement = new GlobalThreadDataElement(this.getGlobalMapping().getGlobalMappingElement(callPathMappingID, 0), false);
				    thread.addFunction(globalThreadDataElement, callPathMappingID);
				    globalThreadDataElement.setInclusiveValue(0,lineDataChild.d0+lineDataChild.d1);
				    globalThreadDataElement.setExclusiveValue(0,lineDataChild.d0);
				    globalThreadDataElement.setNumberOfCalls(lineDataChild.i0);

				}
				children.clear();
				System.out.println(inputString);
				parent=true;
			    }
			    else if(inputString.charAt(length-1)==']'){
					// check for cycle line
					if (inputString.indexOf("<cycle") >= 0) {
						if(parent)
				    		parents.add(getCycleLineData(inputString));
						else
				    		children.add(getCycleLineData(inputString));
					} else {
						if(parent)
				    		parents.add(getParentLineData(inputString));
						else
				    		children.add(getChildLineData(inputString));
					}
			    }
			}
			else if(inputString.charAt(length-1)==']'){
			    System.out.println(getSummaryLineData(inputString).s0);
			}
		    }
		    genericTokenizer = new StringTokenizer(inputString, " \t\n\r");
		}
	    }
	    thread.setThreadData(0);
	    this.setMeanDataAllMetrics(0,this.getNumberOfMetrics());

	    if(CallPathUtilFuncs.isAvailable(getGlobalMapping().getMappingIterator(0))){
		setCallPathDataPresent(true);
		CallPathUtilFuncs.buildRelations(getGlobalMapping());
	    }

	    time = (System.currentTimeMillis()) - time;
	    System.out.println("Done processing data!");
	    System.out.println("Time to process (in milliseconds): " + time);

	    //Need to notify observers that we are done.  Be careful here.
	    //It is likely that they will modify swing elements.  Make sure
	    //to dump request onto the event dispatch thread to ensure
	    //safe update of said swing elements.  Remember, swing is not thread
	    //safe for the most part.
	    EventQueue.invokeLater(new Runnable(){
		    public void run(){
			GprofOutputSession.this.notifyObservers();
		    }
		});
	}
        catch(Exception e){
	    UtilFncs.systemError(e, null, "GOS01");
	}
    }
    
    //####################################
    //Private Section.
    //####################################

    //######
    //Gprof.dat string processing methods.
    //######

	private void getFieldLengths(String string) {

	/* parse a line that looks like:
index  %time    self descendants  called+self    name       index
...or...
index  % time   self    children  called         name       index
[xxxx] 100.0 xxxx.xx xxxxxxxx.xx xxxxxxx+xxxxxxx ssssss...
	*/
	    StringTokenizer st = new StringTokenizer(string, " \t\n\r");
		String index = st.nextToken();
		String percent = st.nextToken();
		if (percent.compareTo("%") == 0)
			percent += " " + st.nextToken();
		String self = st.nextToken();
		String descendants = st.nextToken();
		String called = st.nextToken();
		String name = st.nextToken();
		// this should be 0, left justified
		indexStart = string.indexOf(index);
		System.out.println("index at: " + indexStart);
		// this should be about 7, right justified
		percentStart = string.indexOf(percent);
		System.out.println("percent at: " + percentStart);
		// this should be about 13, right justified
		selfStart = string.indexOf(percent) + percent.length() + 1;
		System.out.println("self at: " + selfStart);
		// this should be about 21, right justified
		descendantsStart = string.indexOf(self) + self.length() + 1;
		System.out.println("descendants at: " + descendantsStart);
		// this should be about 33, left justified
		calledStart = string.indexOf(descendants) + descendants.length() + 1;
		System.out.println("called at: " + calledStart);
		// this should be about 49, left justified
		nameStart = string.indexOf(name);
		System.out.println("name at: " + nameStart);
		return;
	}

    private LineData getSelfLineData(String string){
	LineData lineData = new LineData();
	try{
	    StringTokenizer st = new StringTokenizer(string, " \t\n\r");
	    
	    //In some implementations, the self line will not give
	    //the number of calls for the top level function (usually main).
	    //Check the number of tokens to see if we are in this case.  If so,
	    //by default, we assume a number of calls value of 1.
	    int numberOfTokens = st.countTokens();

	    //Skip the first token.
		// Entries are numbered with consecutive integers. 
		// Each function therefore has an index number, which 
		// appears at the beginning of its primary line. Each 
		// cross-reference to a function, as a caller or 
		// subroutine of another, gives its index number as 
		// well as its name. The index number guides you if 
		// you wish to look for the entry for that function.
	    st.nextToken();

		// This is the percentage of the total time that was 
		// spent in this function, including time spent in 
		// subroutines called from this function. The time 
		// spent in this function is counted again for the 
		// callers of this function. Therefore, adding up these 
		// percentages is meaningless.
	    lineData.d0 = Double.parseDouble(st.nextToken());

		// This is the total amount of time spent in this 
		// function. This should be identical to the number 
		// printed in the seconds field for this function in 
		// the flat profile.
	    lineData.d1 = Double.parseDouble(st.nextToken());

		// This is the total amount of time spent in the 
		// subroutine calls made by this function. This should 
		// be equal to the sum of all the self and children 
		// entries of the children listed directly below this 
		// function.
	    lineData.d2 = Double.parseDouble(st.nextToken());

		// This is the number of times the function was called. 
		// If the function called itself recursively, there are 
		// two numbers, separated by a `+'. The first number 
		// counts non-recursive calls, and the second counts 
		// recursive calls. 
	    if(numberOfTokens < 7)
			// if the number of calls is absent, assume 1.
			lineData.i0 = 1;
	    else {
		String tmpStr = st.nextToken();
		if (tmpStr.indexOf("+") >= 0) {
		} else {
	    	StringTokenizer st2 = new StringTokenizer(tmpStr, "+");
			lineData.i0 = Integer.parseInt(st2.nextToken());
			// do this?
			// lineData.i0 += Integer.parseInt(st2.nextToken());
		}
		}
	    
	    lineData.s0 = st.nextToken(); //Name
		while (st.hasMoreTokens()) {
			String tmp = st.nextToken();
			if ((tmp.indexOf("[") != 0) && (!tmp.endsWith("]")))
	    		lineData.s0 += " " + tmp; //Name
		}
	}
	catch(Exception e){
	    UtilFncs.systemError(e, null, "GOS02");
	}
	return lineData;
    }

    private LineData getParentLineData(String string){
	LineData lineData = new LineData();
	try{
	    StringTokenizer st1 = new StringTokenizer(string, " \t\n\r");
	    
		// get the estimate of the amount of time spent in self when 
		// it was called from parent
	    lineData.d0 = Double.parseDouble(st1.nextToken());
		// get the estimate of the amount of time spent in subroutines 
		// of self when self was called from parent. The sum of the 
		// self and children fields is an estimate of the amount of 
		// time spent within calls to self from parent.
	    lineData.d1 = Double.parseDouble(st1.nextToken());
	    
		// For cycles, there is no ratio. To check for cycles, check
		// to see if there is a ratio.
		String tmpStr = st1.nextToken();
		if (tmpStr.indexOf("/") >= 0) {
	   		StringTokenizer st2 = new StringTokenizer(tmpStr, "/");
			// the number of times self was called from parent 
	    	lineData.i0 = Integer.parseInt(st2.nextToken());
			// the total number of nonrecursive calls to self from all 
			// its parents
	    	lineData.i1 = Integer.parseInt(st2.nextToken());
		} else {
	    	lineData.i0 = Integer.parseInt(tmpStr);
	    	lineData.i1 = Integer.parseInt(tmpStr);
		}

	    lineData.s0 = st1.nextToken(); //Name
		while (st1.hasMoreTokens()) {
			String tmp = st1.nextToken();
			if ((tmp.indexOf("[") != 0) && (!tmp.endsWith("]")))
	    		lineData.s0 += " " + tmp; //Name
		}
	}
	catch(Exception e){
		System.out.println("***\n" + string + "\n***");
		e.printStackTrace();
		UtilFncs.systemError(e, null, "GOS03");
	}
	return lineData;
    }

    private LineData getCycleLineData(String string){
	LineData lineData = new LineData();
	try{
	    StringTokenizer st1 = new StringTokenizer(string, " \t\n\r");
		// unlike the other line parsers, this function assumed a fixed
		// location for values.  That may be erroneous, but I think that
		// is the format for gprof output. Sample lines:
		/*
index  % time    self  children called     name
                                             <spontaneous>
                 0.16     1.77    1/1        start [1]
[2]    100.00    0.16     1.77    1      main [2]
                 1.77        0    1/1        a <cycle 1> [5]
----------------------------------------
                 1.77        0    1/1        main [2]
[3]     91.71    1.77        0    1+5    <cycle 1 as a whole> [3]
                 1.02        0    3          b <cycle 1> [4]
                 0.75        0    2          a <cycle 1> [5]
                    0        0    6/6        c [6]
----------------------------------------
                                  3          a <cycle 1> [5]
[4]     52.85    1.02        0    0      b <cycle 1> [4]
                                  2          a <cycle 1> [5]
                    0        0    3/6        c [6]
----------------------------------------
                 1.77        0    1/1        main [2]
                                  2          b <cycle 1> [4]
[5]     38.86    0.75        0    1      a <cycle 1> [5]
                                  3          b <cycle 1> [4]
                    0        0    3/6        c [6]
----------------------------------------
                    0        0    3/6        b <cycle 1> [4]
                    0        0    3/6        a <cycle 1> [5]
[6]      0.00       0        0    6      c [6]
                0.02        0.09    2379             hypre_SMGRelax <cycle 2> [11]
-----------------------------------------------
----------------------------------------
		*/
	    
		String tmpStr = string.substring(selfStart,descendantsStart).trim();
		if (tmpStr.length() > 0)
	    	lineData.d0 = Double.parseDouble(tmpStr);
		else
	    	lineData.d0 = 0.0;

		tmpStr = string.substring(descendantsStart,calledStart).trim();
		if (tmpStr.length() > 0)
	    	lineData.d1 = Double.parseDouble(tmpStr);
		else
	    	lineData.d1 = 0.0;

		// check for a ratio
		tmpStr = string.substring(calledStart,nameStart).trim();
		if (tmpStr.indexOf("/") >= 0) {
	   		StringTokenizer st2 = new StringTokenizer(tmpStr, "/");
			// the number of times self was called from parent 
	    	lineData.i0 = Integer.parseInt(st2.nextToken());
			// the total number of nonrecursive calls to self from all 
			// its parents
	    	lineData.i1 = Integer.parseInt(st2.nextToken());
		} else {
    		lineData.i0 = Integer.parseInt(tmpStr);
    		lineData.i1 = lineData.i0;
		}

		// the rest is the name
		int end = string.lastIndexOf("[") - 1;
	    lineData.s0 = string.substring(40,end).trim();
	}
	catch(Exception e){
		System.out.println("***\n" + string + "\n***");
		e.printStackTrace();
		UtilFncs.systemError(e, null, "GOS03");
	}
	return lineData;
    }

    private LineData getChildLineData(String string){
	LineData lineData = new LineData();
	try{
	    StringTokenizer st1 = new StringTokenizer(string, " \t\n\r");
	    
		// get the estimate of the amount of time spent directly 
		// in child when it was called from self
	    lineData.d0 = Double.parseDouble(st1.nextToken());
		// get the estimate of the amount of time spent in 
		// subroutines of child when child was called from self. 
		// The sum of the self and children fields is an estimate 
		// of the total time spent in calls to child from self
	    lineData.d1 = Double.parseDouble(st1.nextToken());
	    
		// for cycles, there is no ratio. To check for cycles, check
		// to see if there is a ratio.
		String tmpStr = st1.nextToken();
		if (tmpStr.indexOf("/") >= 0) {
			// This ratio is used to determine how much of self and 
			// children time gets credited to parent.
	   		StringTokenizer st2 = new StringTokenizer(tmpStr, "/");
			// get the number of calls to child from self
	    	lineData.i0 = Integer.parseInt(st2.nextToken());
			// get the total number of nonrecursive calls to report. 
	    	lineData.i1 = Integer.parseInt(st2.nextToken());
		} else {
	    	lineData.i0 = Integer.parseInt(tmpStr);
	    	lineData.i1 = Integer.parseInt(tmpStr);
		}

	    lineData.s0 = st1.nextToken(); //Name
		while (st1.hasMoreTokens()) {
			String tmp = st1.nextToken();
			if ((tmp.indexOf("[") != 0) && (!tmp.endsWith("]")))
	    		lineData.s0 += " " + tmp; //Name
		}
	}
	catch(Exception e){
		System.out.println("***\n" + string + "\n***");
		e.printStackTrace();
		UtilFncs.systemError(e, null, "GOS03");
	}
	return lineData;
    }

    private LineData getSummaryLineData(String string){
	LineData lineData = new LineData();
	try{
	    StringTokenizer st = new StringTokenizer(string, " \t\n\r");
	    
	    lineData.d0 = Double.parseDouble(st.nextToken());
	    lineData.d1 = Double.parseDouble(st.nextToken());
	    lineData.d2 = Double.parseDouble(st.nextToken());
	    if(st.countTokens()>5) {
	    	lineData.i0 = Integer.parseInt(st.nextToken());
	    	lineData.d3 = Double.parseDouble(st.nextToken());
	    	lineData.d4 = Double.parseDouble(st.nextToken());
		} else {
	    	lineData.i0 = 1;
	    	lineData.d3 = lineData.d2;
	    	lineData.d4 = lineData.d2;
		}
	    
	    lineData.s0 = st.nextToken(); //Name
		while (st.hasMoreTokens()) {
			String tmp = st.nextToken();
			if ((tmp.indexOf("[") != 0) && (!tmp.endsWith("]")))
	    		lineData.s0 += " " + tmp; //Name
		}
	}
	catch(Exception e){
		System.out.println(string);
	    UtilFncs.systemError(e, null, "GOS04");
	}
	return lineData;
    }

    
    //######
    //End - Gprof.dat string processing methods.
    //######

	private int indexStart = 0;
	private int percentStart = 0;
	private int selfStart = 0;
	private int descendantsStart = 0;
	private int calledStart = 0;
	private int nameStart = 0;

    //####################################
    //End - Private Section.
    //####################################

    //####################################
    //Instance data.
    //####################################
    //####################################
    //End - Instance data.
    //####################################
}
