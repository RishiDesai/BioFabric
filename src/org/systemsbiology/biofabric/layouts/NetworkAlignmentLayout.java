/*
**    Copyright (C) 2003-2017 Institute for Systems Biology
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

package org.systemsbiology.biofabric.layouts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import org.systemsbiology.biofabric.model.AnnotationSet;
import org.systemsbiology.biofabric.model.BioFabricNetwork;
import org.systemsbiology.biofabric.model.FabricLink;
import org.systemsbiology.biofabric.util.AsynchExitRequestException;
import org.systemsbiology.biofabric.util.BTProgressMonitor;
import org.systemsbiology.biofabric.util.LoopReporter;
import org.systemsbiology.biofabric.util.NID;
import org.systemsbiology.biofabric.util.UiUtil;

import static org.systemsbiology.biofabric.analysis.NetworkAlignment.COVERED_EDGE;
import static org.systemsbiology.biofabric.analysis.NetworkAlignment.GRAPH1;
import static org.systemsbiology.biofabric.analysis.NetworkAlignment.INDUCED_GRAPH2;
import static org.systemsbiology.biofabric.analysis.NetworkAlignment.HALF_UNALIGNED_GRAPH2;
import static org.systemsbiology.biofabric.analysis.NetworkAlignment.FULL_UNALIGNED_GRAPH2;

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
  
  public List<NID.WithName> doNodeLayout(BioFabricNetwork.RelayoutBuildData rbd, Params params, BTProgressMonitor monitor)
          throws AsynchExitRequestException {
    
    BioFabricNetwork.NetworkAlignmentBuildData nabd = (BioFabricNetwork.NetworkAlignmentBuildData) rbd;
    
    List<NID.WithName> targetIDs;
    
    if (nabd.forOrphans) {
      targetIDs = (new DefaultLayout()).defaultNodeOrder(nabd.allLinks, nabd.loneNodeIDs, null, monitor);
    } else {
      targetIDs = BFSNodeGroupLayout(nabd, monitor);
    }
    
    installNodeOrder(targetIDs, nabd, monitor);
    return (new ArrayList<NID.WithName>(targetIDs));
  }
  
  /***************************************************************************
   **
   ** Breadth first search based on node groups
   */
  
  public List<NID.WithName> BFSNodeGroupLayout(BioFabricNetwork.NetworkAlignmentBuildData nabd,
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
    
    Iterator<FabricLink> alit = nabd.allLinks.iterator();
    while (alit.hasNext()) {
      FabricLink nextLink = alit.next();
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
    
    NodeGroupMap grouper = new NodeGroupMap(nabd, DefaultNodeGroupOrder);
    
    // master list of nodes in each group
    SortedMap<Integer, List<NID.WithName>> classToGroup = new TreeMap<Integer, List<NID.WithName>>();
    
    for (int i = 0; i < grouper.numGroups(); i++) {
      classToGroup.put(i, new ArrayList<NID.WithName>());
    }
    
    Set<NID.WithName> allNodes = BioFabricNetwork.extractNodes(nabd.allLinks, nabd.loneNodeIDs, monitor);
    for (NID.WithName node : allNodes) {
      int nodeClass = grouper.getIndex(node);
      classToGroup.get(nodeClass).add(node);
    }
    
    for (List<NID.WithName> group : classToGroup.values()) { // sort by decreasing degree
      grouper.sortByDecrDegree(group);
    }
    
    SortedMap<Integer, List<NID.WithName>> targetsGroup = new TreeMap<Integer, List<NID.WithName>>(),
            queueGroup = new TreeMap<Integer, List<NID.WithName>>(),
            targsLeftToGoGroup = new TreeMap<Integer, List<NID.WithName>>();
    
    // each node group (not singletons) gets queue and targets list
//    for (int i = 1; i < NUMBER_NODE_GROUPS_MINUS1; i++) {
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

//    int currGroup = 1;
    int currGroup = 0;
//    while (currGroup < NUMBER_NODE_GROUPS_MINUS1) {
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
              monitor, .25, .50, currGroup, grouper);
    }
    
    //
    // Add lone nodes and "flatten" out the targets into one list
    //

//    targetsGroup.put(PURPLE_SINGLETON, new ArrayList<NID.WithName>());
//    targetsGroup.get(PURPLE_SINGLETON).addAll(classToGroup.get(PURPLE_SINGLETON));
//    targetsGroup.put(RED_SINGLETON, new ArrayList<NID.WithName>());
//    targetsGroup.get(RED_SINGLETON).addAll(classToGroup.get(RED_SINGLETON));
    
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

    installAnnotations(nabd, targetsGroup, targets, grouper);
    
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
    
    LoopReporter lr = new LoopReporter(targsToGo.size(), 20, monitor, startFrac, endFrac, "progress.nodeOrdering");
    int lastSize = targsToGo.size();
    List<NID.WithName> queue = queuesGroup.get(currGroup);
    List<NID.WithName> leftToGo = targsLeftToGoGroup.get(currGroup);
    
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
  
  private void installAnnotations(BioFabricNetwork.NetworkAlignmentBuildData nabd,
                                  SortedMap<Integer, List<NID.WithName>> targetsGroup,
                                  List<NID.WithName> targets, NodeGroupMap grouper) {
    
    Map<Integer, List<NID.WithName>> layerZeroAnnot = new TreeMap<Integer, List<NID.WithName>>();

//    for (int i = 0; i <= NUMBER_NODE_GROUPS_MINUS1; i++) { // include singletons
    for (int i = 0; i < grouper.numGroups(); i++) { // include singletons
      List<NID.WithName> group = targetsGroup.get(i);
      if (group.isEmpty()) {
        continue;
      }
      layerZeroAnnot.put(i, new ArrayList<NID.WithName>()); // add first and last node in each group
      layerZeroAnnot.get(i).add(group.get(0));
      layerZeroAnnot.get(i).add(group.get(group.size() - 1));
    }
    
    AnnotationSet annots = new AnnotationSet();
    for (Map.Entry<Integer, List<NID.WithName>> entry : layerZeroAnnot.entrySet()) {
      
      int nodeGroup = entry.getKey();
      String start = entry.getValue().get(0).toString(), end = entry.getValue().get(1).toString();
      int min = - 1, max = - 1;
      
      // make more efficient
      for (int i = 0; i < targets.size(); i++) {
        if (start.equals(targets.get(i).toString())) {
          min = i;
        }
        if (end.equals(targets.get(i).toString())) {
          max = i;
        }
      }
      if (min > max || min < 0) {
//        System.out.println(min + "  " + max +"  NG:" + nodeGroup);
        throw new IllegalStateException("Annotation min max error in NetAlign Layout");
      }
      
      AnnotationSet.Annot annot = new AnnotationSet.Annot(DefaultNodeGroupOrder[nodeGroup], min, max, 0);
      annots.addAnnot(annot);
    }
    nabd.setNodeAnnots(annots);
    return;
  }
  
  /***************************************************************************
   **
   ** LG = LINK GROUP
   **
   ** FIRST LG  = PURPLE EDGES           // COVERERED EDGE
   ** SECOND LG = BLUE EDGES             // GRAPH1
   ** THIRD LG  = RED EDGES              // INDUCED_GRAPH2
   ** FOURTH LG = ORANGE EDGES           // HALF_UNALIGNED_GRAPH2    (TECHNICALLY RED EDGES)
   ** FIFTH LG  = YELLOW EDGES           // FULL_UNALIGNED_GRAPH2    (TECHNICALLY RED EDGES)
   **
   ** PURPLE NODE =  ALIGNED NODE
   ** RED NODE    =  UNALINGED NODE
   **
   **
   ** WE HAVE 18 DISTINCT CLASSES (NODE GROUPS) FOR EACH ALIGNED AND UNALIGNED NODE
   ** TECHNICALLY 20 INCLUDING THE SINGLETON ALIGNED AND SINGLETON UNALIGNED NODES
   **
   */
  
  public static final int NUMBER_LINK_GROUPS = 5;   // 0..4
  
  private static final int
          PURPLE_EDGES = 0,
          BLUE_EDGES = 1,
          RED_EDGES = 2,
          ORANGE_EDGES = 3,
          YELLOW_EDGES = 4;

//  private static final int NUMBER_NODE_GROUPS_MINUS1 = 19; // 0..19
//
//  private static final int
//          PURPLE_SINGLETON = 0,
//          RED_SINGLETON = 19;
  
  
  public static final String[] DefaultNodeGroupOrder = {
          "(P:0)",
          "(P:P)",            // FIRST THREE LINK GROUPS
          "(P:B)",
          "(P:pRp)",
          "(P:P/B)",
          "(P:P/pRp)",
          "(P:B/pRp)",
          "(P:P/B/pRp)",
          "(P:pRr)",          // PURPLE NODES IN LINK GROUP 3
          "(P:P/pRr)",
          "(P:B/pRr)",
          "(P:pRp/pRr)",
          "(P:P/B/pRr)",
          "(P:P/pRp/pRr)",
          "(P:B/pRp/pRr)",
          "(P:P/B/pRp/pRr)",
          "(R:pRr)",          // RED NODES IN LINK GROUP 5
          "(R:rRr)",
          "(R:pRr/rRr)",
          "(R:0)"
  };
  
  public static final String[] DefaultNodeGroupOrderAlign = {
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
  
  /***************************************************************************
   **
   ** HashMap based Data structure
   */
  
  public static class NodeGroupMap {
    
    private Map<NID.WithName, Set<FabricLink>> nodeToLinks_;
    private Map<NID.WithName, Set<NID.WithName>> nodeToNeighbors_;
    private Map<NID.WithName, Boolean> mergedToCorrect_;
    private Map<GroupID, Integer> groupIDtoIndex;
    private final int numGroups;
    
    public NodeGroupMap(BioFabricNetwork.NetworkAlignmentBuildData nabd, String[] nodeGroupOrder) {
      this(nabd.allLinks, nabd.loneNodeIDs, nabd.mergedToCorrect_, nodeGroupOrder);
    }
    
    public NodeGroupMap(Set<FabricLink> allLinks, Set<NID.WithName> loneNodeIDs,
                        Map<NID.WithName, Boolean> mergedToCorrect, String[] nodeGroupOrder) {
      this.mergedToCorrect_ = mergedToCorrect;
      this.numGroups = nodeGroupOrder.length;
      generateStructs(allLinks, loneNodeIDs);
      generateMap(nodeGroupOrder);
      return;
    }
    
    private void generateStructs(Set<FabricLink> allLinks, Set<NID.WithName> loneNodeIDs) {
      
      nodeToLinks_ = new HashMap<NID.WithName, Set<FabricLink>>();
      nodeToNeighbors_ = new HashMap<NID.WithName, Set<NID.WithName>>();
      
      for (FabricLink link : allLinks) {
        NID.WithName src = link.getSrcID(), trg = link.getTrgID();
        
        if (nodeToLinks_.get(src) == null) {
          nodeToLinks_.put(src, new HashSet<FabricLink>());
        }
        if (nodeToLinks_.get(trg) == null) {
          nodeToLinks_.put(trg, new HashSet<FabricLink>());
        }
        if (nodeToNeighbors_.get(src) == null) {
          nodeToNeighbors_.put(src, new HashSet<NID.WithName>());
        }
        if (nodeToNeighbors_.get(trg) == null) {
          nodeToNeighbors_.put(trg, new HashSet<NID.WithName>());
        }
        
        nodeToLinks_.get(src).add(link);
        nodeToLinks_.get(trg).add(link);
        nodeToNeighbors_.get(src).add(trg);
        nodeToNeighbors_.get(trg).add(src);
      }
      
      for (NID.WithName node : loneNodeIDs) {
        nodeToLinks_.put(node, new HashSet<FabricLink>());
        nodeToNeighbors_.put(node, new HashSet<NID.WithName>());
      }
      return;
    }
    
    private void generateMap(String[] nodeGroupOrder) {
      groupIDtoIndex = new HashMap<GroupID, Integer>();
      for (int index = 0; index < nodeGroupOrder.length; index++) {
        GroupID gID = new GroupID(nodeGroupOrder[index]);
        groupIDtoIndex.put(gID, index);
      }
      return;
    }
    
    /***************************************************************************
     **
     ** Return the index from the given node group ordering
     */
    
    public int getIndex(NID.WithName node) {
      GroupID groupID = generateID(node);
      if (groupIDtoIndex.get(groupID) == null) {
//        System.out.println(groupID + " null");
        throw new IllegalStateException("GroupID not found in given order");
      }
      return (groupIDtoIndex.get(groupID));
    }
    
    public int numGroups() {
      return (numGroups);
    }
    
    /***************************************************************************
     **
     ** Hash function
     */
    
    private GroupID generateID(NID.WithName node) {
      StringBuilder sb = new StringBuilder();
      
      //
      // See which types of link groups the node's links are in
      //
      
      String[] possibleRels = {COVERED_EDGE, GRAPH1, INDUCED_GRAPH2, HALF_UNALIGNED_GRAPH2, FULL_UNALIGNED_GRAPH2};
      boolean[] inLG = new boolean[NUMBER_LINK_GROUPS];
      
      for (FabricLink link : nodeToLinks_.get(node)) {
        for (int rel = 0; rel < inLG.length; rel++) {
          if (link.getRelation().equals(possibleRels[rel])) {
            inLG[rel] = true;
          }
        }
      }
      
      List<String> tags = new ArrayList<String>();
      if (inLG[PURPLE_EDGES]) {
        tags.add("P");
      }
      if (inLG[BLUE_EDGES]) {
        tags.add("B");
      }
      if (inLG[RED_EDGES]) {
        tags.add("pRp");
      }
      if (inLG[ORANGE_EDGES]) {
        tags.add("pRr");
      }
      if (inLG[YELLOW_EDGES]) {
        tags.add("rRr");
      }
      
      sb.append("(");
      sb.append((isPurple(node) ? "P" : "R") + ":");  // aligned/unaligned node
      
      for (int i = 0; i < tags.size(); i++) {         // link group tags
        if (i != tags.size() - 1) {
          sb.append(tags.get(i) + "/");
        } else {
          sb.append(tags.get(i));
        }
      }
      
      if (tags.size() == 0) { // for singletons
        sb.append("0");
      }
      
      sb.append(")");
      
      // aligned correctly
//      if (mergedToCorrect_ == null) {
//        sb.append(0);
//      } else {
//        sb.append((mergedToCorrect_.get(node)) ? 1 : 0);
//      }
      UiUtil.fixMePrintout("GroupID correctly aligned");
      return (new GroupID(sb.toString()));
    }
    
    /*******************************************************************
     **
     ** Identifies Aligned Nodes if they have a dash ('-') in name:
     */
    
    private boolean isPurple(NID.WithName node) {
      UiUtil.fixMePrintout("FIX ME:find way to identify aligned nodes besides having dash in name");
      return (node.getName().contains("-"));
    }
    
    /*******************************************************************
     **
     ** Sorts Node group on decreasing degree
     */
    
    void sortByDecrDegree(List<NID.WithName> group) {
      Collections.sort(group, new Comparator<NID.WithName>() {
        public int compare(NID.WithName node1, NID.WithName node2) {
          int diffSize = nodeToNeighbors_.get(node2).size() - nodeToNeighbors_.get(node1).size();
          return (diffSize != 0) ? diffSize : node1.getName().compareTo(node2.getName());
        }
      });
      return;
    }
    
  }
  
  /***************************************************************************
   **
   ** Hash for Hash-map data structure for node groups
   */
  
  private static class GroupID {
    
    private final String key;
    
    public GroupID(String key) {
      this.key = key;
    }
    
    @Override
    public String toString() {
      return (key);
    }
    
    @Override
    public boolean equals(Object o) {
      if (this == o) return (true);
      if (! (o instanceof GroupID)) return (false);
      
      GroupID groupID = (GroupID) o;
      
      if (key != null ? (! key.equals(groupID.key)) : (groupID.key != null)) return (false);
      return (true);
    }
    
    @Override
    public int hashCode() {
      return (key != null ? key.hashCode() : 0);
    }
    
  }
  
}

//  public static final String[] DefaultNodeGroupOrder = {
//          "(P:0)",
//          "(P:P)",            // FIRST THREE LINK GROUPS
//          "(P:B)",
//          "(P:pRp)",
//          "(P:P/B)",
//          "(P:P/pRp)",
//          "(P:B/pRp)",
//          "(P:P/B/pRp)",
//          "(P:pRr)",          // PURPLE NODES IN LINK GROUP 3
//          "(P:P/pRr)",
//          "(P:B/pRr)",
//          "(P:pRp/pRr)",
//          "(P:P/B/pRr)",
//          "(P:P/pRp/pRr)",
//          "(P:B/pRp/pRr)",
//          "(P:P/B/pRp/pRr)",
//          "(R:pRr)",          // RED NODES IN LINK GROUP 5
//          "(R:rRr)",
//          "(R:pRr/rRr)",
//          "(R:0)"
//  };

//      sb.append((inLG[PURPLE_EDGES] ? "P"   : 0) + "/");
//      sb.append((inLG[BLUE_EDGES]   ? "B"   : 0) + "/");
//      sb.append((inLG[RED_EDGES]    ? "pRp" : 0) + "/");
//      sb.append((inLG[ORANGE_EDGES] ? "pRr" : 0) + "/");
//      sb.append((inLG[YELLOW_EDGES] ? "rRr" : 0) + "/");

//      sb.append((inLG[PURPLE_EDGES] ? "P/"   : "") + "");
//      sb.append((inLG[BLUE_EDGES]   ? "B/"   : "") + "");
//      sb.append((inLG[RED_EDGES]    ? "pRp/" : "") + "");
//      sb.append((inLG[ORANGE_EDGES] ? "pRr/" : "") + "");
//      sb.append((inLG[YELLOW_EDGES] ? "rRr"  : "") + "");
//
//      if (sb.substring(sb.length()-1, sb.length()).equals("/")) {
//        sb.delete(sb.length()-1, sb.length());
//      }

//PURPLE_SINGLETON = 0,
//          PURPLE_WITH_ONLY_PURPLE = 1,             // FIRST THREE LINK GROUPS
//          PURPLE_WITH_ONLY_BLUE = 2,
//          PURPLE_WITH_ONLY_RED = 3,
//          PURPLE_WITH_PURPLE_BLUE = 4,
//          PURPLE_WITH_PURPLE_RED = 5,
//          PURPLE_WITH_BLUE_RED = 6,
//          PURPLE_WITH_PURPLE_BLUE_RED = 7,
//
//          PURPLE_WITH_ONLY_ORANGE = 8,              // PURPLE NODES IN LINK GROUP 3
//          PURPLE_WITH_PURPLE_ORANGE = 9,
//          PURPLE_WITH_BLUE_ORANGE = 10,
//          PURPLE_WITH_RED_ORANGE = 11,
//          PURPLE_WITH_PURPLE_BLUE_ORANGE = 12,
//          PURPLE_WITH_PURPLE_RED_ORANGE = 13,
//          PURPLE_WITH_BLUE_RED_ORANGE = 14,
//          PURPLE_WITH_PURPLE_BLUE_RED_ORANGE = 15,
//
//          RED_WITH_ORANGE = 16,                    // RED NODES IN LINK GROUP 5
//          RED_WITH_ONLY_YELLOW = 17,
//          RED_WITH_ORANGE_YELLOW = 18,
//        RED_SINGLETON = 19;

//  private static final String[] DefaultNodeGroupOrder = {
//          "(P:0/0/0/0/0/0)",
//          "(P:P/0/0/0/0/0)",            // 1
//          "(P:0/B/0/0/0/0)",
//          "(P:0/0/pRp/0/0/0)",
//          "(P:P/B/0/0/0/0)",
//          "(P:P/0/pRp/0/0/0)",
//          "(P:0/B/pRp/0/0/0)",        // 6
//          "(P:P/B/pRp/0/0/0)",
//          "(P:0/0/0/pRr/0/0)",
//          "(P:P/0/0/pRr/0/0)",
//          "(P:0/B/0/pRr/0/0)",
//          "(P:0/0/pRp/pRr/0/0)",      // 11
//          "(P:P/B/0/pRr/0/0)",
//          "(P:P/0/pRp/pRr/0/0)",
//          "(P:0/B/pRp/pRr/0/0)",
//          "(P:P/B/pRp/pRr/0/0)",
//          "(R:0/0/0/pRr/0/0)",          // 16
//          "(R:0/0/0/0/rRr/0)",
//          "(R:0/0/0/pRr/rRr/0)",
//          "(R:0/0/0/0/0/0)"
//  };
