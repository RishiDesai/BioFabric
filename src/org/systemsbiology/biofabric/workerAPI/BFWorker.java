/*
**    Copyright (C) 2003-2018 Institute for Systems Biology 
**                            Seattle, Washington, USA. 
**
**    This library is free software; you can redistribute it and/or
**    modify it under the terms of the GNU Lesser General Public
**    License as published by the Free Software Foundation; either
**    version 2.1 of the License, or (at your option) any later version.
**
**    This library is distributed in the hope that it will be useful,
**    but WITHOUT ANY WARRANTY; without even the implied warranty of
**    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
**    Lesser General Public License for more details.
**
**    You should have received a copy of the GNU Lesser General Public
**    License along with this library; if not, write to the Free Software
**    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package org.systemsbiology.biofabric.workerAPI;

import org.systemsbiology.biofabric.util.GoodnessChart;

/****************************************************************************
**
** An interface for monitoring progress
*/

public interface BFWorker {
 
  ////////////////////////////////////////////////////////////////////////////
  //
  // PUBLIC METHODS
  //
  ////////////////////////////////////////////////////////////////////////////

  /***************************************************************************
  **
  ** Get the progress monitor
  */

  public BTProgressMonitor getMonitor();  
  
  /***************************************************************************
  **
  ** Set the run core
  */
  
  public void setCore(BackgroundCore core);
  
  /***************************************************************************
  **
  ** Launch the run core
  */
  
  public void launchWorker();
  
  /***************************************************************************
  **
  ** Stash exception for later display
  */
  
  public void stashException(Exception ex);
  
  /***************************************************************************
  **
  ** Add goodness chart
  */  
  
  public void makeSuperChart();
 
}