/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.kstream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.KStream;

import org.springframework.cloud.stream.binder.AbstractBinder;
import org.springframework.cloud.stream.binder.BinderHeaders;
import org.springframework.cloud.stream.binder.Binding;
import org.springframework.cloud.stream.binder.ConsumerProperties;
import org.springframework.cloud.stream.binder.DefaultBinding;
import org.springframework.cloud.stream.binder.EmbeddedHeadersMessageConverter;
import org.springframework.cloud.stream.binder.HeaderMode;
import org.springframework.cloud.stream.binder.MessageValues;
import org.springframework.cloud.stream.binder.ProducerProperties;
import org.springframework.cloud.stream.kstream.config.KStreamBinderProperties;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.integration.codec.Codec;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHeaders;
import org.springframework.util.Assert;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.ObjectUtils;

/**
 * @author Marius Bogoevici
 */
public class KStreamBinder extends AbstractBinder<KStream<Object, Object>, ConsumerProperties, ProducerProperties> {

	private final EmbeddedHeadersMessageConverter embeddedHeadersMessageConverter = new
																							EmbeddedHeadersMessageConverter();

	private Codec codec;

	private KStreamBinderProperties kStreamBinderProperties;

	private String[] headers;

	public KStreamBinder(Codec codec, KStreamBinderProperties kStreamBinderProperties) {
		this.codec = codec;
		this.headers = headersToMap(kStreamBinderProperties);
	}

	@Override
	protected Binding<KStream<Object, Object>> doBindConsumer(String name, String group, KStream<Object, Object> inputTarget, ConsumerProperties properties) {
		return new DefaultBinding<>(name, group, inputTarget, null);
	}

	@Override
	protected Binding<KStream<Object, Object>> doBindProducer(String name, KStream<Object, Object> outboundBindTarget, ProducerProperties properties) {
		if (HeaderMode.embeddedHeaders.equals(properties.getHeaderMode())) {
			outboundBindTarget = outboundBindTarget.map((k, v) -> {
				if (v instanceof Message) {
					try {
						return new KeyValue<>(k, serializeAndEmbedHeadersIfApplicable((Message<?>) v));
					} catch (Exception e) {
						throw new IllegalArgumentException(e);
					}
				} else {
					throw new IllegalArgumentException("Wrong type of message " + v);
				}
			});
		}
		outboundBindTarget.map((k, v) -> new KeyValue<>((byte[]) k, (byte[]) v)).to(Serdes.ByteArray(), Serdes.ByteArray(), name);
		return new DefaultBinding<>(name, null, outboundBindTarget, null);
	}

	private byte[] serializeAndEmbedHeadersIfApplicable(Message<?> message) throws Exception {
		MessageValues transformed = serializePayloadIfNecessary(message);
		byte[] payload;

		Object contentType = transformed.get(MessageHeaders.CONTENT_TYPE);
		// transform content type headers to String, so that they can be properly embedded in JSON
		if (contentType instanceof MimeType) {
			transformed.put(MessageHeaders.CONTENT_TYPE, contentType.toString());
		}
		Object originalContentType = transformed.get(BinderHeaders.BINDER_ORIGINAL_CONTENT_TYPE);
		if (originalContentType instanceof MimeType) {
			transformed.put(BinderHeaders.BINDER_ORIGINAL_CONTENT_TYPE, originalContentType.toString());
		}
		payload = embeddedHeadersMessageConverter.embedHeaders(transformed, headers);
		return payload;
	}

	private static String[] headersToMap(KStreamBinderProperties configurationProperties) {
		String[] headersToMap;
		if (ObjectUtils.isEmpty(configurationProperties.getHeaders())) {
			headersToMap = BinderHeaders.STANDARD_HEADERS;
		} else {
			String[] combinedHeadersToMap = Arrays.copyOfRange(BinderHeaders.STANDARD_HEADERS, 0,
					BinderHeaders.STANDARD_HEADERS.length + configurationProperties.getHeaders().length);
			System.arraycopy(configurationProperties.getHeaders(), 0, combinedHeadersToMap,
					BinderHeaders.STANDARD_HEADERS.length,
					configurationProperties.getHeaders().length);
			headersToMap = combinedHeadersToMap;
		}
		return headersToMap;
	}

	final MessageValues serializePayloadIfNecessary(Message<?> message) {
		Object originalPayload = message.getPayload();
		Object originalContentType = message.getHeaders().get(MessageHeaders.CONTENT_TYPE);

		// Pass content type as String since some transport adapters will exclude
		// CONTENT_TYPE Header otherwise
		Object contentType = JavaClassMimeTypeConversion
				.mimeTypeFromObject(originalPayload, ObjectUtils.nullSafeToString(originalContentType)).toString();
		Object payload = serializePayloadIfNecessary(originalPayload);
		MessageValues messageValues = new MessageValues(message);
		messageValues.setPayload(payload);
		messageValues.put(MessageHeaders.CONTENT_TYPE, contentType);
		if (originalContentType != null && !originalContentType.toString().equals(contentType.toString())) {
			messageValues.put(BinderHeaders.BINDER_ORIGINAL_CONTENT_TYPE, originalContentType.toString());
		}
		return messageValues;
	}

	private byte[] serializePayloadIfNecessary(Object originalPayload) {
		if (originalPayload instanceof byte[]) {
			return (byte[]) originalPayload;
		} else {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			try {
				if (originalPayload instanceof String) {
					return ((String) originalPayload).getBytes("UTF-8");
				}
				this.codec.encode(originalPayload, bos);
				return bos.toByteArray();
			} catch (IOException e) {
				throw new SerializationFailedException("unable to serialize payload [" + originalPayload.getClass().getName() + "]", e);
			}
		}
	}

	private abstract static class JavaClassMimeTypeConversion {

		private static ConcurrentMap<String, MimeType> mimeTypesCache = new ConcurrentHashMap<>();

		static MimeType mimeTypeFromObject(Object payload, String originalContentType) {
			Assert.notNull(payload, "payload object cannot be null.");
			if (payload instanceof byte[]) {
				return MimeTypeUtils.APPLICATION_OCTET_STREAM;
			}
			if (payload instanceof String) {
				return MimeTypeUtils.APPLICATION_JSON_VALUE.equals(originalContentType) ? MimeTypeUtils.APPLICATION_JSON
					: MimeTypeUtils.TEXT_PLAIN;
			}
			String className = payload.getClass().getName();
			MimeType mimeType = mimeTypesCache.get(className);
			if (mimeType == null) {
				String modifiedClassName = className;
				if (payload.getClass().isArray()) {
					// Need to remove trailing ';' for an object array, e.g.
					// "[Ljava.lang.String;" or multi-dimensional
					// "[[[Ljava.lang.String;"
					if (modifiedClassName.endsWith(";")) {
						modifiedClassName = modifiedClassName.substring(0, modifiedClassName.length() - 1);
					}
					// Wrap in quotes to handle the illegal '[' character
					modifiedClassName = "\"" + modifiedClassName + "\"";
				}
				mimeType = MimeType.valueOf("application/x-java-object;type=" + modifiedClassName);
				mimeTypesCache.put(className, mimeType);
			}
			return mimeType;
		}

	}
}
