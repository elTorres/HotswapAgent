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

import org.hotswap.agent.annotation.FileEvent;
import org.hotswap.agent.watch.WatchEventListener;
import org.hotswap.agent.watch.WatchFileEvent;
import org.hotswap.agent.watch.Watcher;
import org.hotswap.agent.watch.WatcherFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.ClosedWatchServiceException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Created by bubnik on 2.11.13.
 */
public class WatcherNIO2Test {
    private static final long TIMEOUT_SECONDS = 10;

    private Watcher watcher;
    private Path temp;

    @Before
    public void setup() throws IOException {
        watcher = new WatcherFactory().getWatcher();
        temp = Files.createTempDirectory("watcherNIO2Test");
        watcher.run();
    }

    @After
    public void tearDown() throws IOException {
        if (watcher != null) {
            watcher.stop();
        }
        deleteRecursively(temp);
    }

    @Test
    public void createFile() throws Exception {
        final CountDownLatch called = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        watcher.addEventListener(null, temp.toUri(), new WatchEventListener() {
            @Override
            public void onEvent(WatchFileEvent event) {
                try {
                    assertEquals("New file event type", FileEvent.CREATE, event.getEventType());
                    assertTrue("File name", event.getURI().toString().endsWith("test.class"));
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    called.countDown();
                }
            }
        });

        Files.createFile(temp.resolve("test.class"));

        await(called);
        assertNoFailure(failure);
    }

    @Test
    public void watchesSamePathAfterLastOwnerCloses() throws Exception {
        final ClassLoader firstOwner = new ClassLoader(null) {
        };
        final ClassLoader secondOwner = new ClassLoader(null) {
        };
        final AtomicInteger firstEvents = new AtomicInteger();
        final CountDownLatch firstEvent = new CountDownLatch(1);
        final CountDownLatch secondEvent = new CountDownLatch(1);

        watcher.addEventListener(firstOwner, temp.toUri(), countingListener(firstEvents, firstEvent));
        Files.createFile(temp.resolve("first.class"));
        await(firstEvent);

        watcher.closeClassLoader(firstOwner);
        watcher.addEventListener(secondOwner, temp.toUri(), countingListener(new AtomicInteger(), secondEvent));
        Files.createFile(temp.resolve("second.class"));
        await(secondEvent);

        assertEquals(1, firstEvents.get());
    }

    @Test
    public void closeClassLoaderKeepsOtherAndPermanentListeners() throws Exception {
        final ClassLoader firstOwner = new ClassLoader(null) {
        };
        final ClassLoader secondOwner = new ClassLoader(null) {
        };
        final AtomicInteger firstEvents = new AtomicInteger();
        final AtomicInteger secondEvents = new AtomicInteger();
        final AtomicInteger permanentEvents = new AtomicInteger();
        final CountDownLatch firstRound = new CountDownLatch(3);
        final CountDownLatch secondRound = new CountDownLatch(2);

        watcher.addEventListener(firstOwner, temp.toUri(),
                stagedListener(firstEvents, firstRound, secondRound));
        watcher.addEventListener(secondOwner, temp.toUri(),
                stagedListener(secondEvents, firstRound, secondRound));
        watcher.addEventListener(null, temp.toUri(),
                stagedListener(permanentEvents, firstRound, secondRound));

        Files.createFile(temp.resolve("first.class"));
        await(firstRound);

        watcher.closeClassLoader(null);
        watcher.closeClassLoader(new ClassLoader(null) {
        });
        watcher.closeClassLoader(firstOwner);
        watcher.closeClassLoader(firstOwner);
        Files.createFile(temp.resolve("second.class"));
        await(secondRound);

        assertEquals(1, firstEvents.get());
        assertEquals(2, secondEvents.get());
        assertEquals(2, permanentEvents.get());
    }

    @Test
    public void runTwiceKeepsTheSameConsumerThread() throws Exception {
        Thread firstRunner = runnerThread();

        watcher.run();

        assertSame(firstRunner, runnerThread());
        assertTrue(firstRunner.isAlive());
    }

    @Test
    public void stopIsTerminalAndIdempotent() throws Exception {
        watcher.stop();
        watcher.stop();

        expectIllegalState(new Runnable() {
            @Override
            public void run() {
                watcher.run();
            }
        });
        expectIllegalState(new Runnable() {
            @Override
            public void run() {
                watcher.addEventListener(null, temp.toUri(), noOpListener());
            }
        });
        expectIllegalState(new Runnable() {
            @Override
            public void run() {
                try {
                    watcher.addEventListener(null, temp.toUri().toURL(), noOpListener());
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        });
        expectIllegalState(new Runnable() {
            @Override
            public void run() {
                try {
                    ((AbstractNIO2Watcher) watcher).addDirectory(temp);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        });
    }

    @Test
    public void stopJoinsWatcherAndDispatcherThreads() throws Exception {
        Thread runner = runnerThread();
        Thread dispatcher = dispatcherThread();

        watcher.stop();

        assertFalse(runner.isAlive());
        assertFalse(dispatcher.isAlive());
    }

    @Test
    public void stopFromCallbackDoesNotWaitOnItsOwnThread() throws Exception {
        final CountDownLatch called = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread dispatcher = dispatcherThread();
        watcher.addEventListener(null, temp.toUri(), new WatchEventListener() {
            @Override
            public void onEvent(WatchFileEvent event) {
                try {
                    watcher.stop();
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    called.countDown();
                }
            }
        });

        Files.createFile(temp.resolve("stop.class"));

        await(called);
        dispatcher.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertNoFailure(failure);
        assertFalse(dispatcher.isAlive());
    }

    @Test
    public void invalidLastKeyDoesNotStopConsumer() throws Exception {
        ControlledWatchService service = new ControlledWatchService(false, false, false);
        ControlledWatcher controlled = replaceWithControlledWatcher(service);
        FakeWatchKey invalid = new FakeWatchKey(temp, false);
        controlled.addKey(invalid);
        service.offer(invalid);

        controlled.run();
        await(invalid.validityChecked);

        assertEquals(0, controlled.keyCount());
        assertTrue(runnerThread().isAlive());
        controlled.addEventListener(null, temp.toUri(), noOpListener());
    }

    @Test
    public void unexpectedWatchServiceCloseMakesWatcherTerminal() throws Exception {
        ControlledWatchService service = new ControlledWatchService(false, false, false);
        replaceWithControlledWatcher(service).run();
        await(service.pollEntered);

        service.closeExternally();

        await(service.closeAttempted);
        expectIllegalState(new Runnable() {
            @Override
            public void run() {
                watcher.addEventListener(null, temp.toUri(), noOpListener());
            }
        });
    }

    @Test
    public void closeFailureStillStopsRemainingWorkers() throws Exception {
        ControlledWatchService service = new ControlledWatchService(true, false, false);
        replaceWithControlledWatcher(service).run();

        watcher.stop();

        assertEquals(1, service.closeCalls.get());
        assertFalse(runnerThread().isAlive());
        expectIllegalState(new Runnable() {
            @Override
            public void run() {
                watcher.run();
            }
        });
    }

    @Test
    public void stopMarksTerminalBeforeWaitingForClose() throws Exception {
        ControlledWatchService service = new ControlledWatchService(false, true, false);
        replaceWithControlledWatcher(service).run();
        final AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        Thread stopper = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    watcher.stop();
                } catch (Throwable e) {
                    stopFailure.compareAndSet(null, e);
                }
            }
        });

        stopper.start();
        await(service.closeAttempted);
        expectIllegalState(new Runnable() {
            @Override
            public void run() {
                watcher.run();
            }
        });
        service.allowClose.countDown();
        stopper.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

        assertFalse(stopper.isAlive());
        assertNoFailure(stopFailure);
    }

    @Test
    public void interruptedStopRestoresInterruptAndFinishesLater() throws Exception {
        Thread.currentThread().interrupt();

        watcher.stop();

        assertTrue(Thread.interrupted());
        expectIllegalState(new Runnable() {
            @Override
            public void run() {
                watcher.run();
            }
        });
    }

    @Test
    public void concurrentAddCompletesBeforeOwnerRemoval() throws Exception {
        ControlledWatchService service = new ControlledWatchService(false, false, false);
        final ControlledWatcher controlled = replaceWithControlledWatcher(service);
        final ClassLoader firstOwner = new ClassLoader(null) {
        };
        final ClassLoader secondOwner = new ClassLoader(null) {
        };
        final CountDownLatch secondCalled = new CountDownLatch(1);
        final AtomicInteger firstEvents = new AtomicInteger();
        controlled.addEventListener(firstOwner, temp.toUri(), countingListener(firstEvents, new CountDownLatch(0)));
        controlled.blockNextRegistration();
        controlled.run();

        final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread add = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    controlled.addEventListener(secondOwner, temp.toUri(),
                            countingListener(new AtomicInteger(), secondCalled));
                } catch (Throwable e) {
                    workerFailure.compareAndSet(null, e);
                }
            }
        });
        add.start();
        await(controlled.registrationEntered);

        Thread close = new Thread(new Runnable() {
            @Override
            public void run() {
                controlled.closeClassLoader(firstOwner);
            }
        });
        close.start();
        controlled.registrationRelease.countDown();
        add.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        close.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        controlled.emit(temp.resolve("concurrent.class"));
        await(secondCalled);

        assertFalse(add.isAlive());
        assertFalse(close.isAlive());
        assertNoFailure(workerFailure);
        assertEquals(0, firstEvents.get());
    }

    @Test
    public void invalidTreeKeyDoesNotHideNewRegistration() throws Exception {
        InspectableTreeWatcher treeWatcher = new InspectableTreeWatcher();
        watcher.stop();
        watcher = treeWatcher;
        treeWatcher.addInvalidKey(temp);
        final CountDownLatch called = new CountDownLatch(1);

        watcher.addEventListener(null, temp.toUri(), countingListener(new AtomicInteger(), called));
        watcher.run();
        Files.createFile(temp.resolve("tree.class"));

        await(called);
        assertEquals(1, treeWatcher.validKeyCount());
    }

    private ControlledWatcher replaceWithControlledWatcher(ControlledWatchService service) throws IOException {
        watcher.stop();
        ControlledWatcher controlled = new ControlledWatcher(service);
        watcher = controlled;
        return controlled;
    }

    private WatchEventListener countingListener(final AtomicInteger count, final CountDownLatch... rounds) {
        return new WatchEventListener() {
            @Override
            public void onEvent(WatchFileEvent event) {
                count.incrementAndGet();
                for (CountDownLatch latch : rounds) {
                    latch.countDown();
                }
            }
        };
    }

    private WatchEventListener stagedListener(final AtomicInteger count, final CountDownLatch firstRound,
            final CountDownLatch secondRound) {
        return new WatchEventListener() {
            @Override
            public void onEvent(WatchFileEvent event) {
                if (count.incrementAndGet() == 1) {
                    firstRound.countDown();
                } else {
                    secondRound.countDown();
                }
            }
        };
    }

    private WatchEventListener noOpListener() {
        return new WatchEventListener() {
            @Override
            public void onEvent(WatchFileEvent event) {
            }
        };
    }

    private Thread runnerThread() throws Exception {
        Field field = AbstractNIO2Watcher.class.getDeclaredField("runner");
        field.setAccessible(true);
        return (Thread) field.get(watcher);
    }

    private Thread dispatcherThread() throws Exception {
        Field dispatcherField = AbstractNIO2Watcher.class.getDeclaredField("dispatcher");
        dispatcherField.setAccessible(true);
        EventDispatcher dispatcher = (EventDispatcher) dispatcherField.get(watcher);
        Field runnableField = EventDispatcher.class.getDeclaredField("runnable");
        runnableField.setAccessible(true);
        return (Thread) runnableField.get(dispatcher);
    }

    private void expectIllegalState(Runnable operation) {
        try {
            operation.run();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    private void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("Timed out waiting for asynchronous watcher activity",
                latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private void assertNoFailure(AtomicReference<Throwable> failure) {
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static class ControlledWatcher extends AbstractNIO2Watcher {
        private final CountDownLatch registrationEntered = new CountDownLatch(1);
        private final CountDownLatch registrationRelease = new CountDownLatch(1);
        private volatile boolean blockRegistration;

        ControlledWatcher(WatchService replacement) throws IOException {
            watcher.close();
            watcher = replacement;
        }

        @Override
        protected void registerAll(Path dir) throws IOException {
            if (blockRegistration) {
                blockRegistration = false;
                registrationEntered.countDown();
                try {
                    if (!registrationRelease.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to register directory");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Registration interrupted", e);
                }
            }
        }

        void blockNextRegistration() {
            blockRegistration = true;
        }

        void addKey(FakeWatchKey key) {
            keys.put(key, key.path);
        }

        int keyCount() {
            return keys.size();
        }

        void emit(Path path) {
            dispatcher.add(new FakeWatchEvent(StandardWatchEventKinds.ENTRY_CREATE,
                    path.getFileName()), path);
        }
    }

    private static class InspectableTreeWatcher extends TreeWatcherNIO {
        InspectableTreeWatcher() throws IOException {
        }

        @Override
        protected void registerAll(Path dir) throws IOException {
            if (WatcherFactory.IS_WINDOWS) {
                super.registerAll(dir);
                return;
            }

            // Exercise TreeWatcherNIO's key handling with a real, non-recursive registration off Windows.
            Path portableDir = (Path) Proxy.newProxyInstance(Path.class.getClassLoader(),
                    new Class<?>[] { Path.class }, (proxy, method, args) -> {
                        if ("register".equals(method.getName()) && method.isVarArgs()) {
                            return dir.register((WatchService) args[0], (WatchEvent.Kind<?>[]) args[1]);
                        }
                        try {
                            return method.invoke(dir, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            super.registerAll(portableDir);
        }

        void addInvalidKey(Path path) {
            keys.put(new FakeWatchKey(path, false), path);
        }

        int validKeyCount() {
            int count = 0;
            for (WatchKey key : keys.keySet()) {
                if (key.isValid()) {
                    count++;
                }
            }
            return count;
        }
    }

    private static class ControlledWatchService implements WatchService {
        private final boolean failClose;
        private final boolean blockClose;
        private final CountDownLatch pollEntered = new CountDownLatch(1);
        private final CountDownLatch closeAttempted = new CountDownLatch(1);
        private final CountDownLatch allowClose = new CountDownLatch(1);
        private final BlockingQueue<WatchKey> queuedKeys = new LinkedBlockingQueue<>();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private volatile boolean closed;

        ControlledWatchService(boolean failClose, boolean blockClose, boolean initiallyClosed) {
            this.failClose = failClose;
            this.blockClose = blockClose;
            this.closed = initiallyClosed;
            if (!blockClose) {
                allowClose.countDown();
            }
        }

        void offer(WatchKey key) {
            queuedKeys.offer(key);
        }

        void closeExternally() {
            closed = true;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            closed = true;
            closeAttempted.countDown();
            try {
                if (blockClose && !allowClose.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to finish watch service close");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Watch service close interrupted", e);
            }
            if (failClose) {
                throw new IOException("Expected close failure");
            }
        }

        @Override
        public WatchKey poll() {
            pollEntered.countDown();
            if (closed) {
                throw new ClosedWatchServiceException();
            }
            return queuedKeys.poll();
        }

        @Override
        public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
            pollEntered.countDown();
            if (closed) {
                throw new ClosedWatchServiceException();
            }
            WatchKey key = queuedKeys.poll(timeout, unit);
            if (key == null && closed) {
                throw new ClosedWatchServiceException();
            }
            return key;
        }

        @Override
        public WatchKey take() throws InterruptedException {
            pollEntered.countDown();
            WatchKey key = queuedKeys.take();
            if (closed) {
                throw new ClosedWatchServiceException();
            }
            return key;
        }
    }

    private static class FakeWatchKey implements WatchKey {
        private final Path path;
        private volatile boolean valid;
        private final CountDownLatch validityChecked = new CountDownLatch(1);

        FakeWatchKey(Path path, boolean valid) {
            this.path = path;
            this.valid = valid;
        }

        @Override
        public boolean isValid() {
            validityChecked.countDown();
            return valid;
        }

        @Override
        public List<WatchEvent<?>> pollEvents() {
            return Collections.emptyList();
        }

        @Override
        public boolean reset() {
            return valid;
        }

        @Override
        public void cancel() {
            valid = false;
        }

        @Override
        public Watchable watchable() {
            return path;
        }
    }

    private static class FakeWatchEvent implements WatchEvent<Path> {
        private final Kind<Path> kind;
        private final Path context;

        FakeWatchEvent(Kind<Path> kind, Path context) {
            this.kind = kind;
            this.context = context;
        }

        @Override
        public Kind<Path> kind() {
            return kind;
        }

        @Override
        public int count() {
            return 1;
        }

        @Override
        public Path context() {
            return context;
        }
    }
}
