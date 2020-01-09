package com.ms.silverking.cloud.dht.common;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.ms.silverking.cloud.dht.NamespaceCreationOptions;
import com.ms.silverking.cloud.dht.NamespaceOptions;
import com.ms.silverking.cloud.dht.client.ClientDHTConfiguration;
import com.ms.silverking.cloud.dht.client.ClientDHTConfigurationProvider;
import com.ms.silverking.cloud.dht.client.Namespace;
import com.ms.silverking.cloud.dht.client.NamespaceCreationException;
import com.ms.silverking.cloud.dht.client.NamespaceModificationException;
import com.ms.silverking.cloud.dht.client.impl.NamespaceOptionsClientCS;
import com.ms.silverking.cloud.dht.client.impl.SimpleNamespaceCreator;
import com.ms.silverking.cloud.dht.daemon.storage.NamespaceNotCreatedException;
import com.ms.silverking.cloud.dht.daemon.storage.NamespaceOptionsClientSS;
import com.ms.silverking.cloud.dht.meta.DHTMetaWatcher;
import com.ms.silverking.cloud.zookeeper.ZooKeeperConfig;
import com.ms.silverking.collection.HashedListMap;
import com.ms.silverking.log.Log;
import com.ms.silverking.thread.lwt.LWTThreadUtil;

public abstract class NamespaceOptionsClientBase implements NamespaceOptionsClientCS, NamespaceOptionsClientSS {
    private final ClientDHTConfiguration    dhtConfig;
    private final Map<Long, NamespaceOptions> systemNamespaceOptions;

    private NamespaceCreationOptions nsCreationOptions;
    private DHTMetaWatcher dhtMetaWatcher;

    private static final int    dhtMetaWatcherIntervalMillis = 60 * 60 * 1000;
    protected static final boolean    debug = false;
    private static final ConcurrentMap<String,NamespaceCreationOptions> nsCreationOptionsMap;

    static {
        nsCreationOptionsMap = new ConcurrentHashMap<>();
    }

    public NamespaceOptionsClientBase(ClientDHTConfigurationProvider dhtConfigurationProvider) {
        SimpleNamespaceCreator  nsCreator;

        this.dhtConfig = dhtConfigurationProvider.getClientDHTConfiguration();
        systemNamespaceOptions = new HashMap<>();
        nsCreator = new SimpleNamespaceCreator();
        systemNamespaceOptions.put(nsCreator.createNamespace(com.ms.silverking.cloud.dht.client.Namespace.systemName).contextAsLong(), DHTConstants.dynamicNamespaceOptions);
        systemNamespaceOptions.put(nsCreator.createNamespace(com.ms.silverking.cloud.dht.client.Namespace.nodeName).contextAsLong(), DHTConstants.dynamicNamespaceOptions);
        systemNamespaceOptions.put(nsCreator.createNamespace(Namespace.replicasName).contextAsLong(), DHTConstants.dynamicNamespaceOptions);
    }

    ////// ====== Abstract method for concrete implementations (e.g.: NSP or ZooKeeper) ======
    /**
     * Get the default relative timeout in millis for higher-level wait on getNamespacePropertiesWithTimeout
     * @return timeout time in millis
     */
    abstract protected long getDefaultRelTimeoutMillis();

    /**
     *  Write the given NamespaceProperties, implementation itself shall maintain
     * @param nsContext hashed-namespace name
     * @param nsProperties nsProperties to write
     * @throws NamespacePropertiesPutException if encountered any exception
     */
    abstract protected void putNamespaceProperties(long nsContext, NamespaceProperties nsProperties) throws NamespacePropertiesPutException;

    /**
     * Read the latest full version of NamespaceProperties with creationTime issued; <b>null</b> shall be returned if not exists
     * @param nsContext hashed-namespace name
     * @return the latest version NamespaceProperties with creationTime issued, or <b>null</b> if not exists
     * @throws NamespacePropertiesRetrievalException if encountered any exception (except value not exists case, which shall return null)
     */
    abstract protected NamespaceProperties retrieveFullNamespaceProperties(long nsContext) throws NamespacePropertiesRetrievalException;

    /**
     * Delete nsProperties for a specific namespace (could be soft-delete or hard-delete)
     * @param nsContext hashed-namespace name
     * @throws NamespacePropertiesDeleteException if encountered any exception (except value not exists case, which shall return null)
     */
    abstract protected void deleteNamespaceProperties(long nsContext) throws NamespacePropertiesDeleteException;

    /**
     * @return a identifier name for the concrete implementation
     */
    abstract protected String implementationName();

    /**
     * This API is for backward compatibility, since some implementation may still need properties file to bootstrap
     * (Exposed for serverside use)
     *
     * @param nsDir the namespace data directory
     * @return the full nsProperties for ns bootstrap, or <b>null</b> if nsProperties not found (e.g. ns has been deleted)
     * @throws NamespacePropertiesRetrievalException if encountered any exception (except value not exists case, which shall return null)
     */
    abstract public NamespaceProperties getNsPropertiesForRecovery(File nsDir) throws NamespacePropertiesRetrievalException;

    public void createNamespace(String nsName, NamespaceProperties nsProperties) throws NamespaceCreationException {
        ensureNSCreationOptionsSet();
        if (debug) {
            System.out.printf("canBeExplicitlyCreated %s\n", nsCreationOptions.canBeExplicitlyCreated(nsName));
        }
        if (nsCreationOptions.canBeExplicitlyCreated(nsName)) {
            try {
                storeNamespaceProperties(nsName, nsProperties, false);
            } catch (NamespaceModificationException e) {
                throw new NamespaceCreationException("createNamespace is disallowed on an existing ns [" + nsName + "] with modified nsOptions", e);
            }
        } else {
            throw new NamespaceCreationException("Namespace creation not allowed for [" + nsName + "] due to the filter rule: " + nsCreationOptions);
        }
    }

    public void modifyNamespace(String nsName, NamespaceProperties nsProperties) throws NamespaceModificationException {
        ensureNSCreationOptionsSet();
        if (debug) {
            System.out.printf("canBeExplicitlyCreated %s\n", nsCreationOptions.canBeExplicitlyCreated(nsName));
        }
        if (nsCreationOptions.canBeExplicitlyCreated(nsName)) {
            try {
                storeNamespaceProperties(nsName, nsProperties, true);
            } catch (NamespaceCreationException e) {
                throw new NamespaceModificationException(e);
            }
        } else {
            throw new NamespaceModificationException("Namespace creation not allowed for [" + nsName + "] due to the filter rule: " + nsCreationOptions);
        }
    }

    public void deleteNamespace(String nsName) throws NamespacePropertiesDeleteException {
        /*
         * These codes might be updated in the future; For now:
         * - SNP impl will simply throw Exception since server side cannot handle such request
         * - ZK impl will work and deleteAllNamespaceProperties is sufficient as clientside actions (server can handle the deletion in ZK server, since ZK impl has no dependency on properties file for bootstrap)
         */
        long    nsContext;

        nsContext = NamespaceUtil.nameToContext(nsName);
        // sufficient for ZKImpl as clientside actions for now
        deleteNamespaceProperties(nsContext);
    }

    ////// ====== Internal shared logic ======
    // This method for now is used in client side, so it will downgrade nsName to nsContext, and enrich the nsProperties
    private void storeNamespaceProperties(String nsName, NamespaceProperties nsProperties, boolean mutate) throws NamespaceCreationException, NamespaceModificationException {
        boolean    retry;
        long       nsContext;

        if (nsProperties.hasName() && !nsProperties.getName().equals(nsName)) {
            Log.warning("Wrong call path could be invoked: nsName=[" + nsName + "] will overwrite nsProperties.name=[" + nsProperties.getName() + "]");
        }
        // Enrich NamespaceProperties before downgrade to nsContext
        nsProperties = nsProperties.name(nsName);
        nsContext = NamespaceUtil.nameToContext(nsName);
        do {
            NamespaceProperties existingProperties;

            retry = false; // need to be reset at each iteration in case of "infinite loop"
            try {
                existingProperties = getNamespacePropertiesWithTimeout(nsContext, getDefaultRelTimeoutMillis());
            } catch (TimeoutException te) {
                Log.warning("Failed to store namespace due to timeout "+ String.format("%s", nsName));
                throw new NamespaceCreationException(Long.toHexString(nsContext), te);
            } catch (NamespacePropertiesRetrievalException re) {
                Log.warning(re);
                throw new NamespaceCreationException("RetrievalException during first property check", re);
            }

            if (existingProperties != null) {
                if (!existingProperties.partialEquals(nsProperties)) {
                    if (mutate) {
                        if (existingProperties.canBeReplacedBy(nsProperties)) {
                            retry = tryModifyNamespaceProperties(nsContext, nsProperties);
                        } else {
                            String errMsg = "Trying to modify namespace [" + nsName + "] NamespaceProperties in mutable mode";
                            Log.warning(errMsg);
                            Log.warning("existingProperties", existingProperties);
                            Log.warning("nsProperties", nsProperties);
                            throw new NamespaceModificationException(errMsg + "(some immutable of fields of NamespaceProperties may be updated)");
                        }
                    } else {
                        String errMsg = "Trying to modify namespace [" + nsName + "] NamespaceProperties in immutable mode";
                        Log.warning(errMsg);
                        Log.warning("existingProperties", existingProperties);
                        Log.warning("nsProperties", nsProperties);
                        throw new NamespaceCreationException(errMsg + "(may need to explicitly call modifyNamespace method for mutation)");
                    }
                } else {
                    // Already created with the same options. No further action required.
                    retry = false; // explicitly set to false in case of "infinite loop"
                }
            } else {
                // No existing property exists
                retry = tryCreateNamespaceProperties(nsContext, nsProperties);
            }
        } while (retry);
    }

    private boolean tryCreateNamespaceProperties(long nsContext, NamespaceProperties nsProperties) throws NamespaceCreationException {
        boolean needRetry;

        needRetry = false;
        try {
            putNamespaceProperties(nsContext, nsProperties);
        } catch (NamespacePropertiesPutException pe) {
            // If a simultaneous put is detected, then we must check for
            // consistency among the created namespaces.
            // For other errors, we try this also on the
            try {
                boolean optionsMatch;

                try {
                    optionsMatch = verifyNamespaceProperties(nsContext, nsProperties);
                    if (!optionsMatch) {
                        throw new NamespaceCreationException("Namespace already created with incompatible properties");
                    }
                } catch (NamespaceNotCreatedException nnce) {
                    // Should not be possible any more, but leave old retry in for now.
                    needRetry = true;
                } catch (RuntimeException re) {
                    Log.logErrorWarning(re);
                    Log.warning(pe);
                    Log.logErrorWarning(pe, "Couldn't store options due to exception");
                }
            } catch (NamespacePropertiesRetrievalException re) {
                Log.warning("storeNamespaceProperties failing");
                Log.warning("PutException");
                pe.printStackTrace();
                Log.warning("RetrievalException");
                re.printStackTrace();
                throw new NamespaceCreationException(re);
            }
        }

        return needRetry;
    }


    private boolean tryModifyNamespaceProperties(long nsContext, NamespaceProperties nsProperties) throws NamespaceModificationException {
        try {
            putNamespaceProperties(nsContext, nsProperties);
            return false; // no retry for nsProperties modification
        } catch (NamespacePropertiesPutException pe) {
            throw new NamespaceModificationException(pe);
        }
    }

    private boolean verifyNamespaceProperties(long nsContext, NamespaceProperties nsProperties) throws NamespacePropertiesRetrievalException {
        NamespaceProperties    existingProperties;

        if (debug) {
            System.out.printf("verifyNamespaceProperties(%x, %s)\n", nsContext, nsProperties);
        }
        existingProperties = getNamespaceProperties(nsContext);
        if (debug) {
            System.out.println("Done verifyNamespaceProperties");
        }
        if (existingProperties == null) {
            throw new NamespaceNotCreatedException("No existing properties found");
        }
        return existingProperties.partialEquals(nsProperties);
    }

    private boolean verifyNamespaceOptions(long nsContext, NamespaceOptions nsOptions) throws NamespacePropertiesRetrievalException {
        NamespaceOptions    existingOptions;

        if (debug) {
            System.out.printf("verifyNamespaceOptions(%x, %s)\n", nsContext, nsOptions);
        }
        existingOptions = getNamespaceOptions(nsContext);
        if (debug) {
            System.out.println("Done verifyNamespaceOptions");
        }
        return existingOptions.equals(nsOptions);
    }

    public NamespaceProperties getNamespaceProperties(long nsContext) throws NamespacePropertiesRetrievalException {
        if (nsContext == NamespaceUtil.metaInfoNamespace.contextAsLong()) {
            return NamespaceUtil.metaInfoNamespaceProperties;
        } else {
            NamespaceOptions    nsOptions;

            nsOptions = systemNamespaceOptions.get(nsContext);
            if (nsOptions != null) {
                return new NamespaceProperties(nsOptions);
            } else {
                LWTThreadUtil.setBlocked();
                try {
                    return retrieveFullNamespaceProperties(nsContext);
                } finally {
                    LWTThreadUtil.setNonBlocked();
                }
            }
        }
    }

    public NamespaceOptions getNamespaceOptions(long nsContext) throws NamespacePropertiesRetrievalException {
        NamespaceProperties nsProperties = getNamespaceProperties(nsContext);
        return nsProperties == null ? null : nsProperties.getOptions();
    }

    public NamespaceProperties getNamespacePropertiesWithTimeout(long nsContext, long relTimeoutMillis) throws NamespacePropertiesRetrievalException, TimeoutException {
        if (nsContext == NamespaceUtil.metaInfoNamespace.contextAsLong()) {
            return NamespaceUtil.metaInfoNamespaceProperties;
        } else {
            NamespaceProperties    nsProperties;

            nsProperties = NamespaceUtil.systemNamespaceProperties.get(nsContext);
            if (nsProperties != null) {
                return nsProperties;
            } else {
                LWTThreadUtil.setBlocked();
                try {
                    NamespaceOptionsClientBase.ActiveOptionsRequest request;
                    boolean                 requestsOutstanding;

                    if (debug) {
                        System.out.printf("getNamespacePropertiesWithTimeout(%x) timeout %d\n", nsContext, relTimeoutMillis);
                    }
                    requestsOutstanding = false;
                    request = new NamespaceOptionsClientBase.ActiveOptionsRequest(nsContext, relTimeoutMillis);
                    activeOptionsRequestLock.lock();
                    try {
                        List<NamespaceOptionsClientBase.ActiveOptionsRequest> requestList;

                        requestList = activeOptionsRequests.getList(nsContext);
                        requestsOutstanding = requestList.size() > 0;
                        requestList.add(request);
                    } finally {
                        activeOptionsRequestLock.unlock();
                    }
                    if (debug) {
                        System.out.printf("requestsOutstanding %s\n", requestsOutstanding);
                    }
                    if (!requestsOutstanding) {
                        nsProperties = retrieveFullNamespaceProperties(nsContext);
                        activeOptionsRequestLock.lock();
                        try {
                            List<NamespaceOptionsClientBase.ActiveOptionsRequest>  requestList;

                            requestList = activeOptionsRequests.getList(nsContext);
                            for (int i = 1; i < requestList.size(); i++) {
                                requestList.get(i).setComplete(nsProperties);
                            }
                            requestList.clear();
                        } finally {
                            activeOptionsRequestLock.unlock();
                        }
                    } else {
                        if (debug) {
                            System.out.printf("%s::request.waitForCompletion()\n", implementationName());
                        }
                        nsProperties = request.waitForCompletion();
                        if (debug) {
                            System.out.printf("request.waitForCompletion() complete %s\n", nsProperties);
                        }
                        if (nsProperties == null) {
                            nsProperties = retrieveFullNamespaceProperties(nsContext);
                        }
                    }
                } finally {
                    LWTThreadUtil.setNonBlocked();
                }
                if (debug) {
                    System.out.printf("getNamespaceProperties storedDef %s\n", nsProperties);
                }
                return nsProperties;
            }
        }
    }

    public NamespaceProperties getNamespacePropertiesAndTryAutoCreate(String nsName) throws NamespacePropertiesRetrievalException, TimeoutException {
        NamespaceProperties    nsProperties;
        long                   nsContext;

        nsContext = NamespaceUtil.nameToContext(nsName);
        nsProperties = getNamespacePropertiesWithTimeout(nsContext, getDefaultRelTimeoutMillis());
        if (nsProperties == null) {
            ensureNSCreationOptionsSet();
            if (debug) {
                System.out.printf("canBeAutoCreated %s\n", nsCreationOptions.canBeAutoCreated(nsName));
            }
            if (nsCreationOptions.canBeAutoCreated(nsName)) {
                try {
                    storeNamespaceProperties(nsName, new NamespaceProperties(nsCreationOptions.getDefaultNamespaceOptions()), false);
                } catch (NamespaceCreationException | NamespaceModificationException e) {
                    throw new RuntimeException("Failure autocreating namespace", e);
                }
                nsProperties = getNamespacePropertiesWithTimeout(nsContext, getDefaultRelTimeoutMillis());
                if (nsProperties == null) {
                    throw new RuntimeException("Failure autocreating namespace. null nsProperties");
                }
            }
        }
        return nsProperties;
    }

    private void ensureNSCreationOptionsSet() {
        String              dhtName;
        ZooKeeperConfig     zkConfig;

        dhtName = dhtConfig.getName();
        zkConfig = dhtConfig.getZKConfig();

        if (debug) {
            Log.warning("ensureNSCreationOptionsSet()");
            Thread.dumpStack();
        }
        synchronized (this) {
            if (nsCreationOptions == null) {
                nsCreationOptions = nsCreationOptionsMap.get(dhtName);
            }
            if (nsCreationOptions == null) {
                if (dhtMetaWatcher == null) {
                    assert nsCreationOptions == null;
                    try {
                        if (debug) {
                            Log.warning("ensureNSCreationOptionsSet() calling dhtMetaWatcher");
                        }
                        dhtMetaWatcher = new DHTMetaWatcher(zkConfig, dhtName, dhtMetaWatcherIntervalMillis, debug);
                        dhtMetaWatcher.waitForDHTConfiguration();
                        nsCreationOptions = dhtMetaWatcher.getDHTConfiguration().getNSCreationOptions();
                        nsCreationOptionsMap.put(dhtName, nsCreationOptions);
                        if (debug) {
                            Log.warning("nsCreationOptions: ", nsCreationOptions);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    public NamespaceCreationOptions getNamespaceCreationOptions() {
        ensureNSCreationOptionsSet();
        return nsCreationOptions;
    }

    ///// ====== Internal client concurrency mechanism for nsOptions create/modify request ======
    private final Lock activeOptionsRequestLock = new ReentrantLock();
    private final HashedListMap<Long, NamespaceOptionsClientBase.ActiveOptionsRequest> activeOptionsRequests = new HashedListMap<>(true);
    private static final int   activeOptionsRequestTimeoutMillis = 2 * 60 * 1000;

    private static class ActiveOptionsRequest {
        private final long    nsContext; // currently stored solely for debugging/logging
        private final long    absTimeoutMillis;
        private NamespaceProperties   storedData;

        ActiveOptionsRequest(long nsContext, long relTimeoutMillis) {
            this.nsContext = nsContext;
            this.absTimeoutMillis = SystemTimeUtil.skSystemTimeSource.absTimeMillis() + relTimeoutMillis;
        }

        NamespaceProperties waitForCompletion() throws TimeoutException {
            synchronized (this) {
                while (storedData == null) {
                    long    relDeadlineMillis;
                    long    timeToWaitMillis;

                    relDeadlineMillis = Math.max(0, absTimeoutMillis - SystemTimeUtil.skSystemTimeSource.absTimeMillis());
                    try {
                        timeToWaitMillis = Math.min(activeOptionsRequestTimeoutMillis, relDeadlineMillis);
                        if (debug) {
                            System.out.printf("timeToWaitMillis %d\n", timeToWaitMillis);
                        }
                        if (timeToWaitMillis == 0) {
                            throw new TimeoutException(Long.toString(absTimeoutMillis));
                        }
                        this.wait(timeToWaitMillis);
                        if (debug) {
                            System.out.printf("out of wait\n");
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                if (storedData == null) {
                    throw new RuntimeException("ActiveOptionsRequest.waitForCompletion() timed out");
                }
            }
            if (debug) {
                System.out.printf("storedDef %s", storedData);
            }
            return storedData;
        }

        void setComplete(NamespaceProperties data) {
            synchronized (this) {
                if (this.storedData != null) {
                    Log.warning(data);
                    Log.warning(this.storedData);
                    Log.warning("Unexpected multiple completion for ActiveOptionsRequest");
                } else {
                    this.storedData = data;
                }
                this.notifyAll();
            }
        }
    }

    @Override
    public String toString() {
        return String.format("NamespaceOptionsClient: { dhtName=%s, port=%d, backend=%s, zkConfigPath=%s }",
                dhtConfig.getName(), dhtConfig.getPort(),
                implementationName(), dhtConfig.getZKConfig().getConnectString());
    }
}
