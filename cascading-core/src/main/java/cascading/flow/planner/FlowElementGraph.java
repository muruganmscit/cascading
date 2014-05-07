/*
 * Copyright (c) 2007-2014 Concurrent, Inc. All Rights Reserved.
 *
 * Project and contact information: http://www.cascading.org/
 *
 * This file is part of the Cascading project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cascading.flow.planner;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cascading.flow.FlowElement;
import cascading.flow.planner.graph.AnnotatedElementSet;
import cascading.flow.planner.graph.AnnotatedGraph;
import cascading.flow.planner.graph.ElementDirectedGraph;
import cascading.flow.planner.graph.ElementGraph;
import cascading.pipe.Checkpoint;
import cascading.pipe.Pipe;
import cascading.pipe.Splice;
import cascading.pipe.SubAssembly;
import cascading.tap.Tap;
import cascading.util.Util;
import org.jgrapht.Graphs;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.traverse.DepthFirstIterator;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Class ElementGraph represents the executable FlowElement graph. */
public class FlowElementGraph extends ElementDirectedGraph implements AnnotatedGraph
  {
  /** Field LOG */
  private static final Logger LOG = LoggerFactory.getLogger( FlowElementGraph.class );

  /** Field resolved */
  private boolean resolved;

  private PlatformInfo platformInfo;
  /** Field sources */
  private Map<String, Tap> sources;
  /** Field sinks */
  private Map<String, Tap> sinks;
  /** Field traps */
  private Map<String, Tap> traps;
  /** Field checkpoints */
  private Map<String, Tap> checkpoints;
  /** Field requireUniqueCheckpoints */
  private boolean requireUniqueCheckpoints;

  // used for creating isolated test graphs
  protected FlowElementGraph()
    {
    }

  public FlowElementGraph( FlowElementGraph flowElementGraph )
    {
    this();
    this.platformInfo = flowElementGraph.platformInfo;
    this.sources = flowElementGraph.sources;
    this.sinks = flowElementGraph.sinks;
    this.traps = flowElementGraph.traps;
    this.checkpoints = flowElementGraph.checkpoints;
    this.requireUniqueCheckpoints = flowElementGraph.requireUniqueCheckpoints;

    if( flowElementGraph.annotations != null )
      this.annotations = new AnnotatedElementSet( flowElementGraph.annotations );

    Graphs.addAllVertices( this, flowElementGraph.vertexSet() );
    Graphs.addAllEdges( this, flowElementGraph, flowElementGraph.edgeSet() );
    }

  public FlowElementGraph( Pipe[] pipes, Map<String, Tap> sources, Map<String, Tap> sinks )
    {
    this( null, pipes, sources, sinks, Collections.<String, Tap>emptyMap(), Collections.<String, Tap>emptyMap(), false );
    }

  /**
   * Constructor ElementGraph creates a new ElementGraph instance.
   *
   * @param pipes   of type Pipe[]
   * @param sources of type Map<String, Tap>
   * @param sinks   of type Map<String, Tap>
   */
  public FlowElementGraph( PlatformInfo platformInfo, Pipe[] pipes, Map<String, Tap> sources, Map<String, Tap> sinks, Map<String, Tap> traps, Map<String, Tap> checkpoints, boolean requireUniqueCheckpoints )
    {
    this();
    this.platformInfo = platformInfo;
    this.sources = sources;
    this.sinks = sinks;
    this.traps = traps;
    this.checkpoints = checkpoints;
    this.requireUniqueCheckpoints = requireUniqueCheckpoints;

    assembleGraph( pipes, sources, sinks );

    verifyGraph();
    }

  public Map<String, Tap> getSourceMap()
    {
    return sources;
    }

  public Map<String, Tap> getSinkMap()
    {
    return sinks;
    }

  public Map<String, Tap> getTrapMap()
    {
    return traps;
    }

  public Map<String, Tap> getCheckpointsMap()
    {
    return checkpoints;
    }

  public Collection<Tap> getSources()
    {
    return sources.values();
    }

  public Collection<Tap> getSinks()
    {
    return sinks.values();
    }

  public Collection<Tap> getTraps()
    {
    return traps.values();
    }

  protected void initialize( Map<String, Tap> sources, Map<String, Tap> sinks, Pipe... tails )
    {
    this.sources = sources;
    this.sinks = sinks;
    this.traps = Util.createHashMap();

    assembleGraph( tails, sources, sinks );

    verifyGraph();
    }

  private void assembleGraph( Pipe[] pipes, Map<String, Tap> sources, Map<String, Tap> sinks )
    {
    HashMap<String, Tap> sourcesCopy = new HashMap<String, Tap>( sources );
    HashMap<String, Tap> sinksCopy = new HashMap<String, Tap>( sinks );

    for( Pipe pipe : pipes )
      makeGraph( pipe, sourcesCopy, sinksCopy );

    addExtents( sources, sinks );
    }

  private void verifyGraph()
    {
    if( vertexSet().isEmpty() )
      return;

    Set<String> checkpointNames = new HashSet<String>();

    // need to verify that only Extent instances are origins in this graph. Otherwise a Tap was not properly connected
    TopologicalOrderIterator<FlowElement, Scope> iterator = getTopologicalIterator();

    FlowElement flowElement = null;

    while( iterator.hasNext() )
      {
      try
        {
        flowElement = iterator.next();
        }
      catch( IllegalArgumentException exception )
        {
        if( flowElement == null )
          throw new ElementGraphException( "unable to traverse to the first element" );

        throw new ElementGraphException( flowElement, "unable to traverse to the next element after " + flowElement );
        }

      if( requireUniqueCheckpoints && flowElement instanceof Checkpoint )
        {
        String name = ( (Checkpoint) flowElement ).getName();

        if( checkpointNames.contains( name ) )
          throw new ElementGraphException( (Pipe) flowElement, "may not have duplicate checkpoint names in assembly, found: " + name );

        checkpointNames.add( name );
        }

      if( incomingEdgesOf( flowElement ).size() != 0 && outgoingEdgesOf( flowElement ).size() != 0 )
        continue;

      if( flowElement instanceof Extent )
        continue;

      if( flowElement instanceof Pipe )
        {
        if( incomingEdgesOf( flowElement ).size() == 0 )
          throw new ElementGraphException( (Pipe) flowElement, "no Tap connected to head Pipe: " + flowElement + ", possible ambiguous branching, try explicitly naming tails" );
        else
          throw new ElementGraphException( (Pipe) flowElement, "no Tap connected to tail Pipe: " + flowElement + ", possible ambiguous branching, try explicitly naming tails" );
        }

      if( flowElement instanceof Tap )
        throw new ElementGraphException( (Tap) flowElement, "no Pipe connected to Tap: " + flowElement );
      else
        throw new ElementGraphException( flowElement, "unknown element type: " + flowElement );
      }
    }

  /**
   * Method copyGraph returns a partial copy of the current ElementGraph. Only Vertices and Edges are copied.
   *
   * @return ElementGraph
   */
  public FlowElementGraph copyElementGraph()
    {
    FlowElementGraph copy = new FlowElementGraph();
    Graphs.addGraph( copy, this );

    copy.traps = new HashMap<String, Tap>( this.traps );

    return copy;
    }

  /**
   * created to support the ability to generate all paths between the head and tail of the process.
   *
   * @param sources
   * @param sinks
   */
  private void addExtents( Map<String, Tap> sources, Map<String, Tap> sinks )
    {
    addVertex( Extent.head );

    for( String source : sources.keySet() )
      {
      Scope scope = addEdge( Extent.head, sources.get( source ) );

      // edge may already exist, if so, above returns null
      if( scope != null )
        scope.setName( source );
      }

    addVertex( Extent.tail );

    for( String sink : sinks.keySet() )
      {
      Scope scope;

      try
        {
        scope = addEdge( sinks.get( sink ), Extent.tail );
        }
      catch( IllegalArgumentException exception )
        {
        throw new ElementGraphException( "missing pipe for sink tap: [" + sink + "]" );
        }

      if( scope == null )
        throw new ElementGraphException( "cannot sink to the same path from multiple branches: [" + Util.join( sinks.values() ) + "]" );

      scope.setName( sink );
      }
    }

  /**
   * Performs one rule check, verifies group does not join duplicate tap resources.
   * <p/>
   * Scopes are always named after the source side of the source -> target relationship
   */
  private void makeGraph( Pipe current, Map<String, Tap> sources, Map<String, Tap> sinks )
    {
    LOG.debug( "adding pipe: {}", current );

    if( current instanceof SubAssembly )
      {
      for( Pipe pipe : SubAssembly.unwind( current.getPrevious() ) )
        makeGraph( pipe, sources, sinks );

      return;
      }

    if( containsVertex( current ) )
      return;

    addVertex( current );

    Tap sink = sinks.remove( current.getName() );

    if( sink != null )
      {
      LOG.debug( "adding sink: {}", sink );

      addVertex( sink );

      LOG.debug( "adding edge: {} -> {}", current, sink );

      addEdge( current, sink ).setName( current.getName() );
      }

    // PipeAssemblies should always have a previous
    if( SubAssembly.unwind( current.getPrevious() ).length == 0 )
      {
      Tap source = sources.remove( current.getName() );

      if( source != null )
        {
        LOG.debug( "adding source: {}", source );

        addVertex( source );

        LOG.debug( "adding edge: {} -> {}", source, current );

        Scope scope = addEdge( source, current );

        scope.setName( current.getName() );

        setOrdinal( current, scope );
        }
      }

    for( Pipe previous : SubAssembly.unwind( current.getPrevious() ) )
      {
      makeGraph( previous, sources, sinks );

      LOG.debug( "adding edge: {} -> ", previous, current );

      if( getEdge( previous, current ) != null )
        throw new ElementGraphException( previous, "cannot distinguish pipe branches, give pipe unique name: " + previous );

      Scope scope = addEdge( previous, current );

      scope.setName( previous.getName() ); // name scope after previous pipe

      setOrdinal( current, scope );
      }
    }

  private void setOrdinal( Pipe current, Scope scope )
    {
    if( current instanceof Splice )
      {
      Integer ordinal = ( (Splice) current ).getPipePos().get( scope.getName() );
      scope.setOrdinal( ordinal );

      if( ( (Splice) current ).isJoin() && ordinal != 0 )
        scope.setNonBlocking( false );
      }
    }

  /**
   * Method getTopologicalIterator returns the topologicalIterator of this ElementGraph object.
   *
   * @return the topologicalIterator (type TopologicalOrderIterator<FlowElement, Scope>) of this ElementGraph object.
   */
  public TopologicalOrderIterator<FlowElement, Scope> getTopologicalIterator()
    {
    return new TopologicalOrderIterator<>( this );
    }

  /**
   * Method getDepthFirstIterator returns the depthFirstIterator of this ElementGraph object.
   *
   * @return the depthFirstIterator (type DepthFirstIterator<FlowElement, Scope>) of this ElementGraph object.
   */
  public DepthFirstIterator<FlowElement, Scope> getDepthFirstIterator()
    {
    return new DepthFirstIterator<>( this, Extent.head );
    }

  private SimpleDirectedGraph<FlowElement, Scope> copyWithTraps()
    {
    FlowElementGraph copy = this.copyElementGraph();

    copy.addTrapsToGraph();

    return copy;
    }

  private void addTrapsToGraph()
    {
    DepthFirstIterator<FlowElement, Scope> iterator = getDepthFirstIterator();

    while( iterator.hasNext() )
      {
      FlowElement element = iterator.next();

      if( !( element instanceof Pipe ) )
        continue;

      Pipe pipe = (Pipe) element;
      Tap trap = traps.get( pipe.getName() );

      if( trap == null )
        continue;

      addVertex( trap );

      if( LOG.isDebugEnabled() )
        LOG.debug( "adding trap edge: " + pipe + " -> " + trap );

      if( getEdge( pipe, trap ) != null )
        continue;

      addEdge( pipe, trap ).setName( pipe.getName() ); // name scope after previous pipe
      }
    }

  /**
   * Method writeDOT writes this element graph to a DOT file for easy visualization and debugging.
   *
   * @param filename of type String
   */
  @Override
  public void writeDOT( String filename )
    {
    ElementGraphs.printElementGraph( filename, this.copyWithTraps(), platformInfo );
    }

  /** Method resolveFields performs a breadth first traversal and resolves the tuple fields between each Pipe instance. */
  public void resolveFields()
    {
    if( resolved )
      throw new IllegalStateException( "element graph already resolved" );

    TopologicalOrderIterator<FlowElement, Scope> iterator = getTopologicalIterator();

    while( iterator.hasNext() )
      resolveFields( iterator.next() );

    resolved = true;
    }

  private void resolveFields( FlowElement source )
    {
    if( source instanceof Extent )
      return;

    Set<Scope> incomingScopes = incomingEdgesOf( source );
    Set<Scope> outgoingScopes = outgoingEdgesOf( source );

    List<FlowElement> flowElements = Graphs.successorListOf( this, source );

    if( flowElements.size() == 0 )
      throw new IllegalStateException( "unable to find next elements in pipeline from: " + source.toString() );

    Scope outgoingScope = source.outgoingScopeFor( incomingScopes );

    if( LOG.isDebugEnabled() && outgoingScope != null )
      {
      LOG.debug( "for modifier: " + source );
      if( outgoingScope.getArgumentsSelector() != null )
        LOG.debug( "setting outgoing arguments: " + outgoingScope.getArgumentsSelector() );
      if( outgoingScope.getOperationDeclaredFields() != null )
        LOG.debug( "setting outgoing declared: " + outgoingScope.getOperationDeclaredFields() );
      if( outgoingScope.getKeySelectors() != null )
        LOG.debug( "setting outgoing group: " + outgoingScope.getKeySelectors() );
      if( outgoingScope.getOutValuesSelector() != null )
        LOG.debug( "setting outgoing values: " + outgoingScope.getOutValuesSelector() );
      }

    for( Scope scope : outgoingScopes )
      scope.copyFields( outgoingScope );
    }

  @Override
  public ElementGraph copyGraph()
    {
    return new FlowElementGraph( this );
    }
  }
