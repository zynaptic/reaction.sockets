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
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.logging.Level;

import com.zynaptic.reaction.Deferred;
import com.zynaptic.reaction.Logger;
import com.zynaptic.reaction.Reactor;
import com.zynaptic.reaction.sockets.SocketHandle;
import com.zynaptic.reaction.sockets.SocketService;

/**
 * Implements the client socket opener state machine. This carries out the
 * client socket setup process, returning an opened socket handle on completion.
 * 
 * @author Chris Holgate
 */
final class ClientSocketOpener implements CompletionHandler<Void, Deferred<SocketHandle>> {
  private final Reactor reactor;
  private final Logger logger;
  private final SocketHandleCore socketHandle;

  /**
   * Provides the protected constructor for creating new client socket opener
   * instances. This should only be called from the associated socket service
   * implementation.
   * 
   * @param reactor This is the reactor service which is to be used for
   *   asynchronous event management.
   * @param logger This is the log service which is to be used for message
   *   logging.
   * @param socketService This is the socket service which was responsible for
   *   creating the socket handle.
   */
  ClientSocketOpener(Reactor reactor, Logger logger, SocketService socketService) {
    this.reactor = reactor;
    this.logger = logger;
    socketHandle = new SocketHandleCore(reactor, socketService);
  }

  /**
   * Initiates a socket open request on the client socket, using the specified
   * remote address and port.
   * 
   * @param address This is the remote address of the socket server to which this
   *   client is connecting.
   * @param port This is the remote port number of the socket server to which this
   *   client is connecting.
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion, passing a reference to this socket handle as its
   *   callback parameter.
   */
  synchronized Deferred<SocketHandle> open(InetAddress address, int port) {
    try {
      SocketAddress remoteAddr = new InetSocketAddress(address, port);
      AsynchronousSocketChannel socketChannel = AsynchronousSocketChannel.open();
      Deferred<SocketHandle> deferredOpen = reactor.newDeferred();
      socketHandle.setup(socketChannel, remoteAddr.toString());
      socketChannel.connect(remoteAddr, deferredOpen, this);
      return deferredOpen.makeRestricted();
    } catch (Exception error) {
      return reactor.failDeferred(error);
    }
  }

  /*
   * Provides the callback handler for successful completion of the socket open
   * request.
   */
  public synchronized void completed(Void result, Deferred<SocketHandle> deferredOpen) {
    logger.log(Level.INFO, "Opened client socket <" + socketHandle.getSocketId() + ">");
    deferredOpen.callback(socketHandle);
  }

  /*
   * Provides the error callback handler for unsuccessful completion of the socket
   * open request.
   */
  public synchronized void failed(Throwable error, Deferred<SocketHandle> deferredOpen) {
    if (error instanceof Exception) {
      deferredOpen.errback((Exception) error);
    } else {
      deferredOpen.errback(new Exception("Unexpected error condition", error));
    }
  }
}
