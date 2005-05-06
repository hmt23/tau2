package edu.uoregon.tau.paraprof;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.util.*;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import net.java.games.jogl.*;
import edu.uoregon.tau.dms.dss.*;
import edu.uoregon.tau.dms.dss.Thread;
import edu.uoregon.tau.paraprof.enums.ValueType;
import edu.uoregon.tau.paraprof.enums.VisType;
import edu.uoregon.tau.paraprof.vis.*;

public class ThreeDeeWindow extends JFrame implements ActionListener, MenuListener, KeyListener, Observer,
        Printable {

    private final int defaultToScatter = 4000;

    GLCanvas canvas;
    VisRenderer visRenderer = new VisRenderer();

    private Plot plot;
    private Axes axes;
    private ColorScale colorScale = new ColorScale();

    private ParaProfTrial ppTrial;

    private JMenu optionsMenu = null;
    private JMenu windowsMenu = null;

    private ThreeDeeControlPanel controlPanel;

    private ThreeDeeSettings settings = new ThreeDeeSettings();

    private ThreeDeeSettings latestSettings;

    private Object lock = new Object();

    private ThreeDeeSettings oldSettings;

    private javax.swing.Timer jTimer;

    private JSplitPane jSplitPane;

    private Animator animator;

    private TriangleMeshPlot triangleMeshPlot;
    private BarPlot barPlot;
    private ScatterPlot scatterPlot;
    private Axes fullDataPlotAxes;
    private Axes scatterPlotAxes;

    private Vector functionNames;
    private Vector threadNames;
    private Vector functions;
    private Vector threads;

    private int units;

    float maxHeightValue = 0;
    float maxColorValue = 0;

    float minScatterValues[];
    float maxScatterValues[];

    public ThreeDeeWindow(ParaProfTrial ppTrial) {
        this.ppTrial = ppTrial;

        ppTrial.getSystemEvents().addObserver(this);

        this.setTitle("ParaProf Visualizer");

        //Add some window listener code
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent evt) {
                thisWindowClosing(evt);
            }
        });

        setupMenus();

        ThreeDeeWindow.this.validate();

        // IA64 workaround
        String os = System.getProperty("os.name").toLowerCase();
        String cpu = System.getProperty("os.arch").toLowerCase();
        if (os.startsWith("linux") && cpu.equals("ia64")) {
            this.show();
        }

        GLCapabilities glCapabilities = new GLCapabilities();

        //glCapabilities.setHardwareAccelerated(true);

        canvas = GLDrawableFactory.getFactory().createGLCanvas(glCapabilities);

        canvas.setSize(200, 200);

        DataSource dataSource = ppTrial.getDataSource();
        int numThreads = dataSource.getNumThreads();

        if (numThreads > defaultToScatter) {
            settings.setVisType(VisType.SCATTER_PLOT);
        }

        generate3dModel(true, settings);

        oldSettings = (ThreeDeeSettings) settings.clone();

        visRenderer.addShape(plot);
        visRenderer.addShape(colorScale);
        canvas.addGLEventListener(visRenderer);

        //canvas.addGLEventListener(new Gears.GearRenderer());

        canvas.addKeyListener(this);

        JPanel panel = new JPanel() {
            public Dimension getMinimumSize() {
                return new Dimension(10, 10);
            }
        };

        panel.addKeyListener(this);
        panel.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();

        gbc.fill = GridBagConstraints.BOTH;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 1;
        gbc.gridheight = 1;
        panel.add(canvas, gbc);
        panel.setPreferredSize(new Dimension(5, 5));

        controlPanel = new ThreeDeeControlPanel(this, settings, ppTrial, visRenderer);
        jSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, panel, controlPanel);
        jSplitPane.setContinuousLayout(true);
        jSplitPane.setResizeWeight(1.0);
        jSplitPane.setOneTouchExpandable(true);
        jSplitPane.addKeyListener(this);
        this.getContentPane().add(jSplitPane);

        this.setSize(1000, 700);

        //        //Grab the screen size.
        //        Toolkit tk = Toolkit.getDefaultToolkit();
        //        Dimension screenDimension = tk.getScreenSize();
        //        int screenHeight = screenDimension.height;
        //        int screenWidth = screenDimension.width;

        // IA64 workaround
        if (os.startsWith("linux") && cpu.equals("ia64")) {
            this.validate();
        }
        this.show();

        if (System.getProperty("vis.fps") != null) {
            jTimer = new javax.swing.Timer(1000, this);
            jTimer.start();
        }

        ParaProf.incrementNumWindows();

    }

    private void generateScatterPlot(boolean autoSize, ThreeDeeSettings settings) {

        Function[] scatterFunctions = settings.getScatterFunctions();

        ValueType[] scatterValueTypes = settings.getScatterValueTypes();
        int[] scatterMetricIDs = settings.getScatterMetricIDs();

        DataSource dataSource = ppTrial.getDataSource();
        int numThreads = dataSource.getNumThreads();

        float[][] values = new float[numThreads][4];

        int threadIndex = 0;

        minScatterValues = new float[4];
        maxScatterValues = new float[4];
        for (int f = 0; f < 4; f++) {
            minScatterValues[f] = Float.MAX_VALUE;
        }

        for (Iterator it = ppTrial.getDataSource().getNodes(); it.hasNext();) {
            Node node = (Node) it.next();
            for (Iterator it2 = node.getContexts(); it2.hasNext();) {
                Context context = (Context) it2.next();
                for (Iterator it3 = context.getThreads(); it3.hasNext();) {
                    edu.uoregon.tau.dms.dss.Thread thread = (edu.uoregon.tau.dms.dss.Thread) it3.next();

                    for (int f = 0; f < scatterFunctions.length; f++) {
                        if (scatterFunctions[f] != null) {
                            FunctionProfile functionProfile = thread.getFunctionProfile(scatterFunctions[f]);

                            if (functionProfile != null) {
                                values[threadIndex][f] = (float) scatterValueTypes[f].getValue(functionProfile,
                                        scatterMetricIDs[f]);
                                maxScatterValues[f] = Math.max(maxScatterValues[f], values[threadIndex][f]);
                                minScatterValues[f] = Math.min(minScatterValues[f], values[threadIndex][f]);
                            }
                        }
                    }
                    threadIndex++;
                }
            }
        }

        if (scatterPlotAxes == null) {
            scatterPlotAxes = new Axes();
        }

        setAxisStrings();

        axes = scatterPlotAxes;

        if (scatterPlot == null) {
            scatterPlot = new ScatterPlot();
            if (numThreads > defaultToScatter) {
                scatterPlot.setSphereSize(0);
            }
        }

        scatterPlot.setSize(15, 15, 15);
        scatterPlot.setAxes(axes);
        scatterPlot.setValues(values);
        scatterPlot.setColorScale(colorScale);
        plot = scatterPlot;
    }

    private void generate3dModel(boolean autoSize, ThreeDeeSettings settings) {

        if (plot != null) {
            plot.cleanUp();
        }
        
        if (settings.getVisType() == VisType.SCATTER_PLOT) {
            generateScatterPlot(autoSize, settings);
            return;
        }

        if (triangleMeshPlot == null && barPlot == null) {
            autoSize = true;
        }

        DataSource dataSource = ppTrial.getDataSource();

        int numThreads = dataSource.getNumThreads();
        int numFunctions = 0;
        
        // We must actually count the number of functions, in case there is a group mask
        for (Iterator funcIter = ppTrial.getDataSource().getFunctions(); funcIter.hasNext();) {
            Function function = (Function) funcIter.next();

            if (ppTrial.displayFunction(function)) {
                numFunctions++;
            }
        }
        
        
        
        float[][] heightValues = new float[numFunctions][numThreads];
        float[][] colorValues = new float[numFunctions][numThreads];

        boolean addFunctionNames = false;
        if (functionNames == null) {
            functionNames = new Vector();
            functions = new Vector();
            addFunctionNames = true;
        }

        if (threadNames == null) {
            threadNames = new Vector();
            threads = new Vector();

            for (Iterator it = ppTrial.getDataSource().getNodes(); it.hasNext();) {
                Node node = (Node) it.next();
                for (Iterator it2 = node.getContexts(); it2.hasNext();) {
                    Context context = (Context) it2.next();
                    for (Iterator it3 = context.getThreads(); it3.hasNext();) {
                        edu.uoregon.tau.dms.dss.Thread thread = (edu.uoregon.tau.dms.dss.Thread) it3.next();
                        threadNames.add(thread.getNodeID() + ":" + thread.getContextID() + ":"
                                + thread.getThreadID());
                        threads.add(thread);
                    }
                }
            }
        }

        maxHeightValue = 0;
        maxColorValue = 0;

        int funcIndex = 0;
        for (Iterator funcIter = ppTrial.getDataSource().getFunctions(); funcIter.hasNext();) {
            Function function = (Function) funcIter.next();

            if (!ppTrial.displayFunction(function)) {
                continue;
            }

            if (addFunctionNames) {
                functionNames.add(function.getName());
                functions.add(function);
            }
            int threadIndex = 0;
            for (Iterator it = ppTrial.getDataSource().getNodes(); it.hasNext();) {
                Node node = (Node) it.next();
                for (Iterator it2 = node.getContexts(); it2.hasNext();) {
                    Context context = (Context) it2.next();
                    for (Iterator it3 = context.getThreads(); it3.hasNext();) {
                        edu.uoregon.tau.dms.dss.Thread thread = (edu.uoregon.tau.dms.dss.Thread) it3.next();
                        FunctionProfile functionProfile = thread.getFunctionProfile(function);

                        if (functionProfile != null) {
                            heightValues[funcIndex][threadIndex] = (float) settings.getHeightValue().getValue(
                                    functionProfile, settings.getHeightMetricID());
                            colorValues[funcIndex][threadIndex] = (float) settings.getColorValue().getValue(
                                    functionProfile, settings.getColorMetricID());

                            maxHeightValue = Math.max(maxHeightValue, heightValues[funcIndex][threadIndex]);
                            maxColorValue = Math.max(maxColorValue, colorValues[funcIndex][threadIndex]);
                        }
                        threadIndex++;
                    }
                }
            }
            funcIndex++;
        }

        if (autoSize) {
            int plotWidth = 20;
            int plotDepth = 20;
            int plotHeight = 20;

            float ratio = (float) threadNames.size() / functionNames.size();

            if (ratio > 2)
                ratio = 2;
            if (ratio < 0.5f)
                ratio = 0.5f;

            if (ratio > 1.0f) {
                plotDepth = (int) (30 * (1 / ratio));
                plotWidth = 30;

            } else if (ratio < 1.0f) {
                plotDepth = 30;
                plotWidth = (int) (30 * (ratio));

            } else {
                plotWidth = 30;
                plotDepth = 30;
            }
            plotHeight = 6;

            settings.setSize(plotWidth, plotDepth, plotHeight);
            visRenderer.setAim(new Vec(settings.getPlotWidth() / 2, settings.getPlotDepth() / 2, 0));
            settings.setRegularAim(visRenderer.getAim());
        }

        if (fullDataPlotAxes == null) {
            fullDataPlotAxes = new Axes();
            fullDataPlotAxes.setHighlightColor(ppTrial.getColorChooser().getHighlightColor());
        }

        setAxisStrings();

        axes = fullDataPlotAxes;

        //axes.setOrientation(settings.getAxisOrientation());

        if (settings.getVisType() == VisType.TRIANGLE_MESH_PLOT) {
            axes.setOnEdge(false);
            if (triangleMeshPlot == null) {
                triangleMeshPlot = new TriangleMeshPlot();
                triangleMeshPlot.initialize(axes, settings.getPlotWidth(), settings.getPlotDepth(),
                        settings.getPlotHeight(), heightValues, colorValues, colorScale);
                plot = triangleMeshPlot;
            } else {
                triangleMeshPlot.setValues(settings.getPlotWidth(), settings.getPlotDepth(),
                        settings.getPlotHeight(), heightValues, colorValues);
                plot = triangleMeshPlot;
            }
        } else {
            axes.setOnEdge(true);

            if (barPlot == null) {

                barPlot = new BarPlot();
                barPlot.initialize(axes, settings.getPlotWidth(), settings.getPlotDepth(),
                        settings.getPlotHeight(), heightValues, colorValues, colorScale);
                plot = barPlot;
            } else {
                barPlot.setValues(settings.getPlotWidth(), settings.getPlotDepth(), settings.getPlotHeight(),
                        heightValues, colorValues);
                plot = barPlot;
            }
        }
    }

    private void updateSettings(ThreeDeeSettings newSettings) {


        
        if (oldSettings.getAxisOrientation() != newSettings.getAxisOrientation()) {
            axes.setOrientation(newSettings.getAxisOrientation());
        }

        if (oldSettings.getVisType() != newSettings.getVisType()) {
            // I know this is the same as the thing below, but that will probably change, I want this separate for now
            visRenderer.removeShape(plot);
            visRenderer.removeShape(colorScale);
            generate3dModel(false, newSettings);
            visRenderer.addShape(plot);
            visRenderer.addShape(colorScale);

            plot.setSelectedCol(newSettings.getSelections()[1]);
            plot.setSelectedRow(newSettings.getSelections()[0]);

            if (newSettings.getVisType() == VisType.SCATTER_PLOT) {

                visRenderer.setAim(settings.getScatterAim());
                visRenderer.setEye(settings.getScatterEye());

            } else if (newSettings.getVisType() == VisType.TRIANGLE_MESH_PLOT
                    || newSettings.getVisType() == VisType.BAR_PLOT) {
                visRenderer.setAim(settings.getRegularAim());
                visRenderer.setEye(settings.getRegularEye());
            }

        } else {

            if (newSettings.getVisType() == VisType.SCATTER_PLOT) {
                visRenderer.removeShape(plot);
                visRenderer.removeShape(colorScale);
                generate3dModel(false, newSettings);
                visRenderer.addShape(plot);
                visRenderer.addShape(colorScale);
            } else if (newSettings.getVisType() == VisType.TRIANGLE_MESH_PLOT
                    || newSettings.getVisType() == VisType.BAR_PLOT) {

                settings.setSize((int) plot.getWidth(), (int) plot.getDepth(), (int) plot.getHeight());

                if (oldSettings.getHeightMetricID() != newSettings.getHeightMetricID()
                        || oldSettings.getHeightValue() != newSettings.getHeightValue()
                        || oldSettings.getColorValue() != newSettings.getColorValue()
                        || oldSettings.getColorMetricID() != newSettings.getColorMetricID()) {
                    generate3dModel(false, newSettings);
                } else {

                    //                    plot.setSize(newSettings.getPlotWidth(), newSettings.getPlotDepth(),
                    //                            newSettings.getPlotHeight());
                    //                    axes.setSize(newSettings.getPlotWidth(), newSettings.getPlotDepth(),
                    //                            newSettings.getPlotHeight());

                    plot.setSelectedCol(newSettings.getSelections()[1]);
                    plot.setSelectedRow(newSettings.getSelections()[0]);
                }
            }
        }

        oldSettings = (ThreeDeeSettings) newSettings.clone();
    }

    public void redraw() {
        // We try to get the JSplitPane to reset the divider since the 
        // different plots have differing widths of controls 
        jSplitPane.revalidate();
        jSplitPane.validate();
        jSplitPane.resetToPreferredSizes();
        updateSettings(settings);
        visRenderer.redraw();
    }

    private void helperAddRadioMenuItem(String name, String command, boolean on, ButtonGroup group, JMenu menu) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(name, on);
        item.addActionListener(this);
        item.setActionCommand(command);
        group.add(item);
        menu.add(item);
    }

    private void setupMenus() {

        JMenuBar mainMenu = new JMenuBar();
        JMenu subMenu = null;
        JMenuItem menuItem = null;

        JMenu fileMenu = new JMenu("File");

        subMenu = new JMenu("Save ...");
        subMenu.getPopupMenu().setLightWeightPopupEnabled(false);
        subMenu.getPopupMenu().setLightWeightPopupEnabled(false);

        menuItem = new JMenuItem("Save Image");
        menuItem.addActionListener(this);
        subMenu.add(menuItem);

        fileMenu.add(subMenu);
        fileMenu.getPopupMenu().setLightWeightPopupEnabled(false);

        menuItem = new JMenuItem("Preferences...");
        menuItem.addActionListener(this);
        fileMenu.add(menuItem);

        menuItem = new JMenuItem("Print");
        menuItem.addActionListener(this);
        fileMenu.add(menuItem);

        menuItem = new JMenuItem("Close This Window");
        menuItem.addActionListener(this);
        fileMenu.add(menuItem);

        menuItem = new JMenuItem("Exit ParaProf!");
        menuItem.addActionListener(this);
        fileMenu.add(menuItem);

        fileMenu.addMenuListener(this);

        optionsMenu = new JMenu("Options");
        optionsMenu.getPopupMenu().setLightWeightPopupEnabled(false);

        JMenu unitsSubMenu = new JMenu("Select Units");
        unitsSubMenu.getPopupMenu().setLightWeightPopupEnabled(false);

        ButtonGroup group = new ButtonGroup();

        JRadioButtonMenuItem button = new JRadioButtonMenuItem("hr:min:sec", false);
        button.addActionListener(this);
        group.add(button);
        unitsSubMenu.add(button);

        button = new JRadioButtonMenuItem("Seconds", false);
        button.addActionListener(this);
        group.add(button);
        unitsSubMenu.add(button);

        button = new JRadioButtonMenuItem("Milliseconds", false);
        button.addActionListener(this);
        group.add(button);
        unitsSubMenu.add(button);

        button = new JRadioButtonMenuItem("Microseconds", true);
        button.addActionListener(this);
        group.add(button);
        unitsSubMenu.add(button);

        optionsMenu.add(unitsSubMenu);

        windowsMenu = new JMenu("Windows");
        windowsMenu.getPopupMenu().setLightWeightPopupEnabled(false);

        menuItem = new JMenuItem("Show ParaProf Manager");
        menuItem.addActionListener(this);
        windowsMenu.add(menuItem);

        menuItem = new JMenuItem("Show Function Ledger");
        menuItem.addActionListener(this);
        windowsMenu.add(menuItem);

        menuItem = new JMenuItem("Show Group Ledger");
        menuItem.addActionListener(this);
        windowsMenu.add(menuItem);

        menuItem = new JMenuItem("Show User Event Ledger");
        menuItem.addActionListener(this);
        windowsMenu.add(menuItem);

        menuItem = new JMenuItem("Show Call Path Relations");
        menuItem.addActionListener(this);
        windowsMenu.add(menuItem);

        menuItem = new JMenuItem("Close All Sub-Windows");
        menuItem.addActionListener(this);
        windowsMenu.add(menuItem);

        windowsMenu.addMenuListener(this);
        JMenu helpMenu = new JMenu("Help");
        helpMenu.getPopupMenu().setLightWeightPopupEnabled(false);

        menuItem = new JMenuItem("Show Help Window");
        menuItem.addActionListener(this);
        helpMenu.add(menuItem);

        menuItem = new JMenuItem("About ParaProf");
        menuItem.addActionListener(this);
        helpMenu.add(menuItem);

        helpMenu.addMenuListener(this);

        // now add all the menus to the main menu
        mainMenu.add(fileMenu);
        mainMenu.add(optionsMenu);
        mainMenu.add(windowsMenu);
        mainMenu.add(helpMenu);

        setJMenuBar(mainMenu);
    }

    // Menu Interface Implementation

    public void menuSelected(MenuEvent evt) {
        try {
            if (ppTrial.groupNamesPresent())
                ((JMenuItem) windowsMenu.getItem(2)).setEnabled(true);
            else
                ((JMenuItem) windowsMenu.getItem(2)).setEnabled(false);

            if (ppTrial.userEventsPresent())
                ((JMenuItem) windowsMenu.getItem(3)).setEnabled(true);
            else
                ((JMenuItem) windowsMenu.getItem(3)).setEnabled(false);

        } catch (Exception e) {
            new ParaProfErrorDialog(e);
        }
    }

    public void menuDeselected(MenuEvent evt) {
    }

    public void menuCanceled(MenuEvent evt) {
    }

    public int getUnits() {
        return units;
    }

    private Vector selectedFunctions = new Vector();

    public void update(Observable o, Object arg) {
        String tmpString = (String) arg;

        if (tmpString.equals("subWindowCloseEvent")) {
            closeThisWindow();
        } else if (tmpString.equals("prefEvent")) {

        } else if (tmpString.equals("colorEvent")) {

            if (fullDataPlotAxes != null) {
                fullDataPlotAxes.setHighlightColor(ppTrial.getColorChooser().getHighlightColor());
                visRenderer.redraw();
            }
            //
            //            for (Iterator funcIter = ppTrial.getDataSource().getFunctions(); funcIter.hasNext();) {
            //                Function function = (Function) funcIter.next();
            //                if (function == ppTrial.getHighlightedFunction()) {
            //                    int index = functions.indexOf(function);
            //                    
            //                }
            //            }

        } else if (tmpString.equals("dataEvent")) {

            functionNames = null;

            if (settings.getVisType() == VisType.BAR_PLOT
                    || settings.getVisType() == VisType.TRIANGLE_MESH_PLOT) {
                settings.setSize((int) plot.getWidth(), (int) plot.getDepth(), (int) plot.getHeight());
                settings.setRegularAim(visRenderer.getAim());
                settings.setRegularEye(visRenderer.getEye());
            } else if (settings.getVisType() == VisType.SCATTER_PLOT) {
                //                settings.setSize((int) plot.getWidth(), (int) plot.getDepth(), (int) plot.getHeight());
                settings.setScatterAim(visRenderer.getAim());
                settings.setScatterEye(visRenderer.getEye());
            }

            generate3dModel(false, settings);
            visRenderer.redraw();
            controlPanel.dataChanged();
        }

    }

    void thisWindowClosing(java.awt.event.WindowEvent e) {
        closeThisWindow();
    }

    void closeThisWindow() {
        setVisible(false);
        ppTrial.getSystemEvents().deleteObserver(this);
        ParaProf.decrementNumWindows();

        if (plot != null) {
            plot.cleanUp();
        }

        if (visRenderer != null) {
            visRenderer.cleanUp();
        }
        //animator.stop();
        //jTimer.stop();
        //jTimer = null;
        visRenderer = null;
        plot = null;

        dispose();
//        System.gc();
    }

    private void help(boolean display) {
        //Show the ParaProf help window.
        ParaProf.helpWindow.clearText();
        if (display)
            ParaProf.helpWindow.show();
        ParaProf.helpWindow.writeText("This is the 3D Window");
        ParaProf.helpWindow.writeText("");
        ParaProf.helpWindow.writeText("This window displays profile data in three dimensions through the Triangle Mesh Plot, the Bar Plot, and the ScatterPlot");
        ParaProf.helpWindow.writeText("");
        ParaProf.helpWindow.writeText("Change between the plots by selecting the desired type from the radio buttons in the upper right.");
        ParaProf.helpWindow.writeText("");
        ParaProf.helpWindow.writeText("Experiment with the controls at the right.");
        ParaProf.helpWindow.writeText("");
    }

    private long lastCall = 0;

    public BufferedImage getImage() {
        return visRenderer.createScreenShot();
    }

    public int print(Graphics g, PageFormat pageFormat, int page) {
        try {
            if (page >= 1) {
                return NO_SUCH_PAGE;
            }

            ParaProfUtils.scaleForPrint(g, pageFormat, canvas.getWidth(), canvas.getHeight());

            BufferedImage screenShot = visRenderer.createScreenShot();

            ImageObserver imageObserver = new ImageObserver() {
                public boolean imageUpdate(Image image, int a, int b, int c, int d, int e) {
                    return false;
                }
            };

            g.drawImage(screenShot, 0, 0, Color.black, imageObserver);

            //            renderIt((Graphics2D) g, false, true, false);

            return Printable.PAGE_EXISTS;
        } catch (Exception e) {
            new ParaProfErrorDialog(e);
            return NO_SUCH_PAGE;
        }
    }

    public void actionPerformed(ActionEvent evt) {
        try {
            Object EventSrc = evt.getSource();

            if (EventSrc instanceof javax.swing.Timer) {
                // the timer has ticked, get progress and post
                if (visRenderer == null) { // if it's been closed, go away
                    ((javax.swing.Timer)EventSrc).stop();
                    return;
                }

                long time = System.currentTimeMillis();

                int numFrames = visRenderer.getFramesRendered();
                if (numFrames != 0) {
                    visRenderer.setFramesRendered(0);

                    float fps = numFrames / ((time - lastCall) / (float) 1000);

                    visRenderer.setFps(fps);

                    System.out.println("FPS = " + fps);
                    lastCall = time;
                }
                return;
            }

            String arg = evt.getActionCommand();

            if (arg.equals("Exit ParaProf!")) {
                setVisible(false);
                dispose();
                ParaProf.exitParaProf(0);

            } else if (arg.equals("Preferences...")) {
                ppTrial.getPreferencesWindow().showPreferencesWindow();
            } else if (arg.equals("Close This Window")) {
                closeThisWindow();
            } else if (arg.equals("Show ParaProf Manager")) {
                (new ParaProfManagerWindow()).show();
            } else if (arg.equals("Show Function Ledger")) {
                (new LedgerWindow(ppTrial, 0)).show();
            } else if (arg.equals("Show Group Ledger")) {
                (new LedgerWindow(ppTrial, 1)).show();
            } else if (arg.equals("Show User Event Ledger")) {
                (new LedgerWindow(ppTrial, 2)).show();
            } else if (arg.equals("Show Call Path Relations")) {
                CallPathTextWindow tmpRef = new CallPathTextWindow(ppTrial, -1, -1, -1,
                        new DataSorter(ppTrial), 2);
                ppTrial.getSystemEvents().addObserver(tmpRef);
                tmpRef.show();
            } else if (arg.equals("Close All Sub-Windows")) {
                ppTrial.getSystemEvents().updateRegisteredObjects("subWindowCloseEvent");

            } else if (arg.equals("Print")) {
                ParaProfUtils.print(this);
            } else if (arg.equals("Microseconds")) {
                units = 0;
                setAxisStrings();
                controlPanel.dataChanged();
                visRenderer.redraw();
            } else if (arg.equals("Milliseconds")) {
                units = 1;
                setAxisStrings();
                controlPanel.dataChanged();
                visRenderer.redraw();
            } else if (arg.equals("Seconds")) {
                units = 2;
                setAxisStrings();
                controlPanel.dataChanged();
                visRenderer.redraw();
            } else if (arg.equals("hr:min:sec")) {
                units = 3;
                setAxisStrings();
                controlPanel.dataChanged();
                visRenderer.redraw();

            } else if (arg.equals("Save Image")) {
                ParaProfImageOutput.save3dImage(ThreeDeeWindow.this);
            } else if (arg.equals("Close All Sub-Windows")) {
                ppTrial.getSystemEvents().updateRegisteredObjects("subWindowCloseEvent");
            } else if (arg.equals("About ParaProf")) {
                JOptionPane.showMessageDialog(this, ParaProf.getInfoString());
            } else if (arg.equals("Show Help Window")) {
                this.help(true);
            }

        } catch (Exception e) {
            ParaProfUtils.handleException(e);
        }
    }

    /**
     * @return Returns the colorScale.
     */
    public ColorScale getColorScale() {
        return colorScale;
    }

    /**
     * @return Returns the plot.
     */
    public Plot getPlot() {
        return plot;
    }

    /**
     * @param plot The plot to set.
     */
    public void setPlot(Plot plot) {
        this.plot = plot;
    }

    public Vector getFunctionNames() {
        return functionNames;
    }

    public Vector getThreadNames() {
        return threadNames;
    }

    public String getFunctionName(int index) {
        if (functionNames == null)
            return null;
        return (String) functionNames.get(index);
    }

    public String getThreadName(int index) {
        if (threadNames == null)
            return null;
        return (String) threadNames.get(index);
    }

    public String getSelectedHeightValue() {
        if (threads == null || functionNames == null)
            return "";

        if (settings.getSelections()[1] < 0 || settings.getSelections()[0] < 0)
            return "";

        Thread thread = (Thread) threads.get(settings.getSelections()[1]);

        Function function = ppTrial.getDataSource().getFunction(
                (String) functionNames.get(settings.getSelections()[0]));
        FunctionProfile fp = thread.getFunctionProfile(function);

        if (fp == null) {
            return "no value";
        }

        int units = this.units;
        ParaProfMetric ppMetric = ppTrial.getMetric(settings.getHeightMetricID());
        if (!ppMetric.isTimeMetric()) {
            units = 0;
        }

        return UtilFncs.getOutputString(units,
                settings.getHeightValue().getValue(fp, settings.getHeightMetricID()), 6).trim()
                + getUnitsString(units, settings.getHeightValue(), ppMetric);

        //Double.toString(settings.getHeightValue().getValue(fp, settings.getHeightMetricID()));

    }

    public String getSelectedColorValue() {
        if (threads == null || functionNames == null)
            return "";

        if (settings.getSelections()[1] < 0 || settings.getSelections()[0] < 0)
            return "";

        Thread thread = (Thread) threads.get(settings.getSelections()[1]);

        Function function = ppTrial.getDataSource().getFunction(
                (String) functionNames.get(settings.getSelections()[0]));
        FunctionProfile fp = thread.getFunctionProfile(function);

        if (fp == null) {
            return "no value";
        }

        int units = this.units;
        ParaProfMetric ppMetric = ppTrial.getMetric(settings.getColorMetricID());
        if (!ppMetric.isTimeMetric()) {
            units = 0;
        }

        return UtilFncs.getOutputString(units,
                settings.getColorValue().getValue(fp, settings.getColorMetricID()), 6).trim()
                + getUnitsString(units, settings.getColorValue(), ppMetric);

        //return Double.toString(settings.getColorValue().getValue(fp, settings.getColorMetricID()));

    }

    private String getUnitsString(int units, ValueType valueType, ParaProfMetric ppMetric) {
        return valueType.getSuffix(units, ppMetric);
    }

    private void setAxisStrings() {

        if (settings.getVisType() == VisType.SCATTER_PLOT) {

            Function[] scatterFunctions = settings.getScatterFunctions();
            ValueType[] scatterValueTypes = settings.getScatterValueTypes();
            int[] scatterMetricIDs = settings.getScatterMetricIDs();

            Vector axisNames = new Vector();
            for (int f = 0; f < scatterFunctions.length; f++) {
                if (scatterFunctions[f] != null) {
                    // e.g. "MPI_Recv()\n(Exclusive, Time)"
                    if (scatterValueTypes[f] == ValueType.NUMCALLS || scatterValueTypes[f] == ValueType.NUMSUBR) {
                        axisNames.add(scatterFunctions[f].getName() + "\n(" + scatterValueTypes[f].toString()
                                + ")");
                    } else {
                        axisNames.add(scatterFunctions[f].getName() + "\n(" + scatterValueTypes[f].toString()
                                + ", " + ppTrial.getMetricName(scatterMetricIDs[f]) + ")");
                    }
                } else {
                    axisNames.add("none");
                }
            }

            Vector[] axisStrings = new Vector[4];

            for (int i = 0; i < 4; i++) {
                if (minScatterValues[i] == Float.MAX_VALUE) {
                    minScatterValues[i] = 0;
                }

                ParaProfMetric ppMetric = ppTrial.getMetric(scatterMetricIDs[i]);

                int units = scatterValueTypes[i].getUnits(this.units, ppMetric);

                axisStrings[i] = new Vector();
                axisStrings[i].add(UtilFncs.getOutputString(units, minScatterValues[i], 6).trim());
                axisStrings[i].add(UtilFncs.getOutputString(units,
                        minScatterValues[i] + (maxScatterValues[i] - minScatterValues[i]) * .25, 6).trim());
                axisStrings[i].add(UtilFncs.getOutputString(units,
                        minScatterValues[i] + (maxScatterValues[i] - minScatterValues[i]) * .50, 6).trim());
                axisStrings[i].add(UtilFncs.getOutputString(units,
                        minScatterValues[i] + (maxScatterValues[i] - minScatterValues[i]) * .75, 6).trim());
                axisStrings[i].add(UtilFncs.getOutputString(units,
                        minScatterValues[i] + (maxScatterValues[i] - minScatterValues[i]), 6).trim());
            }

            ParaProfMetric ppMetric = ppTrial.getMetric(scatterMetricIDs[3]);
            int units = scatterValueTypes[3].getUnits(this.units, ppMetric);

            colorScale.setStrings(UtilFncs.getOutputString(units, minScatterValues[3], 6).trim(),
                    UtilFncs.getOutputString(units, maxScatterValues[3], 6).trim(), (String) axisNames.get(3));

            scatterPlotAxes.setStrings((String) axisNames.get(0), (String) axisNames.get(1),
                    (String) axisNames.get(2), axisStrings[0], axisStrings[1], axisStrings[2]);

        } else {

            Vector zStrings = new Vector();
            zStrings.add("0");

            int units;

            ParaProfMetric ppMetric = ppTrial.getMetric(settings.getHeightMetricID());
            units = settings.getHeightValue().getUnits(this.units, ppMetric);

            zStrings.add(UtilFncs.getOutputString(units, maxHeightValue * 0.25, 6).trim());
            zStrings.add(UtilFncs.getOutputString(units, maxHeightValue * 0.50, 6).trim());
            zStrings.add(UtilFncs.getOutputString(units, maxHeightValue * 0.75, 6).trim());
            zStrings.add(UtilFncs.getOutputString(units, maxHeightValue, 6).trim());

            String zAxisLabel = settings.getHeightValue().getSuffix(units, ppMetric);

            ppMetric = ppTrial.getMetric(settings.getColorMetricID());
            units = settings.getColorValue().getUnits(this.units, ppMetric);

            String colorAxisLabel = settings.getColorValue().getSuffix(units, ppMetric);

            colorScale.setStrings("0", UtilFncs.getOutputString(units, maxColorValue, 6).trim(), colorAxisLabel);

            //String zAxisLabel = settings.getHeightValue().toString() + ", " + ppTrial.getMetricName(settings.getHeightMetricID());
            //String zAxisLabel = "";

            fullDataPlotAxes.setStrings("Threads", "Functions", zAxisLabel, threadNames, functionNames,
                    zStrings);
        }
    }

    /* (non-Javadoc)
     * @see java.awt.event.KeyListener#keyPressed(java.awt.event.KeyEvent)
     */
    public void keyPressed(KeyEvent e) {
        // TODO Auto-generated method stub

    }

    /* (non-Javadoc)
     * @see java.awt.event.KeyListener#keyReleased(java.awt.event.KeyEvent)
     */
    public void keyReleased(KeyEvent e) {
        // TODO Auto-generated method stub

    }

    /* (non-Javadoc)
     * @see java.awt.event.KeyListener#keyTyped(java.awt.event.KeyEvent)
     */
    public void keyTyped(KeyEvent e) {
        // TODO Auto-generated method stub
        try {
            // zoom in and out on +/-
            if (e.getKeyChar() == '+') {
                visRenderer.zoomIn();
            } else if (e.getKeyChar() == '-') {
                visRenderer.zoomOut();
            }
        } catch (Exception exp) {
            ParaProfUtils.handleException(exp);
        }

    }

}
