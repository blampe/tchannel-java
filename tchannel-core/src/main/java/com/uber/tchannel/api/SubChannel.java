/*
 * Copyright (c) 2015 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.uber.tchannel.api;

import com.google.common.util.concurrent.ListenableFuture;
import com.uber.tchannel.api.errors.TChannelConnectionTimeout;
import com.uber.tchannel.api.errors.TChannelError;
import com.uber.tchannel.api.errors.TChannelNoPeerAvailable;
import com.uber.tchannel.api.handlers.RequestHandler;
import com.uber.tchannel.channels.Connection;
import com.uber.tchannel.channels.PeerManager;
import com.uber.tchannel.channels.SubPeer;
import com.uber.tchannel.errors.ErrorType;
import com.uber.tchannel.handlers.ResponseRouter;
import com.uber.tchannel.headers.ArgScheme;
import com.uber.tchannel.headers.TransportHeaders;
import com.uber.tchannel.messages.JSONSerializer;
import com.uber.tchannel.messages.JsonRequest;
import com.uber.tchannel.messages.JsonResponse;
import com.uber.tchannel.messages.RawRequest;
import com.uber.tchannel.messages.RawResponse;
import com.uber.tchannel.messages.Request;
import com.uber.tchannel.messages.Serializer;
import com.uber.tchannel.messages.ThriftRequest;
import com.uber.tchannel.messages.ThriftResponse;
import com.uber.tchannel.messages.ThriftSerializer;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class SubChannel {

    private final String service;
    private final TChannel topChannel;
    private final PeerManager peerManager;
    private final long initTimeout;
    private final Connection.Direction preferredDirection;

    private Map<String, RequestHandler> requestHandlers = new HashMap<>();
    private List<SubPeer> peers = new ArrayList<>();

    private final Serializer serializer = new Serializer(new HashMap<ArgScheme, Serializer.SerializerInterface>() {
        {
            put(ArgScheme.JSON, new JSONSerializer());
            put(ArgScheme.THRIFT, new ThriftSerializer());
        }
    });

    public SubChannel(String service, TChannel topChannel) {
        this.service = service;
        this.topChannel = topChannel;
        this.peerManager = topChannel.getPeerManager();
        this.initTimeout = topChannel.getInitTimeout();
        this.preferredDirection = Connection.Direction.NONE;
    }

    public SubChannel(String service, TChannel topChannel, Connection.Direction preferredDirection) {
        this.service = service;
        this.topChannel = topChannel;
        this.peerManager = topChannel.getPeerManager();
        this.initTimeout = topChannel.getInitTimeout();
        this.preferredDirection = preferredDirection;
    }

    public String getServiceName() {
        return this.service;
    }

    public PeerManager getPeerManager() {
        return this.peerManager;
    }

    public SubChannel register(String endpoint, RequestHandler requestHandler) {
        requestHandlers.put(endpoint, requestHandler);
        return this;
    }

    public RequestHandler getRequestHandler(String endpoint) {
        return requestHandlers.get(endpoint);
    }

    public Connection.Direction getPreferredDirection() {
        return preferredDirection;
    }

    public SubChannel setPeers(List<InetSocketAddress> peers) {
        for (InetSocketAddress peer : peers) {
            this.peers.add(new SubPeer(peer, this));
        }

        return this;
    }

    private final Random random = new Random();
    public SubPeer choosePeer() {
        SubPeer res = null;
        if (peers.size() == 0) {
            return null;
        }

        int start = new Random().nextInt(peers.size());
        int i = start;
        boolean stop = false;
        do {
            i = (i + 1) % peers.size();
            SubPeer peer = peers.get(i);
            stop = peer.updateScore();
            if (stop || res == null) {
                res = peer;
            } else if (peer.getScore() > res.getScore()) {
                res = peer;
            }
        } while (!stop && i != start);

        return res;
    }

    public Connection connect() throws TChannelError {
        SubPeer peer = choosePeer();
        Connection conn = peer.getPreferredConnection();
        if (conn == null) {
            conn = peer.connectTo();
        }

        return conn;
    }

    protected <V> ListenableFuture<V> sendRequest(
        Request request,
        InetAddress host,
        int port
    ) throws TChannelError {

        // Get an outbound channel
        Connection conn = null;

        if (host != null) {
            conn = this.peerManager.findOrNew(new InetSocketAddress(host, port));
        } else if (peers.size() == 0) {
            throw new TChannelNoPeerAvailable();
        } else {
            conn = connect();
        }

        if (!conn.waitForIdentified(this.initTimeout)) {
            request.release();
            conn.clean();
            if (conn.lastError() != null) {
                return ResponseRouter.createError(request, ErrorType.NetworkError, conn.lastError());
            } else {
                return ResponseRouter.createError(request, ErrorType.NetworkError, new TChannelConnectionTimeout());
            }
        }

        // Set the ArgScheme as RAW if its not set
        Map<String, String> transportHeaders = request.getTransportHeaders();
        if (!transportHeaders.containsKey(TransportHeaders.ARG_SCHEME_KEY)) {
            request.setTransportHeader(TransportHeaders.ARG_SCHEME_KEY, ArgScheme.RAW.getScheme());
        }

        // Get a response router for our outbound channel
        ResponseRouter router = conn.channel().pipeline().get(ResponseRouter.class);
        return router.expectResponse(request);
    }

    public <T, U> ListenableFuture<ThriftResponse<U>> send(
            ThriftRequest<T> request,
            InetAddress host,
            int port
    ) throws TChannelError {
        // Set the "cn" header
        // TODO: should make "cn" an option
        request.setTransportHeader(TransportHeaders.CALLER_NAME_KEY, this.topChannel.getServiceName());
        return sendRequest(request, host, port);
    }

    public <T, U> ListenableFuture<ThriftResponse<U>> send(
        ThriftRequest<T> request
    ) throws TChannelError {
        return send(request, null, 0);
    }

    public <T, U> ListenableFuture<JsonResponse<U>> send(
        JsonRequest<T> request,
        InetAddress host,
        int port
    ) throws TChannelError {
        // Set the "cn" header
        // TODO: should make "cn" an option
        request.setTransportHeader(TransportHeaders.CALLER_NAME_KEY, this.topChannel.getServiceName());
        return sendRequest(request, host, port);
    }

    public <T, U> ListenableFuture<JsonResponse<U>> send(
        JsonRequest<T> request
    ) throws TChannelError {
        return send(request, null, 0);
    }

    public ListenableFuture<RawResponse> send(
        RawRequest request,
        InetAddress host,
        int port
    ) throws TChannelError {
        // Set the "cn" header
        // TODO: should make "cn" an option
        request.setTransportHeader(TransportHeaders.CALLER_NAME_KEY, this.topChannel.getServiceName());
        return sendRequest(request, host, port);
    }

    public ListenableFuture<RawResponse> send(
        RawRequest request
    ) throws TChannelError {
        return send(request, null, 0);
    }
}