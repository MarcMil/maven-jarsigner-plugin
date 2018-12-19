package org.apache.maven.plugins.jarsigner;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.jarsigner.JarSigner;
import org.apache.maven.shared.jarsigner.JarSignerRequest;
import org.apache.maven.shared.jarsigner.JarSignerUtil;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.cli.Commandline;
import org.apache.maven.shared.utils.cli.javatool.JavaToolException;
import org.apache.maven.shared.utils.cli.javatool.JavaToolResult;
import org.apache.maven.shared.utils.io.FileUtils;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.ResourceBundle;
import org.apache.maven.shared.utils.ReaderFactory;

/**
 * Maven Jarsigner Plugin base class.
 *
 * @author <a href="cs@schulte.it">Christian Schulte</a>
 */
public abstract class AbstractJarsignerMojo
    extends AbstractMojo
{

    /**
     * See <a href="https://docs.oracle.com/javase/7/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.verbose", defaultValue = "false" )
    private boolean verbose;

    /**
     * See <a href="https://docs.oracle.com/javase/7/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.keystore" )
    private String keystore;

    /**
     * See <a href="https://docs.oracle.com/javase/7/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.storetype" )
    private String storetype;

    /**
     * See <a href="https://docs.oracle.com/javase/7/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.storepass" )
    private String storepass;

    /**
     * See <a href="https://docs.oracle.com/javase/7/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.providerName" )
    private String providerName;

    /**
     * See <a href="https://docs.oracle.com/javase/7/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.providerClass" )
    private String providerClass;

    /**
     * See <a href="https://docs.oracle.com/javase/7/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.providerArg" )
    private String providerArg;

    /**
     * See <a href="https://docs.oracle.com/javase/7/docs/technotes/tools/windows/jarsigner.html#Options">options</a>.
     */
    @Parameter( property = "jarsigner.alias" )
    private String alias;

    /**
     * The maximum memory available to the JAR signer, e.g. <code>256M</code>. See <a
     * href="https://docs.oracle.com/javase/7/docs/technotes/tools/windows/java.html#Xms">-Xmx</a> for more details.
     */
    @Parameter( property = "jarsigner.maxMemory" )
    private String maxMemory;

    /**
     * Archive to process. If set, neither the project artifact nor any attachments or archive sets are processed.
     */
    @Parameter( property = "jarsigner.archive" )
    private File archive;

    /**
     * The base directory to scan for JAR files using Ant-like inclusion/exclusion patterns.
     *
     * @since 1.1
     */
    @Parameter( property = "jarsigner.archiveDirectory" )
    private File archiveDirectory;

    /**
     * The Ant-like inclusion patterns used to select JAR files to process. The patterns must be relative to the
     * directory given by the parameter {@link #archiveDirectory}. By default, the pattern
     * <code>&#42;&#42;/&#42;.?ar</code> is used.
     *
     * @since 1.1
     */
    @Parameter
    private String[] includes = { "**/*.?ar" };

    /**
     * The Ant-like exclusion patterns used to exclude JAR files from processing. The patterns must be relative to the
     * directory given by the parameter {@link #archiveDirectory}.
     *
     * @since 1.1
     */
    @Parameter
    private String[] excludes = {};

    /**
     * List of additional arguments to append to the jarsigner command line.
     */
    @Parameter( property = "jarsigner.arguments" )
    private String[] arguments;

    /**
     * Set to {@code true} to disable the plugin.
     */
    @Parameter( property = "jarsigner.skip", defaultValue = "false" )
    private boolean skip;

    /**
     * Controls processing of the main artifact produced by the project.
     *
     * @since 1.1
     */
    @Parameter( property = "jarsigner.processMainArtifact", defaultValue = "true" )
    private boolean processMainArtifact;

    /**
     * Controls processing of project attachments. If enabled, attached artifacts that are no JAR/ZIP files will be
     * automatically excluded from processing.
     *
     * @since 1.1
     */
    @Parameter( property = "jarsigner.processAttachedArtifacts", defaultValue = "true" )
    private boolean processAttachedArtifacts;

    /**
     * Must be set to true if the password must be given via a protected
     * authentication path such as a dedicated PIN reader.
     *
     * @since 1.3
     */
    @Parameter( property = "jarsigner.protectedAuthenticationPath", defaultValue = "false" )
    private boolean protectedAuthenticationPath;

    /**
     * A set of artifact classifiers describing the project attachments that should be processed. This parameter is only
     * relevant if {@link #processAttachedArtifacts} is <code>true</code>. If empty, all attachments are included.
     *
     * @since 1.2
     */
    @Parameter
    private String[] includeClassifiers;

    /**
     * A set of artifact classifiers describing the project attachments that should not be processed. This parameter is
     * only relevant if {@link #processAttachedArtifacts} is <code>true</code>. If empty, no attachments are excluded.
     *
     * @since 1.2
     */
    @Parameter
    private String[] excludeClassifiers;

    /**
     * The Maven project.
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    private MavenProject project;

    /**
     * The Maven settings.
     *
     * @since 1.5
     */
    @Parameter( defaultValue = "${settings}", readonly = true, required = true )
    private Settings settings;

    /**
     * Location of the working directory.
     *
     * @since 1.3
     */
    @Parameter( defaultValue = "${project.basedir}" )
    private File workingDirectory;

    /**
     */
    @Component
    private JarSigner jarSigner;

    /**
     * The current build session instance. This is used for
     * toolchain manager API calls.
     *
     * @since 1.3
     */
    @Parameter( defaultValue = "${session}", readonly = true, required = true )
    private MavenSession session;

    /**
     * To obtain a toolchain if possible.
     *
     * @since 1.3
     */
    @Component
    private ToolchainManager toolchainManager;

    /**
     * @since 1.3.2
     */
    @Component( hint = "mng-4384" )
    private SecDispatcher securityDispatcher;

    /**
     * How many times to try to sign or verify a jar (assuming each previous attempt is a failure).
     *
     * This option may be desirable if you are using a Time Stamp Authority,
     * and your network conditions cause intermittent failures.
     *
     * The default is "1" for one attempt.
     *
     * Less than 0 and "0" we be treated as 1 (because using "skip" is accepted and encouraged,
     * and an infinite retry loop is undesirable).
     *
     * @since 3.0.1
     */
    @Parameter( property = "jarsigner.maxTries", defaultValue = "1" )
    private int maxTries;

    public final void execute()
        throws MojoExecutionException
    {
        if ( !this.skip )
        {
            if ( maxTries <= 0 )
            {
                maxTries = 1;
            }
            Toolchain toolchain = getToolchain();

            if ( toolchain != null )
            {
                getLog().info( "Toolchain in maven-jarsigner-plugin: " + toolchain );
                jarSigner.setToolchain( toolchain );
            }

            int processed = 0;

            if ( this.archive != null )
            {
                processArchive( this.archive );
                processed++;
            }
            else
            {
                if ( processMainArtifact )
                {
                    processed += processArtifact( this.project.getArtifact() ) ? 1 : 0;
                }

                if ( processAttachedArtifacts )
                {
                    Collection<String> includes = new HashSet<>();
                    if ( includeClassifiers != null )
                    {
                        includes.addAll( Arrays.asList( includeClassifiers ) );
                    }

                    Collection<String> excludes = new HashSet<>();
                    if ( excludeClassifiers != null )
                    {
                        excludes.addAll( Arrays.asList( excludeClassifiers ) );
                    }

                    for ( Artifact artifact : this.project.getAttachedArtifacts() )
                    {
                        if ( !includes.isEmpty() && !includes.contains( artifact.getClassifier() ) )
                        {
                            continue;
                        }

                        if ( excludes.contains( artifact.getClassifier() ) )
                        {
                            continue;
                        }

                        processed += processArtifact( artifact ) ? 1 : 0;
                    }
                }
                else
                {
                    if ( verbose )
                    {
                        getLog().info( getMessage( "ignoringAttachments" ) );
                    }
                    else
                    {
                        getLog().debug( getMessage( "ignoringAttachments" ) );
                    }
                }

                if ( archiveDirectory != null )
                {
                    String includeList = ( includes != null ) ? StringUtils.join( includes, "," ) : null;
                    String excludeList = ( excludes != null ) ? StringUtils.join( excludes, "," ) : null;

                    List<File> jarFiles;
                    try
                    {
                        jarFiles = FileUtils.getFiles( archiveDirectory, includeList, excludeList );
                    }
                    catch ( IOException e )
                    {
                        throw new MojoExecutionException( "Failed to scan archive directory for JARs: "
                            + e.getMessage(), e );
                    }

                    for ( File jarFile : jarFiles )
                    {
                        processArchive( jarFile );
                        processed++;
                    }
                }
            }

            getLog().info( getMessage( "processed", processed ) );
        }
        else
        {
            getLog().info( getMessage( "disabled", null ) );
        }
    }

    /**
     * Creates the jar signer request to be executed.
     *
     * @param archive the archive file to treat by jarsigner
     * @return the request
     * @throws MojoExecutionException if an exception occurs
     * @since 1.3
     */
    protected abstract JarSignerRequest createRequest( File archive )
        throws MojoExecutionException;

    /**
     * Gets a string representation of a {@code Commandline}.
     * <p>
     * This method creates the string representation by calling {@code commandLine.toString()} by default.
     * </p>
     *
     * @param commandLine The {@code Commandline} to get a string representation of.
     * @return The string representation of {@code commandLine}.
     * @throws NullPointerException if {@code commandLine} is {@code null}.
     */
    protected String getCommandlineInfo( final Commandline commandLine )
    {
        if ( commandLine == null )
        {
            throw new NullPointerException( "commandLine" );
        }

        String commandLineInfo = commandLine.toString();
        commandLineInfo = StringUtils.replace( commandLineInfo, this.storepass, "'*****'" );
        return commandLineInfo;
    }

    public String getStoretype()
    {
        return storetype;
    }

    public String getStorepass()
    {
        return storepass;
    }

    /**
     * Checks whether the specified artifact is a ZIP file.
     *
     * @param artifact The artifact to check, may be <code>null</code>.
     * @return <code>true</code> if the artifact looks like a ZIP file, <code>false</code> otherwise.
     */
    private boolean isZipFile( final Artifact artifact )
    {
        return artifact != null && artifact.getFile() != null && JarSignerUtil.isZipFile( artifact.getFile() );
    }

    /**
     * Processes a given artifact.
     *
     * @param artifact The artifact to process.
     * @return <code>true</code> if the artifact is a JAR and was processed, <code>false</code> otherwise.
     * @throws NullPointerException if {@code artifact} is {@code null}.
     * @throws MojoExecutionException if processing {@code artifact} fails.
     */
    private boolean processArtifact( final Artifact artifact )
        throws MojoExecutionException
    {
        if ( artifact == null )
        {
            throw new NullPointerException( "artifact" );
        }

        boolean processed = false;

        if ( isZipFile( artifact ) )
        {
            processArchive( artifact.getFile() );

            processed = true;
        }
        else
        {
            if ( this.verbose )
            {
                getLog().info( getMessage( "unsupported", artifact ) );
            }
            else if ( getLog().isDebugEnabled() )
            {
                getLog().debug( getMessage( "unsupported", artifact ) );
            }
        }

        return processed;
    }

    /**
     * Pre-processes a given archive.
     *
     * @param archive The archive to process, must not be <code>null</code>.
     * @throws MojoExecutionException If pre-processing failed.
     */
    protected void preProcessArchive( final File archive )
        throws MojoExecutionException
    {
        // default does nothing
    }

    /**
     * Processes a given archive.
     *
     * @param archive The archive to process.
     * @throws NullPointerException if {@code archive} is {@code null}.
     * @throws MojoExecutionException if processing {@code archive} fails.
     */
    private void processArchive( final File archive )
        throws MojoExecutionException
    {
        if ( archive == null )
        {
            throw new NullPointerException( "archive" );
        }

        preProcessArchive( archive );

        if ( this.verbose )
        {
            getLog().info( getMessage( "processing", archive ) );
        }
        else if ( getLog().isDebugEnabled() )
        {
            getLog().debug( getMessage( "processing", archive ) );
        }

        JarSignerRequest request = createRequest( archive );
        request.setVerbose( verbose );
        request.setAlias( alias );
        request.setArchive( archive );
        request.setKeystore( keystore );
        request.setStoretype( storetype );
        request.setProviderArg( providerArg );
        request.setProviderClass( providerClass );
        request.setProviderName( providerName );
        request.setWorkingDirectory( workingDirectory );
        request.setMaxMemory( maxMemory );
        request.setProtectedAuthenticationPath( protectedAuthenticationPath );

        // Preserves 'file.encoding' the plugin is executed with.
        final List<String> additionalArguments = new ArrayList<>();

        boolean fileEncodingSeen = false;

        if ( this.arguments != null )
        {
            for ( final String argument : this.arguments )
            {
                if ( argument.trim().startsWith( "-J-Dfile.encoding=" ) )
                {
                    fileEncodingSeen = true;
                }

                additionalArguments.add( argument );
            }
        }

        if ( !fileEncodingSeen )
        {
            additionalArguments.add( "-J-Dfile.encoding=" + ReaderFactory.FILE_ENCODING );
        }

        // Adds proxy information.
        if ( this.settings != null && this.settings.getActiveProxy() != null
                 && StringUtils.isNotEmpty( this.settings.getActiveProxy().getHost() ) )
        {
            additionalArguments.add( "-J-Dhttp.proxyHost=" + this.settings.getActiveProxy().getHost() );
            additionalArguments.add( "-J-Dhttps.proxyHost=" + this.settings.getActiveProxy().getHost() );
            additionalArguments.add( "-J-Dftp.proxyHost=" + this.settings.getActiveProxy().getHost() );

            if ( this.settings.getActiveProxy().getPort() > 0 )
            {
                additionalArguments.add( "-J-Dhttp.proxyPort=" + this.settings.getActiveProxy().getPort() );
                additionalArguments.add( "-J-Dhttps.proxyPort=" + this.settings.getActiveProxy().getPort() );
                additionalArguments.add( "-J-Dftp.proxyPort=" + this.settings.getActiveProxy().getPort() );
            }

            if ( StringUtils.isNotEmpty( this.settings.getActiveProxy().getNonProxyHosts() ) )
            {
                additionalArguments.add( "-J-Dhttp.nonProxyHosts=\""
                                             + this.settings.getActiveProxy().getNonProxyHosts() + "\"" );

                additionalArguments.add( "-J-Dftp.nonProxyHosts=\""
                                             + this.settings.getActiveProxy().getNonProxyHosts() + "\"" );

            }
        }

        request.setArguments( !additionalArguments.isEmpty()
                                  ? additionalArguments.toArray( new String[ additionalArguments.size() ] )
                                  : null );

        // Special handling for passwords through the Maven Security Dispatcher
        request.setStorepass( decrypt( storepass ) );

        try
        {
            sign( jarSigner, request, maxTries );
        }
        catch ( JavaToolException e )
        {
            throw new MojoExecutionException( getMessage( "commandLineException", e.getMessage() ), e );
        }
    }

    /**
     * Attempts signing with a maximum number of maxTries times. If all attempts fail, MojoExecutionException is thrown.
     * If java tool invocation could not be created, a JavaToolException will be thrown.
     *
     * @param jarSigner the JarSigner
     * @param request the JarSignerRequest
     * @param maxTries a positive integer
     * @throws JavaToolException
     * @throws MojoExecutionException
     */
    void sign( JarSigner jarSigner, JarSignerRequest request, int maxTries ) 
            throws JavaToolException, MojoExecutionException
    {
        Commandline commandLine = null;
        int resultCode = 0;
        for ( int attempt = 0; attempt < maxTries; attempt++ )
        {
            JavaToolResult result = jarSigner.execute( request );
            resultCode = result.getExitCode();
            commandLine = result.getCommandline();
            if ( resultCode == 0 )
            {
                return;
            }
        }
        throw new MojoExecutionException( getMessage( "failure", getCommandlineInfo( commandLine ), resultCode ) );
    }

    protected String decrypt( String encoded )
        throws MojoExecutionException
    {
        try
        {
            return securityDispatcher.decrypt( encoded );
        }
        catch ( SecDispatcherException e )
        {
            getLog().error( "error using security dispatcher: " + e.getMessage(), e );
            throw new MojoExecutionException( "error using security dispatcher: " + e.getMessage(), e );
        }
    }

    /**
     * Gets a message for a given key from the resource bundle backing the implementation.
     *
     * @param key The key of the message to return.
     * @param args Arguments to format the message with or {@code null}.
     * @return The message with key {@code key} from the resource bundle backing the implementation.
     * @throws NullPointerException if {@code key} is {@code null}.
     * @throws java.util.MissingResourceException
     *             if there is no message available matching {@code key} or accessing
     *             the resource bundle fails.
     */
    private String getMessage( final String key, final Object[] args )
    {
        if ( key == null )
        {
            throw new NullPointerException( "key" );
        }

        return new MessageFormat( ResourceBundle.getBundle( "jarsigner" ).getString( key ) ).format( args );
    }

    private String getMessage( final String key )
    {
        return getMessage( key, null );
    }

    String getMessage( final String key, final Object arg )
    {
        return getMessage( key, new Object[] { arg } );
    }

    private String getMessage( final String key, final Object arg1, final Object arg2 )
    {
        return getMessage( key, new Object[] { arg1, arg2 } );
    }

    /**
     * the part with ToolchainManager lookup once we depend on
     * 2.0.9 (have it as prerequisite). Define as regular component field then.
     * hint: check maven-compiler-plugin code
     *
     * @return Toolchain instance
     */
    private Toolchain getToolchain()
    {
        Toolchain tc = null;
        if ( toolchainManager != null )
        {
            tc = toolchainManager.getToolchainFromBuildContext( "jdk", session );
        }

        return tc;
    }
}
