/* 
	
	TotalStatWindowPanel.java
	
	Title:			jRacy
	Author:			Robert Bell
	Description:	
*/

package jRacy;

import java.util.*;
import java.text.*;
import java.awt.font.TextAttribute;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;


public class TotalStatWindowPanel extends JPanel implements ActionListener, MouseListener
{		
	public TotalStatWindowPanel()
	{
		try{
			setSize(new java.awt.Dimension(xPanelSize, yPanelSize));
			
			//Schedule a repaint of this panel.
			this.repaint();
		}
		catch(Exception e)
		{
			jRacy.systemError(null, "TSWP01");
		}
	
	}
	
	
	public TotalStatWindowPanel(int inServerNumber,
								int inContextNumber,
								int inThreadNumber,
								TotalStatWindow inTSWindow)
	{
		try{
			setSize(new java.awt.Dimension(xPanelSize, yPanelSize));
			setBackground(Color.white);

			serverNumber = inServerNumber;
			contextNumber = inContextNumber;
			threadNumber = inThreadNumber;

			tSWindow = inTSWindow;
			
			//Add this object as a mouse listener.
			addMouseListener(this);
			
			//Add items to the popu menu.
			JMenuItem functionDetailsItem = new JMenuItem("Show Function Details");
			functionDetailsItem.addActionListener(this);
			popup.add(functionDetailsItem);
			
			JMenuItem changeColorItem = new JMenuItem("Change Function Color");
			changeColorItem.addActionListener(this);
			popup.add(changeColorItem);
			
			JMenuItem maskFunctionItem = new JMenuItem("Reset to Generic Color");
			maskFunctionItem.addActionListener(this);
			popup.add(maskFunctionItem);
			
			this.repaint();
		}
		catch(Exception e)
		{
			jRacy.systemError(null, "TSWP02");
		}
	
	}
	

	public void paintComponent(Graphics g)
	{
		try{
			super.paintComponent(g);
			
			double tmpSum;
			double tmpDataValue;
			Color tmpColor;
			
			

			int yCoord = 0;
			
			
			//In this window, a Monospaced font has to be used.  This will probably not be the same
			//font as the rest of jRacy.  As a result, some extra work will have to be done to calculate
			//spacing.
			int fontSize = jRacy.jRacyPreferences.getBarHeight();
			spacing = jRacy.jRacyPreferences.getBarSpacing();
			
			int tmpXWidthCalc = 0;
			
			String tmpString = null;
			String dashString = "";
			
			
			//Create font.
			MonoFont = new Font("Monospaced", jRacy.jRacyPreferences.getFontStyle(), fontSize);
			//Compute the font metrics.
			fmMonoFont = g.getFontMetrics(MonoFont);
			maxFontAscent = fmMonoFont.getMaxAscent();
			maxFontDescent = fmMonoFont.getMaxDescent();
			
			if(spacing <= (maxFontAscent + maxFontDescent))
			{
				spacing = spacing + 1;
			}
			
			//Grab the appropriate thread.
			
			tmpThreadDataElementList = tSWindow.getStaticMainWindowSystemData();
			
			
			Rectangle clipRect = g.getClipBounds();
			
			int yBeg = (int) clipRect.getY();
			int yEnd = (int) (yBeg + clipRect.getHeight());
			int startThreadElement = 0;
			int endThreadElement = 0;
			
			
		    //To be on the safe side, have an alternative to the clip rectangle.
		    if ((clipRect != null))
		    {
		    	//Draw the heading!
				tmpString = jRacy.staticSystemData.getHeading();
				int tmpInt = tmpString.length();
				
				for(int i=0; i<tmpInt; i++)
				{
					dashString = dashString + "-";
				}
				
				g.setColor(Color.black);
				g.setFont(MonoFont);
				yCoord = yCoord + spacing;
				g.drawString(dashString, 20, yCoord);
				yCoord = yCoord + spacing + 10;
				g.drawString(tmpString, 20, yCoord);
				yCoord = yCoord + spacing + 10;
				g.drawString(dashString, 20, yCoord);
				
				startLocation = yCoord;
				
		    	//Set up some panel dimensions.
		    	newYPanelSize = yCoord + ((tmpThreadDataElementList.size() + 1) * spacing);
		    	
		    	startThreadElement = ((yBeg - yCoord) / spacing) - 1;
		    	endThreadElement  = ((yEnd - yCoord) / spacing) + 1;
		    	
		    	if((yCoord > yBeg) || (yCoord > yEnd))
		    	{
		    		if(yCoord > yBeg)
		    		{
		    			startThreadElement = 0;
		    		}
		    		
		    		if(yCoord > yEnd)
		    		{
		    			endThreadElement = 0;
		    		}
		    	}
		    	
		    	if(startThreadElement < 0)
		    		startThreadElement = 0;
		    		
		    	if(endThreadElement < 0)
		    		endThreadElement = 0;
		    	
		    	if(startThreadElement > (tmpThreadDataElementList.size() - 1))
		    		startThreadElement = (tmpThreadDataElementList.size() - 1);
		    		
		    	if(endThreadElement > (tmpThreadDataElementList.size() - 1))
		    		endThreadElement = (tmpThreadDataElementList.size() - 1);
		    	
		    	yCoord = yCoord + (startThreadElement * spacing);
		    	
		    	for(int i = startThreadElement; i <= endThreadElement; i++)
		    	{	
		    		tmpSMWThreadDataElement = (SMWThreadDataElement) tmpThreadDataElementList.elementAt(i);
					tmpString = tmpSMWThreadDataElement.getTStatString();
					
					yCoord = yCoord + spacing;
					
		    		g.setColor(Color.black);
						
					AttributedString as = new AttributedString(tmpString);
					as.addAttribute(TextAttribute.FONT, MonoFont);
					
					if((jRacy.clrChooser.getHighlightColorFunctionID()) != -1)
					{
						if((tmpSMWThreadDataElement.getFunctionID()) == (jRacy.clrChooser.getHighlightColorFunctionID()))
							as.addAttribute(TextAttribute.FOREGROUND, 
								(jRacy.clrChooser.getHighlightColor()),
								jRacy.staticSystemData.getPositionOfName(), tmpString.length());
						else
						{
							as.addAttribute(TextAttribute.FOREGROUND, 
								(tmpSMWThreadDataElement.getFunctionColor()),
								jRacy.staticSystemData.getPositionOfName(), tmpString.length());
						}
					}
					else
					{
						as.addAttribute(TextAttribute.FOREGROUND, 
							(tmpSMWThreadDataElement.getFunctionColor()),
							jRacy.staticSystemData.getPositionOfName(), tmpString.length()); 
					}
					
					g.drawString(as.getIterator(), 20, yCoord);
					
					//Figure out how wide that string was for x coord reasons.
					if(tmpXWidthCalc < (20 + fmMonoFont.stringWidth(tmpString) + 5))
					{
						tmpXWidthCalc = (20 + fmMonoFont.stringWidth(tmpString) + 15);
					}
				}
		    		
				//Resize the panel if needed.
				if((newYPanelSize >= yPanelSize) || (tmpXWidthCalc  >= xPanelSize))
				{
					yPanelSize = newYPanelSize + 1;
					xPanelSize = tmpXWidthCalc + 1;
					
					revalidate();
				}	
					
			}
			else
			{
				//Draw the heading!
				tmpString = jRacy.staticSystemData.getHeading();
				int tmpInt = tmpString.length();
				
				for(int i=0; i<tmpInt; i++)
				{
					dashString = dashString + "-";
				}
				
				g.setColor(Color.black);
				g.setFont(MonoFont);
				yCoord = yCoord + spacing;
				g.drawString(dashString, 20, yCoord);
				yCoord = yCoord + spacing + 10;
				g.drawString(tmpString, 20, yCoord);
				yCoord = yCoord + spacing + 10;
				g.drawString(dashString, 20, yCoord);
				
				startLocation = yCoord;
				
		    	//Set up some panel dimensions.
		    	newYPanelSize = yCoord + ((tmpThreadDataElementList.size() + 1) * spacing);
				
				//Cycle through the elements getting the strings.
				for(Enumeration e1 = tmpThreadDataElementList.elements(); e1.hasMoreElements() ;)
				{	
					tmpSMWThreadDataElement = (SMWThreadDataElement) e1.nextElement();
					tmpString = tmpSMWThreadDataElement.getTStatString();
					
					if(tmpString != null)
					{
						
						yCoord = yCoord + spacing;
						
						g.setColor(Color.black);
						
						AttributedString as = new AttributedString(tmpString);
						as.addAttribute(TextAttribute.FONT, MonoFont);
						
						if((jRacy.clrChooser.getHighlightColorFunctionID()) != -1)
						{
							if((tmpSMWThreadDataElement.getFunctionID()) == (jRacy.clrChooser.getHighlightColorFunctionID()))
								as.addAttribute(TextAttribute.FOREGROUND, 
									(jRacy.clrChooser.getHighlightColor()),
									jRacy.staticSystemData.getPositionOfName(), tmpString.length());
							else
							{
								as.addAttribute(TextAttribute.FOREGROUND, 
									(tmpSMWThreadDataElement.getFunctionColor()),
									jRacy.staticSystemData.getPositionOfName(), tmpString.length());
							}
						}
						else
						{
							as.addAttribute(TextAttribute.FOREGROUND, 
								(tmpSMWThreadDataElement.getFunctionColor()),
								jRacy.staticSystemData.getPositionOfName(), tmpString.length()); 
						}
						
						g.drawString(as.getIterator(), 20, yCoord);
						
						//Figure out how wide that string was for x coord reasons.
						if(tmpXWidthCalc < (20 + fmMonoFont.stringWidth(tmpString) + 5))
						{
							tmpXWidthCalc = (20 + fmMonoFont.stringWidth(tmpString) + 15);
						}
					}
				}
						
				//Resize the panel if needed.
				if((newYPanelSize >= yPanelSize) || (tmpXWidthCalc  >= xPanelSize))
				{
					yPanelSize = newYPanelSize + 1;
					xPanelSize = tmpXWidthCalc + 1;
					
					revalidate();
				}
			}
		}
		catch(Exception e)
		{
			jRacy.systemError(null, "TSWP03");
		}
		
	}
	
	//******************************
	//Event listener code!!
	//******************************
	
	
	//ActionListener code.
	public void actionPerformed(ActionEvent evt)
	{
		try{
			Object EventSrc = evt.getSource();
			
			SMWThreadDataElement tmpSMWThreadDataElement = null;
			
			if(EventSrc instanceof JMenuItem)
			{
				String arg = evt.getActionCommand();
				if(arg.equals("Show Function Details"))
				{
					
					if(clickedOnObject instanceof SMWThreadDataElement)
					{
						tmpSMWThreadDataElement = (SMWThreadDataElement) clickedOnObject;
						//Bring up an expanded data window for this function, and set this function as highlighted.
						jRacy.clrChooser.setHighlightColorFunctionID(tmpSMWThreadDataElement.getFunctionID());
						FunctionDataWindow tmpRef = new FunctionDataWindow(tmpSMWThreadDataElement.getFunctionName(), jRacy.staticMainWindow.getSMWData());
						jRacy.systemEvents.addObserver(tmpRef);
						tmpRef.show();
					}
				}
				else if(arg.equals("Change Function Color"))
				{	
					int functionID = -1;
					
					//Get the clicked on object.
					if(clickedOnObject instanceof SMWThreadDataElement)
						functionID = ((SMWThreadDataElement) clickedOnObject).getFunctionID();
					
					GlobalMapping globalMappingReference = jRacy.staticSystemData.getGlobalMapping();
					GlobalMappingElement tmpGME = (GlobalMappingElement) globalMappingReference.getGlobalMappingElement(functionID);
					
					Color tmpCol = tmpGME.getFunctionColor();
					
					JColorChooser tmpJColorChooser = new JColorChooser();
					tmpCol = tmpJColorChooser.showDialog(this, "Please select a new color", tmpCol);
					if(tmpCol != null)
					{
						tmpGME.setSpecificColor(tmpCol);
						tmpGME.setColorFlag(true);
						
						jRacy.systemEvents.updateRegisteredObjects("colorEvent");
					}
				}
				
				else if(arg.equals("Reset to Generic Color"))
				{	
					
					int functionID = -1;
					
					//Get the clicked on object.
					if(clickedOnObject instanceof SMWThreadDataElement)
						functionID = ((SMWThreadDataElement) clickedOnObject).getFunctionID();
					
					GlobalMapping globalMappingReference = jRacy.staticSystemData.getGlobalMapping();
					GlobalMappingElement tmpGME = (GlobalMappingElement) globalMappingReference.getGlobalMappingElement(functionID);
					
					tmpGME.setColorFlag(false);
					jRacy.systemEvents.updateRegisteredObjects("colorEvent");
				}
			}
		}
		catch(Exception e)
		{
			jRacy.systemError(null, "TSWP04");
		}
	}
	
	//Ok, now the mouse listeners for this panel.
	public void mouseClicked(MouseEvent evt)
	{
		
		try{
			//Get the location of the mouse.
			//Get the location of the mouse.
			int xCoord = evt.getX();
			int yCoord = evt.getY();
			
			int fontSize = jRacy.jRacyPreferences.getBarHeight();
			
			//Get the number of times clicked.
			int clickCount = evt.getClickCount();
			
			int tmpInt1 = yCoord - startLocation;
			int tmpInt2 = tmpInt1 / spacing;
			int tmpInt3 = (tmpInt2 + 1) * spacing;
			int tmpInt4 = tmpInt3 - maxFontAscent;
			
			if((tmpInt1 >= tmpInt4) && (tmpInt1 <= tmpInt3))
			{
				if(tmpInt2 < (tmpThreadDataElementList.size())) 
				{
					tmpSMWThreadDataElement = (SMWThreadDataElement) tmpThreadDataElementList.elementAt(tmpInt2);
					
					
					if(fmMonoFont != null)
					{
						String tmpString = tmpSMWThreadDataElement.getTStatString();
						int stringWidth = fmMonoFont.stringWidth(tmpString) + 20;
					
						if(xCoord <= stringWidth)
						{
						
							if((evt.getModifiers() & InputEvent.BUTTON1_MASK) == 0)
							{
								//Set the clickedSMWMeanDataElement.
								clickedOnObject = tmpSMWThreadDataElement;
								popup.show(this, evt.getX(), evt.getY());
							}
							else
							{
								//Want to set the clicked on function to the current highlight color or, if the one
								//clicked on is already the current highlighted one, set it back to normal.
								if((jRacy.clrChooser.getHighlightColorFunctionID()) == -1)
								{
									jRacy.clrChooser.setHighlightColorFunctionID(tmpSMWThreadDataElement.getFunctionID());
								}
								else
								{
									if(!((jRacy.clrChooser.getHighlightColorFunctionID()) == (tmpSMWThreadDataElement.getFunctionID())))
										jRacy.clrChooser.setHighlightColorFunctionID(tmpSMWThreadDataElement.getFunctionID());
									else
										jRacy.clrChooser.setHighlightColorFunctionID(-1);
								}
							}
						}
					}
				}
			}
		}
		catch(Exception e)
		{
			jRacy.systemError(null, "TSWP05");
		}
	}
	
	public void mousePressed(MouseEvent evt) {}
	public void mouseReleased(MouseEvent evt) {}
	public void mouseEntered(MouseEvent evt) {}
	public void mouseExited(MouseEvent evt) {}
	
	public Dimension getPreferredSize()
	{
		return new Dimension(xPanelSize, (yPanelSize + 10));
	}
	
	//Instance stuff.
	
	int xPanelSize = 800;
	int yPanelSize = 600;
	int newXPanelSize = 0;
	int newYPanelSize = 0;
	
	
	//Some drawing details.
	int startLocation = 0;
	int maxFontAscent = 0;
	int maxFontDescent = 0;
	int spacing = 0;
	
	int serverNumber;
	int	contextNumber;
	int	threadNumber;
 	TotalStatWindow tSWindow;
 	Vector tmpThreadDataElementList;
 	SMWThreadDataElement tmpSMWThreadDataElement;
 	
 	Font MonoFont = null;
 	FontMetrics fmMonoFont = null;
 	
 	//**********
	//Popup menu definitions.
	private JPopupMenu popup = new JPopupMenu();
	//**********
	
	//**********
	//Other useful variables.
	Object clickedOnObject = null;
	//End - Other useful variables.
	//**********
}