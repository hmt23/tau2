/* 
  GlobalServer.java

  Title:      ParaProf
  Author:     Robert Bell
  Description:  
*/

package ParaProf;

import java.util.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import javax.swing.*;
import javax.swing.event.*;

public class GlobalServer implements Serializable 
{
  //Constructor.
  public GlobalServer()
  {
    serverName = null;
    contextList = new Vector();
    numberOfContexts = 0;
  }
  
  public GlobalServer(String inServerName)
  {
    serverName = inServerName;
    contextList = new Vector();
    numberOfContexts = 0;
  }
  
  //Rest of the public functions.
  public void setServerName(String inServerName)
  {
    serverName = inServerName;
  }
  
  public String getServerName()
  {
    return serverName;
  }
  
  public void addContext(GlobalContext inGlobalContextObject)
  {
    //Keeping track of the number of contexts on this server.
    numberOfContexts++;
    //Now add the context to the end of the list ... the default
    //for addElement in a Vector.
    contextList.addElement(inGlobalContextObject);
  }
  
  public boolean isContextPresent(String inContextName)
  {
    GlobalContext contextObject;
    String tmpString;
    
    for(Enumeration e = contextList.elements(); e.hasMoreElements() ;)
    {
      contextObject = (GlobalContext) e.nextElement();
      tmpString = contextObject.getContextName();
      if(inContextName.equals(tmpString))
        return true;
    }
    //If here, it means that the server name was not in the list.
    return false;
  }
  
  public Vector getContextList() //Called by ListModel routines.
  {
    return contextList;
  }
  
  //Instance data.
  String serverName;
  Vector contextList;
  int numberOfContexts;
  
}
