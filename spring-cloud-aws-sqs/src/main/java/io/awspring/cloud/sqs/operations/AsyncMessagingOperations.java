/*
 * Copyright 2013-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.awspring.cloud.sqs.operations;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;

/**
 * Asynchronous messaging operations. Implementations should have defaults for {@link Nullable} fields, and can provide
 * chained methods interfaces for a more fluent API.
 *
 * @author Tomaz Fernandes
 * @since 3.0
 */
public interface AsyncMessagingOperations<T> {

	/**
	 * Send a {@link Message} to the default endpoint with the provided payload. The payload will be serialized if
	 * necessary.
	 *
	 * @param payload the payload to send.
	 * @return a {@link CompletableFuture} to be completed with the message's {@link UUID}.
	 */
	CompletableFuture<SendResult<T>> sendAsync(T payload);

	/**
	 * Send a message to the provided endpoint with the provided payload. The payload will be serialized if necessary.
	 *
	 * @param endpointName the endpoint to send the message to.
	 * @param payload the payload to send.
	 * @return a {@link CompletableFuture} to be completed with the message's {@link UUID}.
	 */
	CompletableFuture<SendResult<T>> sendAsync(@Nullable String endpointName, T payload);

	/**
	 * Send the provided message along with its headers to the provided endpoint. The payload will be serialized if
	 * necessary, and headers will be converted to the specific messaging system metadata types.
	 *
	 * @param endpointName the endpoint to send the message to.
	 * @param message the message to be sent.
	 * @return a {@link CompletableFuture} to be completed with the message's {@link UUID}.
	 */
	CompletableFuture<SendResult<T>> sendAsync(@Nullable String endpointName, Message<T> message);

	/**
	 * Send the provided messages along with their headers to the provided endpoint. The payloads will be serialized if
	 * necessary, and headers will be converted to the specific messaging system metadata types.
	 *
	 * @param endpointName the endpoint to send the messages to.
	 * @param messages the messages to be sent.
	 * @return a {@link CompletableFuture} to be completed with the message's {@link UUID}.
	 */
	CompletableFuture<SendResult.Batch<T>> sendManyAsync(@Nullable String endpointName,
			Collection<Message<T>> messages);

	/**
	 * Receive a message from the default endpoint with default settings.
	 * @return a {@link CompletableFuture} to be completed with the message, or {@link Optional#empty()} if none is
	 * returned.
	 */
	CompletableFuture<Optional<Message<T>>> receiveAsync();

	/**
	 * Receive a message from the provided endpoint and convert the payload to the provided class. If no message is
	 * returned after the specified {@link Duration}, an {@link Optional#empty()} is returned.
	 * <p>
	 * Any headers provided in the additional headers parameter will be added to the {@link Message} instances returned
	 * by this method. The implementation can also allow some specific headers to change particular settings, in which
	 * case the headers are removed before sending. See the implementation javadocs for more information.
	 *
	 * @param endpointName the endpoint from which to receive the messages.
	 * @param payloadClass the class to which the payload should be converted to.
	 * @param pollTimeout the maximum amount of time to wait for messages.
	 * @param additionalHeaders headers to be added to the received messages.
	 * @return a {@link CompletableFuture} to be completed with the message, or {@link Optional#empty()} if none is
	 * returned.
	 */
	CompletableFuture<Optional<Message<T>>> receiveAsync(@Nullable String endpointName, @Nullable Class<? extends T> payloadClass,
			@Nullable Duration pollTimeout, @Nullable Map<String, Object> additionalHeaders);

	/**
	 * Receive a batch of messages from the default endpoint with default settings.
	 * @return a {@link CompletableFuture} to be completed with the messages, or an empty collection if none is
	 * returned.
	 */
	CompletableFuture<Collection<Message<T>>> receiveManyAsync();

	/**
	 * Receive a batch of messages from the provided endpoint and convert the payloads to the provided class. If no
	 * message is returned after the specified {@link Duration}, an empty collection is returned.
	 * <p>
	 * Any headers provided in the additional headers parameter will be added to the {@link Message} instances returned
	 * by this method. The implementation can also allow some specific headers to change particular settings, in which
	 * case the headers are removed before sending. See the implementation javadocs for more information.
	 *
	 * @param endpointName the endpoint from which to receive the messages.
	 * @param payloadClass the class to which the payloads should be converted to.
	 * @param pollTimeout the maximum amount of time to wait for messages.
	 * @param maxNumberOfMessages the maximum number of messages to receive.
	 * @param additionalHeaders headers to be added to the received messages.
	 * @return a {@link CompletableFuture} to be completed with the messages, or an empty collection if none is
	 * returned.
	 */
	// @formatter:off
	CompletableFuture<Collection<Message<T>>> receiveManyAsync(@Nullable String endpointName,
															   @Nullable Class<? extends T> payloadClass,
															   @Nullable Duration pollTimeout,
															   @Nullable Integer maxNumberOfMessages,
															   @Nullable Map<String, Object> additionalHeaders);
	// @formatter:on

}