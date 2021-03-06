/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.undertow.websockets.jsr;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import javax.websocket.DecodeException;
import javax.websocket.Endpoint;
import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;

import io.undertow.websockets.api.FragmentedFrameHandler;
import io.undertow.websockets.api.WebSocketFrameHeader;
import io.undertow.websockets.api.WebSocketSession;
import org.xnio.Buffers;

/**
 * {@link AbstractFrameHandler} subclass which will allow to use {@link MessageHandler.Partial} implementations
 * to operated on received fragments.
 *
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
class PartialFrameHandler extends AbstractFrameHandler<MessageHandler> implements FragmentedFrameHandler {
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private UTF8Output utf8Output;

    public PartialFrameHandler(UndertowSession session, Endpoint endpoint) {
        super(session, endpoint);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public void onTextFrame(WebSocketSession s, WebSocketFrameHeader header, ByteBuffer... payload) {
        HandlerWrapper handler = getHandler(FrameType.TEXT);
        if (handler != null) {

            String text;
            boolean last = header.isLastFragement();
            if (utf8Output == null && last) {
                text = toString(payload);
            } else {
                if (utf8Output == null) {
                    utf8Output = new UTF8Output(payload);
                } else {
                    utf8Output.write(payload);
                }
                text = utf8Output.extract();
                if (last) {
                    utf8Output = null;
                }
            }
            if (handler.getMessageType() == String.class) {
                ((MessageHandler.Partial) handler.getHandler()).onMessage(text, last);
            } else if (handler.getMessageType() == Reader.class) {
                ((MessageHandler.Partial) handler.getHandler()).onMessage(new StringReader(text), last);
            } else {
                try {
                    Object object = getSession().getEncoding().decodeText(handler.getMessageType(), text);
                    ((MessageHandler.Whole) handler.getHandler()).onMessage(object);
                } catch (DecodeException e) {
                    getEndpoint().onError(getSession(), e);
                }
            }
        }
    }

    @Override
    protected void verify(Class<?> type, MessageHandler handler) {
        if (handler instanceof MessageHandler.Partial && type == PongMessage.class) {
            throw JsrWebSocketMessages.MESSAGES.pongMessageNotSupported();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public void onBinaryFrame(WebSocketSession s, WebSocketFrameHeader header, ByteBuffer... payload) {
        HandlerWrapper handler = getHandler(FrameType.BYTE);
        if (handler != null) {
            MessageHandler.Partial mHandler = (MessageHandler.Partial) handler.getHandler();
            if (handler.getMessageType() == ByteBuffer.class) {
                mHandler.onMessage(toBuffer(payload), header.isLastFragement());
            } else if (handler.getMessageType() == byte[].class) {
                long size = Buffers.remaining(payload);
                if (size == 0) {
                    mHandler.onMessage(EMPTY, header.isLastFragement());
                } else {
                    byte[] data = toArray(payload);
                    mHandler.onMessage(data, header.isLastFragement());
                }
            } else if (handler.getMessageType() == InputStream.class) {
                long size = Buffers.remaining(payload);
                if (size == 0) {
                    mHandler.onMessage(new ByteArrayInputStream(EMPTY), header.isLastFragement());
                } else {
                    byte[] data = toArray(payload);
                    mHandler.onMessage(new ByteArrayInputStream(data), header.isLastFragement());
                }
            } else {
                try {
                    //TODO: can we decode partial frames? seems kinda silly
                    Object object = getSession().getEncoding().decodeBinary(handler.getMessageType(), toArray(payload));
                    mHandler.onMessage(object, header.isLastFragement());
                } catch (DecodeException e) {
                    getEndpoint().onError(getSession(), e);
                }
            }
        }
    }

    protected static String toString(ByteBuffer... payload) {
        ByteBuffer buffer = toBuffer(payload);
        if (buffer.hasArray()) {
            return new String(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining(), UTF_8);
        } else {
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            return new String(data, UTF_8);
        }
    }

}
