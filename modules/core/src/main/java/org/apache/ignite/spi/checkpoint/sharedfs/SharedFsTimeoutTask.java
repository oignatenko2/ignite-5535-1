/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.apache.ignite.spi.checkpoint.sharedfs;

import org.apache.ignite.*;
import org.apache.ignite.marshaller.*;
import org.apache.ignite.spi.*;
import org.gridgain.grid.*;
import org.apache.ignite.spi.checkpoint.*;
import org.gridgain.grid.util.typedef.internal.*;
import java.io.*;
import java.util.*;

/**
 * Implementation of {@link org.apache.ignite.spi.IgniteSpiThread} that takes care about outdated files.
 * Every checkpoint has expiration date after which it makes no sense to
 * keep it. This class periodically compares files last access time with given
 * expiration time.
 * <p>
 * If this file was not accessed then it is deleted. If file access time is
 * different from modification date new expiration date is set.
 */
class SharedFsTimeoutTask extends IgniteSpiThread {
    /** Map of files to their access and expiration date. */
    private Map<File, SharedFsTimeData> files = new HashMap<>();

    /** Messages logger. */
    private IgniteLogger log;

    /** Messages marshaller. */
    private IgniteMarshaller marshaller;

    /** */
    private final Object mux = new Object();

    /** Timeout listener. */
    private CheckpointListener lsnr;

    /**
     * Creates new instance of task that looks after files.
     *
     * @param gridName Grid name.
     * @param marshaller Messages marshaller.
     * @param log Messages logger.
     */
    SharedFsTimeoutTask(String gridName, IgniteMarshaller marshaller, IgniteLogger log) {
        super(gridName, "grid-sharedfs-timeout-worker", log);

        assert marshaller != null;
        assert log != null;

        this.marshaller = marshaller;
        this.log = log.getLogger(getClass());
    }

    /** {@inheritDoc} */
    @Override public void body() throws InterruptedException {
        long nextTime = 0;

        Collection<String> rmvKeys = new HashSet<>();

        while (!isInterrupted()) {
            rmvKeys.clear();

            synchronized (mux) {
                // nextTime is 0 only on first iteration.
                if (nextTime != 0) {
                    long delay;

                    if (nextTime == -1)
                        delay = 5000;
                    else {
                        assert nextTime > 0;

                        delay = nextTime - U.currentTimeMillis();
                    }

                    while (delay > 0) {
                        mux.wait(delay);

                        delay = nextTime - U.currentTimeMillis();
                    }
                }

                Map<File, SharedFsTimeData> snapshot = new HashMap<>(files);

                long now = U.currentTimeMillis();

                nextTime = -1;

                // Check files one by one and physically remove
                // if (now - last modification date) > expiration time
                for (Map.Entry<File, SharedFsTimeData> entry : snapshot.entrySet()) {
                    File file = entry.getKey();

                    SharedFsTimeData timeData = entry.getValue();

                    try {
                        if (timeData.getLastAccessTime() != file.lastModified())
                            timeData.setExpireTime(SharedFsUtils.read(file, marshaller, log).getExpireTime());
                    }
                    catch (IgniteCheckedException e) {
                        U.error(log, "Failed to marshal/unmarshal in checkpoint file: " + file.getAbsolutePath(), e);

                        continue;
                    }
                    catch (IOException e) {
                        if (!file.exists()) {
                            files.remove(file);

                            rmvKeys.add(timeData.getKey());
                        }
                        else
                            U.error(log, "Failed to read checkpoint file: " + file.getAbsolutePath(), e);

                        continue;
                    }

                    if (timeData.getExpireTime() > 0)
                        if (timeData.getExpireTime() <= now)
                            if (!file.delete() && file.exists())
                                U.error(log, "Failed to delete check point file by timeout: " + file.getAbsolutePath());
                            else {
                                files.remove(file);

                                rmvKeys.add(timeData.getKey());

                                if (log.isDebugEnabled())
                                    log.debug("File was deleted by timeout: " + file.getAbsolutePath());
                            }
                        else
                            if (timeData.getExpireTime() < nextTime || nextTime == -1)
                                nextTime = timeData.getExpireTime();
                }
            }

            CheckpointListener lsnr = this.lsnr;

            if (lsnr != null)
                for (String key : rmvKeys)
                    lsnr.onCheckpointRemoved(key);
        }

        synchronized (mux) {
            files.clear();
        }
    }

    /**
     * Adds file to a list of files this task should look after.
     *
     * @param file File being watched.
     * @param timeData File expiration and access information.
     */
    void add(File file, SharedFsTimeData timeData) {
        assert file != null;
        assert timeData != null;

        synchronized (mux) {
            files.put(file, timeData);

            mux.notifyAll();
        }
    }

    /**
     * Adds list of files this task should looks after.
     *
     * @param newFiles List of files.
     */
    void add(Map<File, SharedFsTimeData> newFiles) {
        assert newFiles != null;

        synchronized (mux) {
            files.putAll(newFiles);

            mux.notifyAll();
        }
    }

    /**
     * Stops watching file.
     *
     * @param file File that task should not look after anymore.
     */
    void remove(File file) {
        assert file != null;

        synchronized (mux) {
            files.remove(file);
        }
    }

    /**
     * Stops watching file list.
     *
     * @param delFiles List of files this task should not look after anymore.
     */
    void remove(Iterable<File> delFiles) {
        assert delFiles != null;

        synchronized (mux) {
            for (File file : delFiles)
                files.remove(file);
        }
    }

    /**
     * Sets listener.
     *
     * @param lsnr Listener.
     */
    void setCheckpointListener(CheckpointListener lsnr) {
        this.lsnr = lsnr;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(SharedFsTimeoutTask.class, this);
    }
}