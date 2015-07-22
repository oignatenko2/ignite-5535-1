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

package org.apache.ignite.internal.processors.cache.distributed.near;

import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.processors.affinity.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.cache.distributed.*;
import org.apache.ignite.internal.processors.cache.distributed.dht.*;
import org.apache.ignite.internal.processors.cache.transactions.*;
import org.apache.ignite.internal.processors.cache.version.*;
import org.apache.ignite.internal.transactions.*;
import org.apache.ignite.internal.util.future.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.plugin.security.*;
import org.apache.ignite.transactions.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;

import static org.apache.ignite.transactions.TransactionConcurrency.*;

/**
 * Near cache for transactional cache.
 */
public class GridNearTransactionalCache<K, V> extends GridNearCacheAdapter<K, V> {
    /** */
    private static final long serialVersionUID = 0L;

    /** DHT cache. */
    private GridDhtCache<K, V> dht;

    /**
     * Empty constructor required for {@link Externalizable}.
     */
    public GridNearTransactionalCache() {
        // No-op.
    }

    /**
     * @param ctx Context.
     */
    public GridNearTransactionalCache(GridCacheContext<K, V> ctx) {
        super(ctx);
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteCheckedException {
        super.start();

        ctx.io().addHandler(ctx.cacheId(), GridNearGetResponse.class, new CI2<UUID, GridNearGetResponse>() {
            @Override public void apply(UUID nodeId, GridNearGetResponse res) {
                processGetResponse(nodeId, res);
            }
        });

        ctx.io().addHandler(ctx.cacheId(), GridNearLockResponse.class, new CI2<UUID, GridNearLockResponse>() {
            @Override public void apply(UUID nodeId, GridNearLockResponse res) {
                processLockResponse(nodeId, res);
            }
        });
    }

    /**
     * @param dht DHT cache.
     */
    public void dht(GridDhtCache<K, V> dht) {
        this.dht = dht;
    }

    /** {@inheritDoc} */
    @Override public GridDhtCache<K, V> dht() {
        return dht;
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Map<K, V>> getAllAsync(
        @Nullable final Collection<? extends K> keys,
        boolean forcePrimary,
        boolean skipTx,
        @Nullable final GridCacheEntryEx entry,
        @Nullable UUID subjId,
        String taskName,
        final boolean deserializePortable,
        final boolean skipVals
    ) {
        ctx.checkSecurity(SecurityPermission.CACHE_READ);

        if (F.isEmpty(keys))
            return new GridFinishedFuture<>(Collections.<K, V>emptyMap());

        if (keyCheck)
            validateCacheKeys(keys);

        IgniteTxLocalAdapter tx = ctx.tm().threadLocalTx(ctx);

        CacheOperationContext opCtx = ctx.operationContextPerCall();

        final boolean skipStore = opCtx != null && opCtx.skipStore();

        if (tx != null && !tx.implicit() && !skipTx) {
            return asyncOp(tx, new AsyncOp<Map<K, V>>(keys) {
                @Override public IgniteInternalFuture<Map<K, V>> op(IgniteTxLocalAdapter tx) {
                    return tx.getAllAsync(ctx,
                        ctx.cacheKeysView(keys),
                        entry,
                        deserializePortable,
                        skipVals,
                        false,
                        skipStore);
                }
            });
        }

        subjId = ctx.subjectIdPerCall(subjId, opCtx);

        return loadAsync(null,
            ctx.cacheKeysView(keys),
            false,
            forcePrimary,
            subjId,
            taskName,
            deserializePortable,
            skipVals ? null : opCtx != null ? opCtx.expiry() : null,
            skipVals,
            skipStore);
    }

    /**
     * @param tx Transaction.
     * @param keys Keys to load.
     * @param readThrough Read through flag.
     * @param deserializePortable Deserialize portable flag.
     * @param expiryPlc Expiry policy.
     * @param skipVals Skip values flag.
     * @return Future.
     */
    IgniteInternalFuture<Map<K, V>> txLoadAsync(GridNearTxLocal tx,
        @Nullable Collection<KeyCacheObject> keys,
        boolean readThrough,
        boolean deserializePortable,
        @Nullable IgniteCacheExpiryPolicy expiryPlc,
        boolean skipVals) {
        assert tx != null;

        GridNearGetFuture<K, V> fut = new GridNearGetFuture<>(ctx,
            keys,
            readThrough,
            false,
            false,
            tx,
            CU.subjectId(tx, ctx.shared()),
            tx.resolveTaskName(),
            deserializePortable,
            expiryPlc,
            skipVals);

        // init() will register future for responses if it has remote mappings.
        fut.init();

        return fut;
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     */
    @SuppressWarnings({"RedundantTypeArguments"})
    public void clearLocks(UUID nodeId, GridDhtUnlockRequest req) {
        assert nodeId != null;

        GridCacheVersion obsoleteVer = ctx.versions().next();

        List<KeyCacheObject> keys = req.nearKeys();

        if (keys != null) {
            AffinityTopologyVersion topVer = ctx.affinity().affinityTopologyVersion();

            for (KeyCacheObject key : keys) {
                while (true) {
                    GridDistributedCacheEntry entry = peekExx(key);

                    try {
                        if (entry != null) {
                            entry.doneRemote(
                                req.version(),
                                req.version(),
                                null,
                                req.committedVersions(),
                                req.rolledbackVersions(),
                                /*system invalidate*/false);

                            // Note that we don't reorder completed versions here,
                            // as there is no point to reorder relative to the version
                            // we are about to remove.
                            if (entry.removeLock(req.version())) {
                                if (log.isDebugEnabled())
                                    log.debug("Removed lock [lockId=" + req.version() + ", key=" + key + ']');

                                // Try to evict near entry dht-mapped locally.
                                evictNearEntry(entry, obsoleteVer, topVer);
                            }
                            else {
                                if (log.isDebugEnabled())
                                    log.debug("Received unlock request for unknown candidate " +
                                        "(added to cancelled locks set): " + req);
                            }

                            ctx.evicts().touch(entry, topVer);
                        }
                        else if (log.isDebugEnabled())
                            log.debug("Received unlock request for entry that could not be found: " + req);

                        break;
                    }
                    catch (GridCacheEntryRemovedException ignored) {
                        if (log.isDebugEnabled())
                            log.debug("Received remove lock request for removed entry (will retry) [entry=" + entry +
                                ", req=" + req + ']');
                    }
                }
            }
        }
    }

    /**
     * @param nodeId Primary node ID.
     * @param req Request.
     * @return Remote transaction.
     * @throws IgniteCheckedException If failed.
     * @throws GridDistributedLockCancelledException If lock has been cancelled.
     */
    @SuppressWarnings({"RedundantTypeArguments"})
    @Nullable public GridNearTxRemote startRemoteTx(UUID nodeId, GridDhtLockRequest req)
        throws IgniteCheckedException, GridDistributedLockCancelledException {
        List<KeyCacheObject> nearKeys = req.nearKeys();

        GridNearTxRemote tx = null;

        ClassLoader ldr = ctx.deploy().globalLoader();

        if (ldr != null) {
            Collection<IgniteTxKey> evicted = null;

            for (int i = 0; i < nearKeys.size(); i++) {
                KeyCacheObject key = nearKeys.get(i);

                if (key == null)
                    continue;

                IgniteTxKey txKey = ctx.txKey(key);

                Collection<GridCacheMvccCandidate> cands = req.candidatesByIndex(i);

                if (log.isDebugEnabled())
                    log.debug("Unmarshalled key: " + key);

                GridNearCacheEntry entry = null;

                while (true) {
                    try {
                        entry = peekExx(key);

                        if (entry != null) {
                            // Handle implicit locks for pessimistic transactions.
                            if (req.inTx()) {
                                tx = ctx.tm().nearTx(req.version());

                                if (tx == null) {
                                    tx = new GridNearTxRemote(
                                        ctx.shared(),
                                        nodeId,
                                        req.nearNodeId(),
                                        req.nearXidVersion(),
                                        req.threadId(),
                                        req.version(),
                                        null,
                                        ctx.systemTx(),
                                        ctx.ioPolicy(),
                                        PESSIMISTIC,
                                        req.isolation(),
                                        req.isInvalidate(),
                                        req.timeout(),
                                        req.txSize(),
                                        req.subjectId(),
                                        req.taskNameHash()
                                    );

                                    tx = ctx.tm().onCreated(null, tx);

                                    if (tx == null || !ctx.tm().onStarted(tx))
                                        throw new IgniteTxRollbackCheckedException("Failed to acquire lock " +
                                            "(transaction has been completed): " + req.version());
                                }

                                tx.addEntry(ctx,
                                    txKey,
                                    GridCacheOperation.NOOP,
                                    null /*Value.*/,
                                    null /*dr version*/,
                                    req.skipStore());
                            }

                            // Add remote candidate before reordering.
                            // Owned candidates should be reordered inside entry lock.
                            entry.addRemote(
                                req.nodeId(),
                                nodeId,
                                req.threadId(),
                                req.version(),
                                req.timeout(),
                                tx != null,
                                tx != null && tx.implicitSingle(),
                                req.owned(entry.key())
                            );

                            assert cands.isEmpty() : "Received non-empty candidates in dht lock request: " + cands;

                            if (!req.inTx())
                                ctx.evicts().touch(entry, req.topologyVersion());
                        }
                        else {
                            if (evicted == null)
                                evicted = new LinkedList<>();

                            evicted.add(txKey);
                        }

                        // Double-check in case if sender node left the grid.
                        if (ctx.discovery().node(req.nodeId()) == null) {
                            if (log.isDebugEnabled())
                                log.debug("Node requesting lock left grid (lock request will be ignored): " + req);

                            if (tx != null)
                                tx.rollback();

                            return null;
                        }

                        // Entry is legit.
                        break;
                    }
                    catch (GridCacheEntryRemovedException ignored) {
                        assert entry.obsoleteVersion() != null : "Obsolete flag not set on removed entry: " +
                            entry;

                        if (log.isDebugEnabled())
                            log.debug("Received entry removed exception (will retry on renewed entry): " + entry);

                        if (tx != null) {
                            tx.clearEntry(txKey);

                            if (log.isDebugEnabled())
                                log.debug("Cleared removed entry from remote transaction (will retry) [entry=" +
                                    entry + ", tx=" + tx + ']');
                        }
                    }
                }
            }

            if (tx != null && evicted != null) {
                assert !evicted.isEmpty();

                for (IgniteTxKey evict : evicted)
                    tx.addEvicted(evict);
            }
        }
        else {
            String err = "Failed to acquire deployment class loader for message: " + req;

            U.warn(log, err);

            throw new IgniteCheckedException(err);
        }

        return tx;
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    private void processLockResponse(UUID nodeId, GridNearLockResponse res) {
        assert nodeId != null;
        assert res != null;

        GridNearLockFuture fut = (GridNearLockFuture)ctx.mvcc().<Boolean>future(res.version(),
            res.futureId());

        if (fut != null)
            fut.onResult(nodeId, res);
    }

    /** {@inheritDoc} */
    @Override protected IgniteInternalFuture<Boolean> lockAllAsync(
        Collection<KeyCacheObject> keys,
        long timeout,
        IgniteTxLocalEx tx,
        boolean isInvalidate,
        boolean isRead,
        boolean retval,
        TransactionIsolation isolation,
        long accessTtl
    ) {
        CacheOperationContext opCtx = ctx.operationContextPerCall();

        GridNearLockFuture fut = new GridNearLockFuture(ctx,
            keys,
            (GridNearTxLocal)tx,
            isRead,
            retval,
            timeout,
            accessTtl,
            CU.empty0(),
            opCtx != null && opCtx.skipStore());

        if (!ctx.mvcc().addFuture(fut))
            throw new IllegalStateException("Duplicate future ID: " + fut);

        fut.map();

        return fut;
    }

    /**
     * @param e Transaction entry.
     * @param topVer Topology version.
     * @return {@code True} if entry is locally mapped as a primary or back up node.
     */
    protected boolean isNearLocallyMapped(GridCacheEntryEx e, AffinityTopologyVersion topVer) {
        return ctx.affinity().belongs(ctx.localNode(), e.partition(), topVer);
    }

    /**
     *
     * @param e Entry to evict if it qualifies for eviction.
     * @param obsoleteVer Obsolete version.
     * @param topVer Topology version.
     * @return {@code True} if attempt was made to evict the entry.
     */
    protected boolean evictNearEntry(GridCacheEntryEx e, GridCacheVersion obsoleteVer, AffinityTopologyVersion topVer) {
        assert e != null;
        assert obsoleteVer != null;

        if (isNearLocallyMapped(e, topVer)) {
            if (log.isDebugEnabled())
                log.debug("Evicting dht-local entry from near cache [entry=" + e + ", tx=" + this + ']');

            if (e.markObsolete(obsoleteVer))
                return true;
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public void unlockAll(Collection<? extends K> keys) {
        if (keys.isEmpty())
            return;

        try {
            GridCacheVersion ver = null;

            int keyCnt = -1;

            Map<ClusterNode, GridNearUnlockRequest> map = null;

            Collection<KeyCacheObject> locKeys = new LinkedList<>();

            for (K key : keys) {
                while (true) {
                    KeyCacheObject cacheKey = ctx.toCacheKeyObject(key);

                    GridDistributedCacheEntry entry = peekExx(cacheKey);

                    if (entry == null)
                        break; // While.

                    try {
                        GridCacheMvccCandidate cand = entry.candidate(ctx.nodeId(), Thread.currentThread().getId());

                        AffinityTopologyVersion topVer = AffinityTopologyVersion.NONE;

                        if (cand != null) {
                            assert cand.nearLocal() : "Got non-near-local candidate in near cache: " + cand;

                            ver = cand.version();

                            if (map == null) {
                                Collection<ClusterNode> affNodes = CU.allNodes(ctx, cand.topologyVersion());

                                if (F.isEmpty(affNodes))
                                    return;

                                keyCnt = (int)Math.ceil((double)keys.size() / affNodes.size());

                                map = U.newHashMap(affNodes.size());
                            }

                            topVer = cand.topologyVersion();

                            // Send request to remove from remote nodes.
                            ClusterNode primary = ctx.affinity().primary(key, topVer);

                            if (primary == null) {
                                if (log.isDebugEnabled())
                                    log.debug("Failed to unlock key (all partition nodes left the grid).");

                                break;
                            }

                            GridNearUnlockRequest req = map.get(primary);

                            if (req == null) {
                                map.put(primary, req = new GridNearUnlockRequest(ctx.cacheId(), keyCnt));

                                req.version(ver);
                            }

                            // Remove candidate from local node first.
                            GridCacheMvccCandidate rmv = entry.removeLock();

                            if (rmv != null) {
                                if (!rmv.reentry()) {
                                    if (ver != null && !ver.equals(rmv.version()))
                                        throw new IgniteCheckedException("Failed to unlock (if keys were locked separately, " +
                                            "then they need to be unlocked separately): " + keys);

                                    if (!primary.isLocal()) {
                                        assert req != null;

                                        req.addKey(
                                            entry.key(),
                                            ctx);
                                    }
                                    else
                                        locKeys.add(cacheKey);

                                    if (log.isDebugEnabled())
                                        log.debug("Removed lock (will distribute): " + rmv);
                                }
                                else if (log.isDebugEnabled())
                                    log.debug("Current thread still owns lock (or there are no other nodes)" +
                                        " [lock=" + rmv + ", curThreadId=" + Thread.currentThread().getId() + ']');
                            }
                        }

                        assert !topVer.equals(AffinityTopologyVersion.NONE) || cand == null;

                        if (topVer.equals(AffinityTopologyVersion.NONE))
                            topVer = ctx.affinity().affinityTopologyVersion();

                        ctx.evicts().touch(entry, topVer);

                        break;
                    }
                    catch (GridCacheEntryRemovedException ignore) {
                        if (log.isDebugEnabled())
                            log.debug("Attempted to unlock removed entry (will retry): " + entry);
                    }
                }
            }

            if (ver == null)
                return;

            for (Map.Entry<ClusterNode, GridNearUnlockRequest> mapping : map.entrySet()) {
                ClusterNode n = mapping.getKey();

                GridDistributedUnlockRequest req = mapping.getValue();

                if (n.isLocal())
                    dht.removeLocks(ctx.nodeId(), req.version(), locKeys, true);
                else if (!F.isEmpty(req.keys()))
                    // We don't wait for reply to this message.
                    ctx.io().send(n, req, ctx.ioPolicy());
            }
        }
        catch (IgniteCheckedException ex) {
            U.error(log, "Failed to unlock the lock for keys: " + keys, ex);
        }
    }

    /**
     * Removes locks regardless of whether they are owned or not for given
     * version and keys.
     *
     * @param ver Lock version.
     * @param keys Keys.
     */
    @SuppressWarnings({"unchecked"})
    public void removeLocks(GridCacheVersion ver, Collection<KeyCacheObject> keys) {
        if (keys.isEmpty())
            return;

        try {
            int keyCnt = -1;

            Map<ClusterNode, GridNearUnlockRequest> map = null;

            for (KeyCacheObject key : keys) {
                // Send request to remove from remote nodes.
                GridNearUnlockRequest req = null;

                while (true) {
                    GridDistributedCacheEntry entry = peekExx(key);

                    try {
                        if (entry != null) {
                            GridCacheMvccCandidate cand = entry.candidate(ver);

                            if (cand != null) {
                                if (map == null) {
                                    Collection<ClusterNode> affNodes = CU.allNodes(ctx, cand.topologyVersion());

                                    if (F.isEmpty(affNodes))
                                        return;

                                    keyCnt = (int)Math.ceil((double)keys.size() / affNodes.size());

                                    map = U.newHashMap(affNodes.size());
                                }

                                ClusterNode primary = ctx.affinity().primary(key, cand.topologyVersion());

                                if (primary == null) {
                                    if (log.isDebugEnabled())
                                        log.debug("Failed to unlock key (all partition nodes left the grid).");

                                    break;
                                }

                                if (!primary.isLocal()) {
                                    req = map.get(primary);

                                    if (req == null) {
                                        map.put(primary, req = new GridNearUnlockRequest(ctx.cacheId(), keyCnt));

                                        req.version(ver);
                                    }
                                }

                                // Remove candidate from local node first.
                                if (entry.removeLock(cand.version())) {
                                    if (primary.isLocal()) {
                                        dht.removeLocks(primary.id(), ver, F.asList(key), true);

                                        assert req == null;

                                        continue;
                                    }

                                    req.addKey(entry.key(), ctx);
                                }
                            }
                        }

                        break;
                    }
                    catch (GridCacheEntryRemovedException ignored) {
                        if (log.isDebugEnabled())
                            log.debug("Attempted to remove lock from removed entry (will retry) [rmvVer=" +
                                ver + ", entry=" + entry + ']');
                    }
                }
            }

            if (map == null || map.isEmpty())
                return;

            Collection<GridCacheVersion> committed = ctx.tm().committedVersions(ver);
            Collection<GridCacheVersion> rolledback = ctx.tm().rolledbackVersions(ver);

            for (Map.Entry<ClusterNode, GridNearUnlockRequest> mapping : map.entrySet()) {
                ClusterNode n = mapping.getKey();

                GridDistributedUnlockRequest req = mapping.getValue();

                if (!F.isEmpty(req.keys())) {
                    req.completedVersions(committed, rolledback);

                    // We don't wait for reply to this message.
                    ctx.io().send(n, req, ctx.ioPolicy());
                }
            }
        }
        catch (IgniteCheckedException ex) {
            U.error(log, "Failed to unlock the lock for keys: " + keys, ex);
        }
    }

    /** {@inheritDoc} */
    @Override public void onDeferredDelete(GridCacheEntryEx entry, GridCacheVersion ver) {
        assert false : "Should not be called";
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridNearTransactionalCache.class, this);
    }
}