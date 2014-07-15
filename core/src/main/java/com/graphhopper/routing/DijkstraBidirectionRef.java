/*
 *  Licensed to GraphHopper and Peter Karich under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except in 
 *  compliance with the License. You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing;

import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;

import java.util.PriorityQueue;

import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.Weighting;
import com.graphhopper.storage.EdgeEntry;
import com.graphhopper.storage.Graph;
import com.graphhopper.util.EdgeExplorer;
import com.graphhopper.util.EdgeIterator;

/**
 * Calculates best path in bidirectional way.
 * <p/>
 * 'Ref' stands for reference implementation and is using the normal Java-'reference'-way.
 * <p/>
 * @see DijkstraBidirection for an array based but more complicated version
 * @author Peter Karich
 */
public class DijkstraBidirectionRef extends AbstractBidirAlgo
{
    private PriorityQueue<EdgeEntry> openSetFrom;
    private PriorityQueue<EdgeEntry> openSetTo;
    private TIntObjectMap<EdgeEntry> bestWeightMapFrom;
    private TIntObjectMap<EdgeEntry> bestWeightMapTo;
    protected TIntObjectMap<EdgeEntry> bestWeightMapOther;
    protected EdgeEntry currFrom;
    protected EdgeEntry currTo;
    protected PathBidirRef bestPath;
    private boolean updateBestPath = true;

    public DijkstraBidirectionRef( Graph graph, FlagEncoder encoder, Weighting weighting )
    {
        super(graph, encoder, weighting);
        initCollections(1000);
    }

    protected void initCollections( int nodes )
    {
        openSetFrom = new PriorityQueue<EdgeEntry>(nodes / 10);
        bestWeightMapFrom = new TIntObjectHashMap<EdgeEntry>(nodes / 10);

        openSetTo = new PriorityQueue<EdgeEntry>(nodes / 10);
        bestWeightMapTo = new TIntObjectHashMap<EdgeEntry>(nodes / 10);
    }

    @Override
    public void initFrom( int from, double dist )
    {
        currFrom = createEdgeEntry(from, dist);
        if (isTraversalNodeBased())
        {
            bestWeightMapFrom.put(from, currFrom);
        }
        openSetFrom.add(currFrom);
        if (currTo != null)
        {
            bestWeightMapOther = bestWeightMapTo;
            updateBestPath(currTo, from);
        }
    }

    @Override
    public void initTo( int to, double dist )
    {
        currTo = createEdgeEntry(to, dist);
        if (isTraversalNodeBased())
        {
            bestWeightMapTo.put(to, currTo);
        }
        openSetTo.add(currTo);
        if (currFrom != null)
        {
            bestWeightMapOther = bestWeightMapFrom;
            updateBestPath(currFrom, to);
        }
    }

    @Override
    protected Path createAndInitPath()
    {
        bestPath = new PathBidirRef(graph, flagEncoder);
        return bestPath;
    }

    @Override
    protected Path extractPath()
    {
        return bestPath.extract();
    }

    @Override
    void checkState( int fromBase, int fromAdj, int toBase, int toAdj )
    {
        if (bestWeightMapFrom.isEmpty() || bestWeightMapTo.isEmpty())
            throw new IllegalStateException("Either 'from'-edge or 'to'-edge is inaccessible. From:" + bestWeightMapFrom + ", to:" + bestWeightMapTo);
    }

    @Override
    public boolean fillEdgesFrom()
    {
        if (openSetFrom.isEmpty())
            return false;

        currFrom = openSetFrom.poll();
        bestWeightMapOther = bestWeightMapTo;
        fillEdges(currFrom, openSetFrom, bestWeightMapFrom, outEdgeExplorer, false);
        visitedCountFrom++;
        return true;
    }

    @Override
    public boolean fillEdgesTo()
    {
        if (openSetTo.isEmpty())
            return false;
        currTo = openSetTo.poll();
        bestWeightMapOther = bestWeightMapFrom;
        fillEdges(currTo, openSetTo, bestWeightMapTo, inEdgeExplorer, true);
        visitedCountTo++;
        return true;
    }

    // http://www.cs.princeton.edu/courses/archive/spr06/cos423/Handouts/EPP%20shortest%20path%20algorithms.pdf
    // a node from overlap may not be on the best path!
    // => when scanning an arc (v, w) in the forward search and w is scanned in the reverseOrder 
    //    search, update extractPath = μ if df (v) + (v, w) + dr (w) < μ            
    @Override
    public boolean finished()
    {
        if (finishedFrom || finishedTo)
            return true;

        return currFrom.weight + currTo.weight >= bestPath.getWeight();
    }

    void fillEdges( EdgeEntry currEdge, PriorityQueue<EdgeEntry> prioQueue,
            TIntObjectMap<EdgeEntry> shortestWeightMap, EdgeExplorer explorer, boolean reverse )
    {
        int currNode = currEdge.adjNode;
        EdgeIterator iter = explorer.setBaseNode(currNode);
        while (iter.next())
        {
            if (!accept(iter, currEdge.edge))
                continue;            

            int iterationKey = createIdentifier(iter, reverse);
            double tmpWeight = weighting.calcWeight(iter, reverse, currEdge.edge) + currEdge.weight;

            EdgeEntry de = shortestWeightMap.get(iterationKey);
            if (de == null)
            {
                de = new EdgeEntry(iter.getEdge(), iter.getAdjNode(), tmpWeight);
                de.parent = currEdge;
                shortestWeightMap.put(iterationKey, de);
                prioQueue.add(de);
            } else if (de.weight > tmpWeight)
            {
                prioQueue.remove(de);
                de.edge = iter.getEdge();
                de.weight = tmpWeight;
                de.parent = currEdge;
                prioQueue.add(de);
            }

            if (updateBestPath)
                updateBestPath(de, iterationKey);
        }
    }

    @Override
    protected void updateBestPath( EdgeEntry shortestEE, int iterationKey )
    {
        EdgeEntry entryOther = bestWeightMapOther.get(iterationKey);
        if (entryOther == null)
            return;

        boolean reverse = bestWeightMapFrom == bestWeightMapOther;
        if (isTraversalEdgeBased())
        {
            // prevents the path to contain the edge at the meeting point twice in edge-based traversal
            if (entryOther.edge == shortestEE.edge)
            {
                if (reverse)
                {
                    entryOther = entryOther.parent;
                } else
                {
                    shortestEE = shortestEE.parent;
                }
            }
        }

        // update μ
        double newWeight = shortestEE.weight + entryOther.weight;

        // TODO why necessary?
//        if (weighting instanceof TurnWeighting)
//        {
//            newWeight *= weighting.calcWeight(shortestEE.edge, 
//                    (reverse) ? entryOther.adjNode : shortestEE.adjNode, entryOther.edge, reverse);
//        }
        if (newWeight < bestPath.getWeight())
        {
            bestPath.setSwitchToFrom(reverse);
            bestPath.setEdgeEntry(shortestEE);
            bestPath.setWeight(newWeight);
            bestPath.setEdgeEntryTo(entryOther);
        }
    }

    @Override
    public String getName()
    {
        return "dijkstrabi";
    }

    TIntObjectMap<EdgeEntry> getBestFromMap()
    {
        return bestWeightMapFrom;
    }

    TIntObjectMap<EdgeEntry> getBestToMap()
    {
        return bestWeightMapTo;
    }

    void setBestOtherMap( TIntObjectMap<EdgeEntry> other )
    {
        bestWeightMapOther = other;
    }

    void setFromDataStructures( DijkstraBidirectionRef dijkstra )
    {
        openSetFrom = dijkstra.openSetFrom;
        bestWeightMapFrom = dijkstra.bestWeightMapFrom;
        finishedFrom = dijkstra.finishedFrom;
        currFrom = dijkstra.currFrom;
        visitedCountFrom = dijkstra.visitedCountFrom;
        // outEdgeExplorer
    }

    void setToDataStructures( DijkstraBidirectionRef dijkstra )
    {
        openSetTo = dijkstra.openSetTo;
        bestWeightMapTo = dijkstra.bestWeightMapTo;
        finishedTo = dijkstra.finishedTo;
        currTo = dijkstra.currTo;
        visitedCountTo = dijkstra.visitedCountTo;
        // inEdgeExplorer
    }

    void setUpdateBestPath( boolean b )
    {
        updateBestPath = b;
    }

    void setBestPath( PathBidirRef bestPath )
    {
        this.bestPath = bestPath;
    }

    @Override
    boolean isTraversalModeSupported( TRAVERSAL_MODE aTraversalMode )
    {
        return aTraversalMode == TRAVERSAL_MODE.NODE_BASED || // 
                aTraversalMode == TRAVERSAL_MODE.EDGE_BASED_DIRECTION_SENSITIVE;
    }
}
