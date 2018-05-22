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

package org.systemsbiology.biofabric.plugin.core.align;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.systemsbiology.biofabric.layouts.DefaultEdgeLayout;
import org.systemsbiology.biofabric.model.AnnotationSet;
import org.systemsbiology.biofabric.model.BuildData;
import org.systemsbiology.biofabric.model.FabricLink;
import org.systemsbiology.biofabric.model.BioFabricNetwork;
import org.systemsbiology.biofabric.util.AsynchExitRequestException;
import org.systemsbiology.biofabric.util.BTProgressMonitor;
import org.systemsbiology.biofabric.util.LoopReporter;
import org.systemsbiology.biofabric.util.NID;
import org.systemsbiology.biofabric.util.UiUtil;

/****************************************************************************
**
** This is the default layout algorithm for edges. Actually usable in combination 
** with a wide variety of different node layout algorithms, not just the default
*/

public class AlignCycleEdgeLayout extends DefaultEdgeLayout {
  
  ////////////////////////////////////////////////////////////////////////////
  //
  // PRIVATE INSTANCE MEMBERS
  //
  ////////////////////////////////////////////////////////////////////////////

  ////////////////////////////////////////////////////////////////////////////
  //
  // PUBLIC CONSTRUCTORS
  //
  ////////////////////////////////////////////////////////////////////////////

  /***************************************************************************
  **
  ** Constructor
  */

  public AlignCycleEdgeLayout() {
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // PUBLIC METHODS
  //
  ////////////////////////////////////////////////////////////////////////////
  
  /***************************************************************************
  **
  ** Do necessary pre-processing steps (e.g. automatic assignment to link groups)
  */
  
  @Override
  public void preProcessEdges(BuildData.RelayoutBuildData rbd, 
                              BTProgressMonitor monitor) throws AsynchExitRequestException {
    
    List<String> groupOrder = new ArrayList<String>();
    groupOrder.add("G1A");
    groupOrder.add("G12");
    groupOrder.add("G2A");
    rbd.setGroupOrderAndMode(groupOrder, BioFabricNetwork.LayoutMode.PER_NODE_MODE);  
    return;
  } 
  
  /***************************************
  **
  ** Calc link annotations
  */ 
  
  @Override
  protected AnnotationSet calcGroupLinkAnnots(BuildData.RelayoutBuildData rbd, 
                                              List<FabricLink> links, BTProgressMonitor monitor, 
                                              boolean shadow, List<String> linkGroups) throws AsynchExitRequestException { 
    
    NetworkAlignmentBuildData narbd = (NetworkAlignmentBuildData)rbd;
    TreeMap<Integer, NID.WithName> invert = new TreeMap<Integer, NID.WithName>();
    for (NID.WithName node : rbd.nodeOrder.keySet()) {
      invert.put(rbd.nodeOrder.get(node), node);
    }
    ArrayList<NID.WithName> order = new ArrayList<NID.WithName>(invert.values());
    return (calcGroupLinkAnnotsCycle(links, order, monitor, 
                                     shadow, narbd.cycleBounds, linkGroups));
  }

  /***************************************
  **
  ** Write out link annotation file
  */
    
  private AnnotationSet calcGroupLinkAnnotsCycle(List<FabricLink> links, List<NID.WithName> nodes,
                                                 BTProgressMonitor monitor, 
                                                 boolean shadow, List<NID.WithName[]> bounds, 
                                                 List<String> linkGroups) throws AsynchExitRequestException {
  
    String which = (shadow) ? "progress.linkAnnotationShad" : "progress.linkAnnotationNoShad";
    LoopReporter lr = new LoopReporter(links.size(), 20, monitor, 0, 1.0, which); 
      
    HashMap<NID.WithName, Integer> nodeOrder = new HashMap<NID.WithName, Integer>();
    for (int i = 0; i < nodes.size(); i++) {
      nodeOrder.put(nodes.get(i), Integer.valueOf(i));      
    }
    
    NID.WithName currZoner = null;
    NID.WithName[] currLooper = new NID.WithName[2];
    HashSet<NID.WithName> seen = new HashSet<NID.WithName>();
    int cycle = 0;

    AnnotationSet retval = new AnnotationSet();
    int startPos = 0;
    int endPos = 0;
    int numLink = links.size();
    int count = 0;
    for (int i = 0; i < numLink; i++) {
      FabricLink link = links.get(i);
      lr.report();
      if (link.isShadow() && !shadow) {
        continue;
      }
      UiUtil.fixMePrintout("FIRST CYCLE BEING MISSED (check via looper[1] != looper[0] test omitted");
      NID.WithName zoner = getZoneNode(link, nodeOrder, link.isShadow());
      if ((currZoner == null) || !currZoner.equals(zoner)) { // New Zone
        if (currZoner != null) { // End the zone
          if (currZoner.equals(currLooper[1])) {
            if (!currLooper[0].equals(currLooper[1])) {
              String color = (cycle % 2 == 0) ? "Orange" : "Green";
              retval.addAnnot(new AnnotationSet.Annot("cycle " + cycle++, startPos, endPos, 0, color));
            }
          }
        }
        currZoner = zoner;
        for (NID.WithName[] bound : bounds) {
          if (!seen.contains(bound[0]) && bound[0].equals(currZoner)) {
            startPos = count;
            seen.add(bound[0]);
            currLooper = bound;
          }
        }
      }
      endPos = count++;
    }
    if (!currLooper[0].equals(currLooper[1])) {
      String color = (cycle % 2 == 0) ? "Orange" : "Green";
      retval.addAnnot(new AnnotationSet.Annot("cycle " + cycle++, startPos, endPos, 0, color));
    }
    return (retval);
  }
  
  /***************************************
  **
  ** A zone node is the bottom node of a shadow link or the top node of
  ** a regular link
  */
    
  private NID.WithName getZoneNode(FabricLink link, Map<NID.WithName, Integer> nodes, boolean isShadow) {
    int zeroIndex = nodes.get(link.getSrcID()).intValue();
    int oneIndex = nodes.get(link.getTrgID()).intValue();
    if (isShadow) {
      NID.WithName botnode = (zeroIndex < oneIndex) ? link.getTrgID() : link.getSrcID();
      return (botnode);
    } else {
      NID.WithName topnode = (zeroIndex < oneIndex) ? link.getSrcID() : link.getTrgID();
      return (topnode); 
    }
  }

  /***************************************
  **
  ** Get the color
  */
  
  @Override
  protected String getColor(String type, Map<String, String> colorMap) {
    String trimmed = type.trim();
    if (trimmed.equals("G12")) {
      return ("Purple");
    } else if (trimmed.equals("G1A")) {
      return ("PowderBlue");
    } else if (trimmed.equals("G2A")) {
      return ("Pink");
    }
    throw new IllegalArgumentException();
  } 
}
