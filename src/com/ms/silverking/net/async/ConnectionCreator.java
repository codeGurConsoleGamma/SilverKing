package com.ms.silverking.net.async;

import java.nio.channels.SocketChannel;

/**
 * Called by AsyncBase when to establish a connection for the newly
 * accepted Channel.
 */
public interface ConnectionCreator<T extends Connection> {
  public T createConnection(SocketChannel channel, SelectorController<T> selectorController,
      ConnectionListener connectionListener, boolean debug);
}
