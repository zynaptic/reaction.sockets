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
import java.util.LinkedList;
import java.util.logging.Level;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;

import com.zynaptic.reaction.Deferrable;
import com.zynaptic.reaction.Deferred;
import com.zynaptic.reaction.Logger;
import com.zynaptic.reaction.Reactor;
import com.zynaptic.reaction.Threadable;
import com.zynaptic.reaction.sockets.SocketClosedException;
import com.zynaptic.reaction.sockets.SocketHandle;
import com.zynaptic.reaction.sockets.SocketService;

/**
 * Implements an SSL socket adaptor which converts underlying socket connections
 * to SSL/TLS encoded links. Supports adaptation of both client and server side
 * sockets.
 * 
 * @author Chris Holgate
 */
final class SSLSocketAdaptor implements SocketHandle {

  // Specify the state space used by the handshake task handler for handshake
  // transaction management.
  private enum HandshakeTransactionState {
    IDLE, ENGINE_ACTIVE
  };

  // Specify the state space used by the write transaction handler for wrapping
  // outgoing data.
  private enum WriteTransactionState {
    IDLE, CLOSING, CLOSED, TRANSFER_ACTIVE, ENGINE_ACTIVE
  }

  // Specify the state space used by the read transaction handler for unwrapping
  // inbound data.
  private enum ReadTransactionState {
    IDLE, CLOSING, CLOSED, TRANSFER_ACTIVE, ENGINE_ACTIVE
  };

  private final Reactor reactor;
  private final Logger logger;
  private final SocketService socketService;
  private final HandshakeTaskHandler taskHandler;
  private final WriteTransactionHandler writeHandler;
  private final ReadTransactionHandler readHandler;
  private final ReadBufferQueue readBufferQueue;
  private SocketHandle transportSocket;
  private SSLEngine sslEngine;

  /**
   * Provides the protected constructor for creating new SSL socket adaptor
   * instances. This should only be called from the associated socket service
   * implementation.
   * 
   * @param reactor This is the reactor service which is to be used for
   *   asynchronous event management.
   * @param logger This is the log service which is to be used for message
   *   logging.
   * @param socketService This is the socket service which was responsible for
   *   creating the SSL socket adaptor.
   */
  SSLSocketAdaptor(Reactor reactor, Logger logger, SocketService socketService) {
    this.reactor = reactor;
    this.logger = logger;
    this.socketService = socketService;
    this.taskHandler = new HandshakeTaskHandler();
    this.writeHandler = new WriteTransactionHandler();
    this.readHandler = new ReadTransactionHandler();
    this.readBufferQueue = new ReadBufferQueue();
    this.transportSocket = null;
    this.sslEngine = null;
  }

  /**
   * Starts the SSL/TLS handshake processing in order to establish the encoded
   * connection.
   * 
   * @param transportSocket This is the underlying transport socket which is to be
   *   used for transferring encoded data.
   * @param sslEngine This is the SSL engine which is to be used for wrapping and
   *   unwrapping the encoded data.
   * @throws SSLException This exception will be thrown if a problem was
   *   encountered while signalling the SSLEngine to begin a new handshake.
   */
  void startHandshake(SocketHandle transportSocket, SSLEngine sslEngine) throws SSLException {
    this.transportSocket = transportSocket;
    this.sslEngine = sslEngine;
    sslEngine.beginHandshake();
    readHandler.resume();
  }

  /*
   * Implements SocketHandle.getSocketId()
   */
  public String getSocketId() {
    return transportSocket.getSocketId();
  }

  /*
   * Implements SocketHandle.isOpen()
   */
  public boolean isOpen() {
    return writeHandler.isOpen();
  }

  /*
   * Implements SocketHandle.write(...)
   */
  public Deferred<ByteBuffer> write(ByteBuffer writeBuffer) {
    return writeHandler.write(writeBuffer);
  }

  /*
   * Implements SocketHandle.read(...)
   */
  public Deferred<ByteBuffer> read(int readSize) {
    return readBufferQueue.read(readSize);
  }

  /*
   * Implements SocketHandle.read(...)
   */
  public Deferred<ByteBuffer> read(ByteBuffer readBuffer) {
    return readBufferQueue.read(readBuffer);
  }

  /*
   * Implements SocketHandle.close(...)
   */
  public synchronized Deferred<Boolean> close() {
    return writeHandler.close();
  }

  /*
   * Implements the state machine for processing the handshaking tasks. These are
   * run in sequence using an independent thread.
   */
  private final class HandshakeTaskHandler implements Deferrable<Void, Void>, Threadable<Runnable, Void> {
    private HandshakeTransactionState handshakeState = HandshakeTransactionState.IDLE;

    /*
     * Resume processing if the handshake state handler is in its idle state.
     */
    private synchronized void resume() {
      if (handshakeState == HandshakeTransactionState.IDLE) {
        Runnable nextTask = sslEngine.getDelegatedTask();
        if (nextTask != null) {
          handshakeState = HandshakeTransactionState.ENGINE_ACTIVE;
          reactor.runThread(this, nextTask).addDeferrable(this, true);
        } else {
          reactor.runLater(x -> writeHandler.resume(), 0, null);
          reactor.runLater(x -> readHandler.resume(), 0, null);
        }
      }
    }

    /*
     * Run the delegated task.
     */
    public Void run(Runnable nextTask) {
      nextTask.run();
      return null;
    }

    /*
     * Callback on successfully completing the delegated task. Process the next
     * delegated task if available.
     */
    public synchronized Void onCallback(Deferred<Void> deferred, Void data) {
      handshakeState = HandshakeTransactionState.IDLE;
      resume();
      return null;
    }

    /*
     * Error callback on failing to complete the delegated task. This should never
     * happen.
     */
    public synchronized Void onErrback(Deferred<Void> deferred, Exception error) {
      logger.log(Level.SEVERE, "Unexpected error in SSL delegated task for <" + getSocketId() + ">", error);
      handshakeState = HandshakeTransactionState.IDLE;
      resume();
      return null;
    }
  }

  /*
   * Implements the state machine for processing write transactions.
   */
  private final class WriteTransactionHandler
      implements Deferrable<ByteBuffer, Void>, Threadable<ByteBuffer, ByteBuffer> {
    private WriteTransactionState transactionState = WriteTransactionState.IDLE;
    private boolean userTransaction = false;
    private Deferred<ByteBuffer> deferredWrite = null;
    private ByteBuffer writeBuffer = null;
    private Deferred<Boolean> deferredClose = null;

    /*
     * Initiate user write transactions on request.
     */
    private synchronized Deferred<ByteBuffer> write(ByteBuffer writeData) {
      if (writeData == null) {
        return reactor.failDeferred(new IllegalArgumentException("Null write buffer reference is not valid"));
      } else if (deferredWrite != null) {
        return reactor
            .failDeferred(new IllegalStateException("Write transaction already active for <" + getSocketId() + ">"));
      } else if ((transactionState == WriteTransactionState.CLOSING)
          || (transactionState == WriteTransactionState.CLOSED)) {
        return reactor.failDeferred(new SocketClosedException("Attempted write to closed socket"));
      } else if (!writeData.hasRemaining()) {
        socketService.releaseByteBuffer(writeData);
        return reactor.callDeferred(null);
      }

      // Set up the user write transaction and resume execution of the write state
      // machine.
      deferredWrite = reactor.newDeferred();
      writeBuffer = writeData;
      resume();
      return deferredWrite.makeRestricted();
    }

    /*
     * Close the write transaction handler on request. This closes the outbound
     * connection and then relies on the state machine to tidy up any outstanding
     * write request.
     */
    private synchronized Deferred<Boolean> close() {
      if (deferredClose != null) {
        return reactor
            .failDeferred(new IllegalStateException("Close request already active for <" + getSocketId() + ">"));
      }
      sslEngine.closeOutbound();
      deferredClose = reactor.newDeferred();
      resume();
      return deferredClose.makeRestricted();
    }

    /*
     * Indicate whether the socket is closed or in the process of being closed.
     */
    private synchronized boolean isOpen() {
      return ((deferredClose == null) && (transactionState != WriteTransactionState.CLOSED));
    }

    /*
     * Resume processing if the write transaction handler is currently in its idle
     * state.
     */
    private synchronized void resume() {
      if ((transactionState == WriteTransactionState.IDLE) || (transactionState == WriteTransactionState.CLOSING)) {

        // Wait for any outstanding handshaking tasks to complete if required. Also
        // schedules a timed callback to this function to check for subsequent handshake
        // status changes.
        if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
          reactor.runLater(x -> taskHandler.resume(), 0, null);
          reactor.runLater(x -> resume(), 250, null);
        }

        // Wait for an outstanding unwrap operation if required. Also schedules a timed
        // callback to this function to check for subsequent handshake status changes.
        else if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP) {
          reactor.runLater(x -> readHandler.resume(), 0, null);
          reactor.runLater(x -> resume(), 250, null);
        }

        // Halt when the SSL engine outbound and inbound paths are both reported as
        // being closed.
        else if (sslEngine.isOutboundDone()) {
          if (sslEngine.isInboundDone()) {
            transactionState = WriteTransactionState.CLOSED;
            transportSocket.close().addDeferrable(new TransportSocketCloseHandler(), true);
          } else {
            reactor.runLater(x -> readHandler.resume(), 0, null);
            reactor.runLater(x -> resume(), 250, null);
          }
        }

        // Initiate a wrap for a user data buffer.
        else if (deferredWrite != null) {
          transactionState = WriteTransactionState.ENGINE_ACTIVE;
          userTransaction = true;
          reactor.runThread(this, writeBuffer).addDeferrable(this, true);
        }

        // Initiate a wrap when no user data is available, but the handshake state
        // machine requests it.
        else if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
          transactionState = WriteTransactionState.ENGINE_ACTIVE;
          userTransaction = false;
          ByteBuffer emptyBuffer = socketService.getByteBuffer(1).flip();
          reactor.runThread(this, emptyBuffer).addDeferrable(this, true);
        }
      }
    }

    /*
     * Perform SSL wrap processing in an independent thread.
     */
    public synchronized ByteBuffer run(ByteBuffer appData) throws SSLException {
      ByteBuffer packetData = socketService.getByteBuffer(sslEngine.getSession().getPacketBufferSize());
      SSLEngineResult result = sslEngine.wrap(appData, packetData);
      switch (result.getStatus()) {

      // Buffer overflow should not occur if the data buffer is correctly sized.
      case BUFFER_OVERFLOW:
        transactionState = WriteTransactionState.CLOSED;
        logger.log(Level.SEVERE,
            "Closed SSL write transaction handler due to unexpected SSL buffer overflow for <" + getSocketId() + ">");
        break;

      // Log closed condition.
      case CLOSED:
        transactionState = WriteTransactionState.CLOSING;
        logger.log(Level.INFO,
            "Closing SSL write transaction handler due to SSL session closed for <" + getSocketId() + ">");
        break;

      // Buffer underflow is treated in the same manner as a successful operation -
      // buffer handling is carried out on the basis of the post processing buffer
      // state.
      case BUFFER_UNDERFLOW:
      case OK:
        transactionState = WriteTransactionState.TRANSFER_ACTIVE;
        if (!appData.hasRemaining()) {
          writeBuffer = null;
          socketService.releaseByteBuffer(appData);
        }
        break;
      }
      packetData.flip();
      return packetData;
    }

    /*
     * Process callbacks from wrapping data and subsequently writing it to the
     * transport socket.
     */
    public synchronized Void onCallback(Deferred<ByteBuffer> deferred, ByteBuffer bufferData) {

      // Drain the buffer data by writing it to the transport socket.
      if (bufferData != null) {
        transportSocket.write(bufferData).addDeferrable(this, true);
      }

      // Complete the transaction - triggering the deferred callback if it was
      // initiated by a user request.
      else {
        if (userTransaction) {
          deferredWrite.callback(writeBuffer);
          deferredWrite = null;
        }

        // In the closing state, resume to complete the close handshake.
        if (transactionState != WriteTransactionState.CLOSING) {
          transactionState = WriteTransactionState.IDLE;
        }
        resume();
      }
      return null;
    }

    /*
     * On failing to write valid data or wrap it, place the transaction handler in
     * its closed state.
     */
    public synchronized Void onErrback(Deferred<ByteBuffer> deferred, Exception error) {
      if (transactionState == WriteTransactionState.TRANSFER_ACTIVE) {
        logger.log(Level.WARNING,
            "Closed SSL write transaction handler due to transport error for <" + getSocketId() + ">", error);
      } else if (!(error instanceof SocketClosedException)) {
        logger.log(Level.WARNING,
            "Closed SSL write transaction handler due to SSL engine error for <" + getSocketId() + ">", error);
      }
      transactionState = WriteTransactionState.CLOSED;
      if (deferredWrite != null) {
        deferredWrite.errback(error);
        deferredWrite = null;
      }
      return null;
    }

    /*
     * Handle callbacks on closing the associated transport socket.
     */
    private class TransportSocketCloseHandler implements Deferrable<Boolean, Boolean> {
      public Boolean onCallback(Deferred<Boolean> deferred, Boolean data) throws Exception {
        synchronized (WriteTransactionHandler.this) {
          logger.log(Level.INFO, "Clean TLS and transport socket shutdown for <" + getSocketId() + ">");
          if (deferredClose != null) {
            deferredClose.callback(data);
            deferredClose = null;
          }
          return null;
        }
      }

      public Boolean onErrback(Deferred<Boolean> deferred, Exception error) {
        synchronized (WriteTransactionHandler.this) {
          logger.log(Level.WARNING, "Transport socket shutdown error for <" + getSocketId() + ">", error);
          if (deferredClose != null) {
            deferredClose.errback(error);
            deferredClose = null;
          }
          return null;
        }
      }
    }
  }

  /*
   * Implements the state machine for processing read transactions.
   */
  private final class ReadTransactionHandler
      implements Deferrable<ByteBuffer, Void>, Threadable<ByteBuffer, ByteBuffer> {
    private ReadTransactionState transactionState = ReadTransactionState.IDLE;
    private ByteBuffer readBuffer = null;

    /*
     * Resume processing if the read transaction handler is currently in its idle
     * state.
     */
    private synchronized void resume() {
      if ((transactionState == ReadTransactionState.IDLE) || (transactionState == ReadTransactionState.CLOSING)) {

        // Wait for any outstanding handshaking tasks to complete if required. Also
        // schedules a timed callback to this function to check for subsequent handshake
        // status changes.
        if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
          reactor.runLater(x -> taskHandler.resume(), 0, null);
          reactor.runLater(x -> resume(), 250, null);
        }

        // Wait for an outstanding wrap operation if required. Also schedules a timed
        // callback to this function to check for subsequent handshake status changes.
        else if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
          reactor.runLater(x -> writeHandler.resume(), 0, null);
          reactor.runLater(x -> resume(), 250, null);
        }

        // Halt when the SSL engine outbound and inbound paths are both reported as
        // being closed.
        else if (sslEngine.isInboundDone()) {
          if (sslEngine.isOutboundDone()) {
            transactionState = ReadTransactionState.CLOSED;
          } else {
            reactor.runLater(x -> writeHandler.resume(), 0, null);
            reactor.runLater(x -> resume(), 250, null);
          }
        }

        // Initiate a read on the transport socket.
        else {
          transactionState = ReadTransactionState.TRANSFER_ACTIVE;
          if (readBuffer == null) {
            transportSocket.read(sslEngine.getSession().getPacketBufferSize()).addDeferrable(this, true);
          } else {
            transportSocket.read(readBuffer).addDeferrable(this, true);
            readBuffer = null;
          }
        }
      }
    }

    /*
     * Perform SSL unwrap processing in an independent thread.
     */
    public synchronized ByteBuffer run(ByteBuffer packetData) throws SSLException {
      ByteBuffer appData = socketService.getByteBuffer(sslEngine.getSession().getApplicationBufferSize());
      SSLEngineResult result = sslEngine.unwrap(packetData, appData);
      switch (result.getStatus()) {

      // Buffer overflow should not occur if the data buffer is correctly sized.
      case BUFFER_OVERFLOW:
        transactionState = ReadTransactionState.CLOSED;
        logger.log(Level.SEVERE,
            "Closed SSL read transaction handler due to unexpected SSL buffer overflow for <" + getSocketId() + ">");
        break;

      // Log closed condition.
      case CLOSED:
        transactionState = ReadTransactionState.CLOSING;
        logger.log(Level.INFO,
            "Closing SSL read transaction handler due to SSL session being closed for <" + getSocketId() + ">");
        break;

      // Buffer underflow is treated in the same manner as a successful operation -
      // buffer handling is carried out on the basis of the post processing buffer
      // state.
      case BUFFER_UNDERFLOW:
      case OK:
        if (packetData.hasRemaining()) {
          if (packetData.capacity() >= sslEngine.getSession().getPacketBufferSize()) {
            readBuffer = packetData;
          } else {
            readBuffer = socketService.getByteBuffer(sslEngine.getSession().getPacketBufferSize());
            readBuffer.put(packetData).flip();
            socketService.releaseByteBuffer(packetData);
          }
        } else {
          socketService.releaseByteBuffer(packetData);
        }
        if (appData.position() == 0) {
          socketService.releaseByteBuffer(appData);
          appData = null;
        } else {
          appData.flip();
        }
        break;
      }
      return appData;
    }

    /*
     * Process callbacks from reading back data and subsequently unwrapping it.
     */
    public synchronized Void onCallback(Deferred<ByteBuffer> deferred, ByteBuffer bufferData) {

      // On closing the read transaction handler, set the read queue shutdown cause.
      // Resume write transaction processing to complete the close handshake. Note
      // that we explicitly close the outbound connection since this is not done
      // automatically in TLSv1.3 and subsequent versions.
      if (transactionState == ReadTransactionState.CLOSING) {
        readBufferQueue.setShutdownCause(new SocketClosedException("Socket closed on shutting down SSL connection"));
        sslEngine.closeOutbound();
        resume();
      }

      // On completion of the transport socket data transfer initiate an unwrap
      // operation using the reactor thread pool.
      else if (transactionState == ReadTransactionState.TRANSFER_ACTIVE) {
        transactionState = ReadTransactionState.ENGINE_ACTIVE;
        reactor.runThread(this, bufferData).addDeferrable(this, true);
      }

      // On completion of the unwrap operation, append the unwrapped buffer to the
      // read buffer queue and restart read transaction processing.
      else if (transactionState == ReadTransactionState.ENGINE_ACTIVE) {
        transactionState = ReadTransactionState.IDLE;
        if (bufferData != null) {
          readBufferQueue.append(bufferData);
        }
        resume();
      }
      return null;
    }

    /*
     * On failing to read back valid data or unwrap it, place the transaction
     * handler in its closed state. Log the exception and set it as the shutdown
     * cause to be returned on failed read requests.
     */
    public synchronized Void onErrback(Deferred<ByteBuffer> deferred, Exception error) {
      if (transactionState == ReadTransactionState.TRANSFER_ACTIVE) {
        logger.log(Level.WARNING,
            "Closed SSL read transaction handler due to transport error for <" + getSocketId() + ">", error);
      } else {
        logger.log(Level.WARNING,
            "Closed SSL read transaction handler due to SSL engine error for <" + getSocketId() + ">", error);
      }
      transactionState = ReadTransactionState.CLOSED;
      readBufferQueue.setShutdownCause(error);
      return null;
    }
  }

  /*
   * Implements the read buffer queue, which decouples the production of decoded
   * application buffers from their consumption via the socket read API calls.
   */
  private final class ReadBufferQueue {
    private Deferred<ByteBuffer> deferredRead = null;
    private ByteBuffer readBuffer = null;
    private int readSize = 0;
    private final LinkedList<ByteBuffer> queue = new LinkedList<ByteBuffer>();
    private Exception shutdownCause = null;

    /*
     * Append a new read buffer - processing the deferred read callback if it is
     * outstanding.
     */
    private synchronized void append(ByteBuffer readbuffer) {
      queue.addLast(readbuffer);
      if (deferredRead != null) {
        issueDeferredCallback();
      }
    }

    /*
     * Assigns the shutdown cause. Under normal circumstances this should be a
     * SocketClosedException.
     */
    private synchronized void setShutdownCause(Exception error) {
      shutdownCause = error;
      if (deferredRead != null) {
        deferredRead.errback(error);
        deferredRead = null;
      }
    }

    /*
     * Perform a read, given a specified read size.
     */
    private synchronized Deferred<ByteBuffer> read(int newReadSize) {
      if (newReadSize <= 0) {
        return reactor.failDeferred(new IllegalArgumentException("Invalid read size parameter (" + newReadSize + ")"));
      } else if (deferredRead != null) {
        return reactor
            .failDeferred(new IllegalStateException("Read transaction already active for <" + getSocketId() + ">"));
      } else if (queue.isEmpty() && (shutdownCause != null)) {
        return reactor.failDeferred(shutdownCause);
      }
      deferredRead = reactor.newDeferred();
      readBuffer = null;
      readSize = newReadSize;
      Deferred<ByteBuffer> result = deferredRead.makeRestricted();
      if (!queue.isEmpty()) {
        issueDeferredCallback();
      }
      return result;
    }

    /*
     * Perform a read, given a read target buffer.
     */
    private synchronized Deferred<ByteBuffer> read(ByteBuffer newReadBuffer) {
      if (newReadBuffer == null) {
        return reactor.failDeferred(new NullPointerException("Null read buffer reference is not valid"));
      } else if (!newReadBuffer.hasRemaining()) {
        return reactor.failDeferred(new IllegalArgumentException("No space available in specified read buffer"));
      } else if (deferredRead != null) {
        return reactor
            .failDeferred(new IllegalStateException("Read transaction already active for <" + getSocketId() + ">"));
      } else if (queue.isEmpty() && (shutdownCause != null)) {
        return reactor.failDeferred(shutdownCause);
      }
      deferredRead = reactor.newDeferred();
      readBuffer = newReadBuffer;
      readSize = newReadBuffer.remaining();
      Deferred<ByteBuffer> result = deferredRead.makeRestricted();
      if (!queue.isEmpty()) {
        issueDeferredCallback();
      }
      return result;
    }

    /*
     * Issue deferred callback. This supports either returning the next buffer in
     * the queue as-is or copying a portion of that buffer into an independent
     * return buffer.
     */
    private void issueDeferredCallback() {

      // Pass back the entire queued buffer if it is consistent with the requested
      // read size.
      if ((readBuffer == null) && (queue.getFirst().remaining() <= readSize)) {
        deferredRead.callback(queue.removeFirst());
        deferredRead = null;
        return;
      }

      // If required create a read buffer of the specified size.
      if (readBuffer == null) {
        readBuffer = socketService.getByteBuffer(readSize);
      } else {
        readBuffer.compact();
      }

      // Copy the contents of the queued buffer when the queued buffer is larger than
      // the read buffer.
      if (queue.getFirst().remaining() > readBuffer.remaining()) {
        ByteBuffer queueBuffer = queue.getFirst();
        int copyPosition = queueBuffer.position();
        int copySize = readBuffer.remaining();
        readBuffer.put(queueBuffer.slice().limit(copySize));
        queueBuffer.position(copyPosition + copySize);
      }

      // Copy the entire contents of the queued buffer and remove it from the queue.
      else {
        ByteBuffer queueBuffer = queue.removeFirst();
        readBuffer.put(queueBuffer);
        socketService.releaseByteBuffer(queueBuffer);
      }

      // Pass back the generated buffer.
      deferredRead.callback(readBuffer.flip());
      deferredRead = null;
    }
  }
}
