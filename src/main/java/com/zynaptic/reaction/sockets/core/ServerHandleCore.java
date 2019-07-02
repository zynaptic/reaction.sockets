/*
 * Zynaptic Reaction Sockets - An asynchronous programming framework for Java.
 * 
 * Copyright (c) 2016-2019, Zynaptic Limited.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 * 
 * Please visit www.zynaptic.com or contact reaction@zynaptic.com if you need
 * additional information or have any questions.
 */

package com.zynaptic.reaction.sockets.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Collection;
import java.util.logging.Level;

import com.zynaptic.reaction.Deferred;
import com.zynaptic.reaction.Logger;
import com.zynaptic.reaction.Reactor;
import com.zynaptic.reaction.Signal;
import com.zynaptic.reaction.Signalable;
import com.zynaptic.reaction.sockets.ServerHandle;
import com.zynaptic.reaction.sockets.SocketClosedException;
import com.zynaptic.reaction.sockets.SocketHandle;
import com.zynaptic.reaction.sockets.SocketService;

/**
 * Implements a wrapper around the standard Java asynchronous sockets API for
 * interoperability with the Reaction framework.
 * 
 * @author Chris Holgate
 */
final class ServerHandleCore implements ServerHandle, CompletionHandler<AsynchronousSocketChannel, Void> {
  private final Reactor reactor;
  private final Logger logger;
  private final SocketService socketService;
  private AsynchronousServerSocketChannel serverChannel;
  private String serverId;
  private Signal<SocketHandle> acceptSignal;

  /**
   * Provides the protected constructor for creating new socket server instances.
   * This should only be called from the associated socket service implementation.
   * 
   * @param reactor This is the reactor service which is to be used for
   *   asynchronous event management.
   * @param logger This is the log service which is to be used for message
   *   logging.
   * @param socketService This is the socket service which was responsible for
   *   creating the socket server.
   */
  ServerHandleCore(Reactor reactor, Logger logger, SocketService socketService) {
    this.reactor = reactor;
    this.logger = logger;
    this.socketService = socketService;
    this.serverChannel = null;
    this.serverId = null;
    this.acceptSignal = null;
  }

  /**
   * Initiates a server socket open request, binding to the specified local
   * address and port.
   * 
   * @param address This is the local address of the socket server.
   * @param port This is the local port number of the socket server.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion, passing a reference to this server socket handle as
   *   its callback parameter.
   */
  synchronized Deferred<ServerHandle> open(InetAddress address, int port) {
    try {
      SocketAddress localAddr = new InetSocketAddress(address, port);
      serverChannel = AsynchronousServerSocketChannel.open();
      serverChannel.bind(localAddr);
      serverId = localAddr.toString();
      return reactor.callDeferred(this);
    } catch (Exception error) {
      return reactor.failDeferred(error);
    }
  }

  /*
   * Implements ServerHandle.getServerId()
   */
  public synchronized String getServerId() {
    return serverId;
  }

  /*
   * Implements ServerHandle.isOpen()
   */
  public synchronized boolean isOpen() {
    return serverChannel.isOpen();
  }

  /*
   * Implements ServerHandle.addSocketAcceptor(...)
   */
  public synchronized boolean addSocketAcceptor(Signalable<SocketHandle> socketAcceptor) throws SocketClosedException {
    if (!serverChannel.isOpen()) {
      throw new SocketClosedException("Attempted to add socket acceptor to closed server channel");
    }

    // Start accepting on adding the first socket acceptor.
    if (acceptSignal == null) {
      acceptSignal = reactor.newSignal();
      acceptSignal.subscribe(socketAcceptor);
      startAccepting();
      return true;
    }

    // Add subsequent socket acceptors. These may not receive all socket requests.
    else {
      acceptSignal.subscribe(socketAcceptor);
      return false;
    }
  }

  /*
   * Implements ServerHandle.addSocketAcceptors(...)
   */
  public synchronized boolean addSocketAcceptors(Collection<Signalable<SocketHandle>> socketAcceptors)
      throws SocketClosedException {
    if (!serverChannel.isOpen()) {
      throw new SocketClosedException("Attempted to add socket acceptors to closed server channel");
    }

    // Start accepting on adding the first set of socket acceptors.
    if (acceptSignal == null) {
      acceptSignal = reactor.newSignal();
      for (Signalable<SocketHandle> acceptor : socketAcceptors) {
        acceptSignal.subscribe(acceptor);
      }
      startAccepting();
      return true;
    }

    // Add subsequent socket acceptors. These may not receive all socket requests.
    else {
      for (Signalable<SocketHandle> acceptor : socketAcceptors) {
        acceptSignal.subscribe(acceptor);
      }
      return false;
    }
  }

  /*
   * Implements ServerHandle.close()
   */
  public synchronized Deferred<Boolean> close() {
    if (acceptSignal != null) {
      try {
        acceptSignal.signalFinalize(null);
        acceptSignal = null;
        serverChannel.close();
        return reactor.callDeferred(true);
      } catch (Exception error) {
        return reactor.failDeferred(error);
      }
    } else {
      return reactor.callDeferred(false);
    }
  }

  /*
   * Implement completion handler callback. In normal operation this will create a
   * new socket handle for the new client and notify subscribers to the attachment
   * signal.
   */
  public synchronized void completed(AsynchronousSocketChannel socketChannel, Void attachment) {
    if (acceptSignal != null) {

      // Create a new socket handle instance and notify the accept signal subscribers.
      try {
        SocketHandleCore socketHandle = new SocketHandleCore(reactor, socketService);
        socketHandle.setup(socketChannel, socketChannel.getRemoteAddress().toString());
        acceptSignal.signal(socketHandle);
        logger.log(Level.INFO, "Accepted connection from <" + socketHandle.getSocketId() + "> on <" + serverId + ">");
      }

      // Close new socket channel if the client cannot be resolved.
      catch (Exception error) {
        logger.log(Level.WARNING, "Failed to resolve remote client ID for <" + serverId + ">", error);
        try {
          socketChannel.close();
        } catch (Exception discard) {
          // Ignore error.
        }
      }
      startAccepting();
    }

    // Close the new socket channel if the server socket has been closed.
    else {
      try {
        socketChannel.close();
      } catch (Exception discard) {
        // Ignore error.
      }
    }
  }

  /*
   * Implement completion handler failure callback. This closes the server handle
   * so that it no longer accepts requests.
   */
  public synchronized void failed(Throwable error, Void attachment) {
    if (acceptSignal != null) {
      logger.log(Level.WARNING, "Socket error detected - closing server socket for <" + serverId + ">", error);
      acceptSignal.signalFinalize(null);
      acceptSignal = null;
      try {
        serverChannel.close();
      } catch (Exception discard) {
        // Ignore error.
      }
    }
  }

  /*
   * Accept the next incoming request. There is no way of notifying exception
   * conditions, so errors are logged and the server stops accepting any
   * subsequent requests if they occur.
   */
  private void startAccepting() {
    try {
      serverChannel.accept(null, this);
    } catch (Exception error) {
      logger.log(Level.WARNING, "Failed to accept server requests for <" + serverId + ">", error);
      if (acceptSignal != null) {
        acceptSignal.signalFinalize(null);
        acceptSignal = null;
      }
    }
  }
}
