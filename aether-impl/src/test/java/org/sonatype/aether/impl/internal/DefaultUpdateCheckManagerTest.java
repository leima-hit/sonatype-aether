package org.sonatype.aether.impl.internal;

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
import static org.sonatype.aether.repository.RepositoryPolicy.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.impl.UpdateCheck;
import org.sonatype.aether.metadata.Metadata;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.repository.RepositoryPolicy;
import org.sonatype.aether.test.impl.TestRepositorySystemSession;
import org.sonatype.aether.test.util.TestFileUtils;
import org.sonatype.aether.test.util.impl.StubArtifact;
import org.sonatype.aether.test.util.impl.StubMetadata;
import org.sonatype.aether.transfer.ArtifactNotFoundException;
import org.sonatype.aether.transfer.ArtifactTransferException;
import org.sonatype.aether.transfer.MetadataNotFoundException;
import org.sonatype.aether.transfer.MetadataTransferException;

/**
 * @author Benjamin Hanzelmann
 */
public class DefaultUpdateCheckManagerTest
{

    /**
     * 
     */
    private static final int HOUR = 60 * 60 * 1000;

    private DefaultUpdateCheckManager manager;

    private TestRepositorySystemSession session;

    private StubMetadata metadata;

    private RemoteRepository repository;

    private StubArtifact artifact;

    @Before
    public void setup()
        throws IOException
    {
        session = new TestRepositorySystemSession();
        repository = new RemoteRepository( "id", "default", new File( "target/test-DUCM/" ).toURL().toString() );
        manager = new DefaultUpdateCheckManager();
        metadata =
            new StubMetadata( "gid", "aid", "ver", "maven-metadata.xml", Metadata.Nature.RELEASE_OR_SNAPSHOT,
                              TestFileUtils.createTempFile( "metadata" ) );
        artifact =
            new StubArtifact( "gid", "aid", "", "ext", "ver" ).setFile( TestFileUtils.createTempFile( "artifact" ) );
    }

    @Test( expected = Exception.class )
    public void testCheckMetadataFailOnNoFile()
    {
        UpdateCheck<Metadata, MetadataTransferException> check = newMetadataCheck();
        check.setItem( metadata.setFile( null ) );
        check.setFile( null );

        manager.checkMetadata( session, check );
    }

    /**
     * [AETHER-27] updatePolicy:daily should be based on local midnight
     */
    @Test
    public void testUseLocalTimezone()
    {
        long localMidnight;

        Calendar cal = Calendar.getInstance();
        cal.set( Calendar.HOUR, 0 );
        cal.set( Calendar.MINUTE, 0 );
        cal.set( Calendar.SECOND, 0 );
        cal.set( Calendar.MILLISECOND, 0 );
        localMidnight = cal.getTimeInMillis();

        String policy = RepositoryPolicy.UPDATE_POLICY_DAILY;
        assertEquals( false, manager.isUpdatedRequired( session, localMidnight + 1, policy ) );
        assertEquals( true, manager.isUpdatedRequired( session, localMidnight - 1, policy ) );
    }

    @Test
    public void testCheckMetadataUpdatePolicyRequired()
    {
        UpdateCheck<Metadata, MetadataTransferException> check = newMetadataCheck();
        check.setItem( metadata );
        check.setFile( metadata.getFile() );

        Calendar cal = Calendar.getInstance( TimeZone.getTimeZone( "UTC" ) );
        cal.set( Calendar.DATE, cal.get( Calendar.DATE ) - 1 );
        check.setLocalLastUpdated( cal.getTimeInMillis() );

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_ALWAYS );
        manager.checkMetadata( session, check );
        assertNull( check.getException() );
        assertTrue( check.isRequired() );

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );
        manager.checkMetadata( session, check );
        assertNull( check.getException() );
        assertTrue( check.isRequired() );

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_INTERVAL + ":60" );
        manager.checkMetadata( session, check );
        assertNull( check.getException() );
        assertTrue( check.isRequired() );
    }

    @Test
    public void testCheckMetadataUpdatePolicyNotRequired()
    {
        UpdateCheck<Metadata, MetadataTransferException> check = new UpdateCheck<Metadata, MetadataTransferException>();
        check.setItem( metadata );
        check.setFile( metadata.getFile() );
        check.setRepository( repository );
        check.setAuthoritativeRepository( repository );

        check.setLocalLastUpdated( System.currentTimeMillis() );

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_NEVER );
        manager.checkMetadata( session, check );
        assertFalse( check.isRequired() );

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );
        manager.checkMetadata( session, check );
        assertFalse( check.isRequired() );

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_INTERVAL + ":61" );
        manager.checkMetadata( session, check );
        assertFalse( check.isRequired() );

        check.setPolicy( "no particular policy" );
        manager.checkMetadata( session, check );
        assertFalse( check.isRequired() );
    }

    @Test
    public void testCheckMetadata()
        throws FileNotFoundException, IOException
    {
        UpdateCheck<Metadata, MetadataTransferException> check = new UpdateCheck<Metadata, MetadataTransferException>();
        check.setItem( metadata );
        check.setFile( metadata.getFile() );
        check.setRepository( repository );
        check.setAuthoritativeRepository( repository );
        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );

        // never checked before
        manager.checkMetadata( session, check );
        assertEquals( true, check.isRequired() );
        assertEquals( check.getLocalLastUpdated(), 0 );

        // just checked
        check = newMetadataCheck();
        manager.touchMetadata( session, check );

        check = newMetadataCheck();
        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );

        manager.checkMetadata( session, check );
        assertEquals( false, check.isRequired() );

        // no local file, no repo timestamp
        check.setLocalLastUpdated( 0 );
        check.getFile().delete();
        manager.checkMetadata( session, check );
        assertEquals( true, check.isRequired() );
        // (! file.exists && ! repoKey) -> no timestamp

    }

    @Test
    public void testCheckMetadataNoLocalFile()
        throws IOException
    {
        metadata.getFile().delete();

        UpdateCheck<Metadata, MetadataTransferException> check = newMetadataCheck();

        long lastUpdate = new Date().getTime() - ( 1800 * 1000 );
        check.setLocalLastUpdated( lastUpdate );

        // ! file.exists && updateRequired -> check in remote repo
        check.setLocalLastUpdated( lastUpdate );
        manager.checkMetadata( session, check );
        assertEquals( true, check.isRequired() );
    }

    @Test
    public void testCheckMetadataNotFoundInRepoCachingEnabled()
        throws IOException
    {
        metadata.getFile().delete();
        session.setNotFoundCachingEnabled( true );

        UpdateCheck<Metadata, MetadataTransferException> check = newMetadataCheck();

        check.setException( new MetadataNotFoundException( metadata, repository, "" ) );
        manager.touchMetadata( session, check );

        // ! file.exists && ! updateRequired -> artifact not found in remote repo
        check = newMetadataCheck().setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );
        manager.checkMetadata( session, check );
        assertEquals( false, check.isRequired() );
        assertNotNull( check.getException() );
    }

    @Test
    public void testCheckMetadataNotFoundInRepoCachingDisabled()
        throws IOException
    {
        metadata.getFile().delete();
        session.setNotFoundCachingEnabled( false );

        UpdateCheck<Metadata, MetadataTransferException> check = newMetadataCheck();

        check.setException( new MetadataNotFoundException( metadata, repository, "" ) );
        manager.touchMetadata( session, check );

        // ! file.exists && ! updateRequired -> artifact not found in remote repo
        // ignore NotFoundCaching-setting, don't check if update policy does not say so for metadata
        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );
        manager.checkMetadata( session, check );
        assertEquals( false, check.isRequired() );
        assertNotNull( check.getException() );
    }

    @Test
    public void testCheckMetadataErrorFromRepo()
        throws IOException
    {
        metadata.getFile().delete();

        UpdateCheck<Metadata, MetadataTransferException> check = newMetadataCheck();
        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );

        check.setException( new MetadataTransferException( metadata, repository, "some error" ) );
        manager.touchMetadata( session, check );

        // ! file.exists && ! updateRequired && previousError -> depends on transfer error caching
        check = newMetadataCheck();
        session.setTransferErrorCachingEnabled( true );
        manager.checkMetadata( session, check );
        assertEquals( false, check.isRequired() );
        assertNotNull( check.getException() );
    }

    @Test
    public void testCheckMetadataErrorFromRepoNoCaching()
        throws IOException
    {
        metadata.getFile().delete();

        UpdateCheck<Metadata, MetadataTransferException> check = newMetadataCheck();
        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );

        check.setException( new MetadataTransferException( metadata, repository, "some error" ) );
        manager.touchMetadata( session, check );

        // ! file.exists && ! updateRequired && previousError -> depends on transfer error caching
        check = newMetadataCheck();
        session.setTransferErrorCachingEnabled( false );
        manager.checkMetadata( session, check );
        assertEquals( true, check.isRequired() );
        assertNull( check.getException() );
    }

    public UpdateCheck<Metadata, MetadataTransferException> newMetadataCheck()
    {
        UpdateCheck<Metadata, MetadataTransferException> check = new UpdateCheck<Metadata, MetadataTransferException>();
        check.setItem( metadata );
        check.setFile( metadata.getFile() );
        check.setRepository( repository );
        check.setAuthoritativeRepository( repository );
        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_INTERVAL + ":10" );
        return check;
    }

    public UpdateCheck<Artifact, ArtifactTransferException> newArtifactCheck()
    {
        UpdateCheck<Artifact, ArtifactTransferException> check = new UpdateCheck<Artifact, ArtifactTransferException>();
        check.setItem( artifact );
        check.setFile( artifact.getFile() );
        check.setRepository( repository );
        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_INTERVAL + ":10" );
        return check;
    }

    @After
    public void teardown()
    {
        new File( metadata.getFile().getParent(), "resolver-status.properties" ).delete();
        new File( artifact.getFile().getPath() + ".lastUpdated" ).delete();
        metadata.getFile().delete();
        artifact.getFile().delete();
    }

    @Test( expected = IllegalArgumentException.class )
    public void testCheckArtifactFailOnNoFile()
    {
        UpdateCheck<Artifact, ArtifactTransferException> check = newArtifactCheck();
        check.setItem( artifact.setFile( null ) );
        check.setFile( null );

        manager.checkArtifact( session, check );
        assertNotNull( check.getException() );
        assertEquals( 0, check.getLocalLastUpdated() );
    }

    @Test
    public void testCheckArtifactUpdatePolicyRequired()
    {
        UpdateCheck<Artifact, ArtifactTransferException> check = newArtifactCheck();
        check.setItem( artifact );
        check.setFile( artifact.getFile() );

        Calendar cal = Calendar.getInstance( TimeZone.getTimeZone( "UTC" ) );
        cal.set( Calendar.DATE, cal.get( Calendar.DATE ) - 1 );
        long lastUpdate = cal.getTimeInMillis();
        artifact.getFile().setLastModified( lastUpdate );
        check.setLocalLastUpdated( lastUpdate );

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_ALWAYS );
        manager.checkArtifact( session, check );
        assertNull( check.getException() );
        assertTrue( check.isRequired() );

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );
        manager.checkArtifact( session, check );
        assertNull( check.getException() );
        assertTrue( check.isRequired() );

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_INTERVAL + ":60" );
        manager.checkArtifact( session, check );
        assertNull( check.getException() );
        assertTrue( check.isRequired() );
    }

    @Test
    public void testCheckArtifactUpdatePolicyNotRequired()
    {
        UpdateCheck<Artifact, ArtifactTransferException> check = newArtifactCheck();
        check.setItem( artifact );
        check.setFile( artifact.getFile() );

        Calendar cal = Calendar.getInstance( TimeZone.getTimeZone( "UTC" ) );
        cal.set( Calendar.HOUR, cal.get( Calendar.HOUR ) - 1 );
        check.setLocalLastUpdated( cal.getTimeInMillis() );

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_NEVER );
        manager.checkArtifact( session, check );
        assertFalse( check.isRequired() );

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );
        manager.checkArtifact( session, check );
        assertFalse( check.isRequired() );

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_INTERVAL + ":61" );
        manager.checkArtifact( session, check );
        assertFalse( check.isRequired() );

        check.setPolicy( "no particular policy" );
        manager.checkArtifact( session, check );
        assertFalse( check.isRequired() );
    }

    @Test
    public void testCheckArtifact()
        throws FileNotFoundException, IOException
    {
        UpdateCheck<Artifact, ArtifactTransferException> check = newArtifactCheck();
        long fifteenMinutes = new Date().getTime() - ( 15 * 60 * 1000 );
        check.getFile().setLastModified( fifteenMinutes );
        // time is truncated on setLastModfied
        fifteenMinutes = check.getFile().lastModified();

        // never checked before
        manager.checkArtifact( session, check );
        assertEquals( true, check.isRequired() );

        // just checked
        check.setLocalLastUpdated( 0 );
        long lastUpdate = new Date().getTime();
        check.getFile().setLastModified( lastUpdate );
        lastUpdate = check.getFile().lastModified();

        manager.checkArtifact( session, check );
        assertEquals( false, check.isRequired() );

        // no local file, no repo timestamp
        check.setLocalLastUpdated( 0 );
        check.getFile().delete();
        manager.checkArtifact( session, check );
        assertEquals( true, check.isRequired() );
    }

    @Test
    public void testCheckArtifactNoLocalFile()
        throws IOException
    {
        artifact.getFile().delete();
        UpdateCheck<Artifact, ArtifactTransferException> check = newArtifactCheck();

        long lastUpdate = new Date().getTime() - HOUR;

        // ! file.exists && updateRequired -> check in remote repo
        check.setLocalLastUpdated( lastUpdate );
        manager.checkArtifact( session, check );
        assertEquals( true, check.isRequired() );
    }

    @Test
    public void testCheckArtifactNotFoundInRepoCachingEnabled()
        throws IOException
    {
        artifact.getFile().delete();
        session.setNotFoundCachingEnabled( true );

        UpdateCheck<Artifact, ArtifactTransferException> check = newArtifactCheck();
        check.setException( new ArtifactNotFoundException( artifact, repository ) );
        manager.touchArtifact( session, check );

        // ! file.exists && ! updateRequired -> artifact not found in remote repo
        check = newArtifactCheck().setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );
        manager.checkArtifact( session, check );
        assertEquals( false, check.isRequired() );
        assertNotNull( check.getException() );
    }

    @Test
    public void testCheckArtifactNotFoundInRepoCachingDisabled()
        throws IOException
    {
        artifact.getFile().delete();
        session.setNotFoundCachingEnabled( false );

        UpdateCheck<Artifact, ArtifactTransferException> check = newArtifactCheck();
        check.setException( new ArtifactNotFoundException( artifact, repository ) );
        manager.touchArtifact( session, check );

        // ! file.exists && ! updateRequired -> artifact not found in remote repo
        check = newArtifactCheck().setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );
        manager.checkArtifact( session, check );
        assertEquals( true, check.isRequired() );
        assertNull( check.getException() );
    }

    @Test
    public void testCheckArtifactErrorFromRepoCachingEnabled()
        throws IOException
    {
        artifact.getFile().delete();

        UpdateCheck<Artifact, ArtifactTransferException> check = newArtifactCheck();
        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );
        check.setException( new ArtifactTransferException( artifact, repository, "some error" ) );
        manager.touchArtifact( session, check );

        // ! file.exists && ! updateRequired && previousError -> depends on transfer error caching
        check = newArtifactCheck();
        session.setTransferErrorCachingEnabled( true );
        manager.checkArtifact( session, check );
        assertEquals( false, check.isRequired() );
        assertNotNull( check.getException() );
    }

    @Test
    public void testCheckArtifactErrorFromRepoCachingDisabled()
        throws IOException
    {
        artifact.getFile().delete();

        UpdateCheck<Artifact, ArtifactTransferException> check = newArtifactCheck();
        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_DAILY );
        check.setException( new ArtifactTransferException( artifact, repository, "some error" ) );
        manager.touchArtifact( session, check );

        // ! file.exists && ! updateRequired && previousError -> depends on transfer error caching
        check = newArtifactCheck();
        session.setTransferErrorCachingEnabled( false );
        manager.checkArtifact( session, check );
        assertEquals( true, check.isRequired() );
        assertNull( check.getException() );
    }

    @Test
    public void testTouchMetadata()
        throws IOException
    {
        UpdateCheck<Metadata, MetadataTransferException> check = newMetadataCheck();

        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_INTERVAL + ":60" );
        manager.checkMetadata( session, check );
        assertTrue( "check is marked as not required, but should be", check.isRequired() );
        manager.touchMetadata( session, check );

        check = newMetadataCheck();
        check.setPolicy( RepositoryPolicy.UPDATE_POLICY_INTERVAL + ":60" );
        manager.checkMetadata( session, check );
        assertFalse( "check is marked as required directly after touchMetadata()", check.isRequired() );

    }

    @Test
    public void testEffectivePolicy()
    {
        assertEquals( UPDATE_POLICY_ALWAYS,
                      manager.getEffectiveUpdatePolicy( session, UPDATE_POLICY_ALWAYS, UPDATE_POLICY_DAILY ) );
        assertEquals( UPDATE_POLICY_ALWAYS,
                      manager.getEffectiveUpdatePolicy( session, UPDATE_POLICY_ALWAYS, UPDATE_POLICY_NEVER ) );
        assertEquals( UPDATE_POLICY_DAILY,
                      manager.getEffectiveUpdatePolicy( session, UPDATE_POLICY_DAILY, UPDATE_POLICY_NEVER ) );
        assertEquals( UPDATE_POLICY_INTERVAL + ":60",
                      manager.getEffectiveUpdatePolicy( session, UPDATE_POLICY_DAILY, UPDATE_POLICY_INTERVAL + ":60" ) );
        assertEquals( UPDATE_POLICY_INTERVAL + ":60", manager.getEffectiveUpdatePolicy( session, UPDATE_POLICY_INTERVAL
            + ":100", UPDATE_POLICY_INTERVAL + ":60" ) );
    }

}
