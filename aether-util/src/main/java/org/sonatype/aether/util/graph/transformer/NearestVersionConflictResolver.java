package org.sonatype.aether.util.graph.transformer;

/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, 
 * and you may not use this file except in compliance with the Apache License Version 2.0. 
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the Apache License Version 2.0 is distributed on an 
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.sonatype.aether.RepositoryException;
import org.sonatype.aether.collection.DependencyGraphTransformationContext;
import org.sonatype.aether.collection.DependencyGraphTransformer;
import org.sonatype.aether.collection.UnsolvableVersionConflictException;
import org.sonatype.aether.graph.DependencyNode;
import org.sonatype.aether.version.Version;
import org.sonatype.aether.version.VersionConstraint;

/**
 * A dependency graph transformer that resolves version conflicts using the nearest-wins strategy. For a given set of
 * conflicting nodes, one node will be chosen as the winner and the other nodes are removed from the dependency graph.
 * This transformer will query the keys {@link TransformationContextKeys#CONFLICT_IDS} and
 * {@link TransformationContextKeys#SORTED_CONFLICT_IDS} for existing information about conflict ids. In absence of this
 * information, it will automatically invoke the {@link ConflictIdSorter} to calculate it.
 * 
 * @author Benjamin Bentmann
 */
public class NearestVersionConflictResolver
    implements DependencyGraphTransformer
{

    public DependencyNode transformGraph( DependencyNode node, DependencyGraphTransformationContext context )
        throws RepositoryException
    {
        List<?> sortedConflictIds = (List<?>) context.get( TransformationContextKeys.SORTED_CONFLICT_IDS );
        if ( sortedConflictIds == null )
        {
            ConflictIdSorter sorter = new ConflictIdSorter();
            sorter.transformGraph( node, context );

            sortedConflictIds = (List<?>) context.get( TransformationContextKeys.SORTED_CONFLICT_IDS );
        }

        Map<?, ?> conflictIds = (Map<?, ?>) context.get( TransformationContextKeys.CONFLICT_IDS );
        if ( conflictIds == null )
        {
            throw new RepositoryException( "conflict groups have not been identified" );
        }

        Map<DependencyNode, Integer> depths = new IdentityHashMap<DependencyNode, Integer>( conflictIds.size() );
        for ( Object key : sortedConflictIds )
        {
            ConflictGroup group = new ConflictGroup( key );
            depths.clear();
            selectVersion( node, null, 0, depths, group, conflictIds );
            pruneNonSelectedVersions( group, conflictIds );
        }

        return node;
    }

    private void selectVersion( DependencyNode node, DependencyNode parent, int depth,
                                Map<DependencyNode, Integer> depths, ConflictGroup group, Map<?, ?> conflictIds )
        throws RepositoryException
    {
        Integer smallestDepth = depths.get( node );
        if ( smallestDepth == null || smallestDepth.intValue() > depth )
        {
            depths.put( node, Integer.valueOf( depth ) );
        }
        else
        {
            return;
        }

        Object key = conflictIds.get( node );
        if ( group.key.equals( key ) )
        {
            Position pos = new Position( parent, depth );
            if ( parent != null )
            {
                group.positions.add( pos );
            }

            boolean hardConstraint = !node.getVersionConstraint().getRanges().isEmpty();

            if ( hardConstraint )
            {
                group.constraints.add( node.getVersionConstraint() );
            }

            if ( !group.isAcceptable( node.getVersion() ) )
            {
                if ( hardConstraint )
                {
                    throw newFailure( group );
                }
                return;
            }

            group.candidates.put( node, pos );

            if ( group.version == null || isNearer( pos, node.getVersion(), group.position, group.version ) )
            {
                group.version = node.getVersion();
                group.position = pos;
            }

            if ( !group.isAcceptable( group.version ) )
            {
                group.version = null;

                for ( Iterator<Map.Entry<DependencyNode, Position>> it = group.candidates.entrySet().iterator(); it.hasNext(); )
                {
                    Map.Entry<DependencyNode, Position> entry = it.next();
                    Version version = entry.getKey().getVersion();
                    pos = entry.getValue();

                    if ( !group.isAcceptable( version ) )
                    {
                        it.remove();
                    }
                    else if ( group.version == null || isNearer( pos, version, group.position, group.version ) )
                    {
                        group.version = version;
                        group.position = pos;
                    }
                }

                if ( group.version == null )
                {
                    throw newFailure( group );
                }
            }
        }

        depth++;

        for ( DependencyNode child : node.getChildren() )
        {
            selectVersion( child, node, depth, depths, group, conflictIds );
        }
    }

    private UnsolvableVersionConflictException newFailure( ConflictGroup group )
    {
        Collection<String> versions = new LinkedHashSet<String>();
        for ( VersionConstraint constraint : group.constraints )
        {
            versions.add( constraint.toString() );
        }
        return new UnsolvableVersionConflictException( group.key, versions );
    }

    private boolean isNearer( Position pos1, Version ver1, Position pos2, Version ver2 )
    {
        if ( pos1.depth < pos2.depth )
        {
            return true;
        }
        else if ( pos1.depth == pos2.depth && pos1.parent == pos2.parent && ver1.compareTo( ver2 ) > 0 )
        {
            return true;
        }
        return false;
    }

    private void pruneNonSelectedVersions( ConflictGroup group, Map<?, ?> conflictIds )
    {
        for ( Position pos : group.positions )
        {
            for ( Iterator<DependencyNode> it = pos.parent.getChildren().iterator(); it.hasNext(); )
            {
                DependencyNode child = it.next();

                Object key = conflictIds.get( child );

                if ( group.key.equals( key ) )
                {
                    if ( !group.pruned && group.position.depth == pos.depth
                        && group.version.equals( child.getVersion() ) )
                    {
                        group.pruned = true;
                    }
                    else
                    {
                        it.remove();
                    }
                }
            }
        }
    }

    static final class ConflictGroup
    {

        final Object key;

        final Collection<VersionConstraint> constraints = new HashSet<VersionConstraint>();

        final Map<DependencyNode, Position> candidates = new IdentityHashMap<DependencyNode, Position>( 32 );

        Version version;

        Position position;

        final Collection<Position> positions = new LinkedHashSet<Position>();

        boolean pruned;

        public ConflictGroup( Object key )
        {
            this.key = key;
            this.position = new Position( null, Integer.MAX_VALUE );
        }

        boolean isAcceptable( Version version )
        {
            for ( VersionConstraint constraint : constraints )
            {
                if ( !constraint.containsVersion( version ) )
                {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString()
        {
            return key + " > " + version;
        }

    }

    static final class Position
    {

        final DependencyNode parent;

        final int depth;

        final int hash;

        public Position( DependencyNode parent, int depth )
        {
            this.parent = parent;
            this.depth = depth;
            hash = 31 * System.identityHashCode( parent ) + depth;
        }

        @Override
        public boolean equals( Object obj )
        {
            if ( this == obj )
            {
                return true;
            }
            else if ( !( obj instanceof Position ) )
            {
                return false;
            }
            Position that = (Position) obj;
            return this.parent == that.parent && this.depth == that.depth;
        }

        @Override
        public int hashCode()
        {
            return hash;
        }

        @Override
        public String toString()
        {
            return depth + " > " + parent;
        }

    }

}
