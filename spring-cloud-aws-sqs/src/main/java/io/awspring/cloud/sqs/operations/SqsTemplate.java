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

import io.awspring.cloud.sqs.MessageHeaderUtils;
import io.awspring.cloud.sqs.QueueAttributesResolver;
import io.awspring.cloud.sqs.SqsAcknowledgementException;
import io.awspring.cloud.sqs.listener.QueueAttributes;
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy;
import io.awspring.cloud.sqs.listener.SqsHeaders;
import io.awspring.cloud.sqs.listener.acknowledgement.AcknowledgementCallback;
import io.awspring.cloud.sqs.support.converter.MessageAttributeDataTypes;
import io.awspring.cloud.sqs.support.converter.MessageConversionContext;
import io.awspring.cloud.sqs.support.converter.MessagingMessageConverter;
import io.awspring.cloud.sqs.support.converter.SqsMessageConversionContext;
import io.awspring.cloud.sqs.support.converter.SqsMessagingMessageConverter;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.Assert;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResultEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeNameForSends;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeValue;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class SqsTemplate<T> extends AbstractMessagingTemplate<T, Message>
		implements SqsOperations<T>, SqsAsyncOperations<T> {

	private static final Logger logger = LoggerFactory.getLogger(SqsTemplate.class);

	private final Map<String, CompletableFuture<QueueAttributes>> queueAttributesCache = new ConcurrentHashMap<>();

	private final Map<String, SqsMessageConversionContext> conversionContextCache = new ConcurrentHashMap<>();

	private final SqsAsyncClient sqsAsyncClient;

	private final Collection<QueueAttributeName> queueAttributeNames;

	private final QueueNotFoundStrategy queueNotFoundStrategy;

	private final Collection<String> messageAttributeNames;

	private final Collection<String> messageSystemAttributeNames;

	private SqsTemplate(BuilderImpl<T> builder) {
		super(builder.messageConverter, builder.options);
		SqsTemplateOptionsImpl<T> options = builder.options;
		this.sqsAsyncClient = builder.sqsAsyncClient;
		this.messageAttributeNames = options.messageAttributeNames;
		this.queueAttributeNames = options.queueAttributeNames;
		this.queueNotFoundStrategy = options.queueNotFoundStrategy;
		this.messageSystemAttributeNames = options.messageSystemAttributeNames;
	}

	/**
	 * Create a new {@link Builder}.
	 * @return the builder.
	 * @param <T> the payload type.
	 */
	public static <T> Builder<T> builder() {
		return new BuilderImpl<>();
	}

	/**
	 * Create a new {@link SqsTemplate} instance with the provided {@link SqsAsyncClient} and both sync and async
	 * operations.
	 * @param sqsAsyncClient the client to be used by the template.
	 * @return the {@link SqsTemplate} instance.
	 * @param <T> the payload type.
	 */
	public static <T> SqsTemplate<T> newTemplate(SqsAsyncClient sqsAsyncClient) {
		return new BuilderImpl<T>().sqsAsyncClient(sqsAsyncClient).build();
	}

	/**
	 * Create a new {@link SqsTemplate} instance with the provided {@link SqsAsyncClient}, only exposing the sync
	 * methods contained in {@link SqsOperations}.
	 * @param sqsAsyncClient the client.
	 * @return the new template instance.
	 * @param <T> the payload type.
	 */
	public static <T> SqsOperations<T> newSyncTemplate(SqsAsyncClient sqsAsyncClient) {
		return newTemplate(sqsAsyncClient);
	}

	/**
	 * Create a new {@link SqsTemplate} instance with the provided {@link SqsAsyncClient}, only exposing the async
	 * methods contained in {@link SqsAsyncOperations}.
	 * @param sqsAsyncClient the client.
	 * @return the new template instance.
	 * @param <T> the payload type.
	 */
	public static <T> SqsAsyncOperations<T> newAsyncTemplate(SqsAsyncClient sqsAsyncClient) {
		return newTemplate(sqsAsyncClient);
	}

	@Override
	public SendResult<T> send(Consumer<SqsSendOptions.Standard<T>> to) {
		return unwrapCompletionException(sendAsync(to));
	}

	@Override
	public SendResult<T> sendFifo(Consumer<SqsSendOptions.Fifo<T>> to) {
		return unwrapCompletionException(sendFifoAsync(to));
	}

	@Override
	public CompletableFuture<SendResult<T>> sendAsync(Consumer<SqsSendOptions.Standard<T>> to) {
		Assert.notNull(to, "to must not be null");
		SendStandardOptionsImpl<T> options = new SendStandardOptionsImpl<>();
		to.accept(options);
		return sendAsync(options.queue, messageFromSendOptions(options));
	}

	@Override
	public CompletableFuture<SendResult<T>> sendFifoAsync(Consumer<SqsSendOptions.Fifo<T>> to) {
		Assert.notNull(to, "to must not be null");
		SendFifoOptionsImpl<T> options = new SendFifoOptionsImpl<>();
		to.accept(options);
		return sendAsync(options.queue, addFifoHeaders(messageFromSendOptions(options),
				getUUIDOrRandom(options.messageGroupId), getUUIDOrRandom(options.messageDeduplicationId)));
	}

	@Override
	public SendResult.Batch<T> sendManyFifo(String endpoint,
			Collection<org.springframework.messaging.Message<T>> messages) {
		return unwrapCompletionException(sendFifoAsync(endpoint, messages));
	}

	@Override
	public CompletableFuture<SendResult.Batch<T>> sendFifoAsync(@Nullable String endpoint,
			Collection<org.springframework.messaging.Message<T>> messages) {
		Assert.notEmpty(messages, "messages must not be empty");
		return sendManyAsync(endpoint, messages.stream()
				.map(message -> addFifoHeaders(message, UUID.randomUUID(), UUID.randomUUID())).toList());
	}

	private org.springframework.messaging.Message<T> addFifoHeaders(org.springframework.messaging.Message<T> message,
			UUID messageGroupId, UUID messageDeduplicationID) {
		return MessageHeaderUtils.addHeadersToMessage(message,
				Map.of(SqsHeaders.MessageSystemAttributes.SQS_MESSAGE_GROUP_ID_HEADER, messageGroupId.toString(),
						SqsHeaders.MessageSystemAttributes.SQS_MESSAGE_DEDUPLICATION_ID_HEADER,
						messageDeduplicationID.toString()));
	}

	@Override
	public Optional<org.springframework.messaging.Message<T>> receive(Consumer<SqsReceiveOptions.Standard<T>> from) {
		return unwrapCompletionException(receiveAsync(from));
	}

	@Override
	public Optional<org.springframework.messaging.Message<T>> receiveFifo(Consumer<SqsReceiveOptions.Fifo<T>> from) {
		return unwrapCompletionException(receiveFifoAsync(from));
	}

	@Override
	public Collection<org.springframework.messaging.Message<T>> receiveMany(
			Consumer<SqsReceiveOptions.Standard<T>> from) {
		return unwrapCompletionException(receiveManyAsync(from));
	}

	@Override
	public Collection<org.springframework.messaging.Message<T>> receiveManyFifo(
			Consumer<SqsReceiveOptions.Fifo<T>> from) {
		return unwrapCompletionException(receiveManyFifoAsync(from));
	}

	@Override
	public CompletableFuture<Optional<org.springframework.messaging.Message<T>>> receiveAsync(
			Consumer<SqsReceiveOptions.Standard<T>> from) {
		Assert.notNull(from, "from must not be null");
		ReceiveStandardOptionsImpl<T> options = new ReceiveStandardOptionsImpl<>();
		from.accept(options);
		options.maxNumberOfMessages(1);
		Map<String, Object> additionalHeaders = getAdditionalHeaders(options);
		return receiveAsync(options.queue, options.payloadClass, options.pollTimeout, additionalHeaders);
	}

	@Override
	public CompletableFuture<Optional<org.springframework.messaging.Message<T>>> receiveFifoAsync(
			Consumer<SqsReceiveOptions.Fifo<T>> from) {
		Assert.notNull(from, "from must not be null");
		ReceiveFifoOptionsImpl<T> options = new ReceiveFifoOptionsImpl<>();
		from.accept(options);
		options.maxNumberOfMessages(1);
		Map<String, Object> additionalHeaders = handleReceiveRequestHeader(options);
		return receiveAsync(options.queue, options.payloadClass, options.pollTimeout, additionalHeaders);
	}

	@Override
	public CompletableFuture<Collection<org.springframework.messaging.Message<T>>> receiveManyAsync(
			Consumer<SqsReceiveOptions.Standard<T>> from) {
		Assert.notNull(from, "from must not be null");
		ReceiveStandardOptionsImpl<T> options = new ReceiveStandardOptionsImpl<>();
		from.accept(options);
		Map<String, Object> additionalHeaders = getAdditionalHeaders(options);
		return receiveManyAsync(options.queue, options.payloadClass, options.pollTimeout, options.maxNumberOfMessages,
				additionalHeaders);
	}

	@Override
	public CompletableFuture<Collection<org.springframework.messaging.Message<T>>> receiveManyFifoAsync(
			Consumer<SqsReceiveOptions.Fifo<T>> from) {
		Assert.notNull(from, "from must not be null");
		ReceiveFifoOptionsImpl<T> options = new ReceiveFifoOptionsImpl<>();
		from.accept(options);
		Map<String, Object> additionalHeaders = handleReceiveRequestHeader(options);
		return receiveManyAsync(options.queue, options.payloadClass, options.pollTimeout, options.maxNumberOfMessages,
				additionalHeaders);
	}

	private Map<String, Object> handleReceiveRequestHeader(ReceiveFifoOptionsImpl<T> options) {
		Map<String, Object> additionalHeaders = getAdditionalHeaders(options);
		additionalHeaders.put(SqsHeaders.SQS_RECEIVE_REQUEST_ATTEMPT_ID_HEADER,
				getUUIDOrRandom(options.receiveRequestAttemptId));
		return additionalHeaders;
	}

	private Map<String, Object> getAdditionalHeaders(AbstractSqsReceiveOptionsImpl<?, ?> options) {
		Map<String, Object> additionalHeaders = new HashMap<>(options.additionalHeaders);
		if (options.visibilityTimeout != null) {
			additionalHeaders.put(SqsHeaders.SQS_VISIBILITY_TIMEOUT_HEADER, options.visibilityTimeout);
		}
		return additionalHeaders;
	}

	private static UUID getUUIDOrRandom(@Nullable UUID uuid) {
		return uuid != null ? uuid : UUID.randomUUID();
	}

	private org.springframework.messaging.Message<T> messageFromSendOptions(AbstractSqsSendOptionsImpl<T, ?> options) {
		Assert.notNull(options.payload, "payload must not be null");
		MessageBuilder<T> builder = MessageBuilder.withPayload(options.payload).copyHeaders(options.headers);
		if (options.delay != null) {
			builder.setHeader(SqsHeaders.SQS_DELAY_HEADER, options.delay);
		}
		return builder.build();
	}

	@Override
	protected CompletableFuture<SendResult<T>> doSendAsync(String endpointName, Message message,
			org.springframework.messaging.Message<T> originalMessage) {
		return createSendMessageRequest(endpointName, message).thenCompose(this.sqsAsyncClient::sendMessage)
				.thenApply(response -> createSendResult(UUID.fromString(response.messageId()),
						response.sequenceNumber(), endpointName, originalMessage));
	}

	private SendResult<T> createSendResult(UUID messageId, @Nullable String sequenceNumber, String endpointName,
			org.springframework.messaging.Message<T> originalMessage) {
		return new SendResult<>(messageId, endpointName, originalMessage,
				sequenceNumber != null
						? Collections.singletonMap(SqsTemplateParameters.SEQUENCE_NUMBER_PARAMETER_NAME, sequenceNumber)
						: Collections.emptyMap());
	}

	private CompletableFuture<SendMessageRequest> createSendMessageRequest(String endpointName, Message message) {
		return getQueueAttributes(endpointName)
				.thenApply(queueAttributes -> doCreateSendMessageRequest(message, queueAttributes));
	}

	private SendMessageRequest doCreateSendMessageRequest(Message message, QueueAttributes queueAttributes) {
		return SendMessageRequest.builder().queueUrl(queueAttributes.getQueueUrl()).messageBody(message.body())
				.messageDeduplicationId(message.attributes().get(MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID))
				.messageGroupId(message.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID))
				.delaySeconds(getDelaySeconds(message))
				.messageAttributes(excludeKnownFields(message.messageAttributes()))
				.messageSystemAttributes(mapMessageSystemAttributes(message)).build();
	}

	@Override
	protected CompletableFuture<SendResult.Batch<T>> doSendBatchAsync(String endpointName, Collection<Message> messages,
			Collection<org.springframework.messaging.Message<T>> originalMessages) {
		logger.debug("Sending messages {} to endpoint {}", messages, endpointName);
		return createSendMessageBatchRequest(endpointName, messages).thenCompose(this.sqsAsyncClient::sendMessageBatch)
				.thenApply(response -> createSendResultBatch(response, endpointName,
						originalMessages.stream().collect(Collectors.toMap(MessageHeaderUtils::getId, msg -> msg))));
	}

	private SendResult.Batch<T> createSendResultBatch(SendMessageBatchResponse response, String endpointName,
			Map<String, org.springframework.messaging.Message<T>> originalMessagesById) {
		return new SendResult.Batch<>(doCreateSendResultBatch(response, endpointName, originalMessagesById),
				createSendResultFailed(response, endpointName, originalMessagesById));
	}

	private Collection<SendResult.Failed<T>> createSendResultFailed(SendMessageBatchResponse response,
			String endpointName, Map<String, org.springframework.messaging.Message<T>> originalMessagesById) {
		return response.failed().stream()
				.map(entry -> new SendResult.Failed<>(entry.message(), endpointName,
						originalMessagesById.get(entry.id()), Map.of(SqsTemplateParameters.SENDER_FAULT_PARAMETER_NAME,
								entry.senderFault(), SqsTemplateParameters.ERROR_CODE_PARAMETER_NAME, entry.code())))
				.toList();
	}

	private Collection<SendResult<T>> doCreateSendResultBatch(SendMessageBatchResponse response, String endpointName,
			Map<String, org.springframework.messaging.Message<T>> originalMessagesById) {
		return response
				.successful().stream().map(entry -> createSendResult(UUID.fromString(entry.messageId()),
						entry.sequenceNumber(), endpointName, getOriginalMessage(originalMessagesById, entry)))
				.toList();
	}

	private org.springframework.messaging.Message<T> getOriginalMessage(
			Map<String, org.springframework.messaging.Message<T>> originalMessagesById,
			SendMessageBatchResultEntry entry) {
		org.springframework.messaging.Message<T> originalMessage = originalMessagesById.get(entry.id());
		Assert.notNull(originalMessage,
				() -> "Could not correlate send result to original message for id %s. Original messages: %s."
						.formatted(entry.messageId(), originalMessagesById));
		return originalMessage;
	}

	@Nullable
	@Override
	protected MessageConversionContext getReceiveMessageConversionContext(String endpointName, @Nullable Class<? extends T> payloadClass) {
		return this.conversionContextCache.computeIfAbsent(endpointName,
				newEndpoint -> doGetSqsMessageConversionContext(endpointName, payloadClass));
	}

	private SqsMessageConversionContext doGetSqsMessageConversionContext(String newEndpoint, @Nullable Class<? extends T> payloadClass) {
		SqsMessageConversionContext conversionContext = new SqsMessageConversionContext();
		conversionContext.setSqsAsyncClient(this.sqsAsyncClient);
		// At this point we'll already have retrieved and cached the queue attributes
		CompletableFuture<QueueAttributes> queueAttributes = getQueueAttributes(newEndpoint);
		Assert.isTrue(queueAttributes.isDone(), () -> "Queue attributes not done for " + newEndpoint);
		conversionContext.setQueueAttributes(queueAttributes.join());
		if (payloadClass != null) {
			conversionContext.setPayloadClass(payloadClass);
		}
		conversionContext.setAcknowledgementCallback(new TemplateAcknowledgementCallback());
		return conversionContext;
	}

	private CompletableFuture<SendMessageBatchRequest> createSendMessageBatchRequest(String endpointName,
			Collection<Message> messages) {
		return getQueueAttributes(endpointName)
				.thenApply(queueAttributes -> doCreateSendMessageBatchRequest(messages, queueAttributes));
	}

	private SendMessageBatchRequest doCreateSendMessageBatchRequest(Collection<Message> messages,
			QueueAttributes queueAttributes) {
		return SendMessageBatchRequest.builder().queueUrl(queueAttributes.getQueueUrl())
				.entries(messages.stream().map(this::createSendMessageBatchRequestEntry).collect(Collectors.toList()))
				.build();
	}

	private SendMessageBatchRequestEntry createSendMessageBatchRequestEntry(Message message) {
		return SendMessageBatchRequestEntry.builder().id(message.messageId()).messageBody(message.body())
				.messageDeduplicationId(message.attributes().get(MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID))
				.messageGroupId(message.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID))
				.delaySeconds(getDelaySeconds(message))
				.messageAttributes(excludeKnownFields(message.messageAttributes()))
				.messageSystemAttributes(mapMessageSystemAttributes(message)).build();
	}

	private Map<String, MessageAttributeValue> excludeKnownFields(
			Map<String, MessageAttributeValue> messageAttributes) {
		return messageAttributes.entrySet().stream()
				.filter(entry -> !SqsHeaders.SQS_DELAY_HEADER.equals(entry.getKey()))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	@Nullable
	private Integer getDelaySeconds(Message message) {
		return message.messageAttributes().containsKey(SqsHeaders.SQS_DELAY_HEADER)
				? Integer.parseInt(message.messageAttributes().get(SqsHeaders.SQS_DELAY_HEADER).stringValue())
				: null;
	}

	private Map<MessageSystemAttributeNameForSends, MessageSystemAttributeValue> mapMessageSystemAttributes(
			Message message) {
		return message.attributes().entrySet().stream().filter(Predicate.not(entry -> isSkipAttribute(entry.getKey())))
				.collect(Collectors.toMap(entry -> MessageSystemAttributeNameForSends.fromValue(entry.getKey().name()),
						entry -> MessageSystemAttributeValue.builder().dataType(MessageAttributeDataTypes.STRING)
								.stringValue(entry.getValue()).build()));
	}

	private boolean isSkipAttribute(MessageSystemAttributeName name) {
		return MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID.equals(name)
				|| MessageSystemAttributeName.MESSAGE_GROUP_ID.equals(name);
	}

	private CompletableFuture<QueueAttributes> getQueueAttributes(String endpointName) {
		return this.queueAttributesCache.computeIfAbsent(endpointName,
				newName -> QueueAttributesResolver.builder().sqsAsyncClient(this.sqsAsyncClient).queueName(newName)
						.queueNotFoundStrategy(this.queueNotFoundStrategy).queueAttributeNames(this.queueAttributeNames)
						.build().resolveQueueAttributes());
	}

	@Override
	protected Map<String, Object> handleAdditionalHeaders(Map<String, Object> additionalHeaders) {
		HashMap<String, Object> headers = new HashMap<>(additionalHeaders);
		headers.remove(SqsHeaders.SQS_VISIBILITY_TIMEOUT_HEADER);
		headers.remove(SqsHeaders.SQS_RECEIVE_REQUEST_ATTEMPT_ID_HEADER);
		return headers;
	}

	@Override
	protected CompletableFuture<Void> doAcknowledgeMessages(String endpointName,
			Collection<org.springframework.messaging.Message<T>> messages) {
		return deleteMessages(endpointName, messages);
	}

	@Override
	protected CompletableFuture<Collection<Message>> doReceiveAsync(String endpointName, Duration pollTimeout,
			Integer maxNumberOfMessages, Map<String, Object> additionalHeaders) {
		logger.trace(
				"Receiving messages with settings: endpointName - {}, pollTimeout - {}, maxNumberOfMessages - {}, additionalHeaders - {}",
				endpointName, pollTimeout, maxNumberOfMessages, additionalHeaders);
		return createReceiveMessageRequest(endpointName, pollTimeout, maxNumberOfMessages, additionalHeaders)
				.thenCompose(this.sqsAsyncClient::receiveMessage).thenApply(ReceiveMessageResponse::messages);
	}

	private CompletableFuture<Void> deleteMessages(String endpointName,
			Collection<org.springframework.messaging.Message<T>> messages) {
		logger.trace("Acknowledging in queue {} messages {}", endpointName, MessageHeaderUtils.getId(messages));
		return getQueueAttributes(endpointName)
				.thenCompose(attributes -> this.sqsAsyncClient.deleteMessageBatch(DeleteMessageBatchRequest.builder()
						.queueUrl(attributes.getQueueUrl()).entries(createDeleteMessageEntries(messages)).build()))
				.exceptionallyCompose(
						t -> createAcknowledgementException(endpointName, Collections.emptyList(), messages, t))
				.thenCompose(response -> !response.failed().isEmpty()
						? createAcknowledgementException(endpointName,
								getSuccessfulAckMessages(response, messages, endpointName),
								getFailedAckMessages(response, messages, endpointName), null)
						: CompletableFuture.completedFuture(response))
				.whenComplete((response, t) -> logAcknowledgement(endpointName, messages, response, t)).thenRun(() -> {
				});
	}

	private Collection<org.springframework.messaging.Message<T>> getFailedAckMessages(
			DeleteMessageBatchResponse response, Collection<org.springframework.messaging.Message<T>> messages,
			String endpointName) {
		return response.failed().stream().map(BatchResultErrorEntry::id)
				.map(id -> messages.stream().filter(msg -> MessageHeaderUtils.getId(msg).equals(id)).findFirst()
						.orElseThrow(() -> new SqsAcknowledgementException(
								"Could not correlate ids for acknowledgement failure", Collections.emptyList(),
								messages, endpointName)))
				.collect(Collectors.toList());
	}

	private Collection<org.springframework.messaging.Message<T>> getSuccessfulAckMessages(
			DeleteMessageBatchResponse response, Collection<org.springframework.messaging.Message<T>> messages,
			String endpointName) {
		return response.successful().stream().map(DeleteMessageBatchResultEntry::id)
				.map(id -> messages.stream().filter(msg -> MessageHeaderUtils.getId(msg).equals(id)).findFirst()
						.orElseThrow(() -> new SqsAcknowledgementException(
								"Could not correlate ids for acknowledgement failure", Collections.emptyList(),
								messages, endpointName)))
				.collect(Collectors.toList());
	}

	private CompletableFuture<DeleteMessageBatchResponse> createAcknowledgementException(String endpointName,
			Collection<org.springframework.messaging.Message<T>> successfulAckMessages,
			Collection<org.springframework.messaging.Message<T>> failedAckMessages, @Nullable Throwable t) {
		return CompletableFuture.failedFuture(new SqsAcknowledgementException("Error acknowledging messages",
				successfulAckMessages, failedAckMessages, endpointName, t));
	}

	private void logAcknowledgement(String endpointName, Collection<org.springframework.messaging.Message<T>> messages,
			DeleteMessageBatchResponse response, @Nullable Throwable t) {
		if (t != null) {
			logger.error("Error acknowledging in queue {} messages {}", endpointName,
					MessageHeaderUtils.getId(messages));
		}
		else if (!response.failed().isEmpty()) {
			logger.error("Some messages could not be acknowledged in queue {}: {}", endpointName,
					response.failed().stream().map(BatchResultErrorEntry::id).toList());
		}
		else {
			logger.trace("Acknowledged messages in queue {}: {}", endpointName, MessageHeaderUtils.getId(messages));
		}
	}

	private Collection<DeleteMessageBatchRequestEntry> createDeleteMessageEntries(
			Collection<org.springframework.messaging.Message<T>> messages) {
		return messages.stream()
				.map(message -> DeleteMessageBatchRequestEntry.builder().id(MessageHeaderUtils.getId(message))
						.receiptHandle(
								MessageHeaderUtils.getHeaderAsString(message, SqsHeaders.SQS_RECEIPT_HANDLE_HEADER))
						.build())
				.collect(Collectors.toList());
	}

	private CompletableFuture<ReceiveMessageRequest> createReceiveMessageRequest(String endpointName,
			Duration pollTimeout, Integer maxNumberOfMessages, Map<String, Object> additionalHeaders) {
		return getQueueAttributes(endpointName).thenApply(attributes -> doCreateReceiveMessageRequest(pollTimeout,
				maxNumberOfMessages, attributes, additionalHeaders));
	}

	private ReceiveMessageRequest doCreateReceiveMessageRequest(Duration pollTimeout, Integer maxNumberOfMessages,
			QueueAttributes attributes, Map<String, Object> additionalHeaders) {
		ReceiveMessageRequest.Builder builder = ReceiveMessageRequest.builder().queueUrl(attributes.getQueueUrl())
				.maxNumberOfMessages(maxNumberOfMessages).messageAttributeNames(this.messageAttributeNames)
				.attributeNamesWithStrings(this.messageSystemAttributeNames)
				.waitTimeSeconds(pollTimeout.toSecondsPart());
		if (additionalHeaders.containsKey(SqsHeaders.SQS_VISIBILITY_TIMEOUT_HEADER)) {
			builder.visibilityTimeout(
					getValueAs(additionalHeaders, SqsHeaders.SQS_VISIBILITY_TIMEOUT_HEADER, Duration.class)
							.toSecondsPart());
		}
		if (additionalHeaders.containsKey(SqsHeaders.SQS_RECEIVE_REQUEST_ATTEMPT_ID_HEADER)) {
			builder.receiveRequestAttemptId(
					getValueAs(additionalHeaders, SqsHeaders.SQS_RECEIVE_REQUEST_ATTEMPT_ID_HEADER, UUID.class)
							.toString());
		}
		return builder.build();
	}

	private <V> V getValueAs(Map<String, Object> headers, String headerName, Class<V> valueClass) {
		return valueClass.cast(headers.get(headerName));
	}

	private static SqsMessagingMessageConverter createDefaultMessageConverter() {
		return new SqsMessagingMessageConverter();
	}

	/**
	 * Sqs specific options for the {@link SqsTemplate}.
	 * @param <T> the payload type.
	 */
	public interface SqsTemplateOptions<T> extends MessagingTemplateOptions<T, SqsTemplateOptions<T>> {

		/**
		 * The {@link QueueNotFoundStrategy} for this template.
		 * @param queueNotFoundStrategy the strategy.
		 * @return the options instance.
		 */
		SqsTemplateOptions<T> queueNotFoundStrategy(QueueNotFoundStrategy queueNotFoundStrategy);

		/**
		 * The queue attribute names that will be retrieved by this template and added as headers to received messages.
		 * Default is none.
		 * @param queueAttributeNames the names.
		 * @return the options instance.
		 */
		SqsTemplateOptions<T> queueAttributeNames(Collection<QueueAttributeName> queueAttributeNames);

		/**
		 * The message attributes to be retrieved with the message and added as headers to received messages. Default is
		 * ALL.
		 * @param messageAttributeNames the names.
		 * @return the options instance.
		 */
		SqsTemplateOptions<T> messageAttributeNames(Collection<String> messageAttributeNames);

		/**
		 * The message system attributes to be retrieved with the message and added as headers to received messages.
		 * Default is ALL.
		 * @param messageSystemAttributeNames the names.
		 * @return the options instance.
		 */
		SqsTemplateOptions<T> messageSystemAttributeNames(
				Collection<MessageSystemAttributeName> messageSystemAttributeNames);

	}

	private static class SqsTemplateOptionsImpl<T> extends AbstractMessagingTemplateOptions<T, SqsTemplateOptions<T>>
			implements SqsTemplateOptions<T> {

		private Collection<QueueAttributeName> queueAttributeNames = Collections.emptyList();

		private QueueNotFoundStrategy queueNotFoundStrategy = QueueNotFoundStrategy.CREATE;

		private Collection<String> messageAttributeNames = Collections.singletonList("All");

		private Collection<String> messageSystemAttributeNames = Collections.singletonList("All");

		@Override
		public SqsTemplateOptions<T> queueAttributeNames(Collection<QueueAttributeName> queueAttributeNames) {
			Assert.notEmpty(queueAttributeNames, "queueAttributeNames cannot be null or empty");
			this.queueAttributeNames = queueAttributeNames;
			return this;
		}

		@Override
		public SqsTemplateOptions<T> queueNotFoundStrategy(QueueNotFoundStrategy queueNotFoundStrategy) {
			Assert.notNull(queueNotFoundStrategy, "queueNotFoundStrategy cannot be null");
			this.queueNotFoundStrategy = queueNotFoundStrategy;
			return this;
		}

		@Override
		public SqsTemplateOptions<T> messageAttributeNames(Collection<String> messageAttributeNames) {
			this.messageAttributeNames = messageAttributeNames;
			return this;
		}

		@Override
		public SqsTemplateOptions<T> messageSystemAttributeNames(
				Collection<MessageSystemAttributeName> messageSystemAttributeNames) {
			this.messageSystemAttributeNames = messageSystemAttributeNames.stream()
					.map(MessageSystemAttributeName::name).toList();
			return this;
		}

	}

	/**
	 * Builder interface for creating a {@link SqsTemplate} instance.
	 * @param <T> the payload type.
	 */
	public interface Builder<T> {

		/**
		 * Set the {@link SqsAsyncClient} to be used by the {@link SqsTemplate}.
		 * @param sqsAsyncClient the instance.
		 * @return the builder.
		 */
		Builder<T> sqsAsyncClient(SqsAsyncClient sqsAsyncClient);

		/**
		 * Set the {@link MessagingMessageConverter} to be used by the template.
		 * @param messageConverter the converter.
		 * @return the builder.
		 */
		Builder<T> messageConverter(MessagingMessageConverter<Message> messageConverter);

		/**
		 * Configure the default message converter.
		 * @param messageConverterConfigurer a {@link SqsMessagingMessageConverter} consumer.
		 * @return the builder.
		 */
		Builder<T> configureDefaultConverter(Consumer<SqsMessagingMessageConverter> messageConverterConfigurer);

		/**
		 * Configure options for the template.
		 * @param options a {@link SqsTemplateOptions} consumer.
		 * @return the builder.
		 */
		Builder<T> configure(Consumer<SqsTemplateOptions<T>> options);

		/**
		 * Create the template with the provided options, exposing both sync and async methods.
		 * @return the {@link SqsTemplate} instance.
		 */
		SqsTemplate<T> build();

		/**
		 * Create the template with the provided options, exposing only the async methods contained in the
		 * {@link SqsAsyncOperations} interface.
		 * @return the {@link SqsTemplate} instance.
		 */
		SqsAsyncOperations<T> buildAsyncTemplate();

		/**
		 * Create the template with the provided options, exposing only the sync methods contained in the
		 * {@link SqsOperations} interface.
		 * @return the {@link SqsTemplate} instance.
		 */
		SqsOperations<T> buildSyncTemplate();

	}

	private static class BuilderImpl<T> implements Builder<T> {

		private final SqsTemplateOptionsImpl<T> options;

		private SqsAsyncClient sqsAsyncClient;

		private MessagingMessageConverter<Message> messageConverter;

		private BuilderImpl() {
			this.options = new SqsTemplateOptionsImpl<>();
		}

		@Override
		public Builder<T> sqsAsyncClient(SqsAsyncClient sqsAsyncClient) {
			Assert.notNull(sqsAsyncClient, "sqsAsyncClient must not be null");
			this.sqsAsyncClient = sqsAsyncClient;
			return this;
		}

		@Override
		public Builder<T> messageConverter(MessagingMessageConverter<Message> messageConverter) {
			Assert.notNull(messageConverter, "messageConverter must not be null");
			Assert.isNull(this.messageConverter, "messageConverter already configured");
			this.messageConverter = messageConverter;
			return this;
		}

		@Override
		public Builder<T> configureDefaultConverter(Consumer<SqsMessagingMessageConverter> messageConverterConfigurer) {
			Assert.notNull(messageConverterConfigurer, "messageConverterConfigurer must not be null");
			Assert.isNull(this.messageConverter, "messageConverter already configured");
			SqsMessagingMessageConverter defaultMessageConverter = createDefaultMessageConverter();
			messageConverterConfigurer.accept(defaultMessageConverter);
			this.messageConverter = defaultMessageConverter;
			return this;
		}

		@Override
		public Builder<T> configure(Consumer<SqsTemplateOptions<T>> options) {
			Assert.notNull(options, "options must not be null");
			options.accept(this.options);
			return this;
		}

		@Override
		public SqsTemplate<T> build() {
			Assert.notNull(this.sqsAsyncClient, "no sqsAsyncClient set");
			if (this.messageConverter == null) {
				this.messageConverter = createDefaultMessageConverter();
			}
			return new SqsTemplate<>(this);
		}

		@Override
		public SqsOperations<T> buildSyncTemplate() {
			return build();
		}

		@Override
		public SqsAsyncOperations<T> buildAsyncTemplate() {
			return build();
		}

	}

	private static abstract class AbstractSqsSendOptionsImpl<T, O extends SqsSendOptions<T, O>>
			implements SqsSendOptions<T, O> {

		protected final Map<String, Object> headers = new HashMap<>();

		@Nullable
		protected String queue;

		@Nullable
		protected T payload;

		@Nullable
		protected Integer delay;

		@Override
		public O queue(String queue) {
			Assert.hasText(queue, "queue must have text");
			this.queue = queue;
			return self();
		}

		@Override
		public O payload(T payload) {
			Assert.notNull(payload, "payload must not be null");
			this.payload = payload;
			return self();
		}

		@Override
		public O header(String headerName, Object headerValue) {
			Assert.hasText(headerName, "headerName must have text");
			Assert.notNull(headerValue, "headerValue must not be null");
			this.headers.put(headerName, headerValue);
			return self();
		}

		@Override
		public O headers(Map<String, Object> headers) {
			Assert.notNull(headers, "headers must not be null");
			this.headers.putAll(headers);
			return self();
		}

		@Override
		public O delaySeconds(Integer delaySeconds) {
			Assert.notNull(delaySeconds, "delaySeconds must not be null");
			this.delay = delaySeconds;
			return self();
		}

		@SuppressWarnings("unchecked")
		private O self() {
			return (O) this;
		}
	}

	private static class SendStandardOptionsImpl<T> extends AbstractSqsSendOptionsImpl<T, SqsSendOptions.Standard<T>>
			implements SqsSendOptions.Standard<T> {
	}

	private static class SendFifoOptionsImpl<T> extends AbstractSqsSendOptionsImpl<T, SqsSendOptions.Fifo<T>>
			implements SqsSendOptions.Fifo<T> {

		@Nullable
		private UUID messageGroupId;

		@Nullable
		private UUID messageDeduplicationId;

		@Override
		public SqsSendOptions.Fifo<T> messageGroupId(UUID messageGroupId) {
			Assert.notNull(messageGroupId, "messageGroupId must not be null");
			this.messageGroupId = messageGroupId;
			return this;
		}

		@Override
		public SqsSendOptions.Fifo<T> messageDeduplicationId(UUID messageDeduplicationId) {
			Assert.notNull(messageDeduplicationId, "messageDeduplicationId must not be null");
			this.messageDeduplicationId = messageDeduplicationId;
			return this;
		}

	}

	private static abstract class AbstractSqsReceiveOptionsImpl<T, O extends SqsReceiveOptions<T, O>>
			implements SqsReceiveOptions<T, O> {

		protected final Map<String, Object> additionalHeaders = new HashMap<>();

		@Nullable
		protected String queue;

		@Nullable
		protected Duration pollTimeout;

		@Nullable
		protected Duration visibilityTimeout;

		@Nullable
		protected Class<? extends T> payloadClass;

		@Nullable
		protected Integer maxNumberOfMessages;

		@Override
		public O queue(String queue) {
			Assert.notNull(queue, "queue must not be null");
			this.queue = queue;
			return self();
		}

		@Override
		public O pollTimeout(Duration pollTimeout) {
			Assert.notNull(pollTimeout, "pollTimeout must not be null");
			this.pollTimeout = pollTimeout;
			return self();
		}

		@Override
		public O payloadClass(Class<? extends T> payloadClass) {
			Assert.notNull(payloadClass, "payloadClass must not be null");
			this.payloadClass = payloadClass;
			return self();
		}

		@Override
		public O visibilityTimeout(Duration visibilityTimeout) {
			Assert.notNull(visibilityTimeout, "visibilityTimeout must not be null");
			this.visibilityTimeout = visibilityTimeout;
			return self();
		}

		@Override
		public O maxNumberOfMessages(Integer maxNumberOfMessages) {
			Assert.notNull(maxNumberOfMessages, "maxNumberOfMessages must not be null");
			Assert.isTrue(maxNumberOfMessages > 0 && maxNumberOfMessages <= 10,
					"maxNumberOfMessages must be between 0 and 10");
			this.maxNumberOfMessages = maxNumberOfMessages;
			return self();
		}

		@Override
		public O additionalHeader(String name, Object value) {
			Assert.notNull(name, "name must not be null");
			Assert.notNull(value, "value must not be null");
			this.additionalHeaders.put(name, value);
			return self();
		}

		@Override
		public O additionalHeaders(Map<String, Object> additionalHeaders) {
			Assert.notNull(additionalHeaders, "additionalHeaders must not be null");
			this.additionalHeaders.putAll(additionalHeaders);
			return self();
		}

		@SuppressWarnings("unchecked")
		private O self() {
			return (O) this;
		}

	}

	private static class ReceiveStandardOptionsImpl<T> extends
			AbstractSqsReceiveOptionsImpl<T, SqsReceiveOptions.Standard<T>> implements SqsReceiveOptions.Standard<T> {
	}

	private static class ReceiveFifoOptionsImpl<T> extends AbstractSqsReceiveOptionsImpl<T, SqsReceiveOptions.Fifo<T>>
			implements SqsReceiveOptions.Fifo<T> {

		@Nullable
		private UUID receiveRequestAttemptId;

		@Override
		public Fifo<T> receiveRequestAttemptId(UUID receiveRequestAttemptId) {
			Assert.notNull(receiveRequestAttemptId, "receiveRequestAttemptId must not be null");
			this.receiveRequestAttemptId = receiveRequestAttemptId;
			return this;
		}

	}

	private class TemplateAcknowledgementCallback implements AcknowledgementCallback<T> {

		@Override
		public CompletableFuture<Void> onAcknowledge(org.springframework.messaging.Message<T> message) {
			return deleteMessages(MessageHeaderUtils.getHeaderAsString(message, SqsHeaders.SQS_QUEUE_NAME_HEADER),
					Collections.singletonList(message));
		}

		@Override
		public CompletableFuture<Void> onAcknowledge(Collection<org.springframework.messaging.Message<T>> messages) {
			return messages.isEmpty() ? CompletableFuture.completedFuture(null)
					: deleteMessages(MessageHeaderUtils.getHeaderAsString(messages.iterator().next(),
							SqsHeaders.SQS_QUEUE_NAME_HEADER), messages);
		}
	}

}