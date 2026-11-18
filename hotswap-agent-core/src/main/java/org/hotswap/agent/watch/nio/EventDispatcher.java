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

import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.watch.WatchEventListener;
import org.hotswap.agent.watch.WatchFileEvent;

/**
 * The EventDispatcher holds a queue of all events collected by the watcher but
 * not yet processed. It runs on its own thread and is responsible for calling
 * all the registered listeners.
 *
 * Since file system events can spawn too fast, this implementation works as
 * buffer for fast spawning events. The watcher is now responsible for
 * collecting and pushing events in this queue.
 *
 */
public class EventDispatcher implements Runnable {

    /** The logger. */
    protected AgentLogger LOGGER = AgentLogger.getLogger(this.getClass());

    /**
     * The Class Event.
     */
    static class Event {

        /** The event. */
        final WatchEvent<Path> event;

        /** The path. */
        final Path path;

        /**
         * Instantiates a new event.
         *
         * @param event
         *            the event
         * @param path
         *            the path
         */
        public Event(WatchEvent<Path> event, Path path) {
            super();
            this.event = event;
            this.path = path;
        }
    }

    /** The map of listeners.  This is managed by the watcher service*/
    private final Map<Path, List<WatchEventListener>> listeners;

    /** The runnable. */
    private volatile Thread runnable = null;

    private volatile boolean working = false;

    /**
     * Instantiates a new event dispatcher.
     *
     * @param listeners
     *            the listeners
     */
    public EventDispatcher(Map<Path, List<WatchEventListener>> listeners) {
        super();
        this.listeners = listeners;
    }

    /** The event queue. */
    private final ArrayBlockingQueue<Event> eventQueue = new ArrayBlockingQueue<>(500);

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        try {
            while (working) {
                Event event = null;
                try {
                    event = eventQueue.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (working) {
                        LOGGER.error("Event dispatcher interrupted unexpectedly", e);
                    }
                    return;
                }
                if (event != null) {
                    callListeners(event.event, event.path);
                }
            }
        } finally {
            eventQueue.clear();
            synchronized (this) {
                if (runnable == Thread.currentThread()) {
                    runnable = null;
                }
            }
        }
    }

    /**
     * Adds the.
     *
     * @param event
     *            the event
     * @param path
     *            the path
     */
    public void add(WatchEvent<Path> event, Path path) {
        if (working) {
            eventQueue.offer(new Event(event, path));
        }
    }

    /**
     * Call the listeners.
     * Listeners are organized per path in a Map. The number of paths is low so a simple iteration should be fast enough.
     *
     * @param event
     *            the event
     * @param path
     *            the path
     */
    // notify listeners about new event
    private void callListeners(final WatchEvent<?> event, final Path path) {
        List<WatchEventListener> matchingListeners = new ArrayList<>();
        synchronized (listeners) {
            for (Map.Entry<Path, List<WatchEventListener>> entry : listeners.entrySet()) {
                if (path.startsWith(entry.getKey())) {
                    matchingListeners.addAll(entry.getValue());
                }
            }
        }

        if (matchingListeners.isEmpty()) {
            LOGGER.error("No match for  watch event '{}',  path '{}'", event, path);
            return;
        }

        for (WatchEventListener listener : matchingListeners) {
            WatchFileEvent agentEvent = new HotswapWatchFileEvent(event, path);
            try {
                listener.onEvent(agentEvent);
            } catch (Throwable e) {
                LOGGER.error("Error in watch event '{}' listener '{}'", e, agentEvent, listener);
            }
        }
    }

    /**
     * Start.
     */
    public synchronized void start() {
        if (working || runnable != null) {
            return;
        }
        working = true;
        runnable = new Thread(this);
        runnable.setDaemon(true);
        runnable.setName("HotSwap Dispatcher");
        runnable.start();
    }

    /**
     * Stop.
     *
     * @throws InterruptedException
     *             the interrupted exception
     */
    public void stop() throws InterruptedException {
        Thread thread;
        synchronized (this) {
            working = false;
            thread = runnable;
            if (thread != null) {
                thread.interrupt();
            } else {
                eventQueue.clear();
            }
        }

        if (thread != null && thread != Thread.currentThread()) {
            thread.join();
        }

        synchronized (this) {
            if (runnable == thread) {
                runnable = null;
            }
        }
    }
}
