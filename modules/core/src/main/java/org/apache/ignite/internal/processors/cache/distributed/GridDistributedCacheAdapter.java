/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache.distributed;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.compute.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.cluster.*;
import org.apache.ignite.internal.processors.affinity.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.distributed.dht.*;
import org.apache.ignite.internal.processors.cache.distributed.near.*;
import org.apache.ignite.internal.processors.cache.transactions.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.processors.datastreamer.*;
import org.apache.ignite.internal.processors.task.*;
import org.apache.ignite.internal.util.future.*;
import org.apache.ignite.internal.util.lang.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.transactions.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;

import static org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionState.*;
import static org.apache.ignite.internal.processors.task.GridTaskThreadContextKey.*;

/**
 * Distributed cache implementation.
 */
public abstract class GridDistributedCacheAdapter<K, V> extends GridCacheAdapter<K, V> {
    /** */
    private static final long serialVersionUID = 0L;

    /**
     * Empty constructor required by {@link Externalizable}.
     */
    protected GridDistributedCacheAdapter() {
        // No-op.
    }

    /**
     * @param ctx Cache registry.
     * @param startSize Start size.
     */
    protected GridDistributedCacheAdapter(GridCacheContext<K, V> ctx, int startSize) {
        super(ctx, startSize);
    }

    /**
     * @param ctx Cache context.
     * @param map Cache map.
     */
    protected GridDistributedCacheAdapter(GridCacheContext<K, V> ctx, GridCacheConcurrentMap map) {
        super(ctx, map);
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Boolean> txLockAsync(
        Collection<KeyCacheObject> keys,
        long timeout,
        IgniteTxLocalEx tx,
        boolean isRead,
        boolean retval,
        TransactionIsolation isolation,
        boolean isInvalidate,
        long accessTtl
    ) {
        assert tx != null;

        return lockAllAsync(keys, timeout, tx, isInvalidate, isRead, retval, isolation, accessTtl);
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Boolean> lockAllAsync(Collection<? extends K> keys, long timeout) {
        IgniteTxLocalEx tx = ctx.tm().userTxx();

        // Return value flag is true because we choose to bring values for explicit locks.
        return lockAllAsync(ctx.cacheKeysView(keys),
            timeout,
            tx,
            false,
            false,
            /*retval*/true,
            null,
            -1L);
    }

    /**
     * @param keys Keys to lock.
     * @param timeout Timeout.
     * @param tx Transaction
     * @param isInvalidate Invalidation flag.
     * @param isRead Indicates whether value is read or written.
     * @param retval Flag to return value.
     * @param isolation Transaction isolation.
     * @param accessTtl TTL for read operation.
     * @return Future for locks.
     */
    protected abstract IgniteInternalFuture<Boolean> lockAllAsync(Collection<KeyCacheObject> keys,
        long timeout,
        @Nullable IgniteTxLocalEx tx,
        boolean isInvalidate,
        boolean isRead,
        boolean retval,
        @Nullable TransactionIsolation isolation,
        long accessTtl);

    /**
     * @param key Key to remove.
     * @param ver Version to remove.
     */
    public void removeVersionedEntry(KeyCacheObject key, GridCacheVersion ver) {
        GridCacheEntryEx entry = peekEx(key);

        if (entry == null)
            return;

        if (entry.markObsoleteVersion(ver))
            removeEntry(entry);
    }

    /** {@inheritDoc} */
    @Override public void removeAll() throws IgniteCheckedException {
        try {
            AffinityTopologyVersion topVer;

            boolean retry;

            CacheOperationContext opCtx = ctx.operationContextPerCall();

            boolean skipStore = opCtx != null && opCtx.skipStore();

            do {
                retry = false;

                topVer = ctx.affinity().affinityTopologyVersion();

                // Send job to all data nodes.
                Collection<ClusterNode> nodes = ctx.grid().cluster().forDataNodes(name()).nodes();

                if (!nodes.isEmpty()) {
                    ctx.kernalContext().task().setThreadContext(TC_SUBGRID, nodes);

                    retry = !ctx.kernalContext().task().execute(
                        new RemoveAllTask(ctx.name(), topVer, skipStore), null).get();
                }
            }
            while (ctx.affinity().affinityTopologyVersion().compareTo(topVer) != 0 || retry);
        }
        catch (ClusterGroupEmptyCheckedException ignore) {
            if (log.isDebugEnabled())
                log.debug("All remote nodes left while cache remove [cacheName=" + name() + "]");
        }
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<?> removeAllAsync() {
        GridFutureAdapter<Void> opFut = new GridFutureAdapter<>();

        AffinityTopologyVersion topVer = ctx.affinity().affinityTopologyVersion();

        CacheOperationContext opCtx = ctx.operationContextPerCall();

        boolean skipStore = opCtx != null && opCtx.skipStore();

        removeAllAsync(opFut, topVer, skipStore);

        return opFut;
    }

    /**
     * @param opFut Future.
     * @param topVer Topology version.
     * @param skipStore Skip store flag.
     */
    private void removeAllAsync(final GridFutureAdapter<Void> opFut, final AffinityTopologyVersion topVer,
        final boolean skipStore) {
        Collection<ClusterNode> nodes = ctx.grid().cluster().forDataNodes(name()).nodes();

        if (!nodes.isEmpty()) {
            ctx.kernalContext().task().setThreadContext(TC_SUBGRID, nodes);

            IgniteInternalFuture<Boolean> rmvAll = ctx.kernalContext().task().execute(
                new RemoveAllTask(ctx.name(), topVer, skipStore), null);

            rmvAll.listen(new IgniteInClosure<IgniteInternalFuture<Boolean>>() {
                @Override public void apply(IgniteInternalFuture<Boolean> fut) {
                    try {
                        boolean retry = !fut.get();

                        AffinityTopologyVersion topVer0 = ctx.affinity().affinityTopologyVersion();

                        if (topVer0.equals(topVer) && !retry)
                            opFut.onDone();
                        else
                            removeAllAsync(opFut, topVer0, skipStore);
                    }
                    catch (ClusterGroupEmptyCheckedException ignore) {
                        if (log.isDebugEnabled())
                            log.debug("All remote nodes left while cache remove [cacheName=" + name() + "]");

                        opFut.onDone();
                    }
                    catch (IgniteCheckedException e) {
                        opFut.onDone(e);
                    }
                    catch (Error e) {
                        opFut.onDone(e);

                        throw e;
                    }
                }
            });
        }
        else
            opFut.onDone();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridDistributedCacheAdapter.class, this, "super", super.toString());
    }

    /**
     * Remove task.
     */
    @GridInternal
    private static class RemoveAllTask extends ComputeTaskAdapter<Object, Boolean> {
        /** */
        private static final long serialVersionUID = 0L;

        /** Cache name. */
        private final String cacheName;

        /** Affinity topology version. */
        private final AffinityTopologyVersion topVer;

        /** Skip store flag. */
        private final boolean skipStore;

        /**
         * @param cacheName Cache name.
         * @param topVer Affinity topology version.
         * @param skipStore Skip store flag.
         */
        public RemoveAllTask(String cacheName, AffinityTopologyVersion topVer, boolean skipStore) {
            this.cacheName = cacheName;
            this.topVer = topVer;
            this.skipStore = skipStore;
        }

        /** {@inheritDoc} */
        @Nullable @Override public Map<? extends ComputeJob, ClusterNode> map(List<ClusterNode> subgrid,
            @Nullable Object arg) throws IgniteException {
            Map<ComputeJob, ClusterNode> jobs = new HashMap();

            for (ClusterNode node : subgrid)
                jobs.put(new GlobalRemoveAllJob(cacheName, topVer, skipStore), node);

            return jobs;
        }

        /** {@inheritDoc} */
        @Override public ComputeJobResultPolicy result(ComputeJobResult res, List<ComputeJobResult> rcvd) {
            IgniteException e = res.getException();

            if (e != null) {
                if (e instanceof ClusterTopologyException)
                    return ComputeJobResultPolicy.WAIT;

                throw new IgniteException("Remote job threw exception.", e);
            }

            return ComputeJobResultPolicy.WAIT;
        }

        /** {@inheritDoc} */
        @Nullable @Override public Boolean reduce(List<ComputeJobResult> results) throws IgniteException {
            for (ComputeJobResult locRes : results) {
                if (locRes != null && (locRes.getException() != null || !locRes.<Boolean>getData()))
                    return false;
            }

            return true;
        }
    }
    /**
     * Internal job which performs remove all primary key mappings
     * operation on a cache with the given name.
     */
    @GridInternal
    private static class GlobalRemoveAllJob<K,V>  extends TopologyVersionAwareJob {
        /** */
        private static final long serialVersionUID = 0L;

        /** Skip store flag. */
        private final boolean skipStore;

        /**
         * @param cacheName Cache name.
         * @param topVer Topology version.
         * @param skipStore Skip store flag.
         */
        private GlobalRemoveAllJob(String cacheName, @NotNull AffinityTopologyVersion topVer, boolean skipStore) {
            super(cacheName, topVer);

            this.skipStore = skipStore;
        }

        /** {@inheritDoc} */
        @Nullable @Override public Object localExecute(@Nullable IgniteInternalCache cache0) {
            GridCacheAdapter cache = ((IgniteKernal) ignite).context().cache().internalCache(cacheName);

            if (cache == null)
                return true;

            final GridCacheContext<K, V> ctx = cache.context();

            ctx.gate().enter();

            try {
                if (!ctx.affinity().affinityTopologyVersion().equals(topVer))
                    return false; // Ignore this remove request because remove request will be sent again.

                GridDhtCacheAdapter<K, V> dht;
                GridNearCacheAdapter<K, V> near = null;

                if (cache instanceof GridNearCacheAdapter) {
                    near = ((GridNearCacheAdapter<K, V>) cache);
                    dht = near.dht();
                }
                else
                    dht = (GridDhtCacheAdapter<K, V>) cache;

                try (DataStreamerImpl<KeyCacheObject, Object> dataLdr =
                         (DataStreamerImpl) ignite.dataStreamer(cacheName)) {
                    ((DataStreamerImpl) dataLdr).maxRemapCount(0);

                    dataLdr.skipStore(skipStore);

                    dataLdr.receiver(DataStreamerCacheUpdaters.<KeyCacheObject, Object>batched());

                    for (int part : ctx.affinity().primaryPartitions(ctx.localNodeId(), topVer)) {
                        GridDhtLocalPartition locPart = dht.topology().localPartition(part, topVer, false);

                        if (locPart == null || (ctx.rebalanceEnabled() && locPart.state() != OWNING) || !locPart.reserve())
                            return false;

                        try {
                            if (!locPart.isEmpty()) {
                                for (GridDhtCacheEntry o : locPart.entries()) {
                                    if (!o.obsoleteOrDeleted())
                                        dataLdr.removeDataInternal(o.key());
                                }
                            }

                            GridCloseableIterator<Map.Entry<byte[], GridCacheSwapEntry>> iter =
                                dht.context().swap().iterator(part);

                            if (iter != null) {
                                for (Map.Entry<byte[], GridCacheSwapEntry> e : iter)
                                    dataLdr.removeDataInternal(ctx.toCacheKeyObject(e.getKey()));
                            }
                        }
                        finally {
                            locPart.release();
                        }
                    }
                }

                if (near != null) {
                    GridCacheVersion obsoleteVer = ctx.versions().next();

                    for (GridCacheEntryEx e : near.map().allEntries0()) {
                        if (!e.valid(topVer) && e.markObsolete(obsoleteVer))
                            near.removeEntry(e);
                    }
                }
            }
            catch (IgniteCheckedException e) {
                throw U.convertException(e);
            }
            finally {
                ctx.gate().leave();
            }

            return true;
        }
    }
}