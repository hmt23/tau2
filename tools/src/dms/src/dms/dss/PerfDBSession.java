package dms.dss;

import dms.perfdb.*;
import java.util.*;
import java.sql.*;
import java.util.Date;
import paraprof.*;

/**
 * This is the top level class for the Database implementation of the API.
 *
 * <P>CVS $Id: PerfDBSession.java,v 1.8 2004/03/31 18:08:16 khuck Exp $</P>
 * @author	Kevin Huck, Robert Bell
 * @version	0.1
 */
public class PerfDBSession extends DataSession {

    private DB db = null;
    private ConnectionManager connector;
	private Hashtable functionHash = null;
	private Hashtable userEventHash = null;

	public PerfDBSession () {
		super();
	}

// Initialization / termination routines

	public void initialize (Object obj) {
		String configFileName = (String)(obj);
		// initialize the connection to the database,
		// using the configuration settings.
		try {
			connector = new ConnectionManager(configFileName);
			connector.connect();
			db = connector.getDB();
		} catch ( Exception e ) {
			System.exit(0);
		}
	}

	public void initialize (Object obj, String password) {
		String configFileName = (String)(obj);
		// initialize the connection to the database,
		// using the configuration settings.
		try {
			connector = new ConnectionManager(configFileName, password);
			connector.connect();
			db = connector.getDB();
		} catch ( Exception e ) {
		}
	}

	public void terminate () {
		connector.dbclose();
	}

    public ConnectionManager getConnector(){
		return connector;
    }

	// returns Vector of ALL Application objects
	public ListIterator getApplicationList() {
		String whereClause = "";
		return new DataSessionIterator(Application.getApplicationList(db, whereClause));
	}

	// returns Vector of Experiment objects
	public ListIterator getExperimentList() {
		String whereClause = "";
		if (application != null)
			whereClause = "where application = " + application.getID();
		return new DataSessionIterator(Experiment.getExperimentList(db, whereClause));
	}

	// returns Vector of Trial objects
	public ListIterator getTrialList() {
		StringBuffer whereClause = new StringBuffer();
		if (experiment != null) {
			whereClause.append("where t.experiment = " + experiment.getID());
		} else if (application != null) {
			whereClause.append("where e.application = " + application.getID());
		}
		return new DataSessionIterator(Trial.getTrialList(db, whereClause.toString()));
	}

	// set the Application for this session
	public Application setApplication(int id) {
		this.application = null;
		this.experiment = null;
		this.trial = null;
		this.functionHash = null;
		this.userEventHash = null;
		// create a string to hit the database
		String whereClause = " where id = " + id;
		Vector applications = Application.getApplicationList(db, whereClause);
		if (applications.size() == 1) {
			this.application = (Application)applications.elementAt(0);
		} // else exception?
		return this.application;
	}

	// set the Application for this session
	public Application setApplication(String name, String version) {
		this.application = null;
		this.experiment = null;
		this.trial = null;
		this.functionHash = null;
		this.userEventHash = null;
		// create a string to hit the database
		StringBuffer whereClause = new StringBuffer();
		whereClause.append(" where name = '" + name + "'");
		if (version != null) {
			whereClause.append(" and version = " + version);
		}
		Vector applications = Application.getApplicationList(db, whereClause.toString());
		if (applications.size() == 1) {
			this.application = (Application)applications.elementAt(0);
		} // else exception?
		return this.application;
	}
	
	// set the Experiment for this session
	public Experiment setExperiment(int id) {
		this.experiment = null;
		this.trial = null;
		this.functionHash = null;
		this.userEventHash = null;
		// create a string to hit the database
		String whereClause;
		whereClause = " where id = " + id;
		Vector experiments = Experiment.getExperimentList(db, whereClause);
		if (experiments.size() == 1) {
			this.experiment = (Experiment)experiments.elementAt(0);
		} //else exception?

		return this.experiment;
	}

	// set the Trial for this session
	public Trial setTrial(int id) {
		this.trial = null;
		this.functionHash = null;
		this.userEventHash = null;
		// create a string to hit the database
		String whereClause;
		whereClause = " where t.id = " + id;
		Vector trials = Trial.getTrialList(db, whereClause);
		if (trials.size() == 1) {
			this.trial = (Trial)trials.elementAt(0);
		} //else exception?
		
		return this.trial;
	}

	// returns a ListIterator of Functions
	public ListIterator getFunctions() {
		String whereClause = new String();
		if (trial != null) {
			whereClause = " where trial = " + trial.getID();
		} else if (experiment != null) {
			whereClause = " where experiment = " + experiment.getID();
		} else if (application != null) {
			whereClause = " where application = " + application.getID();
		}

		functions = Function.getFunctions(this, db, whereClause);
		if (functionHash == null)
			functionHash = new Hashtable();
		Function fun;
        for(Enumeration en = functions.elements(); en.hasMoreElements() ;) {
			fun = (Function) en.nextElement();
			functionHash.put(new Integer(fun.getIndexID()),fun);
		}
		return new DataSessionIterator(functions);
	}

	// gets the mean & total data for a function
	public void getFunctionDetail(Function function) {
		StringBuffer buf = new StringBuffer();
		buf.append(" where id = " + function.getIndexID());
		if (metrics != null && metrics.size() > 0) {
			buf.append(" and metric in (");
			Metric metric;
        	for(Enumeration en = metrics.elements(); en.hasMoreElements() ;) {
				metric = (Metric) en.nextElement();
				buf.append(metric.getID());
				if (en.hasMoreElements())
					buf.append(", ");
				else
					buf.append(") ");
			}
		}
		FunctionDataObject.getFunctionDetail(db, function, buf.toString());
	}

	// returns a ListIterator of UserEvents
	public ListIterator getUserEvents() {
		String whereClause = new String();
		if (trial != null) {
			whereClause = " where t.id = " + trial.getID();
		} else if (experiment != null) {
			whereClause = " where t.experiment = " + experiment.getID();
		} else if (application != null) {
			whereClause = " where e.application = " + application.getID();
		}
		userEvents = UserEvent.getUserEvents(db, whereClause);
		if (userEventHash == null)
			userEventHash = new Hashtable();
		UserEvent ue;
        for(Enumeration en = userEvents.elements(); en.hasMoreElements() ;) {
			ue = (UserEvent) en.nextElement();
			userEventHash.put(new Integer(ue.getUserEventID()), ue);
		}
		return new DataSessionIterator(userEvents);
	}

	// sets the current function
	public Function setFunction(int id) {
		Function function = null;
		this.functions = new Vector();
		function = getFunction(id);
		if (function != null)
			this.functions.addElement(function);
		return function;
	}

	// sets the current user event
	public UserEvent setUserEvent(int id) {
		UserEvent userEvent = null;
		this.userEvents = new Vector();
		userEvent = getUserEvent(id);
		if (userEvent != null)
			this.userEvents.addElement(userEvent);
		return userEvent;
	}

	public ListIterator getFunctionData() {
		// check to make sure this is a meaningful request
		if (trial == null) {
			System.out.println("Please select a trial before getting function data.");
			return null;
		}

		// get the hash of function names first
		if (functions == null) {
			getFunctions();
		}

		// get the metric count
		int metricCount = 0;
		if (metrics != null && metrics.size() > 0) {
			metricCount = metrics.size();
		} else {
			metricCount = trial.getMetricCount();
		}

		// create a string to hit the database
		StringBuffer buf = new StringBuffer();
		buf.append(" where trial = " + trial.getID());
		if (nodes != null && nodes.size() > 0) {
			buf.append(" and node in (");
			Integer node;
        	for(Enumeration en = nodes.elements(); en.hasMoreElements() ;) {
				node = (Integer) en.nextElement();
				buf.append(node);
				if (en.hasMoreElements())
					buf.append(", ");
				else
					buf.append(") ");
			}
		}
		if (contexts != null && contexts.size() > 0) {
			buf.append(" and context in (");
			Integer context;
        	for(Enumeration en = contexts.elements(); en.hasMoreElements() ;) {
				context = (Integer) en.nextElement();
				buf.append(context);
				if (en.hasMoreElements())
					buf.append(", ");
				else
					buf.append(") ");
			}
		}
		if (threads != null && threads.size() > 0) {
			buf.append(" and thread in (");
			Integer thread;
        	for(Enumeration en = threads.elements(); en.hasMoreElements() ;) {
				thread = (Integer) en.nextElement();
				buf.append(thread);
				if (en.hasMoreElements())
					buf.append(", ");
				else
					buf.append(") ");
			}
		}
		if (functions != null && functions.size() > 0) {
			buf.append(" and function in (");
			Function function;
        	for(Enumeration en = functions.elements(); en.hasMoreElements() ;) {
				function = (Function) en.nextElement();
				buf.append(function.getIndexID());
				if (en.hasMoreElements())
					buf.append(", ");
				else
					buf.append(") ");
			}
		}
		if (metrics != null && metrics.size() > 0) {
			buf.append(" and metric in (");
			Metric metric;
        	for(Enumeration en = metrics.elements(); en.hasMoreElements() ;) {
				metric = (Metric) en.nextElement();
				buf.append(metric.getID());
				if (en.hasMoreElements())
					buf.append(", ");
				else
					buf.append(") ");
			}
		}
		functionData = FunctionDataObject.getFunctionData(db, metricCount, buf.toString());
		return new DataSessionIterator(functionData);
	}
	
	public ListIterator getUserEventData() {
		// check to make sure this is a meaningful request
		if (trial == null) {
			System.out.println("Please select a trial before getting user event data.");
			return null;
		}

		// get the hash of userEvent names first
		if (userEvents == null)
			getUserEvents();

		StringBuffer buf = new StringBuffer();
		buf.append(" where t.id = " + trial.getID());
		if (nodes != null && nodes.size() > 0) {
			buf.append(" and p.node in (");
			Integer node;
        	for(Enumeration en = nodes.elements(); en.hasMoreElements() ;) {
				node = (Integer) en.nextElement();
				buf.append(node);
				if (en.hasMoreElements())
					buf.append(", ");
				else
					buf.append(") ");
			}
		}
		if (contexts != null && contexts.size() > 0) {
			buf.append(" and p.context in (");
			Integer context;
        	for(Enumeration en = contexts.elements(); en.hasMoreElements() ;) {
				context = (Integer) en.nextElement();
				buf.append(context);
				if (en.hasMoreElements())
					buf.append(", ");
				else
					buf.append(") ");
			}
		}
		if (threads != null && threads.size() > 0) {
			buf.append(" and p.thread in (");
			Integer thread;
        	for(Enumeration en = threads.elements(); en.hasMoreElements() ;) {
				thread = (Integer) en.nextElement();
				buf.append(thread);
				if (en.hasMoreElements())
					buf.append(", ");
				else
					buf.append(") ");
			}
		}
		if (userEvents != null && userEvents.size() > 0) {
			buf.append(" and u.id in (");
			UserEvent userEvent;
        	for(Enumeration en = userEvents.elements(); en.hasMoreElements() ;) {
				userEvent = (UserEvent) en.nextElement();
				buf.append(userEvent.getUserEventID());
				if (en.hasMoreElements())
					buf.append(", ");
				else
					buf.append(") ");
			}
		}

		userEventData = UserEventDataObject.getUserEventData(db, buf.toString());
		return new DataSessionIterator(userEventData);
	}
	
	public Function getFunction(int id) {
		Function function = null;
		if (functionHash != null) {
			function = (Function)functionHash.get(new Integer(id));
		}
		if (function == null) {
			// create a string to hit the database
			String whereClause;
			whereClause = " where id = " + id;
			Vector functions = Function.getFunctions(this, db, whereClause);
			if (functions.size() == 1) {
				function = (Function)functions.elementAt(0);
			} //else exception?
			if (functionHash == null)
				functionHash = new Hashtable();
			functionHash.put(new Integer(function.getIndexID()),function);
		}
		return function;
	}
	
	public UserEvent getUserEvent(int id) {
		UserEvent userEvent = null;
		if (userEventHash != null) {
			userEvent = (UserEvent)userEventHash.get(new Integer(id));
		}
		if (userEvent == null) {
			// create a string to hit the database
			String whereClause;
			whereClause = " where u.id = " + id;
			Vector userEvents = UserEvent.getUserEvents(db, whereClause);
			if (userEvents.size() == 1) {
				userEvent = (UserEvent)userEvents.elementAt(0);
			} //else exception?
			if (userEventHash == null)
				userEventHash = new Hashtable();
			userEventHash.put(new Integer(userEvent.getUserEventID()), userEvent);
		}
		return userEvent;
	}
	
	// override the saveTrial method
	public int saveTrial () {
		int newTrialID = trial.saveTrial(db);
		Hashtable newFunHash = saveFunctions(newTrialID);
		saveFunctionData(newFunHash, metrics);
		Hashtable newUEHash = saveUserEvents(newTrialID);
		saveUserEventData(newUEHash);
		return newTrialID;
	}

	// save the functions
	private Hashtable saveFunctions(int newTrialID) {
		Hashtable newFunHash = new Hashtable();
		Enumeration enum = functions.elements();
		Function function;
		while (enum.hasMoreElements()) {
			function = (Function)enum.nextElement();
			int newFunctionID = function.saveFunction(db, newTrialID, metrics);
			newFunHash.put (new Integer(function.getIndexID()), new Integer(newFunctionID));
		}
		return newFunHash;
	}

	// save the function data
	private void saveFunctionData(Hashtable newFunHash, Vector metrics) {
		Enumeration enum = functionData.elements();
		FunctionDataObject fdo;
		while (enum.hasMoreElements()) {
			fdo = (FunctionDataObject)enum.nextElement();
			Integer newFunctionID = (Integer)newFunHash.get(new Integer(fdo.getFunctionIndexID()));
			fdo.saveFunctionData(db, newFunctionID.intValue(), metrics);
		}
	}

	// save the functions
	private Hashtable saveUserEvents(int newTrialID) {
		Hashtable newUEHash = new Hashtable();
		Enumeration enum = userEvents.elements();
		UserEvent userEvent;
		while (enum.hasMoreElements()) {
			userEvent = (UserEvent)enum.nextElement();
			int newUserEventID = userEvent.saveUserEvent(db, newTrialID);
			newUEHash.put (new Integer(userEvent.getUserEventID()), new Integer(newUserEventID));
		}
		return newUEHash;
	}

	// save the function data
	private void saveUserEventData(Hashtable newUEHash) {
		Enumeration enum = userEventData.elements();
		UserEventDataObject uedo;
		while (enum.hasMoreElements()) {
			uedo = (UserEventDataObject)enum.nextElement();
			Integer newUserEventID = (Integer)newUEHash.get(new Integer(uedo.getUserEventID()));
			uedo.saveUserEventData(db, newUserEventID.intValue());
		}
	}

/**
 * Saves the Trial.
 *
 * @param function
 * @return database index ID of the saved trial record
 */
	public int saveTrial(Trial trial) {
		return trial.saveTrial(db);
	}

/**
 * Saves the Function.
 *
 * @param function
 * @return database index ID of the saved function record
 */
	public int saveFunction(Function function, int newTrialID, Vector metrics) {
		return function.saveFunction(db, newTrialID, metrics);
	}

/**
 * Saves the FunctionDataObject.
 *
 * @param functionData
 * @return database index ID of the saved interval_location_profile record
 */
	public void saveFunctionData(FunctionDataObject functionData, int newFunctionID, Vector metrics) {
		functionData.saveFunctionData(db, newFunctionID, metrics);
		return;
	}

/**
 * Saves the UserEvent object.
 *
 * @param userEvent
 * @return database index ID of the saved user_event record
 */
	public int saveUserEvent(UserEvent userEvent, int newTrialID) {
		return userEvent.saveUserEvent(db, newTrialID);
	}

/**
 * Saves the userEventData object.
 *
 * @param userEventData
 * @return database index ID of the saved atomic_location_profile record
 */
	public void saveUserEventData(UserEventDataObject userEventData, int newUserEventID) {
		userEventData.saveUserEventData(db, newUserEventID);
		return;
	}

/**
 * Saves the ParaProfTrial object to the database
 * 
 * @param paraProfTrial
 * @return the database index ID of the saved trial record
 */

	public int saveParaProfTrial(ParaProfTrial paraProfTrial) {
		GlobalMapping mapping = paraProfTrial.getGlobalMapping();
	
		//Build an array of group names.  This speeds lookup of group names.
		Vector groups = mapping.getMapping(1);
		String[] groupNames = new String[groups.size()];
		int position = 0;
		for(Enumeration e = groups.elements(); e.hasMoreElements() ;){
	    	GlobalMappingElement group = (GlobalMappingElement) e.nextElement();
	    	groupNames[position++] = group.getMappingName();
		}

		//Get max node,context, and thread numbers.
		int[] maxNCT = paraProfTrial.getMaxNCTNumbers();
	
		// fix up some trial data
		paraProfTrial.setNodeCount(maxNCT[0]+1);
		paraProfTrial.setNumContextsPerNode(maxNCT[1]+1);
		paraProfTrial.setNumThreadsPerContext(maxNCT[2]+1);

		// get the metric count
		metrics = paraProfTrial.getMetrics();
		int metricCount = metrics.size();

		// create the Vectors to store the data
		functions = new Vector();
		functionData = new Vector();
		userEvents = new Vector();
		userEventData = new Vector();

		// create the functions
		for(Enumeration e = mapping.getMapping(0).elements(); e.hasMoreElements() ;) {
			GlobalMappingElement element = (GlobalMappingElement) e.nextElement();
			if(element!=null) {
				// create a function
				Function function = new Function(this);
				function.setName(element.getMappingName());
				function.setFunctionID(element.getMappingID());
				function.setIndexID(element.getMappingID());
				// function.setTrialID(newTrialID);
				// build the group name
				int[] groupIDs = element.getGroups();
				StringBuffer buf = new StringBuffer();
				for (int i = 0; i < element.getNumberOfGroups() ; i++) {
					if (i > 0) buf.append ("|");
					buf.append(groupNames[groupIDs[i]]);
				}
				function.setGroup(buf.toString());
				// debugging...
				System.out.println("Added function: " + function.getName());
				// put the function in the vector
				functions.add(function);

				// get the total data
				for (int i = 0 ; i < metricCount ; i++) {
					FunctionDataObject funTS = new FunctionDataObject();
					FunctionDataObject funMS = new FunctionDataObject();
		    		funTS.setNumCalls((int)element.getTotalNumberOfCalls());
		    		funTS.setNumSubroutines((int)element.getTotalNumberOfSubRoutines());
		    		funTS.setInclusivePercentage(i, element.getTotalInclusivePercentValue(i));
		    		funTS.setInclusive(i, element.getTotalInclusiveValue(i));
		    		funTS.setExclusivePercentage(i, element.getTotalExclusivePercentValue(i));
		    		funTS.setExclusive(i, element.getTotalExclusiveValue(i));
		    		funTS.setInclusivePerCall(i, element.getTotalUserSecPerCall(i));
		    		funMS.setNumCalls((int)element.getMeanNumberOfCalls());
		    		funMS.setNumSubroutines((int)element.getMeanNumberOfSubRoutines());
		    		funMS.setInclusivePercentage(i, element.getMeanInclusivePercentValue(i));
		    		funMS.setInclusive(i, element.getMeanInclusiveValue(i));
		    		funMS.setExclusivePercentage(i, element.getMeanExclusivePercentValue(i));
		    		funMS.setExclusive(i, element.getMeanExclusiveValue(i));
		    		funMS.setInclusivePerCall(i, element.getMeanUserSecPerCall(i));
					function.addTotalSummary(funTS);
					function.addMeanSummary(funMS);
				}
	    	}
	    }

		// create the user events
		for(Enumeration e = mapping.getMapping(2).elements(); e.hasMoreElements() ;) {
			GlobalMappingElement element = (GlobalMappingElement) e.nextElement();
			if(element!=null) {
				// create a user event
				UserEvent userEvent = new UserEvent();
				userEvent.setName(element.getMappingName());
				// userEvent.setTrialID(newTrialID);
				// build the group name
				int[] groupIDs = element.getGroups();
				StringBuffer buf = new StringBuffer();
				for (int i = 0; i < element.getNumberOfGroups() ; i++) {
					if (i > 0) buf.append ("|");
					buf.append(groupNames[groupIDs[i]]);
				}
				userEvent.setGroup(buf.toString());
				// debugging...
				System.out.println("Added user event: " + userEvent.getName());
				// put the userEvent in the vector
				userEvents.add(userEvent);
	    	}
	    }

	    StringBuffer groupsStringBuffer = new StringBuffer(10);
	    Vector nodes = paraProfTrial.getNCT().getNodes();
	    for(Enumeration e1 = nodes.elements(); e1.hasMoreElements() ;){
		Node node = (Node) e1.nextElement();
		Vector contexts = node.getContexts();
		for(Enumeration e2 = contexts.elements(); e2.hasMoreElements() ;){
		    Context context = (Context) e2.nextElement();
		    Vector threads = context.getThreads();
		    for(Enumeration e3 = threads.elements(); e3.hasMoreElements() ;){
			paraprof.Thread thread = (paraprof.Thread) e3.nextElement();
			Vector functions = thread.getFunctionList();
			Vector userevents = thread.getUsereventList();
			//Write out function data for this thread.
			for(Enumeration e4 = functions.elements(); e4.hasMoreElements() ;){
			    GlobalThreadDataElement function = (GlobalThreadDataElement) e4.nextElement();
			    if (function!=null){
					FunctionDataObject fdo = new FunctionDataObject();
					fdo.setNode(thread.getNodeID());
					fdo.setContext(thread.getContextID());
					fdo.setThread(thread.getThreadID());
					fdo.setFunctionIndexID(function.getMappingID());
					fdo.setNumCalls(function.getNumberOfCalls());
					fdo.setNumSubroutines(function.getNumberOfSubRoutines());
					// fdo.setInclusivePerCall(function.getUserSecPerCall());
					for (int i = 0 ; i < metricCount ; i++) {
						fdo.setInclusive(i, function.getInclusiveValue(i));
						fdo.setExclusive(i, function.getExclusiveValue(i));
						fdo.setInclusivePercentage(i, function.getInclusivePercentValue(i));
						fdo.setExclusivePercentage(i, function.getExclusivePercentValue(i));
						fdo.setInclusivePerCall(i, function.getUserSecPerCall(i));
					}
					functionData.add(fdo);
			    }
			}

			//Write out user event data for this thread.
			if(userevents!=null){
			    for(Enumeration e4 = userevents.elements(); e4.hasMoreElements() ;){
				GlobalThreadDataElement userevent = (GlobalThreadDataElement) e4.nextElement();
				if (userevent!=null){
					UserEventDataObject udo = new UserEventDataObject();
				    udo.setUserEventID(userevent.getMappingID());
					udo.setNode(thread.getNodeID());
					udo.setContext(thread.getContextID());
					udo.setThread(thread.getThreadID());
				    udo.setProfileID(userevent.getUserEventNumberValue());
				    udo.setMaximumValue(userevent.getUserEventMaxValue());
				    udo.setMinimumValue(userevent.getUserEventMinValue());
				    udo.setMeanValue(userevent.getUserEventMeanValue());
				    // udo.setStandardDeviation(userevent.getUserEventStdDevValue());
					userEventData.add(udo);
				}
			    }
			}
		    }
		}    
	    }

		// output the trial data, which also saves the functions, 
		// function data, user events and user event data
		int newTrialID = paraProfTrial.saveTrial(db);
		if (functions != null && functions.size() > 0) {
			Hashtable newFunHash = saveFunctions(newTrialID);
			saveFunctionData(newFunHash, metrics);
		}
		if (userEvents != null && userEvents.size() > 0) {
			Hashtable newUEHash = saveUserEvents(newTrialID);
			if (userEventData != null && userEventData.size() > 0) {
				saveUserEventData(newUEHash);
			}
		}
		return newTrialID;
	    
    }
};

