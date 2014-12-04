/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.visor.cache;

import org.gridgain.grid.cache.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;

import static org.gridgain.grid.kernal.visor.util.VisorTaskUtils.*;

/**
 * Data transfer object for near cache configuration properties.
 */
public class VisorCacheNearConfiguration implements Serializable {
    /** */
    private static final long serialVersionUID = 0L;

    /** Flag to enable/disable near cache eviction policy. */
    private boolean nearEnabled;

    /** Near cache start size. */
    private int nearStartSize;

    /** Near cache eviction policy. */
    private String nearEvictPlc;

    /** Near cache eviction policy maximum size. */
    private Integer nearEvictMaxSize;

    /**
     * @param ccfg Cache configuration.
     * @return Data transfer object for near cache configuration properties.
     */
    public static VisorCacheNearConfiguration from(GridCacheConfiguration ccfg) {
        VisorCacheNearConfiguration cfg = new VisorCacheNearConfiguration();

        cfg.nearEnabled(GridCacheUtils.isNearEnabled(ccfg));
        cfg.nearStartSize(ccfg.getNearStartSize());
        cfg.nearEvictPolicy(compactClass(ccfg.getNearEvictionPolicy()));
        cfg.nearEvictMaxSize(evictionPolicyMaxSize(ccfg.getNearEvictionPolicy()));

        return cfg;
    }

    /**
     * @return Flag to enable/disable near cache eviction policy.
     */
    public boolean nearEnabled() {
        return nearEnabled;
    }

    /**
     * @param nearEnabled New flag to enable/disable near cache eviction policy.
     */
    public void nearEnabled(boolean nearEnabled) {
        this.nearEnabled = nearEnabled;
    }

    /**
     * @return Near cache start size.
     */
    public int nearStartSize() {
        return nearStartSize;
    }

    /**
     * @param nearStartSize New near cache start size.
     */
    public void nearStartSize(int nearStartSize) {
        this.nearStartSize = nearStartSize;
    }

    /**
     * @return Near cache eviction policy.
     */
    @Nullable public String nearEvictPolicy() {
        return nearEvictPlc;
    }

    /**
     * @param nearEvictPlc New near cache eviction policy.
     */
    public void nearEvictPolicy(String nearEvictPlc) {
        this.nearEvictPlc = nearEvictPlc;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(VisorCacheNearConfiguration.class, this);
    }

    /**
     * @return Near cache eviction policy max size.
     */
    @Nullable public Integer nearEvictMaxSize() {
        return nearEvictMaxSize;
    }

    /**
     * @param nearEvictMaxSize New near cache eviction policy max size.
     */
    public void nearEvictMaxSize(@Nullable Integer nearEvictMaxSize) {
        this.nearEvictMaxSize = nearEvictMaxSize;
    }
}