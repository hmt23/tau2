package edu.uoregon.tau.paraprof;

import java.awt.*;
import java.awt.event.*;
import java.awt.print.*;
import java.io.*;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import edu.uoregon.tau.paraprof.interfaces.ImageExport;
import edu.uoregon.tau.paraprof.interfaces.ParaProfWindow;
import edu.uoregon.tau.paraprof.interfaces.UnitListener;
import edu.uoregon.tau.paraprof.script.ParaProfFunctionScript;
import edu.uoregon.tau.paraprof.script.ParaProfScript;
import edu.uoregon.tau.paraprof.script.ParaProfTrialScript;
import edu.uoregon.tau.paraprof.treetable.TreeTableWindow;
import edu.uoregon.tau.perfdmf.*;
import edu.uoregon.tau.perfdmf.Thread;

public class ParaProfUtils {

    static boolean verbose;
    static boolean verboseSet;

    // Suppress default constructor for noninstantiability
    private ParaProfUtils() {
        // This constructor will never be invoked
    }

    public static FunctionBarChartWindow createFunctionBarChartWindow(ParaProfTrial ppTrial, Function function, Component parent) {
        return new FunctionBarChartWindow(ppTrial, function, parent);
    }

    public static FunctionBarChartWindow createFunctionBarChartWindow(ParaProfTrial ppTrial, Thread thread, Function phase,
            Component parent) {
        return new FunctionBarChartWindow(ppTrial, thread, phase, parent);
    }

    public static LedgerWindow createLedgerWindow(ParaProfTrial ppTrial, int windowType) {
        return new LedgerWindow(ppTrial, windowType, null);
    }

    public static LedgerWindow createLedgerWindow(ParaProfTrial ppTrial, int windowType, Component parent) {
        return new LedgerWindow(ppTrial, windowType, parent);
    }

    private static void checkVerbose() {
        if (!verboseSet) {
            if (System.getProperty("paraprof.verbose") != null) {
                verbose = true;
            }
            verboseSet = true;
        }
    }

    public static void verr(String string) {
        checkVerbose();

        if (verbose) {
            System.err.println(string);
        }
    }

    public static void vout(String string) {
        checkVerbose();

        if (verbose) {
            System.out.println(string);
        }
    }

    public static void vout(Object obj, String string) {
        checkVerbose();

        if (verbose) {

            String className = obj.getClass().getName();
            int lastDot = className.lastIndexOf('.');
            if (lastDot != -1) {
                className = className.substring(lastDot + 1);
            }

            System.out.println(className + ": " + string);
        }
    }

    public static void verr(Object obj, String string) {
        checkVerbose();

        if (verbose) {

            String className = obj.getClass().getName();
            int lastDot = className.lastIndexOf('.');
            if (lastDot != -1) {
                className = className.substring(lastDot + 1);
            }

            System.err.println(className + ": " + string);
        }
    }

    public static void helperAddRadioMenuItem(String name, String command, boolean on, ButtonGroup group, JMenu menu,
            ActionListener act) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(name, on);
        item.addActionListener(act);
        item.setActionCommand(command);
        group.add(item);
        menu.add(item);
    }

    public static void addCompItem(Container jPanel, Component c, GridBagConstraints gbc, int x, int y, int w, int h) {
        gbc.gridx = x;
        gbc.gridy = y;
        gbc.gridwidth = w;
        gbc.gridheight = h;
        jPanel.add(c, gbc);
    }

    public static void print(Printable printable) {
        PrinterJob job = PrinterJob.getPrinterJob();
        PageFormat defaultFormat = job.defaultPage();
        PageFormat selectedFormat = job.pageDialog(defaultFormat);
        if (defaultFormat != selectedFormat) { // only proceed if the user did not select cancel
            job.setPrintable(printable, selectedFormat);
            //if (job.getPrintService() != null) {
            if (job.printDialog()) { // only proceed if the user did not select cancel
                try {
                    job.print();
                } catch (PrinterException e) {
                    ParaProfUtils.handleException(e);
                }
            }
            //}
        }

    }

    public static JMenu createHelpMenu(final JFrame owner, final ParaProfWindow ppWindow) {

        ActionListener actionListener = new ActionListener() {
            public void actionPerformed(ActionEvent evt) {

                try {
                    Object EventSrc = evt.getSource();

                    if (EventSrc instanceof JMenuItem) {
                        String arg = evt.getActionCommand();

                        if (arg.equals("About ParaProf")) {
                            JOptionPane.showMessageDialog(owner, ParaProf.getInfoString());
                        } else if (arg.equals("Show Help Window")) {
                            ppWindow.help(true);
                        }
                    }
                } catch (Exception e) {
                    ParaProfUtils.handleException(e);
                }
            }

        };

        JMenu helpMenu = new JMenu("Help");

        JMenuItem menuItem = new JMenuItem("Show Help Window");
        menuItem.addActionListener(actionListener);
        helpMenu.add(menuItem);

        menuItem = new JMenuItem("About ParaProf");
        menuItem.addActionListener(actionListener);
        helpMenu.add(menuItem);

        return helpMenu;
    }

    public static JMenu createFileMenu(final ParaProfWindow window, final Printable printable, final Object panel) {

        if (printable == null) {
            throw new ParaProfException("File menu created with null panel!");
        }

        ActionListener actionListener = new ActionListener() {
            public void actionPerformed(ActionEvent evt) {

                try {

                    String arg = evt.getActionCommand();

                    if (arg.equals("Print")) {
                        ParaProfUtils.print(printable);
                    } else if (arg.equals("Preferences...")) {
                        // this is just in case there is ever a ParaProfWindow that is not a JFrame (there shouldn't be)
                        ParaProf.preferencesWindow.showPreferencesWindow(window instanceof JFrame ? (JFrame) window : null);
                    } else if (arg.equals("Save as Bitmap Image")) {

                        if (panel instanceof ImageExport) {
                            ParaProfImageOutput.saveImage((ImageExport) panel);
                        } else if (panel instanceof ThreeDeeWindow) {
                            ThreeDeeWindow threeDeeWindow = (ThreeDeeWindow) panel;
                            ParaProfImageOutput.save3dImage(threeDeeWindow);
                        } else {
                            throw new ParaProfException("Don't know how to \"Save Image\" for " + panel.getClass());
                        }

                    } else if (arg.equals("Save as Vector Graphics")) {
                        if (panel instanceof ImageExport) {
                            JVMDependent.exportVector((ImageExport) panel);
                        } else if (panel instanceof ThreeDeeWindow) {
                            JOptionPane.showMessageDialog((JFrame) window, "Can't save 3D visualization as vector graphics");
                        } else {
                            throw new ParaProfException("Don't know how to \"Save as Vector Graphics\" for " + panel.getClass());
                        }
                    } else if (arg.equals("Close This Window")) {
                        window.closeThisWindow();
                    } else if (arg.equals("Exit ParaProf!")) {
                        ParaProf.exitParaProf(0);
                    }
                } catch (Exception e) {
                    ParaProfUtils.handleException(e);
                }
            }

        };

        JMenu fileMenu = new JMenu("File");

        JMenu subMenu = new JMenu("Save ...");
        subMenu.getPopupMenu().setLightWeightPopupEnabled(false);

        JMenuItem menuItem = new JMenuItem("Save as Bitmap Image");
        menuItem.addActionListener(actionListener);
        subMenu.add(menuItem);
        menuItem = new JMenuItem("Save as Vector Graphics");
        menuItem.addActionListener(actionListener);
        subMenu.add(menuItem);
        fileMenu.add(subMenu);

        menuItem = new JMenuItem("Preferences...");
        menuItem.addActionListener(actionListener);
        fileMenu.add(menuItem);

        menuItem = new JMenuItem("Print");
        menuItem.addActionListener(actionListener);
        fileMenu.add(menuItem);

        menuItem = new JMenuItem("Close This Window");
        menuItem.addActionListener(actionListener);
        fileMenu.add(menuItem);

        menuItem = new JMenuItem("Exit ParaProf!");
        menuItem.addActionListener(actionListener);
        fileMenu.add(menuItem);

        return fileMenu;
    }

    public static JMenu createThreadMenu(final ParaProfTrial ppTrial, final JFrame owner,
            final edu.uoregon.tau.perfdmf.Thread thread) {

        JMenu threadMenu = new JMenu("Thread");

        JMenuItem menuItem;

        menuItem = new JMenuItem("Function Graph");
        threadMenu.add(menuItem);

        menuItem = new JMenuItem("Callpath Relations");
        threadMenu.add(menuItem);

        menuItem = new JMenuItem("Call Graph");
        threadMenu.add(menuItem);

        menuItem = new JMenuItem("Function Statistics");
        threadMenu.add(menuItem);

        menuItem = new JMenuItem("User Event Statistics");
        threadMenu.add(menuItem);

        return threadMenu;

    }

    public static JMenu createFunctionMenu(final ParaProfTrial ppTrial, final JFrame owner,
            final edu.uoregon.tau.perfdmf.Thread thread) {

        JMenu menu = new JMenu("Function");

        JMenuItem menuItem;

        menuItem = new JMenuItem("Thread Graph");
        menu.add(menuItem);

        menuItem = new JMenuItem("Histogram");
        menu.add(menuItem);

        return menu;

    }

    private static JMenuItem createMenuItem(String text, ActionListener actionListener, boolean enabled) {
        JMenuItem menuItem = new JMenuItem(text);
        menuItem.setEnabled(enabled);
        menuItem.addActionListener(actionListener);
        return menuItem;
    }

    public static JMenu createScriptMenu(final ParaProfTrial ppTrial, final JFrame owner) {

        final JMenu menu = new JMenu("PyScript");

        menu.addMenuListener(new MenuListener() {

            public void menuCanceled(MenuEvent e) {
                // TODO Auto-generated method stub

            }

            public void menuDeselected(MenuEvent e) {
                // TODO Auto-generated method stub

            }

            public void menuSelected(MenuEvent e) {
                menu.removeAll();
                JMenuItem menuitem = new JMenuItem("Reload scripts");
                menuitem.addActionListener(new ActionListener() {

                    public void actionPerformed(ActionEvent e) {
                        ParaProf.loadScripts();
                    }
                });
                menu.add(menuitem);
                //menu.add
                for (int i = 0; i < ParaProf.scripts.size(); i++) {
                    final ParaProfScript pps = (ParaProfScript) ParaProf.scripts.get(i);
                    if (pps instanceof ParaProfTrialScript) {
                        JMenuItem menuItem = new JMenuItem("[Script] " + pps.getName());
                        menuItem.addActionListener(new ActionListener() {
                            public void actionPerformed(ActionEvent e) {
                                try {
                                    ((ParaProfTrialScript) pps).run(ppTrial);
                                } catch (Exception ex) {
                                    new ParaProfErrorDialog("Exception while executing script:", ex);
                                }
                            }
                        });
                        menu.add(menuItem);
                    }
                }

            }
        });

        return menu;
    }

    public static JMenu createWindowsMenu(final ParaProfTrial ppTrial, final JFrame owner) {

        ActionListener actionListener = new ActionListener() {
            public void actionPerformed(ActionEvent evt) {

                try {

                    String arg = evt.getActionCommand();

                    if (arg.equals("ParaProf Manager")) {
                        (new ParaProfManagerWindow()).show();
                    } else if (arg.equals("Function Ledger")) {
                        (new LedgerWindow(ppTrial, 0, owner)).show();
                    } else if (arg.equals("Group Ledger")) {
                        (new LedgerWindow(ppTrial, 1, owner)).show();
                    } else if (arg.equals("User Event Ledger")) {
                        (new LedgerWindow(ppTrial, 2, owner)).show();
                    } else if (arg.equals("Phase Ledger")) {
                        (new LedgerWindow(ppTrial, 3, owner)).show();
                    } else if (arg.equals("3D Visualization")) {

                        if (JVMDependent.version.equals("1.3")) {
                            JOptionPane.showMessageDialog(owner, "3D Visualization requires Java 1.4 or above\n"
                                    + "Please make sure Java 1.4 is in your path, then reconfigure TAU and re-run ParaProf");
                            return;
                        }

                        //Gears.main(null);
                        //(new Gears()).show();

                        try {

                            (new ThreeDeeWindow(ppTrial, owner)).show();
                            //(new ThreeDeeWindow()).show();
                        } catch (UnsatisfiedLinkError e) {
                            JOptionPane.showMessageDialog(owner, "Unable to load jogl library.  Possible reasons:\n"
                                    + "libjogl.so is not in your LD_LIBRARY_PATH.\n"
                                    + "Jogl is not built for this platform.\nOpenGL is not installed\n\n"
                                    + "Jogl is available at jogl.dev.java.net");
                        } catch (UnsupportedClassVersionError e) {
                            JOptionPane.showMessageDialog(owner,
                                    "Unsupported class version.  Are you sure you're using Java 1.4 or above?");
                        } catch (Exception gle) {
                            new ParaProfErrorDialog("Unable to initialize OpenGL: ", gle);
                        }

                    } else if (arg.equals("Close All Sub-Windows")) {
                        ppTrial.updateRegisteredObjects("subWindowCloseEvent");
                    }

                } catch (Exception e) {
                    ParaProfUtils.handleException(e);
                }
            }

        };

        JMenu windowsMenu = new JMenu("Windows");

        JMenuItem menuItem;

        menuItem = new JMenuItem("ParaProf Manager");
        menuItem.addActionListener(actionListener);
        windowsMenu.add(menuItem);

        windowsMenu.add(new JSeparator());

        menuItem = new JMenuItem("3D Visualization");
        menuItem.addActionListener(actionListener);
        windowsMenu.add(menuItem);

        //menuItem = new JMenuItem("Call Path Relations");
        //menuItem.addActionListener(actionListener);
        //windowsMenu.add(menuItem);

        windowsMenu.add(new JSeparator());

        ActionListener fActionListener = new ActionListener() {
            public void actionPerformed(ActionEvent evt) {
                FunctionSelectorDialog fSelector = new FunctionSelectorDialog(owner, true,
                        ppTrial.getDataSource().getFunctions(), null, false);
                if (fSelector.choose()) {
                    Function selectedFunction = (Function) fSelector.getSelectedObject();

                    String arg = evt.getActionCommand();

                    if (arg.equals("Bar Chart")) {
                        FunctionBarChartWindow w = new FunctionBarChartWindow(ppTrial, selectedFunction, owner);
                        w.show();
                    } else if (arg.equals("Histogram")) {
                        HistogramWindow w = new HistogramWindow(ppTrial, selectedFunction, owner);
                        w.show();
                    }
                }
            }
        };

        final JMenu functionWindows = new JMenu("Function");
        functionWindows.getPopupMenu().setLightWeightPopupEnabled(false);

        functionWindows.add(createMenuItem("Bar Chart", fActionListener, true));
        functionWindows.add(createMenuItem("Histogram", fActionListener, true));
        windowsMenu.add(functionWindows);

        ActionListener tActionListener = new ActionListener() {
            public void actionPerformed(ActionEvent evt) {
                String arg = evt.getActionCommand();

                List list = new ArrayList(ppTrial.getDataSource().getAllThreads());
                if (ppTrial.getDataSource().getAllThreads().size() > 1 && arg.equals("User Event Statistics") == false) {
                    list.add(0, ppTrial.getDataSource().getStdDevData());
                    list.add(1, ppTrial.getDataSource().getMeanData());
                }

                FunctionSelectorDialog fSelector = new FunctionSelectorDialog(owner, true, list.iterator(), null, false);
                fSelector.setTitle("Select a Thread");
                if (fSelector.choose()) {
                    edu.uoregon.tau.perfdmf.Thread selectedThread = (edu.uoregon.tau.perfdmf.Thread) fSelector.getSelectedObject();

                    if (arg.equals("Bar Chart")) {
                        FunctionBarChartWindow w = new FunctionBarChartWindow(ppTrial, selectedThread, null, owner);
                        w.setVisible(true);
                    } else if (arg.equals("Statistics Text")) {
                        (new StatWindow(ppTrial, selectedThread, false, null, owner)).setVisible(true);
                    } else if (arg.equals("Statistics Table")) {
                        (new TreeTableWindow(ppTrial, selectedThread, owner)).setVisible(true);
                    } else if (arg.equals("Call Graph")) {
                        (new CallGraphWindow(ppTrial, selectedThread, owner)).setVisible(true);
                    } else if (arg.equals("Call Path Relations")) {
                        (new CallPathTextWindow(ppTrial, selectedThread, owner)).setVisible(true);
                    } else if (arg.equals("User Event Statistics")) {
                        (new StatWindow(ppTrial, selectedThread, true, null, owner)).setVisible(true);
                    }
                }
            }
        };

        final JMenu threadWindows = new JMenu("Thread");
        threadWindows.getPopupMenu().setLightWeightPopupEnabled(false);
        threadWindows.add(createMenuItem("Bar Chart", tActionListener, true));
        threadWindows.add(createMenuItem("Statistics Text", tActionListener, true));
        threadWindows.add(createMenuItem("Statistics Table", tActionListener, true));
        threadWindows.add(createMenuItem("Call Graph", tActionListener, ppTrial.callPathDataPresent()));
        threadWindows.add(createMenuItem("Call Path Relations", tActionListener, ppTrial.callPathDataPresent()));
        threadWindows.add(createMenuItem("User Event Statistics", tActionListener, ppTrial.userEventsPresent()));

        windowsMenu.add(threadWindows);

        windowsMenu.add(new JSeparator());

        menuItem = new JMenuItem("Function Ledger");
        menuItem.addActionListener(actionListener);
        windowsMenu.add(menuItem);

        final JMenuItem groupLedger = new JMenuItem("Group Ledger");
        groupLedger.addActionListener(actionListener);
        windowsMenu.add(groupLedger);

        final JMenuItem userEventLedger = new JMenuItem("User Event Ledger");
        userEventLedger.addActionListener(actionListener);
        windowsMenu.add(userEventLedger);

        if (ppTrial.getDataSource().getPhasesPresent()) {
            final JMenuItem phaseLedger = new JMenuItem("Phase Ledger");
            phaseLedger.addActionListener(actionListener);
            windowsMenu.add(phaseLedger);
        }

        windowsMenu.add(new JSeparator());

        menuItem = new JMenuItem("Close All Sub-Windows");
        menuItem.addActionListener(actionListener);
        windowsMenu.add(menuItem);

        MenuListener menuListener = new MenuListener() {
            public void menuSelected(MenuEvent evt) {
                try {
                    groupLedger.setEnabled(ppTrial.groupNamesPresent());
                    userEventLedger.setEnabled(ppTrial.userEventsPresent());
                } catch (Exception e) {
                    ParaProfUtils.handleException(e);
                }
            }

            public void menuCanceled(MenuEvent e) {
            }

            public void menuDeselected(MenuEvent e) {
            }
        };

        windowsMenu.addMenuListener(menuListener);

        return windowsMenu;
    }

    public static void scaleForPrint(Graphics g, PageFormat pageFormat, int width, int height) {
        double pageWidth = pageFormat.getImageableWidth();
        double pageHeight = pageFormat.getImageableHeight();
        int cols = (int) (width / pageWidth) + 1;
        int rows = (int) (height / pageHeight) + 1;
        double xScale = pageWidth / width;
        double yScale = pageHeight / height;
        double scale = Math.min(xScale, yScale);

        double tx = 0.0;
        double ty = 0.0;
        if (xScale > scale) {
            tx = 0.5 * (xScale - scale) * width;
        } else {
            ty = 0.5 * (yScale - scale) * height;
        }

        Graphics2D g2 = (Graphics2D) g;

        g2.translate((int) pageFormat.getImageableX(), (int) pageFormat.getImageableY());
        g2.translate(tx, ty);
        g2.scale(scale, scale);
    }

    public FunctionBarChartWindow createFunctionAcrossPhasesWindow(ParaProfTrial ppTrial, Thread thread, Function function,
            Component owner) {
        FunctionBarChartWindow functionDataWindow = new FunctionBarChartWindow(ppTrial, function, owner);
        functionDataWindow.changeToPhaseDisplay(thread);
        return functionDataWindow;
    }

    public static JPopupMenu createFunctionClickPopUp(final ParaProfTrial ppTrial, final Function function, final Thread thread,
            final Component owner) {
        ActionListener actionListener = new ActionListener() {
            public void actionPerformed(ActionEvent evt) {
                try {

                    String arg = evt.getActionCommand();

                    if (arg.equals("Show Function Bar Chart")) {
                        FunctionBarChartWindow functionDataWindow = new FunctionBarChartWindow(ppTrial, function, owner);
                        functionDataWindow.show();
                    } else if (arg.equals("Show Function Data over Phases")) {
                        FunctionBarChartWindow functionDataWindow = new FunctionBarChartWindow(ppTrial, function, owner);
                        functionDataWindow.changeToPhaseDisplay(thread);
                        functionDataWindow.show();
                    } else if (arg.equals("Show Function Histogram")) {
                        HistogramWindow hw = new HistogramWindow(ppTrial, function, owner);
                        hw.show();
                    } else if (arg.equals("Assign Function Color")) {
                        ParaProf.colorMap.assignColor(owner, function);
                    } else if (arg.equals("Reset to Default Color")) {
                        ParaProf.colorMap.removeColor(function);
                        ParaProf.colorMap.reassignColors();
                    } else if (arg.equals("Open Profile for this Phase")) {
                        GlobalDataWindow fdw = new GlobalDataWindow(ppTrial, function.getActualPhase());
                        fdw.show();
                        ParaProf.incrementNumWindows();
                    }

                } catch (Exception e) {
                    ParaProfUtils.handleException(e);
                }
            }

        };

        JPopupMenu functionPopup = new JPopupMenu();

        if (function.isPhase()) {
            JMenuItem functionDetailsItem = new JMenuItem("Open Profile for this Phase");
            functionDetailsItem.addActionListener(actionListener);
            functionPopup.add(functionDetailsItem);
        }

        JMenuItem functionDetailsItem = new JMenuItem("Show Function Bar Chart");
        functionDetailsItem.addActionListener(actionListener);
        functionPopup.add(functionDetailsItem);

        JMenuItem functionHistogramItem = new JMenuItem("Show Function Histogram");
        functionHistogramItem.addActionListener(actionListener);
        functionPopup.add(functionHistogramItem);

        if (ppTrial.getDataSource().getPhasesPresent()) {
            JMenuItem jMenuItem = new JMenuItem("Show Function Data over Phases");
            jMenuItem.addActionListener(actionListener);
            functionPopup.add(jMenuItem);
        }

        JMenuItem jMenuItem = new JMenuItem("Assign Function Color");
        jMenuItem.addActionListener(actionListener);
        functionPopup.add(jMenuItem);

        jMenuItem = new JMenuItem("Reset to Default Color");
        jMenuItem.addActionListener(actionListener);
        functionPopup.add(jMenuItem);

        functionPopup.add(new JSeparator());
        for (int i = 0; i < ParaProf.scripts.size(); i++) {
            final ParaProfScript pps = (ParaProfScript) ParaProf.scripts.get(i);
            if (pps instanceof ParaProfFunctionScript) {
                JMenuItem menuItem = new JMenuItem("[Script] " + pps.getName());
                menuItem.addActionListener(new ActionListener() {
                    public void actionPerformed(ActionEvent e) {
                        try {
                            ((ParaProfFunctionScript) pps).runFunction(ppTrial, function);
                        } catch (Exception ex) {
                            new ParaProfErrorDialog("Exception while executing script: ", ex);
                        }

                    }
                });
                functionPopup.add(menuItem);
            }
        }

        return functionPopup;

    }

    public static JMenuItem createStatisticsMenuItem(String text, final ParaProfTrial ppTrial, final Function phase,
            final edu.uoregon.tau.perfdmf.Thread thread, final boolean userEvent, final Component owner) {
        JMenuItem jMenuItem = new JMenuItem(text);
        jMenuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                StatWindow statWindow = new StatWindow(ppTrial, thread, userEvent, phase, owner);
                statWindow.show();
            }

        });
        return jMenuItem;
    }

    public static JMenuItem createStatisticsTableMenuItem(String text, final ParaProfTrial ppTrial, final Function phase,
            final edu.uoregon.tau.perfdmf.Thread thread, final Component owner) {
        JMenuItem jMenuItem = new JMenuItem(text);
        jMenuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                TreeTableWindow ttWindow = new TreeTableWindow(ppTrial, thread, owner);
                ttWindow.show();
            }

        });
        return jMenuItem;
    }

    public static JMenuItem createCallGraphMenuItem(String text, final ParaProfTrial ppTrial,
            final edu.uoregon.tau.perfdmf.Thread thread, final Component owner) {
        JMenuItem jMenuItem = new JMenuItem(text);
        jMenuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                CallGraphWindow tmpRef = new CallGraphWindow(ppTrial, thread, owner);
                tmpRef.show();
            }

        });
        return jMenuItem;
    }

    public static JMenuItem createCallPathThreadRelationMenuItem(String text, final ParaProfTrial ppTrial,
            final edu.uoregon.tau.perfdmf.Thread thread, final Component owner) {
        JMenuItem jMenuItem = new JMenuItem(text);
        jMenuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                CallPathTextWindow callPathTextWindow = new CallPathTextWindow(ppTrial, thread, owner);
                callPathTextWindow.show();
            }

        });
        return jMenuItem;
    }

    public static JMenuItem createThreadDataMenuItem(String text, final ParaProfTrial ppTrial, final Function phase,
            final edu.uoregon.tau.perfdmf.Thread thread, final Component owner) {
        JMenuItem jMenuItem = new JMenuItem(text);
        jMenuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                FunctionBarChartWindow w = new FunctionBarChartWindow(ppTrial, thread, phase, owner);
                w.show();
            }

        });
        return jMenuItem;
    }

    public static JMenuItem createComparisonMenuItem(String text, final ParaProfTrial ppTrial,
            final edu.uoregon.tau.perfdmf.Thread thread, final Component owner) {
        JMenuItem jMenuItem = new JMenuItem(text);
        jMenuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (ParaProf.theComparisonWindow == null) {
                    ParaProf.theComparisonWindow = FunctionBarChartWindow.CreateComparisonWindow(ppTrial, thread, owner);
                } else {
                    ParaProf.theComparisonWindow.addThread(ppTrial, thread);
                }
                ParaProf.theComparisonWindow.show();
            }

        });
        return jMenuItem;
    }

    public static JMenuItem createUserEventBarChartMenuItem(String text, final ParaProfTrial ppTrial,
            final edu.uoregon.tau.perfdmf.Thread thread, final Component owner) {
        JMenuItem jMenuItem = new JMenuItem(text);
        jMenuItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                UserEventWindow w = new UserEventWindow(ppTrial, thread, owner);
                w.show();
            }

        });
        return jMenuItem;
    }

    public static void handleUserEventClick(final ParaProfTrial ppTrial, final UserEvent userEvent, final JComponent owner,
            MouseEvent evt) {

        ActionListener act = new ActionListener() {

            public void actionPerformed(ActionEvent e) {
                Object EventSrc = e.getSource();
                if (EventSrc instanceof JMenuItem) {
                    String arg = e.getActionCommand();
                    if (arg.equals("Show User Event Bar Chart")) {
                        UserEventWindow tmpRef = new UserEventWindow(ppTrial, userEvent, owner);
                        tmpRef.show();
                    } else if (arg.equals("Change User Event Color")) {

                        Color tmpCol = userEvent.getColor();
                        tmpCol = JColorChooser.showDialog(owner, "Please select a new color", tmpCol);
                        if (tmpCol != null) {
                            userEvent.setSpecificColor(tmpCol);
                            userEvent.setColorFlag(true);
                            ppTrial.updateRegisteredObjects("colorEvent");
                        }
                    } else if (arg.equals("Reset to Generic Color")) {

                        userEvent.setColorFlag(false);
                        ppTrial.updateRegisteredObjects("colorEvent");
                    }
                }

            }
        };

        JPopupMenu popup = new JPopupMenu();
        JMenuItem menuItem;
        menuItem = new JMenuItem("Show User Event Bar Chart");
        menuItem.addActionListener(act);
        popup.add(menuItem);

        menuItem = new JMenuItem("Change User Event Color");
        menuItem.addActionListener(act);
        popup.add(menuItem);

        menuItem = new JMenuItem("Reset to Generic Color");
        menuItem.addActionListener(act);
        popup.add(menuItem);

        popup.show(owner, evt.getX(), evt.getY());

    }

    public static void handleThreadClick(final ParaProfTrial ppTrial, final Function phase,
            final edu.uoregon.tau.perfdmf.Thread thread, JComponent owner, MouseEvent evt) {

        String ident;

        if (thread.getNodeID() == -1) {
            ident = "Mean";
        } else if (thread.getNodeID() == -2) {
            ident = "Total";
        } else if (thread.getNodeID() == -3) {
            ident = "Standard Deviation";
        } else {
            ident = "Thread";
        }

        JPopupMenu threadPopup = new JPopupMenu();
        threadPopup.add(createThreadDataMenuItem("Show " + ident + " Bar Chart", ppTrial, phase, thread, owner));
        threadPopup.add(createStatisticsMenuItem("Show " + ident + " Statistics Text Window", ppTrial, phase, thread, false,
                owner));
        threadPopup.add(createStatisticsTableMenuItem("Show " + ident + " Statistics Table", ppTrial, phase, thread, owner));
        threadPopup.add(createCallGraphMenuItem("Show " + ident + " Call Graph", ppTrial, thread, owner));
        threadPopup.add(createCallPathThreadRelationMenuItem("Show " + ident + " Call Path Relations", ppTrial, thread, owner));
        //if (thread.getNodeID() >= 0 && ppTrial.userEventsPresent()) {
        threadPopup.add(createUserEventBarChartMenuItem("Show User Event Bar Chart", ppTrial, thread, owner));
        threadPopup.add(createStatisticsMenuItem("Show User Event Statistics Window", ppTrial, null, thread, true, owner));
        //}
        threadPopup.add(createComparisonMenuItem("Add " + ident + " to Comparison Window", ppTrial, thread, owner));
        threadPopup.show(owner, evt.getX(), evt.getY());

    }

    public static int[] computeClipping(Rectangle clipRect, Rectangle viewRect, boolean toScreen, boolean fullWindow, int size,
            int rowHeight, int yCoord) {

        int startElement, endElement;
        if (!fullWindow) {
            int yBeg = 0;
            int yEnd = 0;

            if (toScreen) {
                yBeg = (int) clipRect.getY();
                yEnd = (int) (yBeg + clipRect.getHeight());
            } else {
                yBeg = (int) viewRect.getY();
                yEnd = (int) (yBeg + viewRect.getHeight());
            }
            startElement = ((yBeg - yCoord) / rowHeight) - 1;
            endElement = ((yEnd - yCoord) / rowHeight) + 1;

            if (startElement < 0)
                startElement = 0;

            if (endElement < 0)
                endElement = 0;

            if (startElement > (size - 1))
                startElement = (size - 1);

            if (endElement > (size - 1))
                endElement = (size - 1);

            if (toScreen) {
                yCoord = yCoord + (startElement * rowHeight);
            }
        } else {
            startElement = 0;
            endElement = (size - 1);
        }

        if (startElement < 0) {
            startElement = 0;
        }

        int[] clips = new int[3];
        clips[0] = startElement;
        clips[1] = endElement;
        clips[2] = yCoord;
        return clips;
    }

    public static JMenu createUnitsMenu(final UnitListener unitListener, int initialUnits, boolean doHours) {

        ActionListener actionListener = new ActionListener() {
            public void actionPerformed(ActionEvent evt) {
                try {
                    String arg = evt.getActionCommand();
                    if (arg.equals("Microseconds")) {
                        unitListener.setUnits(0);
                    } else if (arg.equals("Milliseconds")) {
                        unitListener.setUnits(1);
                    } else if (arg.equals("Seconds")) {
                        unitListener.setUnits(2);
                    } else if (arg.equals("hr:min:sec")) {
                        unitListener.setUnits(3);
                    }

                } catch (Exception e) {
                    ParaProfUtils.handleException(e);
                }
            }

        };

        JMenu unitsSubMenu = new JMenu("Select Units");
        ButtonGroup group = new ButtonGroup();

        JRadioButtonMenuItem button = new JRadioButtonMenuItem("Microseconds", initialUnits == 0);
        button.addActionListener(actionListener);
        group.add(button);
        unitsSubMenu.add(button);

        button = new JRadioButtonMenuItem("Milliseconds", initialUnits == 1);
        button.addActionListener(actionListener);
        group.add(button);
        unitsSubMenu.add(button);

        button = new JRadioButtonMenuItem("Seconds", initialUnits == 2);
        button.addActionListener(actionListener);
        group.add(button);
        unitsSubMenu.add(button);

        button = new JRadioButtonMenuItem("hr:min:sec", initialUnits == 3);
        button.addActionListener(actionListener);
        group.add(button);
        unitsSubMenu.add(button);

        return unitsSubMenu;
    }

    private static int findGroupID(Group groups[], Group group) {
        for (int i = 0; i < groups.length; i++) {
            if (groups[i] == group) {
                return i;
            }
        }
        throw new ParaProfException("Couldn't find group: " + group.getName());
    }

    public static void writePacked(DataSource dataSource, File file) throws FileNotFoundException, IOException {
        //File file = new File("/home/amorris/test.ppk");
        FileOutputStream ostream = new FileOutputStream(file);
        GZIPOutputStream gzip = new GZIPOutputStream(ostream);
        BufferedOutputStream bw = new BufferedOutputStream(gzip);
        DataOutputStream p = new DataOutputStream(bw);

        int numFunctions = dataSource.getNumFunctions();
        int numMetrics = dataSource.getNumberOfMetrics();
        int numUserEvents = dataSource.getNumUserEvents();
        int numGroups = dataSource.getNumGroups();

        // write out magic cookie
        p.writeChar('P');  // two bytes
        p.writeChar('P');  // two bytes
        p.writeChar('K');  // two bytes

        // write out version
        p.writeInt(1);     // four bytes

        // write out lowest compatibility version
        p.writeInt(1);     // four bytes

        // write out size of header in bytes
        p.writeInt(0);     // four bytes

        // write out metric names
        p.writeInt(numMetrics);
        for (int i = 0; i < numMetrics; i++) {
            String metricName = dataSource.getMetricName(i);
            p.writeUTF(metricName);
        }


        // write out group names
        p.writeInt(numGroups);
        Group groups[] = new Group[numGroups];
        int idx = 0;
        for (Iterator it = dataSource.getGroups(); it.hasNext();) {
            Group group = (Group) it.next();
            String groupName = group.getName();
            p.writeUTF(groupName);
            groups[idx++] = group;
        }

        // write out function names
        Function functions[] = new Function[numFunctions];
        idx = 0;
        p.writeInt(numFunctions);
        for (Iterator it = dataSource.getFunctions(); it.hasNext();) {
            Function function = (Function) it.next();
            functions[idx++] = function;
            p.writeUTF(function.getName());

            List thisGroups = function.getGroups();
            if (thisGroups == null) {
                p.writeInt(0);
            } else {
                p.writeInt(thisGroups.size());
                for (int i = 0; i < thisGroups.size(); i++) {
                    Group group = (Group) thisGroups.get(i);
                    p.writeInt(findGroupID(groups, group));
                }
            }
        }

        // write out user event names
        UserEvent userEvents[] = new UserEvent[numUserEvents];
        idx = 0;
        p.writeInt(numUserEvents);
        for (Iterator it = dataSource.getUserEvents(); it.hasNext();) {
            UserEvent userEvent = (UserEvent) it.next();
            userEvents[idx++] = userEvent;
            p.writeUTF(userEvent.getName());
        }

        // write out the number of threads
        p.writeInt(dataSource.getAllThreads().size());

        // write out each thread's data
        for (Iterator it = dataSource.getAllThreads().iterator(); it.hasNext();) {
            edu.uoregon.tau.perfdmf.Thread thread = (edu.uoregon.tau.perfdmf.Thread) it.next();

            p.writeInt(thread.getNodeID());
            p.writeInt(thread.getContextID());
            p.writeInt(thread.getThreadID());

            // count (non-null) function profiles
            int count = 0;
            for (int i = 0; i < numFunctions; i++) {
                FunctionProfile fp = thread.getFunctionProfile(functions[i]);
                if (fp != null) {
                    count++;
                }
            }
            p.writeInt(count);

            // write out function profiles
            for (int i = 0; i < numFunctions; i++) {
                FunctionProfile fp = thread.getFunctionProfile(functions[i]);

                if (fp != null) {
                    p.writeInt(i); // which function (id)
                    p.writeDouble(fp.getNumCalls());
                    p.writeDouble(fp.getNumSubr());

                    for (int j = 0; j < numMetrics; j++) {
                        p.writeDouble(fp.getExclusive(j));
                        p.writeDouble(fp.getInclusive(j));
                    }
                }
            }

            // count (non-null) user event profiles
            count = 0;
            for (int i = 0; i < numUserEvents; i++) {
                UserEventProfile uep = thread.getUserEventProfile(userEvents[i]);
                if (uep != null) {
                    count++;
                }
            }

            p.writeInt(count); // number of user event profiles

            // write out user event profiles
            for (int i = 0; i < numUserEvents; i++) {
                UserEventProfile uep = thread.getUserEventProfile(userEvents[i]);

                if (uep != null) {
                    p.writeInt(i);
                    p.writeInt((int) uep.getNumSamples());
                    p.writeDouble(uep.getMinValue());
                    p.writeDouble(uep.getMaxValue());
                    p.writeDouble(uep.getMeanValue());
                    p.writeDouble(uep.getSumSquared());
                }
            }
        }

        p.close();
        gzip.close();
        ostream.close();

    }

    public static void exportTrial(ParaProfTrial ppTrial, Component owner) {

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export Trial");
        //Set the directory.
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.dir")));
        javax.swing.filechooser.FileFilter fileFilters[] = fileChooser.getChoosableFileFilters();

        fileChooser.setFileFilter(new ParaProfImageFormatFileFilter(ParaProfImageFormatFileFilter.PPK));

        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        int resultValue = fileChooser.showSaveDialog(owner);
        if (resultValue != JFileChooser.APPROVE_OPTION) {
            return;
        }

        try {

            File file = fileChooser.getSelectedFile();
            String path = file.getCanonicalPath();

            String extension = ParaProfImageFormatFileFilter.getExtension(file);
            if (extension == null) {
                path = path + ".ppk";
                file = new File(path);
            }

            if (file.exists()) {
                int response = JOptionPane.showConfirmDialog(owner, file + " already exists\nOverwrite existing file?",
                        "Confirm Overwrite", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (response == JOptionPane.CANCEL_OPTION)
                    return;
            }

            writePacked(ppTrial.getDataSource(), file);

        } catch (Exception e) {
            ParaProfUtils.handleException(e);
        }

    }

    public static boolean rightClick(MouseEvent evt) {
        if ((evt.getModifiers() & InputEvent.BUTTON3_MASK) != 0) {
            return true;
        }
        return false;
    }

    public static String getFunctionName(Function function) {
        if (ParaProf.preferences.getReversedCallPaths()) {
            return function.getReversedName();
        }
        return function.getName();
    }

    public static String getThreadIdentifier(Thread thread) {

        if (thread.getNodeID() == -1) {
            return "Mean";
        } else if (thread.getNodeID() == -2) {
            return "Total";
        } else if (thread.getNodeID() == -3) {
            return "Std. Dev.";
        } else {
            return "n,c,t " + thread.getNodeID() + "," + thread.getContextID() + "," + thread.getThreadID();
        }

    }

    public static void handleException(Exception e) {
        new ParaProfErrorDialog(null, e);
    }

    public static Dimension checkSize(Dimension d) {
        if (!ParaProf.demoMode) {
            return d;
        }

        int width = d.width;
        int height = d.height;

        width = Math.min(width, 640);
        height = Math.min(height, 480);
        return new Dimension(width, height);
    }

    public static NumberFormat createNumberFormatter(final int units) {
        return new NumberFormat() {

            public Number parse(String source, ParsePosition parsePosition) {
                // TODO Auto-generated method stub
                return null;
            }

            public StringBuffer format(double number, StringBuffer toAppendTo, FieldPosition pos) {
                return toAppendTo.append(UtilFncs.getOutputString(units, number, 5));
            }

            public StringBuffer format(long number, StringBuffer toAppendTo, FieldPosition pos) {
                return toAppendTo.append(UtilFncs.getOutputString(units, number, 5));
            }
        };

    }

}
