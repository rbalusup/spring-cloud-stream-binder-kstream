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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.kstream.KStream;

import org.springframework.cloud.stream.binding.StreamListenerParameterAdapter;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MessageConverter;

/**
 * @author Marius Bogoevici
 */
public class KStreamListenerParameterAdapter implements StreamListenerParameterAdapter<KStream, KStream<?, ?>> {

	private final Log logger = LogFactory.getLog(this.getClass());

	private final MessageConverter messageConverter;

	public KStreamListenerParameterAdapter(MessageConverter messageConverter) {
		this.messageConverter = messageConverter;
	}

	@Override
	public boolean supports(Class bindingTargetType, MethodParameter methodParameter) {
		return KStream.class.isAssignableFrom(bindingTargetType)
				&& KStream.class.isAssignableFrom(methodParameter.getParameterType());
	}

	@Override
	public KStream adapt(KStream bindingTarget, MethodParameter parameter) {
		ResolvableType resolvableType = ResolvableType.forMethodParameter(parameter);
		final Class<?> valueClass = (resolvableType.getGeneric(1).getRawClass() != null)
				? (resolvableType.getGeneric(1).getRawClass()) : Object.class;
		if (!Message.class.isAssignableFrom(valueClass)) {
			return bindingTarget.map((o, o2) -> {
				if (o2 instanceof Message) {
					Object payload = ((Message<?>) o2).getPayload();
					if (valueClass.isAssignableFrom(payload.getClass())) {
						return new KeyValue<>(o, payload);
					}
					else {
						return new KeyValue<>(o, messageConverter.fromMessage((Message) o2, valueClass));
					}
				}
				else {
					return new KeyValue<>(o, o2);
				}
			});
		}
		return bindingTarget;

	}

}
