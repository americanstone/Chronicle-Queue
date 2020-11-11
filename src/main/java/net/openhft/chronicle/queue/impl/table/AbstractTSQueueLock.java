/*
 * Copyright 2014-2020 chronicle.software
 *
 * http://chronicle.software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.queue.impl.table;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.StackTrace;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.values.LongValue;
import net.openhft.chronicle.queue.impl.TableStore;
import net.openhft.chronicle.threads.TimingPauser;

import java.io.File;
import java.util.function.Supplier;

public abstract class AbstractTSQueueLock extends AbstractCloseable implements Closeable {
    protected static final long UNLOCKED = Long.MIN_VALUE;
    protected final boolean dontRecoverLockTimeout = Jvm.getBoolean("queue.dont.recover.lock.timeout");

    protected final LongValue lock;
    protected final TimingPauser pauser;
    protected final File path;
    protected final TableStore tableStore;
    private final String lockKey;

    public AbstractTSQueueLock(final String lockKey, final TableStore<?> tableStore, final Supplier<TimingPauser> pauser) {
        this.tableStore = tableStore;
        this.lock = tableStore.doWithExclusiveLock(ts -> ts.acquireValueFor(lockKey));
        this.pauser = pauser.get();
        this.path = tableStore.file();
        this.lockKey = lockKey;
    }

    protected void performClose() {
        Closeable.closeQuietly(lock);
    }

    protected void forceUnlock(long value) {
        boolean unlocked = lock.compareAndSwapValue(value, UNLOCKED);
        Jvm.warn().on(getClass(), "" +
                        "Forced unlock for the " +
                        "lock file:" + path + ", " +
                        "unlocked: " + unlocked,
                new StackTrace("Forced unlock"));
    }

    /**
     * forces the unlock only if the process that currently holds the table store lock is no-longer running, otherwise will block
     */
    public void forceUnlockIfProcessIsDead() throws InterruptedException {
        long i = 0;
        for (; ; ) {
            long pid = this.lock.getValue();
            if (pid == UNLOCKED || Jvm.isProcessAlive(pid))
                return;

            Jvm.debug().on(this.getClass(), "Forced unlock for the lock file:" + this.path + ", unlocked: " + pid, new StackTrace("Forced unlock"));
            if (lock.compareAndSwapValue(pid, UNLOCKED))
                return;

            Thread.sleep(1);

            i++;
            if (i % 1000 == 0) {
                Jvm.warn().on(this.getClass(), String.format("unable to release the lock=%s in file=%s as it is being held by pid=%d, and this process is still running.", lockKey, path, pid));
            }
        }

    }

    @Override
    protected boolean threadSafetyCheck(boolean isUsed) {
        // The lock is thread safe.
        return true;
    }

}
