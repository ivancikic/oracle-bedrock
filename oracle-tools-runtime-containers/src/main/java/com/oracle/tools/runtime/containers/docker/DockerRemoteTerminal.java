/*
 * File: DockerRemoteTerminal.java
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * The contents of this file are subject to the terms and conditions of
 * the Common Development and Distribution License 1.0 (the "License").
 *
 * You may not use this file except in compliance with the License.
 *
 * You can obtain a copy of the License by consulting the LICENSE.txt file
 * distributed with this file, or by consulting https://oss.oracle.com/licenses/CDDL
 *
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file LICENSE.txt.
 *
 * MODIFICATIONS:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 */

package com.oracle.tools.runtime.containers.docker;

import com.oracle.tools.Option;
import com.oracle.tools.Options;
import com.oracle.tools.extensible.AbstractExtensible;
import com.oracle.tools.extensible.Feature;
import com.oracle.tools.io.FileHelper;
import com.oracle.tools.lang.StringHelper;
import com.oracle.tools.options.Timeout;
import com.oracle.tools.options.Variable;
import com.oracle.tools.runtime.Application;
import com.oracle.tools.runtime.ApplicationProcess;
import com.oracle.tools.runtime.MetaClass;
import com.oracle.tools.runtime.Platform;
import com.oracle.tools.runtime.Profile;
import com.oracle.tools.runtime.console.Console;
import com.oracle.tools.runtime.console.EventsApplicationConsole;
import com.oracle.tools.runtime.console.NullApplicationConsole;
import com.oracle.tools.runtime.containers.docker.commands.Build;
import com.oracle.tools.runtime.containers.docker.commands.Events;
import com.oracle.tools.runtime.containers.docker.commands.Kill;
import com.oracle.tools.runtime.containers.docker.commands.Remove;
import com.oracle.tools.runtime.containers.docker.commands.Run;
import com.oracle.tools.runtime.containers.docker.options.ContainerCloseBehaviour;
import com.oracle.tools.runtime.containers.docker.options.DockerfileDeployer;
import com.oracle.tools.runtime.containers.docker.options.ImageCloseBehaviour;
import com.oracle.tools.runtime.java.ClassPathModifier;
import com.oracle.tools.runtime.options.Arguments;
import com.oracle.tools.runtime.options.Discriminator;
import com.oracle.tools.runtime.options.DisplayName;
import com.oracle.tools.runtime.options.PlatformSeparators;
import com.oracle.tools.runtime.options.Ports;
import com.oracle.tools.runtime.options.WorkingDirectory;
import com.oracle.tools.runtime.remote.DeploymentArtifact;
import com.oracle.tools.runtime.remote.RemoteApplicationProcess;
import com.oracle.tools.runtime.remote.RemoteTerminal;
import com.oracle.tools.runtime.remote.options.Deployer;
import com.oracle.tools.table.Table;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A specialized {@link RemoteTerminal} used to generate Docker images and
 * launch Docker containers from those images.
 * <p>
 * Copyright (c) 2016. All Rights Reserved. Oracle Corporation.<br>
 * Oracle is a registered trademark of Oracle Corporation and/or its affiliates.
 *
 * @author Jonathan Knight
 */
public class DockerRemoteTerminal implements RemoteTerminal, Deployer
{
    /**
     * The {@link Logger} to use for log messages.
     */
    private static Logger LOGGER = Logger.getLogger(DockerPlatform.class.getName());

    /**
     * The {@link Platform} to run commands on.
     */
    private Platform platform;

    /**
     * The deployer that will capture ADD commands for the Dockerfile.
     */
    private final DockerfileDeployer deployer;

    /**
     * The {@link List} of commands to add to the Dockerfile.
     */
    private final List<String> dockerFileCommands;

    /**
     * The temporary folder to use to create the Dockerfile.
     */
    private final File tmpFolder;


    /**
     * Construct a new {@link DockerRemoteTerminal}.
     *
     * @param platform  the {@link Platform} the commands will run on
     */
    public DockerRemoteTerminal(Platform platform)
    {
        try
        {
            this.platform           = platform;
            this.tmpFolder          = WorkingDirectory.temporaryDirectory().resolve(platform, Options.of());
            this.deployer           = new DockerfileDeployer(tmpFolder.getCanonicalPath());
            this.dockerFileCommands = new ArrayList<>();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }


    @Override
    public void deploy(List<DeploymentArtifact> artifactsToDeploy,
                       String                   remoteDirectory,
                       Platform                 platform,
                       Option...                deploymentOptions)
    {
        try
        {
            Files.createDirectories(tmpFolder.toPath());
            LOGGER.log(Level.INFO, "Deploying to " + tmpFolder);
            this.deployer.deploy(artifactsToDeploy, remoteDirectory, platform, deploymentOptions);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Error deploying artifacts", e);
        }
    }


    /**
     * Write any mkdir commands to the Dockerfile
     *
     * @param directoryName  the directory to create
     * @param options        the {@link Options} controlling
     *                       the build of the Dockerfile
     */
    @Override
    public void makeDirectories(String  directoryName,
                                Options options)
    {
        dockerFileCommands.add("RUN mkdir -p " + directoryName);
    }


    @Override
    public RemoteApplicationProcess launch(Launchable                   launchable,
                                           Class<? extends Application> applicationClass,
                                           Options                      options)
    {
        String imageTag      = UUID.randomUUID().toString();
        String containerName = UUID.randomUUID().toString();
        Docker docker        = options.get(Docker.class);
        String baseImage     = docker.getBaseImage(applicationClass);

        if (baseImage == null || baseImage.trim().isEmpty())
        {
            throw new RuntimeException("Cannot find a suitable base image for application class " + applicationClass);
        }

        try
        {
            InetAddress localAddress = docker.getValidLocalAddress();

            options.add(Variable.with("local.address", localAddress.getHostAddress()));

            Files.createDirectories(tmpFolder.toPath());

            // Write the Dockerfile
            File dockerFile = writeDockerFile(launchable, baseImage, options);

            // build image
            DockerImage image = createImage(imageTag, dockerFile, docker, options);

            // run the container
            ApplicationProcess containerProcess = runContainer(containerName, launchable, image, docker, options);

            if (containerProcess instanceof RemoteApplicationProcess)
            {
                return (RemoteApplicationProcess) containerProcess;
            }

            return new WrapperRemoteApplicationProcess(containerProcess);
        }
        catch (Exception e)
        {
            LOGGER.log(Level.SEVERE,
                       "An error occurred. Attempting to kill and remove container " + containerName
                       + " and remove image " + imageTag);

            safelyRemoveContainer(containerName, docker);
            safelyRemoveImage(imageTag, docker);

            throw new RuntimeException("An error occurred launching the application inside Docker", e);
        }
        finally
        {
            FileHelper.recursiveDelete(tmpFolder);
        }
    }


    /**
     * Write a Dockerfile that can be used to run the application.
     *
     * @param launchable  the {@link RemoteTerminal.Launchable} to use
     * @param baseImage   the name of the base image to use in the Dockerfile FROM statement
     * @param options     the {@link Options} to use
     *
     * @return  the {@link File} representing the Dockerfile created
     *
     * @throws IOException  if there is an error writing the Dockerfile
     */
    protected File writeDockerFile(Launchable launchable,
                                   String     baseImage,
                                   Options    options) throws IOException
    {
        WorkingDirectory workingDirectory     = options.get(WorkingDirectory.class);
        File             workingDirectoryFile = workingDirectory.resolve(platform, options);
        String           dockerFileName       = "Dockerfile";
        File             dockerFile           = new File(tmpFolder, dockerFileName);
        Properties       variables            = launchable.getEnvironmentVariables(platform, options);

        for (String variableName : variables.stringPropertyNames())
        {
            String value = StringHelper.doubleQuoteIfNecessary(variables.getProperty(variableName));

            dockerFileCommands.add(String.format("ENV %s=%s", variableName, value));
        }

        dockerFileCommands.add("WORKDIR " + workingDirectoryFile);

        try (PrintWriter writer = new PrintWriter(dockerFile))
        {
            writer.println("# ------------------------------------------------------------------------");
            writer.println("# Automatically generated Dockerfile");
            writer.println("# ------------------------------------------------------------------------");

            writer.printf("FROM %s\n\n", baseImage);

            dockerFileCommands.forEach((cmd) -> writer.printf("%s\n\n", cmd));

            writer.println();

            deployer.write(writer);
        }

        if (LOGGER.isLoggable(Level.INFO))
        {
            Table diagnosticsTable = new Table();

            Files.readAllLines(dockerFile.toPath()).forEach(diagnosticsTable::addRow);

            LOGGER.log(Level.INFO,
                       "Oracle Tools Diagnostics: Created Dockerfile for Application...\n"
                       + "------------------------------------------------------------------------\n"
                       + diagnosticsTable.toString() + "\n"
                       + "------------------------------------------------------------------------\n");
        }

        return dockerFile;
    }


    /**
     * Create a Docker image.
     * <p>
     * The image will contain all of the required artifacts to run the application.
     * The image will be tagged with a random UUID.
     *
     * @param imageTag    the tag to apply to the image
     * @param dockerFile  the Dockerfile to use to build the image
     * @param docker      the {@link Docker} environment to use
     * @param options     the {@link Options} to use
     *
     * @return  a {@link DockerImage} representing the built image
     */
    protected DockerImage createImage(String  imageTag,
                                      File    dockerFile,
                                      Docker  docker,
                                      Options options)
    {
        LOGGER.log(Level.INFO, "Building Docker Image...");

        DisplayName displayName    = options.getOrDefault(DisplayName.class, DisplayName.of(""));
        String      dockerFileName = dockerFile.getName();
        Timeout     timeout        = options.getOrDefault(Timeout.class, Build.DEFAULT_TIMEOUT);

        try (Application application =
            platform.launch(Build.fromDockerFile(dockerFileName).withTags(imageTag).labels("oracle.tools.image=true")
            .timeoutAfter(timeout),
                            displayName,
                            Discriminator.of("Image"),
                            docker,
                            ImageCloseBehaviour.none(),
                            WorkingDirectory.at(tmpFolder)))
        {
            if (application.waitFor(timeout) != 0)
            {
                // If there is a failure attempt to remove the image, just in case it was actually created
                String msg = "An error occurred, build returned " + application.exitValue();

                LOGGER.log(Level.SEVERE, msg + ". Attempting to remove image " + imageTag);

                safelyRemoveImage(imageTag, docker);

                throw new RuntimeException(msg);
            }

            DockerImage image = application.get(DockerImage.class);

            if (LOGGER.isLoggable(Level.INFO))
            {
                LOGGER.log(Level.INFO, "Built Docker Image: " + imageTag);
            }

            return image;
        }
    }


    /**
     * Run a container using the specified image.
     *
     * @param containerName  the name of the container
     * @param launchable     the {@link RemoteTerminal.Launchable} that will give the command to execute
     * @param image          the image to use to run the container
     * @param docker         the {@link Docker} environment to use
     * @param options        the {@link Options} to use
     *
     * @return  a {@link DockerContainer} representing the running image
     */
    protected ApplicationProcess runContainer(String      containerName,
                                              Launchable  launchable,
                                              DockerImage image,
                                              Docker      docker,
                                              Options     options)
    {
        Timeout          timeout              = options.get(Timeout.class);
        WorkingDirectory workingDirectory     = options.get(WorkingDirectory.class);
        String           workingDirectoryName = workingDirectory.resolve(platform, options).toString();

        options.add(PlatformSeparators.forUnix());
        options.add(new CPModifier(workingDirectoryName));

        // ----- give the container a random UUID as a name -----
        DisplayName displayName = options.getOrDefault(DisplayName.class, DisplayName.of("Container"));

        // ----- create the arguments to pass to the container as the command to execute
        String    command       = launchable.getCommandToExecute(platform, options);
        List<?>   args          = launchable.getCommandLineArguments(platform, options);
        Arguments containerArgs = Arguments.of(command).with(args);

        // ----- get any captured ports to map -----
        Ports         ports    = options.get(Ports.class);
        List<Integer> portList = ports.getPorts().stream().map(Ports.Port::getActualPort).collect(Collectors.toList());

        // ----- create the Run command -----
        Run runCommand = Run.image(image,
                                   containerName).interactive().hostName(containerName)
                                   .env(launchable.getEnvironmentVariables(platform,
                                                                           options)).publish(portList).autoRemove();

        Options containerOptions = new Options(options).addAll(displayName,
                                                               docker,
                                                               WorkingDirectory.at(tmpFolder),
                                                               ContainerCloseBehaviour.none(),
                                                               ImageCloseBehaviour.remove(),
                                                               containerArgs);

        // ----- start the application to capture Docker events so that we know when the container is in the running state -----
        EventsApplicationConsole.CountDownListener latch     = new EventsApplicationConsole.CountDownListener(1);
        Predicate<String>                          predicate = (line) -> line.contains("container start");
        EventsApplicationConsole eventConsole = new EventsApplicationConsole().withStdOutListener(predicate, latch);

        try (Application events = platform.launch(Events.fromContainer(containerName),
                                                  docker,
                                                  Console.of(eventConsole)))
        {
            // ----- launch the container -----
            // TODO: is this correct? the MetaClass probably needs to be passed in
            MetaClass metaClass = new Application.MetaClass();

            ContainerApplication application = platform.launch(new ContainerMetaClass(runCommand, metaClass),
                                                               containerOptions.asArray());

            // ----- get the container feature from the application -----
            DockerContainer      container = application.get(DockerContainer.class);
            FeatureAddingProfile profile   = new FeatureAddingProfile(image, container);

            // ----- add the container and default close behaviour to the options
            options.add(profile);
            options.add(ImageCloseBehaviour.remove());

            // ----- wait for the container state to be running -----

            try
            {
                if (!latch.await(timeout.to(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS))
                {
                    throw new RuntimeException("Failed to detect container start event within " + timeout);
                }
            }
            catch (InterruptedException e)
            {
                // ignored
            }

            // ----- obtain the port mappings from the container -----
            JsonArray  json    = (JsonArray) container.inspect();
            JsonObject jsonNet = json.getJsonObject(0).getJsonObject("NetworkSettings");

            if (!jsonNet.get("Ports").getValueType().equals(JsonValue.ValueType.NULL))
            {
                JsonObject       jsonPorts   = jsonNet.getJsonObject("Ports");

                List<Ports.Port> mappedPorts = ports.getPorts().stream().map((port) -> {
                                                       String key = port.getActualPort() + "/tcp";
                                                       String hostPort =
                                                           jsonPorts.getJsonArray(key).getJsonObject(0)
                                                           .getString("HostPort");

                                                       return new Ports.Port(port.getName(),
                                                                             port.getActualPort(),
                                                                             Integer.parseInt(hostPort));
                                                   }).collect(Collectors.toList());

                // ----- update the options with the correctly mapped ports -----
                options.remove(Ports.class);
                options.add(Ports.of(mappedPorts));
            }

            // ----- return the process from the container application -----
            return application.getProcess();
        }

    }


    /**
     * Attempt to remove the specified image ignoring any errors
     * that may occur.
     *
     * @param imageTag  the image to remove
     * @param docker    the {@link Docker} environment to use
     */
    private void safelyRemoveImage(String imageTag,
                                   Docker docker)
    {
        try
        {
            try (Application application = platform.launch(Remove.images(imageTag).force(),
                                                           docker,
                                                           NullApplicationConsole.builder()))
            {
                application.waitFor();
            }
        }
        catch (Exception e)
        {
            // we can ignore any error here
        }
    }


    /**
     * Attempt to remove the specified container ignoring any errors
     * that may occur.
     *
     * @param containerName  the container to remove
     * @param docker         the {@link Docker} environment to use
     */
    private void safelyRemoveContainer(String containerName,
                                       Docker docker)
    {
        try
        {
            try (Application application = platform.launch(Kill.containers(containerName),
                                                           docker,
                                                           NullApplicationConsole.builder()))
            {
                application.waitFor();
            }
        }
        catch (Exception e)
        {
            // we can ignore any error here
        }

        try
        {
            try (Application application = platform.launch(Remove.containers(containerName).force(),
                                                           docker,
                                                           NullApplicationConsole.builder()))
            {
                application.waitFor();
            }
        }
        catch (Exception e)
        {
            // we can ignore any error here
        }
    }


    /**
     * A {@link ClassPathModifier} that will replace
     * occurrences of "./:./*" with the actual working
     * directory name.
     */
    private class CPModifier extends ClassPathModifier
    {
        /**
         * The working directory.
         */
        private String workingDirectory;


        /**
         * Create a {@link CPModifier}.
         *
         * @param workingDirectory  the name of the working directory
         */
        CPModifier(String workingDirectory)
        {
            super(false);

            this.workingDirectory = workingDirectory;
        }


        @Override
        public String modify(String classPath)
        {
            String path     = super.modify(classPath);

            String modified = workingDirectory + "/:" + workingDirectory + "/*";

            return path.replace("./:./*", modified);
        }
    }


    /**
     * An implementation of an {@link Application} the sole purpose of which is
     * to be able to capture the {@link ApplicationProcess}.
     */
    public static class ContainerApplication extends AbstractExtensible implements Application
    {
        /**
         * The {@link Platform} on which the {@link Application} was launched.
         */
        private final Platform platform;

        /**
         * The underlying {@link ApplicationProcess} used to internally represent and
         * manage the {@link Application}.
         */
        private final ApplicationProcess process;

        /**
         * The {@link Options} used to launch the {@link Application}.
         */
        private final Options options;


        /**
         * Constructs a {@link ContainerApplication}
         *
         * @param platform  the {@link Platform} on which the {@link Application} was launched
         * @param process   the underlying {@link ApplicationProcess} representing the {@link Application}
         * @param options   the {@link Options} used to launch the {@link Application}
         */
        public ContainerApplication(Platform           platform,
                                    ApplicationProcess process,
                                    Options            options)
        {
            this.platform = platform;
            this.process  = process;
            this.options  = options;
        }


        /**
         * Obtain the {@link ApplicationProcess} representing
         * the {@link Application}.
         *
         * @return  the {@link ApplicationProcess} representing
         *          the {@link Application}
         */
        public ApplicationProcess getProcess()
        {
            return process;
        }


        @Override
        public void close()
        {
        }


        @Override
        public String getName()
        {
            return null;
        }


        @Override
        public Platform getPlatform()
        {
            return platform;
        }


        @Override
        public void close(Option... options)
        {
        }


        @Override
        public int waitFor(Option... options)
        {
            return 0;
        }


        @Override
        public int exitValue()
        {
            return 0;
        }


        @Override
        public long getId()
        {
            return 0;
        }


        @Override
        public Timeout getDefaultTimeout()
        {
            return null;
        }


        @Override
        public Options getOptions()
        {
            return options;
        }
    }


    /**
     * The {@link MetaClass} that is used when starting a Docker
     * container application.
     */
    public static class ContainerMetaClass implements MetaClass<ContainerApplication>
    {
        /**
         * The real {@link MetaClass} to proxy calls to.
         */
        private final MetaClass metaClass;

        /**
         * The {@link Run} command used to start the container.
         */
        private final Run runCommand;


        /**
         * Create a {@link ContainerMetaClass} that wraps the specified
         * {@link MetaClass}.
         *
         * @param metaClass  the {@link MetaClass} to wrap
         */
        public ContainerMetaClass(Run       runCommand,
                                  MetaClass metaClass)
        {
            this.runCommand = runCommand;
            this.metaClass  = metaClass;
        }


        @Override
        public Class<ContainerApplication> getImplementationClass(Platform platform,
                                                                  Options  options)
        {
            return ContainerApplication.class;
        }


        @Override
        public void onLaunching(Platform platform,
                                Options  options)
        {
            metaClass.onLaunching(platform, options);
            runCommand.onLaunching(platform, options);
        }


        @Override
        public void onLaunch(Platform platform,
                             Options  options)
        {
            metaClass.onLaunch(platform, options);
            runCommand.onLaunch(platform, options);
        }


        @Override
        public void onLaunched(Platform             platform,
                               ContainerApplication application,
                               Options              options)
        {
            metaClass.onLaunched(platform, application, options);
            runCommand.onLaunched(platform, application, options);
        }
    }


    /**
     * A {@link Profile} that is added to an {@link Application}s
     * {@link Options} and will when {@link #onLaunched(Platform, Application, Options)}
     * is called add various {@link Feature}s to the {@link Application}
     */
    private class FeatureAddingProfile implements Profile, Option
    {
        /**
         * The {@link Feature}s to ass
         */
        private Feature[] features;


        /**
         * Create a {@link FeatureAddingProfile}
         *
         * @param features  the {@link Feature}s to add
         */
        public FeatureAddingProfile(Feature... features)
        {
            this.features = features;
        }


        @Override
        public void onLaunching(Platform  platform,
                                MetaClass metaClass,
                                Options   options)
        {
            // there is nothing to do here
        }


        @Override
        public void onLaunched(Platform    platform,
                               Application application,
                               Options     options)
        {
            Arrays.stream(features).forEach(application::add);
        }


        @Override
        public void onClosing(Platform    platform,
                              Application application,
                              Options     options)
        {
            // there is nothing to do here
        }
    }


    /**
     * A class that wraps an {@link ApplicationProcess}
     * to make it look like a {@link RemoteApplicationProcess}.
     */
    private class WrapperRemoteApplicationProcess implements RemoteApplicationProcess
    {
        /**
         * The {@link ApplicationProcess} being wrapped.
         */
        private final ApplicationProcess process;


        /**
         * Create a {@link WrapperRemoteApplicationProcess} that wraps
         * the specified {@link ApplicationProcess}.
         *
         * @param process  the {@link ApplicationProcess} being wrapped.
         */
        public WrapperRemoteApplicationProcess(ApplicationProcess process)
        {
            this.process = process;
        }


        @Override
        public void close()
        {
            process.close();
        }


        @Override
        public long getId()
        {
            return process.getId();
        }


        @Override
        @Deprecated
        public void destroy()
        {
            process.close();
        }


        @Override
        public int exitValue()
        {
            return process.exitValue();
        }


        @Override
        public InputStream getErrorStream()
        {
            return process.getErrorStream();
        }


        @Override
        public InputStream getInputStream()
        {
            return process.getInputStream();
        }


        @Override
        public OutputStream getOutputStream()
        {
            return process.getOutputStream();
        }


        @Override
        public int waitFor(Option... options)
        {
            return process.waitFor(options);
        }
    }
}