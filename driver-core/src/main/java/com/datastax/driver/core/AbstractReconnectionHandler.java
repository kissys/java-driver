/*
 *      Copyright (C) 2012-2014 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.util.concurrent.Uninterruptibles;

import com.google.common.util.concurrent.AbstractFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.exceptions.AuthenticationException;
import com.datastax.driver.core.policies.ReconnectionPolicy;

abstract class AbstractReconnectionHandler implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(AbstractReconnectionHandler.class);

    private final ScheduledExecutorService executor;
    private final ReconnectionPolicy.ReconnectionSchedule schedule;
    /**
     * The future that is exposed to clients, representing completion of the current active handler
     */
    private final AtomicReference<Future<?>> currentAttempt;

    private final HandlerFuture handlerFuture = new HandlerFuture();

    private volatile boolean isActive;

    public AbstractReconnectionHandler(ScheduledExecutorService executor, ReconnectionPolicy.ReconnectionSchedule schedule, AtomicReference<Future<?>> currentAttempt) {
        this.executor = executor;
        this.schedule = schedule;
        this.currentAttempt = currentAttempt;
    }

    protected abstract Connection tryReconnect() throws ConnectionException, InterruptedException, UnsupportedProtocolVersionException, ClusterNameMismatchException;
    protected abstract void onReconnection(Connection connection);

    protected boolean onConnectionException(ConnectionException e, long nextDelayMs) { return true; }
    protected boolean onUnknownException(Exception e, long nextDelayMs) { return true; }

    // Retrying on authentication or unsupported protocol version error is unlikely to work
    protected boolean onAuthenticationException(AuthenticationException e, long nextDelayMs) { return false; }
    protected boolean onUnsupportedProtocolVersionException(UnsupportedProtocolVersionException e, long nextDelayMs) { return false; }
    protected boolean onClusterNameMismatchException(ClusterNameMismatchException e, long nextDelayMs) { return false; }

    public void start() {
        long firstDelay = schedule.nextDelayMs();
        logger.debug("First reconnection scheduled in {}ms", firstDelay);
        try {
            handlerFuture.nextTry = executor.schedule(this, firstDelay, TimeUnit.MILLISECONDS);

            while (true) {
                Future<?> previous = currentAttempt.get();
                if (previous != null && !previous.isCancelled()) {
                    logger.debug("Found another already active handler, cancelling");
                    handlerFuture.cancel(false);
                    return;
                }
                if (currentAttempt.compareAndSet(previous, handlerFuture)) {
                    logger.debug("Becoming the active handler");
                    break;
                }
            }
            isActive = true;
        } catch (RejectedExecutionException e) {
            // The executor has been shutdown, fair enough, just ignore
            logger.debug("Aborting reconnection handling since the cluster is shutting down");
        }
    }

    @Override
    public void run() {
        if (handlerFuture.isCancelled()) {
            logger.debug("Got cancelled, stopping");
            return;
        }

        // Just make sure we don't start the first try too fast, in case we find out we need to cancel ourselves in start()
        while (!isActive)
            Uninterruptibles.sleepUninterruptibly(5, TimeUnit.MILLISECONDS);

        try {
            onReconnection(tryReconnect());
            handlerFuture.markAsDone();
            currentAttempt.compareAndSet(handlerFuture, null);
            logger.debug("Reconnection successful, cleared the future");
        } catch (ConnectionException e) {
            long nextDelay = schedule.nextDelayMs();
            if (onConnectionException(e, nextDelay))
                reschedule(nextDelay);
            else
                currentAttempt.compareAndSet(handlerFuture, null);
        } catch (AuthenticationException e) {
            logger.error(e.getMessage());
            long nextDelay = schedule.nextDelayMs();
            if (onAuthenticationException(e, nextDelay)) {
                reschedule(nextDelay);
            } else {
                logger.error("Retry against {} have been suspended. It won't be retried unless the node is restarted.", e.getHost());
                currentAttempt.compareAndSet(handlerFuture, null);
            }
        } catch (InterruptedException e) {
            // If interrupted, skip this attempt but still skip scheduling reconnections
            Thread.currentThread().interrupt();
            reschedule(schedule.nextDelayMs());
        } catch (UnsupportedProtocolVersionException e) {
            logger.error(e.getMessage());
            long nextDelay = schedule.nextDelayMs();
            if (onUnsupportedProtocolVersionException(e, nextDelay)) {
                reschedule(nextDelay);
            } else {
                logger.error("Retry against {} have been suspended. It won't be retried unless the node is restarted.", e.address);
                currentAttempt.compareAndSet(handlerFuture, null);
            }
        } catch (ClusterNameMismatchException e) {
            logger.error(e.getMessage());
            long nextDelay = schedule.nextDelayMs();
            if (onClusterNameMismatchException(e, nextDelay)) {
                reschedule(nextDelay);
            } else {
                logger.error("Retry against {} have been suspended. It won't be retried unless the node is restarted.", e.address);
                currentAttempt.compareAndSet(handlerFuture, null);
            }
        } catch (Exception e) {
            long nextDelay = schedule.nextDelayMs();
            if (onUnknownException(e, nextDelay))
                reschedule(nextDelay);
            else
                currentAttempt.compareAndSet(handlerFuture, null);
        }
    }

    private void reschedule(long nextDelay) {
        // If we got cancelled during the failed reconnection attempt that lead here, don't reschedule
        if (handlerFuture.isCancelled()) {
            currentAttempt.compareAndSet(handlerFuture, null);
            return;
        }

        handlerFuture.nextTry = executor.schedule(this, nextDelay, TimeUnit.MILLISECONDS);
    }

    // The future that the handler exposes to its clients via currentAttempt
    private static class HandlerFuture extends AbstractFuture<Void> {
        // A future representing completion of the next task submitted to the executor
        volatile ScheduledFuture<?> nextTry;

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            // This is a check-then-act, so we may race with the scheduling of the first try, but in that case
            // we'll re-check for cancellation when this first try starts running
            if (nextTry != null) {
                nextTry.cancel(mayInterruptIfRunning);
            }

            return super.cancel(mayInterruptIfRunning);
        }

        void markAsDone() {
            super.set(null);
        }
    }
}
