package org.sonatype.aether.test.util.connector;

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

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;

import org.sonatype.aether.DefaultArtifact;
import org.sonatype.aether.DefaultMetadata;
import org.sonatype.aether.Metadata;
import org.sonatype.aether.NoRepositoryConnectorException;
import org.sonatype.aether.RemoteRepository;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.TransferEvent;
import org.sonatype.aether.TransferEvent.EventType;
import org.sonatype.aether.TransferListener;
import org.sonatype.aether.spi.connector.ArtifactDownload;
import org.sonatype.aether.spi.connector.ArtifactUpload;
import org.sonatype.aether.spi.connector.MetadataDownload;
import org.sonatype.aether.spi.connector.MetadataUpload;
import org.sonatype.aether.spi.connector.RepositoryConnector;
import org.sonatype.aether.spi.connector.RepositoryConnectorFactory;
import org.sonatype.aether.spi.connector.Transfer;
import org.sonatype.aether.test.util.FileUtil;

public class TransferEventTester
{

    public static void testTransferEvents( RepositoryConnectorFactory factory )
        throws IOException, NoRepositoryConnectorException
    {

        RecordingTransferListener listener = new RecordingTransferListener();

        TestContext ctx = setupTestContext( listener );
        testTransferEvents( factory, ctx );
    }

    public static void testTransferEvents( RepositoryConnectorFactory factory, TestContext ctx )
        throws IOException, NoRepositoryConnectorException
    {
        RepositorySystemSession session = ctx.getSession();
        RemoteRepository repository = ctx.getRepository();
        RecordingTransferListener listener = ctx.getRecordingTransferListener();

        RepositoryConnector connector = factory.newInstance( session, repository );

        byte[] pattern = "tmpFile".getBytes();
        File tmpFile = FileUtil.createTempFile( pattern, 10000 );

        Collection<ArtifactUpload> artUps = createTransfers( ArtifactUpload.class, 1, tmpFile );

        connector.put( artUps, null );
        LinkedList<TransferEvent> events = new LinkedList<TransferEvent>( listener.getEvents() );

        TransferEvent currentEvent = events.poll();
        assertNotNull( "initiate event is missing", currentEvent);
        assertEquals( TransferEvent.EventType.INITIATED, currentEvent.getType() );
        // TODO: check mandatory attributes

        currentEvent = events.poll();
        assertNotNull( "start event is missing", currentEvent);
        assertEquals( TransferEvent.EventType.STARTED, currentEvent.getType() );
        // TODO: check mandatory attributes

        EventType progressed = TransferEvent.EventType.PROGRESSED;
        EventType succeeded = TransferEvent.EventType.SUCCEEDED;

        TransferEvent succeedEvent = null;

        int dataLength = 0;
        long transferredBytes = 0;
        while ( ( currentEvent = events.poll() ) != null )
        {
            EventType currentType = currentEvent.getType();

            if ( succeeded.equals( currentType ) )
            {
                succeedEvent = currentEvent;
                break;
            }
            else
            {
                assertTrue( progressed.equals( currentType ) );
                assertTrue( currentEvent.getTransferredBytes() > transferredBytes);
                transferredBytes = currentEvent.getTransferredBytes();
                dataLength += currentEvent.getDataLength();
                // TODO: check mandatory attributes
            }
        }

        // all events consumed
        assertEquals( 0, events.size() );

        // test transferred size
        assertEquals( "progress events transferred bytes don't match", tmpFile.length(), dataLength );
        assertEquals( "succeed event transferred bytes don't match", tmpFile.length(), succeedEvent.getTransferredBytes() );
    }

    public static TestContext setupTestContext()
    {
        return setupTestContext( null );
    }

    public static TestContext setupTestContext( TransferListener listener )
    {

        File testRepo = new File( "target/test-repo" );
        testRepo.mkdirs();

        RemoteRepository repository;
        try
        {
            repository = new RemoteRepository( "test-repo", "default", testRepo.toURI().toURL().toString() );
        }
        catch ( MalformedURLException e )
        {
            // conversion File->URL... should not happen
            throw new UnsupportedOperationException(
                                                     "Malformed URL with File->URI->URL: " + testRepo.getAbsolutePath(),
                                                     e );
        }

        TestRepositorySystemSession session = new TestRepositorySystemSession();

        return new TestContext( repository, session );

    }

    @SuppressWarnings( "unchecked" )
    public static <T extends Transfer> Collection<T> createTransfers( Class<T> cls, int count, File file )
    {
        ArrayList<T> ret = new ArrayList<T>();

        for ( int i = 0; i < count; i++ )
        {
            DefaultArtifact artifact =
                new DefaultArtifact( "testGroup", "testArtifact", "sources", "jar", ( i + 1 ) + "-test" );

            T obj = null;
            if ( cls.isAssignableFrom( ArtifactUpload.class ) )
            {
                obj = (T) new ArtifactUpload( artifact, file );
            }
            else if ( cls.isAssignableFrom( ArtifactDownload.class ) )
            {
                String context = null;
                String checksumPolicy = null;
                obj = (T) new ArtifactDownload( artifact, context, file, checksumPolicy );
            }
            else if ( cls.isAssignableFrom( MetadataUpload.class ) )
            {
                DefaultMetadata metadata =
                    new DefaultMetadata( "testGroup", "testArtifact", ( i + 1 ) + "test", "jar",
                                         Metadata.Nature.RELEASE_OR_SNAPSHOT, file );
                obj = (T) new MetadataUpload( metadata, file );
            }
            else if ( cls.isAssignableFrom( MetadataDownload.class ) )
            {
                obj = (T) new MetadataDownload();
            }

            ret.add( obj );
        }

        return ret;
    }
}