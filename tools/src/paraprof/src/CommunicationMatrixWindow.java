package edu.uoregon.tau.paraprof;

import java.awt.Graphics;
import java.awt.Toolkit;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.net.URL;
import java.util.*;

import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.JOptionPane;

import edu.uoregon.tau.common.Utility;
import edu.uoregon.tau.paraprof.interfaces.ParaProfWindow;
import edu.uoregon.tau.perfdmf.DataSource;
import edu.uoregon.tau.perfdmf.UserEventProfile;
import edu.uoregon.tau.vis.HeatMapData;
import edu.uoregon.tau.vis.HeatMapWindow;

public class CommunicationMatrixWindow implements ParaProfWindow, Observer, Printable {

    private HeatMapData mapData = null;
    private int size = 0;
    private final static String allPaths = "All Paths";
    private static final int COUNT = 0;
    private static final int MAX = 1;
    private static final int MIN = 2;
    private static final int MEAN = 3;
    private static final int STDDEV = 4;
    private static final int VOLUME = 5;
    private HeatMapWindow window = null;
    //private int numEvents = 0;
    private ParaProfTrial ppTrial;

    private CommunicationMatrixWindow(ParaProfTrial ppTrial) {
        this.ppTrial = ppTrial;
        
    }

    public static JFrame createCommunicationMatrixWindow(ParaProfTrial ppTrial, JFrame parentFrame) {
        CommunicationMatrixWindow matrix = new CommunicationMatrixWindow(ppTrial);
        JFrame frame = matrix.doCommunicationMatrix(ppTrial.getDataSource(), parentFrame);

        if (frame == null) {
            return frame;
        }
        frame.setLocation(WindowPlacer.getNewLocation(frame, parentFrame));

        JMenuBar mainMenu = new JMenuBar();
        mainMenu.add(ParaProfUtils.createFileMenu(matrix, matrix, frame));
        mainMenu.add(ParaProfUtils.createWindowsMenu(ppTrial, frame));
        mainMenu.add(ParaProfUtils.createHelpMenu(frame, matrix));

        frame.setJMenuBar(mainMenu);

        frame.pack();

        //Set the help window text if required.
        if (ParaProf.getHelpWindow().isVisible()) {
            matrix.help(false);
        }

        ParaProf.incrementNumWindows();
        ppTrial.addObserver(matrix);
        return frame;
    }

    private boolean updateMapData(DataSource dataSource){
        boolean foundData = false;
        int threadID = 0;
        size = dataSource.getNodeMap().size();
        // declare the heatmap data object
        mapData = new HeatMapData(size);

        for (Iterator<edu.uoregon.tau.perfdmf.Thread> it = dataSource.getAllThreads().iterator(); it.hasNext();) {
            edu.uoregon.tau.perfdmf.Thread thread = it.next();
            if (thread.getThreadID() == 0 && thread.getContextID() == 0) {
                for (Iterator<UserEventProfile> it2 = thread.getUserEventProfiles(); it2.hasNext();) {
                    UserEventProfile uep = it2.next();
                    if (uep != null && uep.getNumSamples(ppTrial.getSelectedSnapshot()) > 0) {
                        String event = uep.getName();
                        if (event.startsWith("Message size sent to node ") && event.indexOf("=>") == -1) {
                            foundData = true;
                            // split the string
                            extractData(uep, threadID, event, event, allPaths);
                        } else if (event.startsWith("Message size sent to node ") && event.indexOf("=>") >= 0) {
                            foundData = true;
                            StringTokenizer st = new StringTokenizer(event, ":");
                            String first = st.nextToken().trim();
                            String path = st.nextToken().trim();
                            // now, split up the path, and handle each node 
                            StringTokenizer st2 = new StringTokenizer(path, "=>");
                            String tmp = null;
                            while (st2.hasMoreTokens()) {
                                tmp = st2.nextToken().trim();
                                extractData(uep, threadID, event, first, tmp);
                            }
                        }
                    }
                }
                threadID++;
            }
        }
        
        if(foundData){
        	 mapData.massageData();
        }
        
        return foundData;
    }
    
    private JFrame doCommunicationMatrix(DataSource dataSource, JFrame mainFrame) {
    	boolean foundData=updateMapData(dataSource);
        if (!foundData) {
            JOptionPane.showMessageDialog(
                    mainFrame,
                    "This trial does not have communication matrix data.\nTo collect communication matrix data, set the environment variable TAU_COMM_MATRIX=1 before executing your application.",
                    "No Communication Matrix Data", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        window = new HeatMapWindow("Message Size Heat Maps", mapData);
        URL url = Utility.getResource("tau32x32.gif");
        if (url != null) {
            window.setIconImage(Toolkit.getDefaultToolkit().getImage(url));
        }

        return window;
    }

    private void extractData(UserEventProfile uep, int thread, String event, String first, String path) {
        double numEvents, eventMax, eventMin, eventMean, eventSumSqr,volume = 0;// stdev
        double[] empty = { 0, 0, 0, 0, 0, 0 };

        StringTokenizer st = new StringTokenizer(first, "Message size sent to node ");
        if (st.hasMoreTokens()) {
            int receiver = Integer.parseInt(st.nextToken());

            double[] pointData = mapData.get(thread, receiver, path);
            if (pointData == null) {
                pointData = empty;
            }

            numEvents = uep.getNumSamples(ppTrial.getSelectedSnapshot());
            pointData[COUNT] += numEvents;

            eventMax = uep.getMaxValue(ppTrial.getSelectedSnapshot());
            pointData[MAX] = Math.max(eventMax, pointData[MAX]);

            eventMin = uep.getMinValue(ppTrial.getSelectedSnapshot());
            if (pointData[MIN] > 0) {
                pointData[MIN] = Math.min(pointData[MIN], eventMin);
            } else {
                pointData[MIN] = eventMin;
            }

            // we'll recompute this later.
            eventMean = uep.getMeanValue(ppTrial.getSelectedSnapshot());
            pointData[MEAN] += eventMean;

            // we'll recompute this later.
            eventSumSqr = uep.getStdDev(ppTrial.getSelectedSnapshot());
            pointData[STDDEV] += eventSumSqr;

            volume = numEvents * eventMean;
            pointData[VOLUME] += volume;
            mapData.put(thread, receiver, path, pointData);
        }
    }

    public JFrame getWindow() {
        try {
            window.setVisible(false);
            ParaProf.decrementNumWindows();
        } catch (Exception e) {
            // do nothing
        }
        window.dispose();
        return window;
    }

    public void closeThisWindow() {
        window.setVisible(false);
    }

    public void help(boolean display) {
        ParaProf.getHelpWindow().clearText();
        if (display) {
            ParaProf.getHelpWindow().setVisible(true);
        }
        ParaProf.getHelpWindow().writeText("Communication Matrix Window");
        ParaProf.getHelpWindow().writeText("");
        ParaProf.getHelpWindow().writeText("This window shows communication data between nodes.");
    }

    public int print(Graphics graphics, PageFormat pageFormat, int pageIndex) throws PrinterException {
        // TODO Auto-generated method stub
        return 0;
    }

    public JFrame getFrame() {
        return window;
    }

	public void update(Observable o, Object arg) {
		String tmpString = (String) arg;

        if (tmpString.equals("subWindowCloseEvent")) {
            closeThisWindow();
        } else if (tmpString.equals("prefEvent")) {

        } else if (tmpString.equals("colorEvent")) {

        } else if (tmpString.equals("dataEvent")) {
        	
//        	HeatMapData mapData = generateData(ppTrial.getDataSource(), ppTrial.getSelectedSnapshot());
//            if (mapData == null) {
//                return;
//            }
//        	
//        	this.mapData = mapData;
//        	processData();
        	updateMapData(ppTrial.getDataSource());
        	window.setMapData(mapData);
        }
		
	}
}