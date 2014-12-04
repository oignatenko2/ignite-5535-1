/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.apache.ignite.logger.log4j;

import org.apache.ignite.lang.*;
import org.apache.log4j.*;
import org.gridgain.grid.util.typedef.internal.*;

import java.io.*;

/**
 * Log4J {@link FileAppender} with added support for grid node IDs.
 */
public class IgniteLog4jFileAppender extends FileAppender implements IgniteLog4jFileAware {
    /** Basic log file name. */
    private String baseFileName;

    /**
     * Default constructor (does not do anything).
     */
    public IgniteLog4jFileAppender() {
        init();
    }

    /**
     * Instantiate a FileAppender with given parameters.
     *
     * @param layout Layout.
     * @param filename File name.
     * @throws IOException If failed.
     */
    public IgniteLog4jFileAppender(Layout layout, String filename) throws IOException {
        super(layout, filename);

        init();
    }

    /**
     * Instantiate a FileAppender with given parameters.
     *
     * @param layout Layout.
     * @param filename File name.
     * @param append Append flag.
     * @throws IOException If failed.
     */
    public IgniteLog4jFileAppender(Layout layout, String filename, boolean append) throws IOException {
        super(layout, filename, append);

        init();
    }

    /**
     * Instantiate a FileAppender with given parameters.
     *
     * @param layout Layout.
     * @param filename File name.
     * @param append Append flag.
     * @param bufIO Buffered IO flag.
     * @param bufSize Buffer size.
     * @throws IOException If failed.
     */
    public IgniteLog4jFileAppender(Layout layout, String filename, boolean append, boolean bufIO, int bufSize)
        throws IOException {
        super(layout, filename, append, bufIO, bufSize);

        init();
    }

    /**
     *
     */
    private void init() {
        IgniteLog4jLogger.addAppender(this);
    }

    /** {@inheritDoc} */
    @Override public synchronized void setFile(String fileName, boolean fileAppend, boolean bufIO, int bufSize)
        throws IOException {
        if (baseFileName != null)
            super.setFile(fileName, fileAppend, bufIO, bufSize);
    }

    /** {@inheritDoc} */
    @Override public synchronized void updateFilePath(IgniteClosure<String, String> filePathClos) {
        A.notNull(filePathClos, "filePathClos");

        if (baseFileName == null)
            baseFileName = fileName;

        fileName = filePathClos.apply(baseFileName);
    }
}