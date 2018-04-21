/*
**
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.systemsbiology.biofabric.model.FabricLink;
import org.systemsbiology.biofabric.util.NID;
import org.systemsbiology.biofabric.util.UiUtil;

/***************************************************************************
 **
 ** HashMap based Data structure
 **
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

public class NodeGroupMap {
  
  ////////////////////////////////////////////////////////////////////////////
  //
  // PRIVATE INSTANCE MEMBERS
  //
  ////////////////////////////////////////////////////////////////////////////
  
  private boolean forPerfectNG_;
  private Set<FabricLink> links_;
  private Set<NID.WithName> loners_;
  private Map<NID.WithName, Boolean> mergedToCorrect_, isAlignedNode_;
  
  private Map<NID.WithName, Set<FabricLink>> nodeToLinks_;
  private Map<NID.WithName, Set<NID.WithName>> nodeToNeighbors_;
  
  private Map<GroupID, Integer> groupIDtoIndex_;
  private Map<Integer, GroupID> indexToGroupID_;
  private Map<GroupID, String> groupIDtoColor_;
  private final int numGroups_;
  
  private Map<String, Double> nodeGroupRatios_, linkGroupRatios_;
  
  public static final int
          PURPLE_EDGES = 0,
          BLUE_EDGES = 1,
          RED_EDGES = 2,
          ORANGE_EDGES = 3,
          YELLOW_EDGES = 4;
  
  public static final int NUMBER_LINK_GROUPS = 5;   // 0..4
  
  public NodeGroupMap(NetworkAlignmentBuildData nabd, String[] nodeGroupOrder, String[][] colorMap) {
    this(nabd.allLinks, nabd.loneNodeIDs, nabd.mergedToCorrect, nabd.isAlignedNode, nabd.forPerfectNG, nodeGroupOrder, colorMap);
  }
  
  public NodeGroupMap(Set<FabricLink> allLinks, Set<NID.WithName> loneNodeIDs,
                      Map<NID.WithName, Boolean> mergedToCorrect, Map<NID.WithName, Boolean> isAlignedNode,
                      boolean forPerfectNG, String[] nodeGroupOrder, String[][] colorMap) {
    this.links_ = allLinks;
    this.loners_ = loneNodeIDs;
    this.mergedToCorrect_ = mergedToCorrect;
    this.isAlignedNode_ = isAlignedNode;
    this.forPerfectNG_ = forPerfectNG;
    this.numGroups_ = nodeGroupOrder.length;
    generateStructs(allLinks, loneNodeIDs);
    generateOrderMap(nodeGroupOrder);
    generateColorMap(colorMap);
    calcNGRatios();
    calcLGRatios();
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
  
  private void generateOrderMap(String[] nodeGroupOrder) {
    groupIDtoIndex_ = new HashMap<GroupID, Integer>();
    indexToGroupID_ = new HashMap<Integer, GroupID>();
    for (int index = 0; index < nodeGroupOrder.length; index++) {
      GroupID gID = new GroupID(nodeGroupOrder[index]);
      groupIDtoIndex_.put(gID, index);
      indexToGroupID_.put(index, gID);
    }
    return;
  }
  
  private void generateColorMap(String[][] colorMap) {
    groupIDtoColor_ = new HashMap<GroupID, String>();
    
    for (String[] ngCol : colorMap) {
      GroupID groupID = new GroupID(ngCol[0]);
      String color = ngCol[1];
      groupIDtoColor_.put(groupID, color);
    }
    return;
  }
  
  /***************************************************************************
   **
   ** Hash function
   */
  
  private GroupID generateID(NID.WithName node) {
    //
    // See which types of link groups the node's links are in
    //
    
    String[] possibleRels = {NetworkAlignment.COVERED_EDGE, NetworkAlignment.GRAPH1,
            NetworkAlignment.INDUCED_GRAPH2, NetworkAlignment.HALF_UNALIGNED_GRAPH2, NetworkAlignment.FULL_UNALIGNED_GRAPH2};
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
    if (tags.isEmpty()) { // singleton
      tags.add("0");
    }
    
    StringBuilder sb = new StringBuilder();
    sb.append("(");
    sb.append(isAlignedNode_.get(node) ? "P" : "R");  // aligned/unaligned node
    sb.append(":");
  
    for (int i = 0; i < tags.size(); i++) {
      sb.append(tags.get(i));    // link group tags
      if (i != tags.size() - 1) {
        sb.append("/");
      }
    }
    
    if (mergedToCorrect_ != null && forPerfectNG_) {  // perfect alignment given and user wants perfect NGs
      sb.append("/");
      if (mergedToCorrect_.get(node) == null) {       // red node
        sb.append(0);
      } else {
        sb.append((mergedToCorrect_.get(node)) ? 1 : 0);
      }
    }
    
    sb.append(")");
    
    return (new GroupID(sb.toString()));
  }
  
  /***************************************************************************
   **
   ** Calculate node group size to total #nodes for each group
   */
  
  private void calcNGRatios() {
    Set<NID.WithName> nodes = nodeToLinks_.keySet();
    double size = (double) nodes.size();
    Set<GroupID> tags = groupIDtoIndex_.keySet();
  
    Map<GroupID, Integer> counts = new HashMap<GroupID, Integer>(); // initial vals
    for (GroupID gID : tags) {
      counts.put(gID, 0);
    }
    
    for (NID.WithName node : nodes) {
      GroupID gID = generateID(node);
      counts.put(gID, counts.get(gID) + 1);
    }
  
    nodeGroupRatios_ = new HashMap<String, Double>();
    for (Map.Entry<GroupID, Integer> count : counts.entrySet()) {
      String tag = count.getKey().getKey();
      double ratio = count.getValue() / size;
      nodeGroupRatios_.put(tag, ratio);
    }
    return;
  }
  
  /***************************************************************************
   **
   ** Calculate link group size to total #links for each group
   */
  
  private void calcLGRatios() {
    
    String[] rels = {NetworkAlignment.COVERED_EDGE, NetworkAlignment.GRAPH1,
            NetworkAlignment.INDUCED_GRAPH2, NetworkAlignment.HALF_UNALIGNED_GRAPH2, NetworkAlignment.FULL_UNALIGNED_GRAPH2};
    double size = (double) links_.size();
    
    Map<String, Integer> counts = new HashMap<String, Integer>(); // initial vals
    for (String rel : rels) {
      counts.put(rel, 0);
    }
    
    for (FabricLink link : links_) {
      String rel = link.getRelation();
      counts.put(rel, counts.get(rel) + 1);
    }
    
    linkGroupRatios_ = new HashMap<String, Double>();
    for (Map.Entry<String, Integer> count : counts.entrySet()) {
      String tag = count.getKey();
      double ratio = count.getValue() / size;
      linkGroupRatios_.put(tag, ratio);
    }
    return;
  }
  
  /***************************************************************************
   **
   ** Return the index in given ordering given node
   */
  
  public int getIndex(NID.WithName node) {
    GroupID groupID = generateID(node);
    Integer index = groupIDtoIndex_.get(groupID);
    if (index == null) {
      throw new IllegalArgumentException("GroupID " + groupID + " not found in given order list; given node " + node.getName());
    }
    return (index);
  }
  
  /***************************************************************************
   **
   ** Return the index from the given ordering given tag
   */
  
  public int getIndex(String key) {
    Integer index = groupIDtoIndex_.get(new GroupID(key));
    if (index == null) {
      throw new IllegalArgumentException("GroupID " + key + " not found in given order list; given key " + key);
    }
    return (index);
  }

  /***************************************************************************
   **
   ** Return the GroupID from the given node group ordering
   */
  
  public String getKey(Integer index) {
    if (indexToGroupID_.get(index) == null) {
      throw new IllegalArgumentException("Index not found in given order list; given index " + index);
    }
    return (indexToGroupID_.get(index).getKey());
  }
 
  /***************************************************************************
   **
   ** Return the Annot Color for index of NG label
   */
  
  public String getColor(Integer index) {
    GroupID groupID = indexToGroupID_.get(index);
    return (groupIDtoColor_.get(groupID));
  }
  
  public int numGroups() {
    return (numGroups_);
  }
  
  public Map<String, Double> getNodeGroupRatios() {
    return (nodeGroupRatios_);
  }
  
  public Map<String, Double> getLinkGroupRatios() {
    return (linkGroupRatios_);
  }
  
  /***************************************************************************
   **
   ** Sorts in decreasing node degree - method is here for convenience
   */
  
  public Comparator<NID.WithName> sortDecrDegree() {
    return (new Comparator<NID.WithName>() {
      @Override
      public int compare(NID.WithName node1, NID.WithName node2) {
        int diffSize = nodeToNeighbors_.get(node2).size() - nodeToNeighbors_.get(node1).size();
        return (diffSize != 0) ? diffSize : node1.getName().compareTo(node2.getName());
      }
    });
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // PRIVATE CLASSES
  //
  ////////////////////////////////////////////////////////////////////////////
  
  /***************************************************************************
   **
   ** Hash
   */
  
  private static class GroupID {
    
    private final String key_;
    
    public GroupID(String key) {
      this.key_ = key;
    }
    
    public String getKey() {
      return (key_);
    }
    
    @Override
    public String toString() {
      return (key_);
    }
    
    @Override
    public boolean equals(Object o) {
      if (this == o) return (true);
      if (! (o instanceof GroupID)) return (false);
      
      GroupID groupID = (GroupID) o;
      
      if (key_ != null ? (! key_.equals(groupID.key_)) : (groupID.key_ != null)) return (false);
      return (true);
    }
    
    @Override
    public int hashCode() {
      return (key_ != null ? key_.hashCode() : 0);
    }
    
  }
}