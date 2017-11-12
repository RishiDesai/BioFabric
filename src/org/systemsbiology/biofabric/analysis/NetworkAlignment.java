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

package org.systemsbiology.biofabric.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.systemsbiology.biofabric.model.FabricLink;

import org.systemsbiology.biofabric.util.AsynchExitRequestException;
import org.systemsbiology.biofabric.util.BTProgressMonitor;
import org.systemsbiology.biofabric.util.LoopReporter;
import org.systemsbiology.biofabric.util.NID;
import org.systemsbiology.biofabric.util.UiUtil;
import org.systemsbiology.biofabric.util.UniqueLabeller;

/****************************************************************************
 **
 ** This merges two individual graphs and an alignment to form the
 ** network alignment
 */

public class NetworkAlignment {
  
  public static final String                // Ordered as in the default link group order
          COVERED_EDGE = "G12",             // Covered Edges
          GRAPH1 = "G1A",                   // G1 Edges w/ two aligned nodes (all non-covered G1 Edges)
          INDUCED_GRAPH2 = "G2A",           // G2 Edges w/ two aligned nodes (induced)
          HALF_UNALIGNED_GRAPH2 = "G2B",    // G2 Edges w/ one aligned node and one unaligned node
          FULL_UNALIGNED_GRAPH2 = "G2C";    // G2 Edges w/ two unaligned nodes
  
  public enum LinkGroup {
    
    COVERED_EDGE("G12"),
    GRAPH1("G1A"),
    INDUCED_GRAPH2("G2A"),
    HALF_UNALIGNED_GRAPH2("G2B"),
    FULL_UNALIGNED_GRAPH2("G2C");
    
    public final String rel;
    
    LinkGroup(String rel) {
      this.rel = rel;
    }
    
  }
  
  private final String TEMPORARY = "TEMP";
  
  ////////////////////////////////////////////////////////////////////////////
  //
  // PRIVATE INSTANCE MEMBERS
  //
  ////////////////////////////////////////////////////////////////////////////
  
  //
  // G1 is the small (#nodes) network, G2 is the large network
  //
  
  private Map<NID.WithName, NID.WithName> mapG1toG2_;
  private ArrayList<FabricLink> linksG1_;
  private HashSet<NID.WithName> lonersG1_;
  private ArrayList<FabricLink> linksG2_;
  private HashSet<NID.WithName> lonersG2_;
  private boolean forClique_;
  private UniqueLabeller idGen_;
  private BTProgressMonitor monitor_;
  
  //
  // largeToMergedID only contains aligned nodes
  //
  
  private Map<NID.WithName, NID.WithName> smallToMergedID_;
  private Map<NID.WithName, NID.WithName> largeToMergedID_;
  private Map<NID.WithName, NID.WithName> mergedIDToSmall_;
  
  private ArrayList<FabricLink> mergedLinks_;
  private Set<NID.WithName> mergedLoners_;
  
  private enum Graph {SMALL, LARGE}
  
  
  ////////////////////////////////////////////////////////////////////////////
  //
  // PUBLIC CONSTRUCTORS
  //
  ////////////////////////////////////////////////////////////////////////////
  
  public NetworkAlignment(ArrayList<FabricLink> mergedLinks, Set<NID.WithName> mergedLoneNodeIDs,
                          Map<NID.WithName, NID.WithName> mapG1toG2,
                          ArrayList<FabricLink> linksG1, HashSet<NID.WithName> lonersG1,
                          ArrayList<FabricLink> linksG2, HashSet<NID.WithName> lonersG2,
                          boolean forCliques, UniqueLabeller idGen, BTProgressMonitor monitor) {
    
    this.mapG1toG2_ = mapG1toG2;
    this.linksG1_ = linksG1;
    this.lonersG1_ = lonersG1;
    this.linksG2_ = linksG2;
    this.lonersG2_ = lonersG2;
    this.forClique_ = forCliques;
    this.idGen_ = idGen;
    this.monitor_ = monitor;
    
    this.mergedLinks_ = mergedLinks;
    this.mergedLoners_ = mergedLoneNodeIDs;
  }
  
  /****************************************************************************
   **
   ** Merge the Network!
   */
  
  public void mergeNetworks() throws AsynchExitRequestException {
    
    //
    // Create merged nodes
    //
    
    createMergedNodes();
    
    //
    // Create individual link sets; "old" refers to pre-merged networks, "new" is merged network
    //
    
    List<FabricLink> newLinksG1 = new ArrayList<FabricLink>();
    Set<NID.WithName> newLonersG1 = new HashSet<NID.WithName>();
    
    createNewLinkList(newLinksG1, newLonersG1, Graph.SMALL);
    
    List<FabricLink> newLinksG2 = new ArrayList<FabricLink>();
    Set<NID.WithName> newLonersG2 = new HashSet<NID.WithName>();
    
    createNewLinkList(newLinksG2, newLonersG2, Graph.LARGE);
    
    //
    // Give each link its respective link relation
    //
    
    createMergedLinkList(newLinksG1, newLinksG2);
    
    finalizeLoneNodeIDs(newLonersG1, newLonersG2);
    
    if (forClique_) {
      (new CliqueMisalignment()).process(mergedLinks_, mergedLoners_, mergedIDToSmall_);
    }
    UiUtil.fixMePrintout("Should Clique Misalignment remain a postprocessing step");
    
    //
    // Output calculated scores to console
    //
    
    new NetworkAlignmentScorer(this).printScores();
    UiUtil.fixMePrintout("Need to add net-align scores to UI");
    
    return;
  }
  
  ////////////////////////////////////////////////////////////////////////////
  //
  // PRIVATE METHODS
  //
  ////////////////////////////////////////////////////////////////////////////
  
  /****************************************************************************
   **
   ** Create merged nodes, install into maps
   */
  
  private void createMergedNodes() {
    
    smallToMergedID_ = new TreeMap<NID.WithName, NID.WithName>();
    largeToMergedID_ = new TreeMap<NID.WithName, NID.WithName>();
    mergedIDToSmall_ = new TreeMap<NID.WithName, NID.WithName>();
    
    for (Map.Entry<NID.WithName, NID.WithName> entry : mapG1toG2_.entrySet()) {
      
      NID.WithName smallNode = entry.getKey(), largeNode = entry.getValue();
      String smallName = smallNode.getName(), largeName = largeNode.getName();
      
      //
      // Aligned nodes merge name in the form large-small
      //
      
      String mergedName = String.format("%s-%s", largeName, smallName);
      
      NID nid = idGen_.getNextOID();
      NID.WithName merged_node = new NID.WithName(nid, mergedName);
      
      smallToMergedID_.put(smallNode, merged_node);
      largeToMergedID_.put(largeNode, merged_node);
      mergedIDToSmall_.put(merged_node, smallNode);
    }
    return;
  }
  
  /****************************************************************************
   **
   ** Create new link lists based on merged nodes for both networks
   */
  
  private void createNewLinkList(List<FabricLink> newLinks, Set<NID.WithName> newLoners, Graph graph)
          throws AsynchExitRequestException {
    
    List<FabricLink> oldLinks;
    Set<NID.WithName> oldLoners;
    Map<NID.WithName, NID.WithName> oldToNewID;
    
    switch (graph) {
      case SMALL:
        oldLinks = linksG1_;
        oldLoners = lonersG1_;
        oldToNewID = smallToMergedID_;
        break;
      case LARGE:
        oldLinks = linksG2_;
        oldLoners = lonersG2_;
        oldToNewID = largeToMergedID_;
        break;
      default:
        throw new IllegalArgumentException();
    }
    
    LoopReporter lr = new LoopReporter(oldLinks.size(), 20, monitor_, 0.0, 1.0, "progress.mergingLinks");
    
    for (FabricLink oldLink : oldLinks) {
      
      NID.WithName oldA = oldLink.getSrcID();
      NID.WithName oldB = oldLink.getTrgID();
      
      //
      // Not all nodes are mapped in the large graph
      //
      
      NID.WithName newA = (oldToNewID.containsKey(oldA)) ? oldToNewID.get(oldA) : oldA;
      NID.WithName newB = (oldToNewID.containsKey(oldB)) ? oldToNewID.get(oldB) : oldB;
      
      FabricLink newLink = new FabricLink(newA, newB, TEMPORARY, false, false);
      // 'directed' must be false
      
      newLinks.add(newLink);
      lr.report();
    }
    
    lr.finish();
    
    for (NID.WithName oldLoner : oldLoners) {
      
      NID.WithName newLoner = (oldToNewID.containsKey(oldLoner)) ? oldToNewID.get(oldLoner) : oldLoner;
      
      newLoners.add(newLoner);
    }
    return;
  }
  
  /****************************************************************************
   **
   ** Combine the two link lists into one, with G2,CC,G1 tags accordingly
   */
  
  private void createMergedLinkList(List<FabricLink> newLinksG1, List<FabricLink> newLinksG2)
          throws AsynchExitRequestException {
    
    long totalSize = newLinksG2.size() + newLinksG2.size();
    LoopReporter lr = new LoopReporter(totalSize, 20, monitor_, 0.0, 1.0, "progress.separatingLinks");
    // SHOULD I HAVE TWO LOOP REPORTERS OR JUST ONE FOR BOTH LOOPS??
    NetAlignFabricLinkLocator comp = new NetAlignFabricLinkLocator();
    Collections.sort(newLinksG1, comp);
    // SORTING TAKES A LONG TIME . . .(THIS IS W/O THE LOOP REPORTER
    
    for (FabricLink linkG2 : newLinksG2) {
      
      int index = Collections.binarySearch(newLinksG1, linkG2, comp);
      
      NID.WithName src = linkG2.getSrcID(), trg = linkG2.getTrgID();
      
      if (index >= 0) {
        addMergedLink(src, trg, COVERED_EDGE);
      } else {
        // contains all alinged nodes; contains() works in O(log(n))
        SortedSet<NID.WithName> alignedNodesG2 = new TreeSet<NID.WithName>(largeToMergedID_.values());
        
        if (alignedNodesG2.contains(src) && alignedNodesG2.contains(trg)) {
          addMergedLink(src, trg, INDUCED_GRAPH2);
        } else if (alignedNodesG2.contains(src) || alignedNodesG2.contains(trg)) {
          addMergedLink(src, trg, HALF_UNALIGNED_GRAPH2);
        } else {
          addMergedLink(src, trg, FULL_UNALIGNED_GRAPH2);
        }
      }
      lr.report();
    }
    
    Collections.sort(newLinksG2, comp);
    
    for (FabricLink linkG1 : newLinksG1) {
      
      int index = Collections.binarySearch(newLinksG2, linkG1, comp);
      
      if (index < 0) {
        addMergedLink(linkG1.getSrcID(), linkG1.getTrgID(), GRAPH1);
      }
      lr.report();
    }
    lr.finish();
    return; // This method is not ideal. . .
  }
  
  /****************************************************************************
   **
   ** Add both non-shadow and shadow links to merged link-list
   */
  
  private void addMergedLink(NID.WithName src, NID.WithName trg, String tag) {
    FabricLink newMergedLink = new FabricLink(src, trg, tag, false, null);
    FabricLink newMergedLinkShadow = new FabricLink(src, trg, tag, true, null);
    
    mergedLinks_.add(newMergedLink);
    mergedLinks_.add(newMergedLinkShadow);
  }
  
  /****************************************************************************
   **
   ** Combine loneNodeIDs lists into one
   */
  
  private void finalizeLoneNodeIDs(Set<NID.WithName> newLonersG1, Set<NID.WithName> newLonersG2) {
    mergedLoners_.addAll(newLonersG1);
    mergedLoners_.addAll(newLonersG2);
    return;
  }
  
  ////////////////////////////////////////////////////////////////////////////
  //
  // INNER CLASSES
  //
  ////////////////////////////////////////////////////////////////////////////
  
  /****************************************************************************
   **
   ** All unaligned edges plus all of their endpoint nodes' edges
   */
  
  private static class CliqueMisalignment {
    
    private CliqueMisalignment() {
    }
    
    private void process(List<FabricLink> mergedLinks, Set<NID.WithName> mergedLoneNodeIDs,
                         Map<NID.WithName, NID.WithName> mergedIDToSmall)
            throws AsynchExitRequestException {
      
      List<FabricLink> nonShdwMergedLinks = new ArrayList<FabricLink>();
      for (FabricLink link : mergedLinks) {
        if (! link.isShadow()) {
          nonShdwMergedLinks.add(link);
        }
      }
      
      Set<NID.WithName> unalignedNodesG1 = new TreeSet<NID.WithName>();
      for (FabricLink link : nonShdwMergedLinks) { // find the nodes of interest
        if (link.getRelation().equals(GRAPH1)) {
          unalignedNodesG1.add(link.getSrcID());
          unalignedNodesG1.add(link.getTrgID());
        }
      }
      
      List<FabricLink> unalignedEdgesG1 = new ArrayList<FabricLink>();
      for (FabricLink link : nonShdwMergedLinks) { // add the edges connecting to the nodes of interest (one hop away)
        
        NID.WithName src = link.getSrcID(), trg = link.getTrgID();
        
        if (unalignedNodesG1.contains(src) || unalignedNodesG1.contains(trg)) {
          unalignedEdgesG1.add(link);
        }
      }
      
      //
      // Go back to old G1 names
      //
      
      List<FabricLink> oldUnalignedEdgesG1 = new ArrayList<FabricLink>();
      for (FabricLink link : unalignedEdgesG1) {
        
        NID.WithName srcNew = link.getSrcID(), trgNew = link.getTrgID();
        NID.WithName srcOld = mergedIDToSmall.get(srcNew), trgOld = mergedIDToSmall.get(trgNew);
        
        FabricLink linkOldName = new FabricLink(srcOld, trgOld, GRAPH1, false, null);
        FabricLink linkOldNameShdw = new FabricLink(srcOld, trgOld, GRAPH1, true, null);
        
        oldUnalignedEdgesG1.add(linkOldName);
        oldUnalignedEdgesG1.add(linkOldNameShdw);
      }
  
      //
      // Change the final link-lists
      //
  
      mergedLinks.clear();
      mergedLoneNodeIDs.clear();
      mergedLinks.addAll(oldUnalignedEdgesG1);
      
      //  GO BACK TO OLD NAMES and ADD SHADOWS BACK
      UiUtil.fixMePrintout("FIX ME:Need to add back the nodes' old names and re-add shadow links");
      return;
    }
    
  }
  
  /****************************************************************************
   **
   ** Calculates (mainly) topological scores of network alignments
   ** such as Edge Coverage (EC), Symmetric Substructure score (S3),
   ** Induced Conserved Substructure (ICS);
   **
   ** Node Correctness (NC) is only for network aligned to itself (like yeasts)
   ** or if we know the correct alignment (we use the assumption that node
   ** names are equal)
   */
  
  private static class NetworkAlignmentScorer {
    
    private Set<FabricLink> links_;
    private double EC, S3, ICS, NC;
    
    public NetworkAlignmentScorer(NetworkAlignment netAlign) {
      this.links_ = new HashSet<FabricLink>(netAlign.mergedLinks_);
      removeDuplicateAndShadow();
      calcTopologicalScores();
      calcOtherScores();
      return;
    }
    
    private void calcTopologicalScores() {
      int numCoveredEdge = 0, numGraph1 = 0, numInducedGraph2 = 0;
      
      for (FabricLink link : links_) {
        if (link.getRelation().equals(COVERED_EDGE)) {
          numCoveredEdge++;
        } else if (link.getRelation().equals(GRAPH1)) {
          numGraph1++;
        } else if (link.getRelation().equals(INDUCED_GRAPH2)) {
          numInducedGraph2++;
        }
      }
      
      if (numCoveredEdge == 0) {
        return;
      }
      
      try {
        EC = ((double) numCoveredEdge) / (numCoveredEdge + numGraph1);
        S3 = ((double) numCoveredEdge) / (numCoveredEdge + numGraph1 + numInducedGraph2);
        ICS = ((double) numCoveredEdge) / (numCoveredEdge + numInducedGraph2); // this is correct right?
      } catch (ArithmeticException ae) {
        EC = -1;
        S3 = -1;
        ICS = -1; // add better error catching
        UiUtil.fixMePrintout("Needs better Net-Align score calculator");
      }
      return;
    }
    
    private void calcOtherScores() {
      
      //
      // Lone Node IDs don't matter here; We are under the assumption
      // that the network is aligned to itself here and the correct
      // node alignment is that the names are equal. This is only
      // practical for the yeast networks.
      //
      
      Set<NID.WithName> nodes = new HashSet<NID.WithName>();
      // I should use BioFabricNetwork.extractNodes but I will add Asynch error later
      for (FabricLink link : links_) {
        nodes.add(link.getSrcID());
        nodes.add(link.getTrgID());
      }
      
      int numNodesCorrect = 0;
      for (NID.WithName node : nodes) {
        if (!node.getName().contains("-")) {
          NC = -1.0; // this isn't a network aligned to itself, so NC is not applicable
          return;
        } else {
          String[] tok = node.getName().split("-");
          if (tok[0].equals(tok[1])) { // assume node names must be equal
            numNodesCorrect++;
          }
        }
      }
      
      NC = ((double)numNodesCorrect) / (nodes.size());
      return;
    }
    
    void printScores() {
      String scores = String.format("SCORES\nEC:%4.4f\nS3:%4.4f\nICS:%4.4f\nNC:%4.4f", EC, S3, ICS, NC);
//      JOptionPane.showMessageDialog(null, scores);
      System.out.println(scores);
      return;
    }
    
    private void removeDuplicateAndShadow() {
      Set<FabricLink> nonShdwLinks = new HashSet<FabricLink>();
      for (FabricLink link : links_) {
        if (! link.isShadow()) { // remove shadow links
          nonShdwLinks.add(link);
        }
      }
      
      //
      // We have to remove synonymous links (a->b) same as (b->a), and keep one;
      // Sort the names and concat into string (the key), so they are the same key in the map.
      // This means (a->b) and (b->a) should make the same string key.
      // If the key already has a value, we got a duplicate link.
      //
      
      Map<String, FabricLink> map = new HashMap<String, FabricLink>();
      for (FabricLink link : nonShdwLinks) {
        
        String[] arr1 = {link.getSrcID().getName(), link.getTrgID().getName()};
        Arrays.sort(arr1);
        String concat = String.format("%s___%s", arr1[0], arr1[1]);
        
        if (map.get(concat) != null) {
          continue; // skip the duplicate
        } else {
          map.put(concat, link);
        }
      }
      
      links_.clear();
      for (Map.Entry<String, FabricLink> entry : map.entrySet()) {
        links_.add(entry.getValue());
      }
      
      return;
    }
    
  }
  
  /***************************************************************************
   **
   ** Used ONLY to order links for creating the merged link set in Network Alignments
   */
  
  private static class NetAlignFabricLinkLocator implements Comparator<FabricLink> {
    
    /***************************************************************************
     **
     ** For any different links in the two separate network link sets, this
     ** says which comes first
     */
    
    public int compare(FabricLink link1, FabricLink link2) {
      
      if (link1.synonymous(link2)) {
        return (0);
      }
      
      //
      // Must sort the node names because A-B must be equivalent to B-A
      //
      
      String[] arr1 = {link1.getSrcID().getName(), link1.getTrgID().getName()};
      Arrays.sort(arr1);
      
      String[] arr2 = {link2.getSrcID().getName(), link2.getTrgID().getName()};
      Arrays.sort(arr2);
      
      String concat1 = String.format("%s___%s", arr1[0], arr1[1]);
      String concat2 = String.format("%s___%s", arr2[0], arr2[1]);
      
      // THIS IS COMPLETELY TEMPORARY - RISHI DESAI 7/16/17
      
      return concat1.compareTo(concat2);
    }
  }
  
}
