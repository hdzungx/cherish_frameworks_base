/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.os;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Collects aggregated telemetry data about Looper message dispatching.
 *
 * @hide Only for use within the system server.
 */
public class LooperStats implements Looper.Observer {
    private static final int TOKEN_POOL_SIZE = 50;

    @GuardedBy("mLock")
    private final SparseArray<Entry> mEntries = new SparseArray<>(512);
    private final Object mLock = new Object();
    private final Entry mOverflowEntry = new Entry("OVERFLOW");
    private final Entry mHashCollisionEntry = new Entry("HASH_COLLISION");
    private final ConcurrentLinkedQueue<DispatchSession> mSessionPool =
            new ConcurrentLinkedQueue<>();
    private final int mEntriesSizeCap;
    private int mSamplingInterval;
    private CachedDeviceState.Readonly mDeviceState;

    public LooperStats(int samplingInterval, int entriesSizeCap) {
        this.mSamplingInterval = samplingInterval;
        this.mEntriesSizeCap = entriesSizeCap;
    }

    public void setDeviceState(@NonNull CachedDeviceState.Readonly deviceState) {
        mDeviceState = deviceState;
    }

    @Override
    public Object messageDispatchStarting() {
        if (deviceStateAllowsCollection() && shouldCollectDetailedData()) {
            DispatchSession session = mSessionPool.poll();
            session = session == null ? new DispatchSession() : session;
            session.startTimeMicro = getElapsedRealtimeMicro();
            session.cpuStartMicro = getThreadTimeMicro();
            return session;
        }

        return DispatchSession.NOT_SAMPLED;
    }

    @Override
    public void messageDispatched(Object token, Message msg) {
        if (!deviceStateAllowsCollection()) {
            return;
        }

        DispatchSession session = (DispatchSession) token;
        Entry entry = getOrCreateEntry(msg);
        synchronized (entry) {
            entry.messageCount++;
            if (session != DispatchSession.NOT_SAMPLED) {
                entry.recordedMessageCount++;
                long latency = getElapsedRealtimeMicro() - session.startTimeMicro;
                long cpuUsage = getThreadTimeMicro() - session.cpuStartMicro;
                entry.totalLatencyMicro += latency;
                entry.maxLatencyMicro = Math.max(entry.maxLatencyMicro, latency);
                entry.cpuUsageMicro += cpuUsage;
                entry.maxCpuUsageMicro = Math.max(entry.maxCpuUsageMicro, cpuUsage);
            }
        }

        recycleSession(session);
    }

    @Override
    public void dispatchingThrewException(Object token, Message msg, Exception exception) {
        if (!deviceStateAllowsCollection()) {
            return;
        }

        DispatchSession session = (DispatchSession) token;
        Entry entry = getOrCreateEntry(msg);
        synchronized (entry) {
            entry.exceptionCount++;
        }

        recycleSession(session);
    }

    private boolean deviceStateAllowsCollection() {
        // Do not collect data if on charger or the state is not set.
        return mDeviceState != null && !mDeviceState.isCharging();
    }

    /** Returns an array of {@link ExportedEntry entries} with the aggregated statistics. */
    public List<ExportedEntry> getEntries() {
        final ArrayList<ExportedEntry> exportedEntries;
        synchronized (mLock) {
            final int size = mEntries.size();
            exportedEntries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                Entry entry = mEntries.valueAt(i);
                synchronized (entry) {
                    exportedEntries.add(new ExportedEntry(entry));
                }
            }
        }
        // Add the overflow and collision entries only if they have any data.
        maybeAddSpecialEntry(exportedEntries, mOverflowEntry);
        maybeAddSpecialEntry(exportedEntries, mHashCollisionEntry);
        return exportedEntries;
    }

    private void maybeAddSpecialEntry(List<ExportedEntry> exportedEntries, Entry specialEntry) {
        synchronized (specialEntry) {
            if (specialEntry.messageCount > 0 || specialEntry.exceptionCount > 0) {
                exportedEntries.add(new ExportedEntry(specialEntry));
            }
        }
    }

    /** Removes all collected data. */
    public void reset() {
        synchronized (mLock) {
            mEntries.clear();
        }
        synchronized (mHashCollisionEntry) {
            mHashCollisionEntry.reset();
        }
        synchronized (mOverflowEntry) {
            mOverflowEntry.reset();
        }
    }

    public void setSamplingInterval(int samplingInterval) {
        mSamplingInterval = samplingInterval;
    }

    @NonNull
    private Entry getOrCreateEntry(Message msg) {
        final boolean isInteractive = mDeviceState.isScreenInteractive();
        final int id = Entry.idFor(msg, isInteractive);
        Entry entry;
        synchronized (mLock) {
            entry = mEntries.get(id);
            if (entry == null) {
                if (mEntries.size() >= mEntriesSizeCap) {
                    // If over the size cap, track totals under a single entry.
                    return mOverflowEntry;
                }
                entry = new Entry(msg, isInteractive);
                mEntries.put(id, entry);
            }
        }

        if (entry.workSourceUid != msg.workSourceUid
                || entry.handler.getClass() != msg.getTarget().getClass()
                || entry.handler.getLooper().getThread() != msg.getTarget().getLooper().getThread()
                || entry.isInteractive != isInteractive) {
            // If a hash collision happened, track totals under a single entry.
            return mHashCollisionEntry;
        }
        return entry;
    }

    private void recycleSession(DispatchSession session) {
        if (session != DispatchSession.NOT_SAMPLED && mSessionPool.size() < TOKEN_POOL_SIZE) {
            mSessionPool.add(session);
        }
    }

    protected long getThreadTimeMicro() {
        return SystemClock.currentThreadTimeMicro();
    }

    protected long getElapsedRealtimeMicro() {
        return SystemClock.elapsedRealtimeNanos() / 1000;
    }

    protected boolean shouldCollectDetailedData() {
        return ThreadLocalRandom.current().nextInt() % mSamplingInterval == 0;
    }

    private static class DispatchSession {
        static final DispatchSession NOT_SAMPLED = new DispatchSession();
        public long startTimeMicro;
        public long cpuStartMicro;
    }

    private static class Entry {
        public final int workSourceUid;
        public final Handler handler;
        public final String messageName;
        public final boolean isInteractive;
        public long messageCount;
        public long recordedMessageCount;
        public long exceptionCount;
        public long totalLatencyMicro;
        public long maxLatencyMicro;
        public long cpuUsageMicro;
        public long maxCpuUsageMicro;

        Entry(Message msg, boolean isInteractive) {
            this.workSourceUid = msg.workSourceUid;
            this.handler = msg.getTarget();
            this.messageName = handler.getMessageName(msg);
            this.isInteractive = isInteractive;
        }

        Entry(String specialEntryName) {
            this.workSourceUid = Message.UID_NONE;
            this.messageName = specialEntryName;
            this.handler = null;
            this.isInteractive = false;
        }

        void reset() {
            messageCount = 0;
            recordedMessageCount = 0;
            exceptionCount = 0;
            totalLatencyMicro = 0;
            maxLatencyMicro = 0;
            cpuUsageMicro = 0;
            maxCpuUsageMicro = 0;
        }

        static int idFor(Message msg, boolean isInteractive) {
            int result = 7;
            result = 31 * result + msg.workSourceUid;
            result = 31 * result + msg.getTarget().getLooper().getThread().hashCode();
            result = 31 * result + msg.getTarget().getClass().hashCode();
            result = 31 * result + (isInteractive ? 1231 : 1237);
            if (msg.getCallback() != null) {
                return 31 * result + msg.getCallback().getClass().hashCode();
            } else {
                return 31 * result + msg.what;
            }
        }
    }

    /** Aggregated data of Looper message dispatching in the in the current process. */
    public static class ExportedEntry {
        public final int workSourceUid;
        public final String handlerClassName;
        public final String threadName;
        public final String messageName;
        public final boolean isInteractive;
        public final long messageCount;
        public final long recordedMessageCount;
        public final long exceptionCount;
        public final long totalLatencyMicros;
        public final long maxLatencyMicros;
        public final long cpuUsageMicros;
        public final long maxCpuUsageMicros;

        ExportedEntry(Entry entry) {
            this.workSourceUid = entry.workSourceUid;
            if (entry.handler != null) {
                this.handlerClassName = entry.handler.getClass().getName();
                this.threadName = entry.handler.getLooper().getThread().getName();
            } else {
                // Overflow/collision entries do not have a handler set.
                this.handlerClassName = "";
                this.threadName = "";
            }
            this.isInteractive = entry.isInteractive;
            this.messageName = entry.messageName;
            this.messageCount = entry.messageCount;
            this.recordedMessageCount = entry.recordedMessageCount;
            this.exceptionCount = entry.exceptionCount;
            this.totalLatencyMicros = entry.totalLatencyMicro;
            this.maxLatencyMicros = entry.maxLatencyMicro;
            this.cpuUsageMicros = entry.cpuUsageMicro;
            this.maxCpuUsageMicros = entry.maxCpuUsageMicro;
        }
    }
}
