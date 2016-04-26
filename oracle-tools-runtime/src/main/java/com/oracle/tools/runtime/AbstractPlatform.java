/*
 * File: AbstractPlatform.java
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

package com.oracle.tools.runtime;

import com.oracle.tools.Option;
import com.oracle.tools.Options;
import com.oracle.tools.extensible.AbstractExtensible;
import com.oracle.tools.runtime.options.ApplicationType;
import com.oracle.tools.runtime.options.MetaClass;

/**
 * An abstract implementation of a {@link Platform}.
 * <p>
 * Copyright (c) 2014. All Rights Reserved. Oracle Corporation.<br>
 * Oracle is a registered trademark of Oracle Corporation and/or its affiliates.
 *
 * @author Jonathan Knight
 *
 * @param <P>  the type of {@link Platform} used by {@link ApplicationLauncher}s
 */
public abstract class AbstractPlatform<P extends Platform> extends AbstractExtensible implements Platform
{
    /**
     * The name of this {@link Platform}.
     */
    private String name;

    /**
     * The {@link Options} for the {@link Platform}.
     */
    private Options options;


    /**
     * Construct an {@link AbstractPlatform} with the specified name.
     *
     * @param name     the name of this {@link Platform}
     * @param options  the {@link Option}s for the {@link Platform}
     */
    public AbstractPlatform(String    name,
                            Option... options)
    {
        super();

        this.name    = name;
        this.options = new Options(options);
    }


    @Override
    public String getName()
    {
        return name;
    }


    @Override
    public Options getOptions()
    {
        return options;
    }


    @Override
    public <A extends Application> A launch(Option... options)
    {
        // establish the initial launch options based on those defined by the platform
        Options launchOptions = new Options(getOptions().asArray());

        // include the options specified when this method was called
        launchOptions.addAll(options);

        // obtain the application type
        ApplicationType<A> applicationType = launchOptions.get(ApplicationType.class);

        // attempt to locate the meta-class using the launchOptions
        MetaClass metaClass = applicationType.getMetaClass(launchOptions);

        // obtain the application launcher for the class of application
        ApplicationLauncher<A> launcher = getApplicationLauncher(applicationType.getType(),
                                                                 metaClass,
                                                                 launchOptions);

        if (launcher == null)
        {
            throw new IllegalArgumentException("Can't determine ApplicationLauncher for " + applicationType
                                               + " using " + launchOptions);
        }
        else
        {
            // add the meta-class and application class as options
            Options builderOptions = new Options(options);

            // add the application class as a launcher option (if and only if it's not already defined)
            builderOptions.addIfAbsent(applicationType);

            // add the meta-class as a launcher option (if and only if it's not already defined)
            builderOptions.addIfAbsent(metaClass);

            // now launch the application
            return launcher.launch(this, builderOptions);
        }
    }


    /**
     * Obtains the {@link ApplicationLauncher} for the given {@link Class} of {@link Application} with the provided
     * {@link MetaClass} and launch {@link Options}.
     *
     * @param applicationClass  the {@link Class} of {@link Application}
     * @param metaClass         the {@link MetaClass} for the {@link Application}
     * @param options           the launch {@link Options} for the {@link Application}
     *
     * @param <A>               the type of the {@link Application}
     * @param <B>               the type of the {@link ApplicationLauncher}
     *
     * @throws UnsupportedOperationException  when an {@link ApplicationLauncher} for the specified {@link Application}
     *                                        {@link Class} and {@link MetaClass} with the provided {@link Options}
     *                                        is unavailable and/or unsupported
     * @return  an {@link ApplicationLauncher} capable of launching the {@link Class} of {@link Application}
     */
    abstract protected <A extends Application,
                        B extends ApplicationLauncher<A>> B getApplicationLauncher(Class<A>     applicationClass,
                                                                                   MetaClass<A> metaClass,
                                                                                   Options      options)
                                                                                   throws UnsupportedOperationException;
}
