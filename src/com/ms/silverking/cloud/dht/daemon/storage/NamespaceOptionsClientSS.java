package com.ms.silverking.cloud.dht.daemon.storage;

import java.io.File;

import com.ms.silverking.cloud.dht.NamespaceOptions;
import com.ms.silverking.cloud.dht.common.NamespaceProperties;
import com.ms.silverking.cloud.dht.common.NamespacePropertiesRetrievalException;
import com.ms.silverking.cloud.dht.common.TimeoutException;

public interface NamespaceOptionsClientSS {
  ////// ====== server side internal query API (take namespace context as arg) ======
  NamespaceProperties getNamespaceProperties(long nsContext) throws NamespacePropertiesRetrievalException;

  NamespaceOptions getNamespaceOptions(long nsContext) throws NamespacePropertiesRetrievalException;

  NamespaceProperties getNamespacePropertiesWithTimeout(long nsContext, long relTimeoutMillis)
      throws NamespacePropertiesRetrievalException, TimeoutException;

  // For backward compatibility, since some implementation may still need properties file to bootstrap
  NamespaceProperties getNsPropertiesForRecovery(File nsDir) throws NamespacePropertiesRetrievalException;
}
