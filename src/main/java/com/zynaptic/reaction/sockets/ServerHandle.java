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

package com.zynaptic.reaction.sockets;

import java.util.Collection;

import com.zynaptic.reaction.Deferred;
import com.zynaptic.reaction.Signalable;

/**
 * Defines the socket server handle interface which is used to interact with a
 * single socket server instance that is bound to a server port.
 * 
 * @author Chris Holgate
 */
public interface ServerHandle {

  /**
   * Accesses the socket server identifier string which is associated with the
   * socket server handle. This will typically be made up of the address and port
   * number of the underlying server port.
   * 
   * @return Returns the socket server identifier associated with the socket
   *   server handle.
   */
  public String getServerId();

  /**
   * Determines whether the socket server is open for accepting new connections.
   * 
   * @return Returns a boolean value which will be set to 'true' if the socket
   *   server is open for accepting new connections and 'false' otherwise.
   */
  public boolean isOpen();

  /**
   * Accesses the socket server connection accept signal. Subscribers to this
   * signal will be notified each time a successful connection request has been
   * made, receiving the socket handle associated with the new connection as the
   * signal parameter. A null reference may also be received as the signal
   * parameter in order to notify subscribers that the socket server has been
   * closed.
   * 
   * @return Returns the signal event object which will notify its subscribers
   *   each time a successful connection request has been made.
   */

  /**
   * Adds a signalable socket acceptor will be notified each time a successful
   * connection request has been made, receiving the socket handle associated with
   * the new connection as the signal parameter. A null reference may also be
   * received as the signal parameter in order to notify socket acceptors that the
   * socket server has been closed. The server only starts accepting connections
   * once the first socket acceptor has been added in this manner.
   * 
   * @param socketAcceptor This is the signalable socket acceptor which is to be
   *   notified of new server connections.
   * @return Returns a boolean flag which will be set to 'true' if the server
   *   started accepting new connections as a result of this call and 'false' if
   *   the server was already accepting connections.
   * @throws SocketClosedException This exception will be thrown if an attempt is
   *   made to add a socket acceptor to a closed socket server.
   */
  public boolean addSocketAcceptor(Signalable<SocketHandle> socketAcceptor) throws SocketClosedException;

  /**
   * Adds multiple signalable socket acceptors will be notified each time a
   * successful connection request has been made, receiving the socket handle
   * associated with the new connection as the signal parameter. A null reference
   * may also be received as the signal parameter in order to notify socket
   * acceptors that the socket server has been closed. The server only starts
   * accepting connections once the first socket acceptor has been added in this
   * manner.
   * 
   * @param socketAcceptor This is the collection of signalable socket acceptors
   *   which are to be notified of new server connections.
   * @return Returns a boolean flag which will be set to 'true' if the server
   *   started accepting new connections as a result of this call and 'false' if
   *   the server was already accepting connections.
   * @throws SocketClosedException This exception will be thrown if an attempt is
   *   made to add a socket acceptor to a closed socket server.
   */
  public boolean addSocketAcceptors(Collection<Signalable<SocketHandle>> socketAcceptors) throws SocketClosedException;

  /**
   * Initiates a close operation on the socket server. Only a single close request
   * may be active at any given time. Closing the socket server rejects any
   * further connection requests and... TODO: Document the effect on previously
   * established connections. Close completion is indicated via the returned
   * deferred event object, where a boolean status value will be passed as the
   * callback parameter. The status flag will be set to 'true' if the socket
   * server was closed as a result of the request and 'false' if the socket server
   * was already closed. TODO: Check this is consistent with implementations.
   * 
   * @return Returns a deferred event object which will have its callbacks
   *   executed on completion of the close operation. Callbacks will be issued
   *   with a boolean status parameter which will be set to 'true' if the socket
   *   server was closed as a result of the close request and 'false' otherwise.
   */
  public Deferred<Boolean> close();

}
