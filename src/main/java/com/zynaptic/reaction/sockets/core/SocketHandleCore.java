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

import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

import com.zynaptic.reaction.Deferred;
import com.zynaptic.reaction.Reactor;
import com.zynaptic.reaction.sockets.SocketClosedException;
import com.zynaptic.reaction.sockets.SocketHandle;
import com.zynaptic.reaction.sockets.SocketService;

/**
 * Implements a wrapper around the standard Java asynchronous sockets API for
 * interoperability with the Reaction framework. This provides the common
 * functionality for both client and server sockets.
 * 
 * @author Chris Holgate
 */
final class SocketHandleCore implements SocketHandle {
  private final Reactor reactor;
  private final SocketService socketService;
  private AsynchronousSocketChannel socketChannel;
  private String socketId;
  private final WriteTransactionHandler writeHandler;
  private final ReadTransactionHandler readHandler;

  /**
   * Provides the protected constructor for creating new socket handle instances.
   * This should only be called from the associated socket service implementation.
   * 
   * @param reactor This is the reactor service which is to be used for
   *   asynchronous event management.
   * @param socketService This is the socket service which was responsible for
   *   creating the socket handle.
   */
  SocketHandleCore(Reactor reactor, SocketService socketService) {
    this.reactor = reactor;
    this.socketService = socketService;
    socketChannel = null;
    socketId = null;
    writeHandler = new WriteTransactionHandler();
    readHandler = new ReadTransactionHandler();
  }

  /**
   * Performs setup on the socket handle instance, configuring the associated
   * socket channel and socket identifier.
   * 
   * @param socketChannel This is the standard Java asynchronous channel instance
   *   which is to be used for transmitting and receiving data. It should be
   *   opened and ready for use.
   * @param socketId This is the socket name which is to be used as a general
   *   purpose identifier.
   */
  synchronized void setup(AsynchronousSocketChannel socketChannel, String socketId) {
    this.socketChannel = socketChannel;
    this.socketId = socketId;
  }

  /*
   * Implements SocketHandle.getSocketId()
   */
  public synchronized String getSocketId() {
    return socketId;
  }

  /*
   * Implements SocketHandle.isOpen()
   */
  public synchronized boolean isOpen() {
    return socketChannel.isOpen();
  }

  /*
   * Implements SocketHandle.write(...)
   */
  public synchronized Deferred<ByteBuffer> write(ByteBuffer writeBuffer) {
    if (!socketChannel.isOpen()) {
      return reactor.failDeferred(new SocketClosedException("Attempted write to closed socket"));
    } else if (writeBuffer == null) {
      return reactor.failDeferred(new NullPointerException("Null write buffer reference is not valid"));
    } else if (!writeBuffer.hasRemaining()) {
      socketService.releaseByteBuffer(writeBuffer);
      return reactor.callDeferred(null);
    }
    return writeHandler.write(writeBuffer);
  }

  /*
   * Implements SocketHandle.read(ByteBuffer ...)
   */
  public synchronized Deferred<ByteBuffer> read(ByteBuffer readBuffer) {
    if (!socketChannel.isOpen()) {
      return reactor.failDeferred(new SocketClosedException("Attempted read to closed socket"));
    } else if (readBuffer == null) {
      return reactor.failDeferred(new NullPointerException("Null read buffer reference is not valid"));
    }
    return readHandler.read(readBuffer.compact());
  }

  /*
   * Implements SocketHandle.read(int ...)
   */
  public Deferred<ByteBuffer> read(int readSize) {
    if (!socketChannel.isOpen()) {
      return reactor.failDeferred(new SocketClosedException("Attempted read to closed socket"));
    } else if (readSize <= 0) {
      return reactor.failDeferred(new IllegalArgumentException("Invalid read size parameter (" + readSize + ")"));
    }
    ByteBuffer readBuffer = socketService.getByteBuffer(readSize);
    return readHandler.read(readBuffer);
  }

  /*
   * Implements SocketHandle.close()
   */
  public synchronized Deferred<Boolean> close() {
    try {
      socketChannel.close();
      return reactor.callDeferred(true);
    } catch (Exception error) {
      return reactor.failDeferred(error);
    }
  }

  /*
   * Implement write transaction handling for a single socket.
   */
  private final class WriteTransactionHandler implements CompletionHandler<Integer, Void> {
    private ByteBuffer writeBuffer;
    private Deferred<ByteBuffer> deferredWrite = null;

    /*
     * Initiate a new write transaction using the specified write buffer. The buffer
     * contains at least one byte of data.
     */
    private synchronized Deferred<ByteBuffer> write(ByteBuffer writeBuffer) {
      if (deferredWrite != null) {
        return reactor
            .failDeferred(new IllegalStateException("Write transaction already active for <" + socketId + ">"));
      }
      this.writeBuffer = writeBuffer;
      deferredWrite = reactor.newDeferred();
      socketChannel.write(writeBuffer, null, this);
      return deferredWrite.makeRestricted();
    }

    /*
     * Process asynchronous write completion.
     */
    public synchronized void completed(Integer result, Void attachment) {

      // Try again if all the data hasn't been transferred.
      if (writeBuffer.hasRemaining()) {
        socketChannel.write(writeBuffer, null, this);
      }

      // Recycle the buffer and pass back a null reference to indicate that it has
      // been used up.
      else {
        deferredWrite.callback(null);
        socketService.releaseByteBuffer(writeBuffer);
        deferredWrite = null;
        writeBuffer = null;
      }
    }

    /*
     * Process asynchronous write failures.
     */
    public synchronized void failed(Throwable error, Void attachment) {
      if (error instanceof AsynchronousCloseException) {
        deferredWrite.errback(new SocketClosedException("Socket closed during write request"));
      } else if (error instanceof Exception) {
        deferredWrite.errback((Exception) error);
      } else {
        deferredWrite.errback(new Exception("Unexpected error condition", error));
      }
      deferredWrite = null;
      writeBuffer = null;
    }
  }

  /*
   * Implement read transaction handling for a single socket.
   */
  private final class ReadTransactionHandler implements CompletionHandler<Integer, Void> {
    private ByteBuffer readBuffer;
    private Deferred<ByteBuffer> deferredRead = null;

    /*
     * Initiate a new read transaction using the specified read buffer.
     */
    private synchronized Deferred<ByteBuffer> read(ByteBuffer readBuffer) {
      if (deferredRead != null) {
        return reactor
            .failDeferred(new IllegalStateException("Read transaction already active for <" + socketId + ">"));
      }
      this.readBuffer = readBuffer;
      deferredRead = reactor.newDeferred();
      socketChannel.read(readBuffer, null, this);
      return deferredRead.makeRestricted();
    }

    /*
     * Process asynchronous read completion.
     */
    public synchronized void completed(Integer result, Void attachment) {

      // Notify EOF condition by returning a socket closed exception.
      if (result < 0) {
        deferredRead.errback(new SocketClosedException("End of file condition detected during read request"));
      }

      // Pass back the buffer contents as the callback parameter.
      else {
        deferredRead.callback(readBuffer.flip());
      }
      deferredRead = null;
      readBuffer = null;
    }

    /*
     * Process asynchronous read failures.
     */
    public synchronized void failed(Throwable error, Void attachment) {
      if (error instanceof AsynchronousCloseException) {
        deferredRead.errback(new SocketClosedException("Socket closed during read request"));
      } else if (error instanceof Exception) {
        deferredRead.errback((Exception) error);
      } else {
        deferredRead.errback(new Exception("Unexpected error condition", error));
      }
      deferredRead = null;
      readBuffer = null;
    }
  }
}
