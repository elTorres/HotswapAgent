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
package org.hotswap.agent.watch.nio;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.logging.AgentLogger.Level;
import org.hotswap.agent.watch.WatchEventListener;
import org.hotswap.agent.watch.Watcher;

/**
 * NIO2 watcher implementation for systems which support
 * ExtendedWatchEventModifier.FILE_TREE
 * <p/>
 * Java 7 (NIO2) watch a directory (or tree) for changes to files.
 * <p/>
 * By http://docs.oracle.com/javase/tutorial/essential/io/examples/WatchDir.java
 *
 * @author Jiri Bubnik
 * @author alpapad@gmail.com
 */
public abstract class AbstractNIO2Watcher implements Watcher {
    private enum State {
        CREATED,
        RUNNING,
        STOPPED
    }

    protected AgentLogger LOGGER = AgentLogger.getLogger(this.getClass());

    protected final static WatchEvent.Kind<?>[] KINDS = new WatchEvent.Kind<?>[] { ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY };

    protected WatchService watcher;
    protected final Map<WatchKey, Path> keys;
    private final Map<Path, List<WatchEventListener>> listeners = new ConcurrentHashMap<>();

    // keep track about which classloader requested which event
    protected Map<WatchEventListener, ClassLoader> classLoaderListeners = new ConcurrentHashMap<>();

    private Thread runner;

    private State state = State.CREATED;

    protected final EventDispatcher dispatcher;

    public AbstractNIO2Watcher() throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.keys = new ConcurrentHashMap<>();
        dispatcher = new EventDispatcher(listeners);
    }

    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>) event;
    }

    /**
     * Registers a listener atomically with its directory watches.
     *
     * @throws IllegalStateException if this watcher has already been stopped
     */
    @Override
    public void addEventListener(ClassLoader classLoader, URI pathPrefix, WatchEventListener listener) {
        synchronized (listeners) {
            ensureActive();

            File path;
            try {
                // check that it is regular file
                // toString() is weird and solves HiarchicalUriException for URI
                // like "file:./src/resources/file.txt".
                path = new File(pathPrefix);
            } catch (IllegalArgumentException e) {
                if (!LOGGER.isLevelEnabled(Level.TRACE)) {
                    LOGGER.warning("Unable to watch for path {}, not a local regular file or directory.", pathPrefix);
                } else {
                    LOGGER.trace("Unable to watch for path {} exception", e, pathPrefix);
                }
                return;
            }

            try {
                addDirectory(path.toPath());
            } catch (IOException e) {
                if (!LOGGER.isLevelEnabled(Level.TRACE)) {
                    LOGGER.warning("Unable to watch for path {}, not a local regular file or directory.", pathPrefix);
                } else {
                    LOGGER.trace("Unable to watch path with prefix '{}' for changes.", e, pathPrefix);
                }
                return;
            }

            Path pathKey = Paths.get(pathPrefix);
            List<WatchEventListener> list = listeners.get(pathKey);
            if (list == null) {
                list = new ArrayList<WatchEventListener>();
                listeners.put(pathKey, list);
            }
            list.add(listener);

            if (classLoader != null) {
                classLoaderListeners.put(listener, classLoader);
            }
        }
    }

    /**
     * Registers a listener atomically with its directory watches.
     *
     * @throws IllegalStateException if this watcher has already been stopped
     */
    @Override
    public void addEventListener(ClassLoader classLoader, URL pathPrefix, WatchEventListener listener) {
        synchronized (listeners) {
            ensureActive();
        }
        if (pathPrefix == null) {
            return;
        }

        try {
            addEventListener(classLoader, pathPrefix.toURI(), listener);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Unable to convert URL to URI " + pathPrefix, e);
        }
    }

    /**
     * Removes only listeners owned by the given classloader. Empty listener
     * sets release their watch keys, but not the shared watch service.
     *
     * @param classLoader classloader to remove; {@code null} is ignored
     */
    @Override
    public void closeClassLoader(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }

        synchronized (listeners) {
            for (Iterator<Entry<WatchEventListener, ClassLoader>> entryIterator =
                    classLoaderListeners.entrySet().iterator(); entryIterator.hasNext();) {
                Entry<WatchEventListener, ClassLoader> entry = entryIterator.next();
                if (entry.getValue().equals(classLoader)) {
                    entryIterator.remove();
                    removeListener(entry.getKey());
                }
            }

            if (listeners.isEmpty()) {
                for (Iterator<WatchKey> iterator = keys.keySet().iterator(); iterator.hasNext();) {
                    WatchKey key = iterator.next();
                    key.cancel();
                    iterator.remove();
                }
            }
        }
        LOGGER.debug("All watch listeners removed for classLoader {}", classLoader);
    }

    private void removeListener(WatchEventListener listener) {
        for (Iterator<Entry<Path, List<WatchEventListener>>> iterator = listeners.entrySet().iterator();
                iterator.hasNext();) {
            Entry<Path, List<WatchEventListener>> entry = iterator.next();
            List<WatchEventListener> pathListeners = entry.getValue();
            if (pathListeners != null) {
                pathListeners.remove(listener);
                if (pathListeners.isEmpty()) {
                    iterator.remove();
                }
            } else {
                iterator.remove();
            }
        }
    }

    private void cancelAndClearRegistrations() {
        for (Iterator<WatchKey> iterator = keys.keySet().iterator(); iterator.hasNext();) {
            iterator.next().cancel();
            iterator.remove();
        }
        listeners.clear();
        classLoaderListeners.clear();
    }

    private void ensureActive() {
        if (state == State.STOPPED) {
            throw new IllegalStateException("Watcher has been stopped and cannot be reused");
        }
    }

    /**
     * Registers the given directory.
     *
     * @param path directory to register
     * @throws IOException if registration fails
     * @throws IllegalStateException if the watcher has been stopped
     */
    public void addDirectory(Path path) throws IOException {
        synchronized (listeners) {
            ensureActive();
            registerAll(path);
        }
    }

    protected abstract void registerAll(final Path dir) throws IOException;

    /**
     * Process all events for one key queued to the watcher.
     *
     * @return true while the watcher remains active
     * @throws InterruptedException if the watcher thread is interrupted
     */
    private boolean processEvents() throws InterruptedException {
        WatchKey key = watcher.poll(10, TimeUnit.MILLISECONDS);
        if (key == null) {
            return isActive();
        }

        synchronized (listeners) {
            if (state == State.STOPPED) {
                return false;
            }

            Path dir = keys.get(key);
            if (dir == null || !key.isValid()) {
                keys.remove(key);
                if (dir == null) {
                    LOGGER.warning("WatchKey '{}' not recognized or no longer valid", key);
                }
                return true;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == OVERFLOW) {
                    LOGGER.warning("WatchKey '{}' overflowed", key);
                    continue;
                }

                WatchEvent<Path> ev = cast(event);
                Path name = ev.context();
                Path child = dir.resolve(name);

                LOGGER.debug("Watch event '{}' on '{}' --> {}", event.kind().name(), child, name);

                dispatcher.add(ev, child);

                if (kind == ENTRY_CREATE) {
                    try {
                        if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                            registerAll(child);
                        }
                    } catch (IOException x) {
                        LOGGER.warning("Unable to register events for directory {}", x, child);
                    }
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                LOGGER.warning("Watcher on {} not valid, removing path", dir);
                keys.remove(key);
            }
            return true;
        }
    }

    private boolean isActive() {
        synchronized (listeners) {
            return state == State.RUNNING;
        }
    }

    @Override
    public void run() {
        synchronized (listeners) {
            ensureActive();
            if (state == State.RUNNING) {
                return;
            }

            state = State.RUNNING;
            runner = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (processEvents()) {
                            // Continue until the watcher is stopped or fails.
                        }
                    } catch (ClosedWatchServiceException e) {
                        if (isActive()) {
                            LOGGER.error("Watch service closed unexpectedly", e);
                            stop();
                        }
                    } catch (InterruptedException e) {
                        if (isActive()) {
                            LOGGER.error("Watch consumer interrupted unexpectedly", e);
                            stop();
                        }
                    } catch (RuntimeException e) {
                        if (isActive()) {
                            LOGGER.error("Unexpected watch consumer failure", e);
                            stop();
                        }
                    }
                }
            });
            runner.setDaemon(true);
            runner.setName("HotSwap Watcher");
            dispatcher.start();
            runner.start();
        }
    }

    /**
     * Stops this watcher terminally. Watch registrations are cleared and the
     * shared watch service is closed after the listener monitor is released.
     * Worker joins avoid waiting on the current callback thread.
     */
    @Override
    public void stop() {
        boolean closeService;
        Thread runnerToJoin;
        synchronized (listeners) {
            closeService = state != State.STOPPED;
            state = State.STOPPED;
            cancelAndClearRegistrations();
            runnerToJoin = runner;
        }

        if (closeService) {
            try {
                watcher.close();
            } catch (IOException e) {
                LOGGER.error("Unable to close watch service", e);
            }
        }

        if (runnerToJoin != null && runnerToJoin != Thread.currentThread()) {
            runnerToJoin.interrupt();
        }

        if (runnerToJoin != null && runnerToJoin != Thread.currentThread()) {
            try {
                runnerToJoin.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warning("Interrupted while waiting for watch consumer to stop", e);
            }
        }

        try {
            dispatcher.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Interrupted while waiting for watcher dispatcher to stop", e);
        }
    }

    /**
     * Get a Watch event modifier. These are platform specific and hiden in sun api's
     *
     * @see <a href="https://github.com/HotswapProjects/HotswapAgent/issues/41">
     *      Issue#41</a>
     * @see <a href=
     *      "http://stackoverflow.com/questions/9588737/is-java-7-watchservice-slow-for-anyone-else">
     *      Is Java 7 WatchService Slow for Anyone Else?</a>
     */
    static WatchEvent.Modifier getWatchEventModifier(String claz, String field) {
        try {
            Class<?> c = Class.forName(claz);
            Field f = c.getField(field);
            return (WatchEvent.Modifier) f.get(c);
        } catch (Exception e) {
            return null;
        }
    }
}