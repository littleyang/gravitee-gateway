package io.gravitee.gateway.core.impl;

import io.gravitee.gateway.core.AsyncHandler;
import io.gravitee.gateway.core.Processor;
import io.gravitee.gateway.http.ContentRequest;
import io.gravitee.gateway.http.Request;
import io.gravitee.gateway.http.Response;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.http.*;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
public class RequestProcessor implements Processor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestProcessor.class);

    protected static final Set<String> HOP_HEADERS;
    static
    {
        Set<String> hopHeaders = new HashSet();
        hopHeaders.add("connection");
        hopHeaders.add("keep-alive");
        hopHeaders.add("proxy-authorization");
        hopHeaders.add("proxy-authenticate");
        hopHeaders.add("proxy-connection");
        hopHeaders.add("transfer-encoding");
        hopHeaders.add("te");
        hopHeaders.add("trailer");
        hopHeaders.add("upgrade");
        HOP_HEADERS = Collections.unmodifiableSet(hopHeaders);
    }

    private final Request request;

    private final AsyncHandler<Response> responseHandler;

    public RequestProcessor(final Request request, final AsyncHandler<Response> responseHandler) {
        this.request = request;
        this.responseHandler = responseHandler;
    }

    @Override
    public void process() {
        try {
            HttpClient client = createHttpClient();
            org.eclipse.jetty.client.api.Request proxyRequest = client.newRequest("http://yrfrlmasbam.corp.leroymerlin.com/api-product/v1/products/69135185?storeId=142&webmetadata=true").method(HttpMethod.GET).version(HttpVersion.HTTP_1_1);

            if (request.hasContent()) {
                proxyRequest.content(new ProxyInputStreamContentProvider(proxyRequest, (ContentRequest) request));
            }

            proxyRequest.send(new ProxyResponseListener(request, new Response()));
        } catch (Exception ex) {
            LOGGER.error("An error occurs while proxying request...", ex);
        }
    }

    protected class ProxyResponseListener extends org.eclipse.jetty.client.api.Response.Listener.Adapter
    {
        private final Request request;
        private final Response response;

        protected ProxyResponseListener(Request request, Response response)
        {
            this.request = request;
            this.response = response;
        }

        @Override
        public void onBegin(org.eclipse.jetty.client.api.Response proxyResponse)
        {
            response.setStatus(proxyResponse.getStatus());
        }

        @Override
        public void onHeaders(org.eclipse.jetty.client.api.Response proxyResponse)
        {
            onServerResponseHeaders(response, proxyResponse);
        }

        @Override
        public void onContent(final org.eclipse.jetty.client.api.Response proxyResponse, ByteBuffer content, final Callback callback)
        {
            byte[] buffer;
            int offset;
            int length = content.remaining();
            if (content.hasArray())
            {
                buffer = content.array();
                offset = content.arrayOffset();
            }
            else
            {
                buffer = new byte[length];
                content.get(buffer);
                offset = 0;
            }

            onResponseContent(request, response, proxyResponse, buffer, offset, length, new Callback()
            {
                @Override
                public void succeeded()
                {
                    callback.succeeded();
                }

                @Override
                public void failed(Throwable x)
                {
                    callback.failed(x);
                    proxyResponse.abort(x);
                }
            });
        }

        @Override
        public void onComplete(Result result)
        {
            if (result.isSucceeded()) {
                onProxyResponseSuccess(request, response, result.getResponse());
            } else {
                onProxyResponseFailure(request, response, result.getResponse(), result.getFailure());
            }

            LOGGER.debug("{} proxying complete", request.getId());
        }
    }

    protected void onProxyResponseSuccess(Request clientRequest, Response proxyResponse, org.eclipse.jetty.client.api.Response serverResponse)
    {
        LOGGER.debug("{} proxying successful", clientRequest.getId());
        responseHandler.handle(proxyResponse);
//        AsyncContext asyncContext = clientRequest.getAsyncContext();
//        asyncContext.complete();
    }

    protected void onProxyResponseFailure(Request clientRequest, Response proxyResponse, org.eclipse.jetty.client.api.Response serverResponse, Throwable failure)
    {
        LOGGER.debug(clientRequest.getId() + " proxying failed", failure);

        if (failure instanceof TimeoutException)
            proxyResponse.setStatus(504);
        else
            proxyResponse.setStatus(502);

        proxyResponse.getHeaders().put(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());

        responseHandler.handle(proxyResponse);

        /*
        if (proxyResponse.isCommitted())
        {
            try
            {
                // Use Jetty specific behavior to close connection.
                proxyResponse.sendError(-1);
                AsyncContext asyncContext = clientRequest.getAsyncContext();
                asyncContext.complete();
            }
            catch (IOException x)
            {
                if (_log.isDebugEnabled())
                    _log.debug(getRequestId(clientRequest) + " could not close the connection", failure);
            }
        }
        else
        {
            proxyResponse.resetBuffer();
            if (failure instanceof TimeoutException)
                proxyResponse.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
            else
                proxyResponse.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            proxyResponse.setHeader(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());
            AsyncContext asyncContext = clientRequest.getAsyncContext();
            asyncContext.complete();
        }
        */
    }

    protected void onResponseContent(Request request, Response response, org.eclipse.jetty.client.api.Response proxyResponse, byte[] buffer, int offset, int length, Callback callback)
    {
        try
        {
            LOGGER.debug("{} proxying content to downstream: {} bytes", request.getId(), length);
            response.getOutputStream().write(buffer, offset, length);
            callback.succeeded();
        }
        catch (Throwable x)
        {
            callback.failed(x);
        }
    }

    protected void onServerResponseHeaders(Response proxyResponse, org.eclipse.jetty.client.api.Response serverResponse)
    {
        for (HttpField field : serverResponse.getHeaders())
        {
            String headerName = field.getName();
            String lowerHeaderName = headerName.toLowerCase(Locale.ENGLISH);
            if (HOP_HEADERS.contains(lowerHeaderName))
                continue;

            String newHeaderValue = field.getValue();
            if (newHeaderValue == null || newHeaderValue.trim().length() == 0)
                continue;

            proxyResponse.getHeaders().put(headerName, newHeaderValue);
        }
    }

    protected class ProxyInputStreamContentProvider extends InputStreamContentProvider {
        private final org.eclipse.jetty.client.api.Request proxyRequest;
        private final ContentRequest request;

        protected ProxyInputStreamContentProvider(org.eclipse.jetty.client.api.Request proxyRequest, ContentRequest request) {
            super(request.getInputStream());
            this.proxyRequest = proxyRequest;
            this.request = request;
        }

        @Override
        public long getLength() {
            return request.getContentLength();
        }

        @Override
        protected ByteBuffer onRead(byte[] buffer, int offset, int length) {
            LOGGER.debug("{} proxying content to upstream: {} bytes", request.getId(), length);
            return onRequestContent(proxyRequest, request, buffer, offset, length);
        }

        protected ByteBuffer onRequestContent(org.eclipse.jetty.client.api.Request proxyRequest, final ContentRequest request, byte[] buffer, int offset, int length) {
            return super.onRead(buffer, offset, length);
        }

        @Override
        protected void onReadFailure(Throwable failure) {
            onClientRequestFailure(proxyRequest, request, failure);
        }
    }

    protected void onClientRequestFailure(org.eclipse.jetty.client.api.Request proxyRequest, Request request, Throwable failure) {
        LOGGER.debug(request.getId() + " client request failure", failure);
        proxyRequest.abort(failure);
    }

    protected HttpClient createHttpClient() throws Exception {
        HttpClient client = new HttpClient();

        // Redirects must be proxied as is, not followed
        client.setFollowRedirects(false);

        // Must not store cookies, otherwise cookies of different clients will mix
        client.setCookieStore(new HttpCookieStore.Empty());

        // Be careful : max threads can't be less than 2 -> deadlock
        QueuedThreadPool qtp = new QueuedThreadPool(200);

        qtp.setName("dispatcher");

        client.setExecutor(qtp);
        client.setIdleTimeout(30000);
        client.setRequestBufferSize(16384);
        client.setResponseBufferSize(163840);

        client.start();

        // Content must not be decoded, otherwise the client gets confused
        client.getContentDecoderFactories().clear();

        return client;
    }

}