/*
 * Copyright 2013-2019 the HotswapAgent authors.
 *
 * This file is part of HotswapAgent.
 *
 * HotswapAgent is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 2 of the License, or (at your
 * option) any later version.
 *
 * HotswapAgent is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with HotswapAgent. If not, see http://www.gnu.org/licenses/.
 */
package org.hotswap.agent.watch;

import java.net.URI;
import java.net.URL;

/**
 * Watches directory trees for file changes. An implementation keeps its
 * underlying watch resources available across application undeployments and
 * releases them only after {@link #stop()}.
 *
 * @author Jiri Bubnik
 */
public interface Watcher {
    /**
     * Register listeners on an event.
     *
     * @param classLoader the classloader to which this path is associated. May be null, but then this
     *                    listener will never be disassociated (even if application is undeployed)
     * @param pathPrefix where to listen
     * @param listener   the listener
     * @throws IllegalStateException if this watcher has already been stopped
     */
    void addEventListener(ClassLoader classLoader, URI pathPrefix, WatchEventListener listener);

    /**
     * Register listeners on an event.
     *
     * @param classLoader the classloader to which this path is associated. May be null, but then this
     *                    listener will never be disassociated (even if application is undeployed)
     * @param pathPrefix where to listen
     * @param listener   the listener
     * @throws IllegalStateException if this watcher has already been stopped
     */
    void addEventListener(ClassLoader classLoader, URL pathPrefix, WatchEventListener listener);

    /**
     * Removes listeners owned by a classloader without affecting other
     * applications or listeners registered with a {@code null} owner.
     * Permanent listeners and watch resources remain available until
     * {@link #stop()}.
     *
     * @param classLoader classloader to close; {@code null} does not identify
     *                    an owner and is ignored
     */
    void closeClassLoader(ClassLoader classLoader);


    /**
     * Starts the watcher agent threads. Repeated calls while the watcher is
     * running do not create additional threads.
     *
     * @throws IllegalStateException if this watcher has already been stopped
     */
    void run();

    /**
     * Stops this watcher terminally and releases its watch resources. The
     * method is idempotent and a stopped watcher cannot be started or receive
     * new listeners again.
     */
    void stop();
}
