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

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

/**
 * Sqs-specific asynchronous messaging operations for Standard and Fifo queues.
 * <p>
 * Note that the Standard queue methods can be used for Fifo queues as long as necessary headers are added for required
 * attributes such as message deduplication id. See {@link io.awspring.cloud.sqs.listener.SqsHeaders} for reference of
 * available headers.
 * <p>
 * Fifo queue methods accept the required attributes and add a random value if none is specified.
 * 
 * @author Tomaz Fernandes
 * @since 3.0
 * @param <T> the message payload type, or {@link Object}.
 */
public interface SqsAsyncOperations<T> extends AsyncMessagingOperations<T> {

	/**
	 * Send a message to a Standard SQS queue using the {@link SqsSendOptions.Standard} options.
	 * @param to a {@link SqsSendOptions.Standard} consumer.
	 * @return a {@link CompletableFuture} to be completed with the {@link UUID} of the message.
	 */
	CompletableFuture<SendResult<T>> sendAsync(Consumer<SqsSendOptions.Standard<T>> to);

	/**
	 * Send a message to a Fifo SQS queue using the {@link SqsSendOptions.Fifo} options.
	 * @param to a {@link SqsSendOptions.Fifo} consumer.
	 * @return a {@link CompletableFuture} to be completed with the {@link UUID} of the message.
	 */
	CompletableFuture<SendResult<T>> sendFifoAsync(Consumer<SqsSendOptions.Fifo<T>> to);

	/**
	 * Send a batch of messages to a Fifo SQS queue.
	 * @param endpoint the endpoint to which to send the messages or null to use the default.
	 * @param messages the messages.
	 * @return a {@link CompletableFuture} to be completed with the {@link SendMessageBatchResponse}.
	 */
	CompletableFuture<SendResult.Batch<T>> sendFifoAsync(@Nullable String endpoint, Collection<Message<T>> messages);

	/**
	 * Receive a message from a Standard SQS queue using the {@link SqsReceiveOptions.Standard} options.
	 * @param from a {@link SqsReceiveOptions.Standard} consumer.
	 * @return a {@link CompletableFuture} to be completed with the message, or {@link Optional#empty()} if none is
	 * returned.
	 */
	CompletableFuture<Optional<Message<T>>> receiveAsync(Consumer<SqsReceiveOptions.Standard<T>> from);

	/**
	 * Receive a message from a Fifo SQS queue using the {@link SqsReceiveOptions.Fifo} options.
	 * @param from a {@link SqsReceiveOptions.Fifo} consumer.
	 * @return a {@link CompletableFuture} to be completed with the message, or {@link Optional#empty()} if none is
	 * returned.
	 */
	CompletableFuture<Optional<Message<T>>> receiveFifoAsync(Consumer<SqsReceiveOptions.Fifo<T>> from);

	/**
	 * Receive a batch of messages from a Standard SQS queue using the {@link SqsReceiveOptions.Standard} options.
	 * @param from a {@link SqsReceiveOptions.Standard} consumer.
	 * @return a {@link CompletableFuture} to be completed with the messages, or an empty collection if none is
	 * returned.
	 */
	CompletableFuture<Collection<Message<T>>> receiveManyAsync(Consumer<SqsReceiveOptions.Standard<T>> from);

	/**
	 * Receive a batch of messages from a Fifo SQS queue using the {@link SqsReceiveOptions.Fifo} options.
	 * @param from a {@link SqsReceiveOptions.Fifo} consumer.
	 * @return a {@link CompletableFuture} to be completed with the messages, or an empty collection if none is
	 * returned.
	 */
	CompletableFuture<Collection<Message<T>>> receiveManyFifoAsync(Consumer<SqsReceiveOptions.Fifo<T>> from);

}