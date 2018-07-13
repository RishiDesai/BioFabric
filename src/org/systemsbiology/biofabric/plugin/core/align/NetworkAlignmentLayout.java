/*
**    File created by Rishi Desai
**
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.systemsbiology.biofabric.io.BuildData;
import org.systemsbiology.biofabric.layoutAPI.NodeLayout;
import org.systemsbiology.biofabric.layouts.DefaultLayout;
import org.systemsbiology.biofabric.model.AnnotationSet;
import org.systemsbiology.biofabric.modelAPI.NetLink;
import org.systemsbiology.biofabric.plugin.PluginSupportFactory;
import org.systemsbiology.biofabric.util.NID;
import org.systemsbiology.biofabric.util.UiUtil;
import org.systemsbiology.biofabric.workerAPI.AsynchExitRequestException;
import org.systemsbiology.biofabric.workerAPI.BTProgressMonitor;
import org.systemsbiology.biofabric.workerAPI.LoopReporter;

/****************************************************************************
 **
 ** This is the default layout algorithm
 */

public class NetworkAlignmentLayout extends NodeLayout {
  
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
  
  public NetworkAlignmentLayout() {
  }
  
  ////////////////////////////////////////////////////////////////////////////
  //
  // PUBLIC METHODS
  //
  ////////////////////////////////////////////////////////////////////////////
  
  /***************************************************************************
   **
   ** Relayout the network!
   */
  
  public List<NID.WithName> doNodeLayout(BuildData.RelayoutBuildData rbd, Params params, BTProgressMonitor monitor)
          throws AsynchExitRequestException {
    
    NetworkAlignmentBuildData nabd = (NetworkAlignmentBuildData) rbd;
    
    List<NID.WithName> targetIDs;
    
    switch (nabd.view) {
      case GROUP:
        targetIDs = bfsNodeGroupLayout(nabd, monitor);
        break;
      case ORPHAN:
        targetIDs = (new DefaultLayout()).defaultNodeOrder(nabd.allLinks, nabd.loneNodeIDs, null, monitor);
        break;
      case CYCLE:
        targetIDs = (new AlignCycleLayout()).doNodeOrder(rbd, params, monitor);
        break;
      default:
        throw new IllegalStateException();
    }
    
    installNodeOrder(targetIDs, nabd, monitor);
    return (new ArrayList<NID.WithName>(targetIDs));
  }
  
  /***************************************************************************
   **
   ** Breadth first search based on node groups
   */

  private List<NID.WithName> bfsNodeGroupLayout(NetworkAlignmentBuildData nabd,
                                                BTProgressMonitor monitor) throws AsynchExitRequestException {
    //
    // Note the allLinks Set has pruned out duplicates and synonymous non-directional links
    //
    //
    // Build a target list, top to bottom, that adds the node with the most
    // links first, and adds those link targets ASAP. If caller supplies a start node,
    // we go there first:
    //
    
    HashMap<NID.WithName, Integer> linkCounts = new HashMap<NID.WithName, Integer>();
    HashMap<NID.WithName, Set<NID.WithName>> targsPerSource = new HashMap<NID.WithName, Set<NID.WithName>>();
    
    HashSet<NID.WithName> targsToGo = new HashSet<NID.WithName>();
    
    int numLink = nabd.allLinks.size();
    LoopReporter lr = new LoopReporter(numLink, 20, monitor, 0.0, 0.25, "progress.calculateNodeDegree");
    
    Iterator<NetLink> alit = nabd.allLinks.iterator();
    while (alit.hasNext()) {
      NetLink nextLink = alit.next();
      lr.report();
      NID.WithName sidwn = nextLink.getSrcID();
      NID.WithName tidwn = nextLink.getTrgID();
      Set<NID.WithName> targs = targsPerSource.get(sidwn);
      if (targs == null) {
        targs = new HashSet<NID.WithName>();
        targsPerSource.put(sidwn, targs);
      }
      targs.add(tidwn);
      targs = targsPerSource.get(tidwn);
      if (targs == null) {
        targs = new HashSet<NID.WithName>();
        targsPerSource.put(tidwn, targs);
      }
      targs.add(sidwn);
      targsToGo.add(sidwn);
      targsToGo.add(tidwn);
      Integer srcCount = linkCounts.get(sidwn);
      linkCounts.put(sidwn, (srcCount == null) ? Integer.valueOf(1) : Integer.valueOf(srcCount.intValue() + 1));
      Integer trgCount = linkCounts.get(tidwn);
      linkCounts.put(tidwn, (trgCount == null) ? Integer.valueOf(1) : Integer.valueOf(trgCount.intValue() + 1));
    }
    lr.finish();
    
    //
    // Initialize data structures for layout
    //
    
    NodeGroupMap grouper;
    
    if (nabd.mode == NodeGroupMap.PerfectNGMode.NODE_CORRECTNESS ||
            nabd.mode == NodeGroupMap.PerfectNGMode.JACCARD_SIMILARITY) {
      grouper = new NodeGroupMap(nabd, defaultNGOrderWithCorrect, ngAnnotcolorsWithCorrect, monitor);
    } else {
      grouper = new NodeGroupMap(nabd, defaultNGOrderWithoutCorrect, ngAnnotColorsWithoutCorrect, monitor);
    }
    
    // master list of nodes in each group
    SortedMap<Integer, List<NID.WithName>> classToGroup = new TreeMap<Integer, List<NID.WithName>>();
    for (int i = 0; i < grouper.numGroups(); i++) {
      classToGroup.put(i, new ArrayList<NID.WithName>());
    }
    // fill the master list with nodes
    Set<NID.WithName> allNodes = PluginSupportFactory.getBuildExtractor().extractNodes(nabd.allLinks, nabd.loneNodeIDs, monitor);
    for (NID.WithName node : allNodes) {
      int nodeClass = grouper.getIndex(node);
      classToGroup.get(nodeClass).add(node);
    }
    // sort by decreasing degree
    for (List<NID.WithName> group : classToGroup.values()) {
      Collections.sort(group, grouper.sortDecrDegree());
    }
    
    SortedMap<Integer, List<NID.WithName>> targetsGroup = new TreeMap<Integer, List<NID.WithName>>(),
            queueGroup = new TreeMap<Integer, List<NID.WithName>>(),
            targsLeftToGoGroup = new TreeMap<Integer, List<NID.WithName>>();
    
    // each node group (singletons too) gets queue and targets list
    for (int i = 0; i < grouper.numGroups(); i++) {
      targetsGroup.put(i, new ArrayList<NID.WithName>());
      queueGroup.put(i, new ArrayList<NID.WithName>());
      targsLeftToGoGroup.put(i, new ArrayList<NID.WithName>());
      for (NID.WithName node : classToGroup.get(i)) {
        targsLeftToGoGroup.get(i).add(node);
      }
    }
    
    //
    // Start breadth-first-search on first node group
    //

    int currGroup = 0;
    while (currGroup < grouper.numGroups()) {
      
      if (targsLeftToGoGroup.get(currGroup).isEmpty()) {
        currGroup++;
        continue; // continue only after each node in group has been visited
      }
      // if queue is empty, pull head node from list
      if (queueGroup.get(currGroup).isEmpty()) {
        NID.WithName head = targsLeftToGoGroup.get(currGroup).remove(0);
        queueGroup.get(currGroup).add(head);
      }
      
      flushQueue(targetsGroup, targsPerSource, linkCounts, targsToGo, targsLeftToGoGroup, queueGroup,
              monitor, .25, 1.00, currGroup, grouper);
    }
    
    //
    // Add lone nodes and "flatten" out the targets into one list
    //

    List<NID.WithName> targets = new ArrayList<NID.WithName>();
    for (int i = 0; i < grouper.numGroups(); i++) {
      List<NID.WithName> group = targetsGroup.get(i);
      for (NID.WithName node : group) {
        targets.add(node);
      }
    }
    
    if (targets.size() != allNodes.size()) {
      throw new IllegalStateException("target numGroups not equal to all-nodes numGroups");
    }

    installAnnotations(nabd, targetsGroup, grouper);
    
    UiUtil.fixMePrintout("Loop Reporter all messed up in NetworkAlignmentLayout.FlushQueue");
    return (targets);
  }
  
  /***************************************************************************
   **
   ** Node ordering, non-recursive:
   */
  
  private void flushQueue(SortedMap<Integer, List<NID.WithName>> targetsGroup,
                          Map<NID.WithName, Set<NID.WithName>> targsPerSource,
                          Map<NID.WithName, Integer> linkCounts,
                          Set<NID.WithName> targsToGo, SortedMap<Integer, List<NID.WithName>> targsLeftToGoGroup,
                          SortedMap<Integer, List<NID.WithName>> queuesGroup,
                          BTProgressMonitor monitor, double startFrac, double endFrac, final int currGroup,
                          NodeGroupMap grouper)
          throws AsynchExitRequestException {
    
    List<NID.WithName> queue = queuesGroup.get(currGroup);
    List<NID.WithName> leftToGo = targsLeftToGoGroup.get(currGroup);
    
    LoopReporter lr = new LoopReporter(leftToGo.size(), 20, monitor, startFrac, endFrac, "progress.nodeOrdering");
    int lastSize = leftToGo.size();
  
    while (! queue.isEmpty()) {
      
      NID.WithName node = queue.remove(0);
      int ttgSize = targsLeftToGoGroup.get(currGroup).size();
      lr.report(lastSize - ttgSize);
      lastSize = ttgSize;
      
      if (targetsGroup.get(currGroup).contains(node)) {
        continue; // visited each node only once
      }
      targetsGroup.get(currGroup).add(node);
      
      if (grouper.getIndex(node) != currGroup) {
        throw new IllegalStateException("Node of incorrect group in queue");
      }
      
      List<NID.WithName> myKids = orderMyKids(targsPerSource, linkCounts, targsToGo, node);
      for (NID.WithName kid : myKids) {
        
        if (! targsToGo.contains(kid)) {
          throw new IllegalStateException("kid not in targsToGo");
        }
        
        int kidGroup = grouper.getIndex(kid);
        
        if (kidGroup == currGroup) {
          if (leftToGo.contains(kid)) {
            queue.add(kid);
            leftToGo.remove(kid);
            targsToGo.remove(kid);
          }
        } else {
          if (! queuesGroup.get(kidGroup).contains(kid)) {
            queuesGroup.get(kidGroup).add(kid); // if node from another group, put it in its queue
          }
        }
      }
    }
    lr.finish();
    return;
  }
  
  /***************************************************************************
   **
   ** Node ordering
   */
  
  private List<NID.WithName> orderMyKids(final Map<NID.WithName, Set<NID.WithName>> targsPerSource,
                                         Map<NID.WithName, Integer> linkCounts,
                                         Set<NID.WithName> targsToGo, final NID.WithName node) {
    Set<NID.WithName> targs = targsPerSource.get(node);
    if (targs == null) {
      return (new ArrayList<NID.WithName>());
    }
    TreeMap<Integer, SortedSet<NID.WithName>> kidMap = new TreeMap<Integer, SortedSet<NID.WithName>>(Collections.reverseOrder());
    Iterator<NID.WithName> tait = targs.iterator();
    while (tait.hasNext()) {
      NID.WithName nextTarg = tait.next();
      Integer count = linkCounts.get(nextTarg);
      SortedSet<NID.WithName> perCount = kidMap.get(count);
      if (perCount == null) {
        perCount = new TreeSet<NID.WithName>();
        kidMap.put(count, perCount);
      }
      perCount.add(nextTarg);
    }
    
    ArrayList<NID.WithName> myKidsToProc = new ArrayList<NID.WithName>();
    Iterator<SortedSet<NID.WithName>> kmit = kidMap.values().iterator();
    while (kmit.hasNext()) {
      SortedSet<NID.WithName> perCount = kmit.next();
      Iterator<NID.WithName> pcit = perCount.iterator();
      while (pcit.hasNext()) {
        NID.WithName kid = pcit.next();
        if (targsToGo.contains(kid)) {
          myKidsToProc.add(kid);
        }
      }
    }
    return (myKidsToProc);
  }
  
  /***************************************************************************
   **
   ** Install Layer Zero Node Annotations
   */
  
  private void installAnnotations(NetworkAlignmentBuildData nabd,
                                  SortedMap<Integer, List<NID.WithName>> targetsGroup, NodeGroupMap grouper) {
    
    AnnotationSet layerZeroAnnots = new AnnotationSet();
    int min = 0;
    
    for (int i = 0; i < grouper.numGroups(); i++) {
      List<NID.WithName> group = targetsGroup.get(i);
      if (group.isEmpty()) {
        continue;
      }
      int max = min + group.size() - 1;
  
      AnnotationSet.Annot annot = new AnnotationSet.Annot(grouper.getKey(i), min, max, 0, grouper.getColor(i));
      layerZeroAnnots.addAnnot(annot);
  
      min += group.size(); // update current minimum
    }
    nabd.setNodeAnnotations(layerZeroAnnots);
    return;
  }
  
  ////////////////////////////////////////////////////////////////////////////
  //
  // PUBLIC CONSTANTS
  //
  ////////////////////////////////////////////////////////////////////////////
  
  public static final String[] defaultNGOrderWithoutCorrect = {
          "(P:0)",
          "(P:P)",
          "(P:B)",
          "(P:pRp)",
          "(P:P/B)",
          "(P:P/pRp)",
          "(P:B/pRp)",
          "(P:P/B/pRp)",
          "(P:pRr)",
          "(P:P/pRr)",
          "(P:B/pRr)",
          "(P:pRp/pRr)",
          "(P:P/B/pRr)",
          "(P:P/pRp/pRr)",
          "(P:B/pRp/pRr)",
          "(P:P/B/pRp/pRr)",
          "(R:pRr)",
          "(R:rRr)",
          "(R:pRr/rRr)",
          "(R:0)"
  };
  
  public static final String[] defaultNGOrderWithCorrect = {
          "(P:0/1)",
          "(P:0/0)",
          "(P:P/1)",
          "(P:P/0)",
          "(P:B/1)",
          "(P:B/0)",
          "(P:pRp/1)",
          "(P:pRp/0)",
          "(P:P/B/1)",
          "(P:P/B/0)",
          "(P:P/pRp/1)",
          "(P:P/pRp/0)",
          "(P:B/pRp/1)",
          "(P:B/pRp/0)",
          "(P:P/B/pRp/1)",
          "(P:P/B/pRp/0)",
          "(P:pRr/1)",
          "(P:pRr/0)",
          "(P:P/pRr/1)",
          "(P:P/pRr/0)",
          "(P:B/pRr/1)",
          "(P:B/pRr/0)",
          "(P:pRp/pRr/1)",
          "(P:pRp/pRr/0)",
          "(P:P/B/pRr/1)",
          "(P:P/B/pRr/0)",
          "(P:P/pRp/pRr/1)",
          "(P:P/pRp/pRr/0)",
          "(P:B/pRp/pRr/1)",
          "(P:B/pRp/pRr/0)",
          "(P:P/B/pRp/pRr/1)",
          "(P:P/B/pRp/pRr/0)",
          "(R:pRr/0)",
          "(R:rRr/0)",
          "(R:pRr/rRr/0)",
          "(R:0/0)"
  };
  
  public static final String[][] ngAnnotColorsWithoutCorrect = {
          {"(P:0)",           "GrayBlue"},
          {"(P:P)",           "Orange"},        // FIRST THREE LINK GROUPS
          {"(P:B)",           "Yellow"},
          {"(P:pRp)",         "Green"},
          {"(P:P/B)",         "Purple"},
          {"(P:P/pRp)",       "Pink"},
          {"(P:B/pRp)",       "PowderBlue"},
          {"(P:P/B/pRp)",     "Peach"},
          {"(P:pRr)",         "GrayBlue"},      // PURPLE NODES IN LINK GROUP 3
          {"(P:P/pRr)",       "Orange"},
          {"(P:B/pRr)",       "Yellow"},
          {"(P:pRp/pRr)",     "Green"},
          {"(P:P/B/pRr)",     "Purple"},
          {"(P:P/pRp/pRr)",   "Pink"},
          {"(P:B/pRp/pRr)",   "PowderBlue"},
          {"(P:P/B/pRp/pRr)", "Peach"},
          {"(R:pRr)",         "GrayBlue"},      // RED NODES IN LINK GROUP 5
          {"(R:rRr)",         "Orange"},
          {"(R:pRr/rRr)",     "Yellow"},
          {"(R:0)",           "Green"}
  };
  
  public static final String[][] ngAnnotcolorsWithCorrect = {
          {"(P:0/1)",          "GrayBlue"},
          {"(P:0/0)",          "DarkGrayBlue"},
          {"(P:P/1)",          "Orange"},
          {"(P:P/0)",          "DarkOrange"},
          {"(P:B/1)",          "Yellow"},
          {"(P:B/0)",          "DarkYellow"},
          {"(P:pRp/1)",        "Green"},
          {"(P:pRp/0)",        "DarkGreen"},
          {"(P:P/B/1)",        "Purple"},
          {"(P:P/B/0)",        "DarkPurple"},
          {"(P:P/pRp/1)",      "Pink"},
          {"(P:P/pRp/0)",      "DarkPink"},
          {"(P:B/pRp/1)",      "PowderBlue"},
          {"(P:B/pRp/0)",      "DarkPowderBlue"},
          {"(P:P/B/pRp/1)",    "Peach"},
          {"(P:P/B/pRp/0)",    "DarkPeach"},
          {"(P:pRr/1)",        "GrayBlue"},
          {"(P:pRr/0)",        "DarkGrayBlue"},
          {"(P:P/pRr/1)",      "Orange"},
          {"(P:P/pRr/0)",      "DarkOrange"},
          {"(P:B/pRr/1)",      "Yellow"},
          {"(P:B/pRr/0)",      "DarkYellow"},
          {"(P:pRp/pRr/1)",    "Green"},
          {"(P:pRp/pRr/0)",    "DarkGreen"},
          {"(P:P/B/pRr/1)",    "Purple"},
          {"(P:P/B/pRr/0)",    "DarkPurple"},
          {"(P:P/pRp/pRr/1)",  "Pink"},
          {"(P:P/pRp/pRr/0)",  "DarkPink"},
          {"(P:B/pRp/pRr/1)",  "PowderBlue"},
          {"(P:B/pRp/pRr/0)",  "DarkPowderBlue"},
          {"(P:P/B/pRp/pRr/1)","Peach"},
          {"(P:P/B/pRp/pRr/0)","DarkPeach"},
          {"(R:pRr/0)",        "DarkGrayBlue"},
          {"(R:rRr/0)",        "DarkOrange"},
          {"(R:pRr/rRr/0)",    "DarkYellow"},
          {"(R:0/0)",          "DarkGreen"}
  };
  
}
