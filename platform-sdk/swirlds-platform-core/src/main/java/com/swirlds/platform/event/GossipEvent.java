/*
 * Copyright (C) 2016-2024 Hedera Hashgraph, LLC
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

package com.swirlds.platform.event;

import static com.swirlds.common.threading.interrupt.Uninterruptable.abortAndLogIfInterrupted;

import com.swirlds.common.io.SelfSerializable;
import com.swirlds.common.io.streams.SerializableDataInputStream;
import com.swirlds.common.io.streams.SerializableDataOutputStream;
import com.swirlds.common.platform.NodeId;
import com.swirlds.platform.system.events.BaseEventHashedData;
import com.swirlds.platform.system.events.EventDescriptor;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * A class used to hold information about an event transferred through gossip
 */
public class GossipEvent implements SelfSerializable {
    private static final long CLASS_ID = 0xfe16b46795bfb8dcL;
    private static final int MAX_SIG_LENGTH = 384;

    private static final class ClassVersion {
        /**
         * Event serialization changes
         *
         * @since 0.46.0
         */
        public static final int BIRTH_ROUND = 3;
    }

    private BaseEventHashedData hashedData;
    /** creator's signature for this event */
    private byte[] signature;

    private Instant timeReceived;

    /**
     * The sequence number of an event before it is added to the write queue.
     */
    public static final long NO_STREAM_SEQUENCE_NUMBER = -1;

    /**
     * Each event is assigned a sequence number as it is written to the preconsensus event stream. This is used to
     * signal when events have been made durable.
     */
    private long streamSequenceNumber = NO_STREAM_SEQUENCE_NUMBER;

    /**
     * The id of the node which sent us this event
     * <p>
     * The sender ID of an event should not be serialized when an event is serialized, and it should not affect the hash
     * of the event in any way.
     */
    private NodeId senderId;

    /**
     * This latch counts down when prehandle has been called on all application transactions contained in this event.
     */
    private final CountDownLatch prehandleCompleted = new CountDownLatch(1);

    @SuppressWarnings("unused") // needed for RuntimeConstructable
    public GossipEvent() {}

    /**
     * @param hashedData   the hashed data for the event
     * @param signature the signature for the event
     */
    public GossipEvent(final BaseEventHashedData hashedData, final byte[] signature) {
        this.hashedData = hashedData;
        this.signature = signature;
        this.timeReceived = Instant.now();
        this.senderId = null;
    }

    /**
     * Set the sequence number in the preconsensus event stream for this event.
     *
     * @param streamSequenceNumber the sequence number
     */
    public void setStreamSequenceNumber(final long streamSequenceNumber) {
        if (this.streamSequenceNumber != NO_STREAM_SEQUENCE_NUMBER) {
            throw new IllegalStateException("sequence number already set");
        }
        this.streamSequenceNumber = streamSequenceNumber;
    }

    /**
     * Get the sequence number in the preconsensus event stream for this event.
     *
     * @return the sequence number
     */
    public long getStreamSequenceNumber() {
        if (streamSequenceNumber == NO_STREAM_SEQUENCE_NUMBER) {
            throw new IllegalStateException("sequence number not set");
        }
        return streamSequenceNumber;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void serialize(final SerializableDataOutputStream out) throws IOException {
        out.writeSerializable(hashedData, false);
        out.writeByteArray(signature);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void deserialize(final SerializableDataInputStream in, final int version) throws IOException {
        hashedData = in.readSerializable(false, BaseEventHashedData::new);
        this.signature = in.readByteArray(MAX_SIG_LENGTH);
        timeReceived = Instant.now();
    }

    /**
     * Get the hashed data for the event.
     */
    public BaseEventHashedData getHashedData() {
        return hashedData;
    }

    /**
     * @return the signature for the event
     */
    public byte[] getSignature() {
        return signature;
    }

    /**
     * Get the descriptor for the event.
     *
     * @return the descriptor for the event
     */
    public EventDescriptor getDescriptor() {
        return hashedData.getDescriptor();
    }

    /**
     * Get the generation of the event.
     *
     * @return the generation of the event
     */
    public long getGeneration() {
        return hashedData.getGeneration();
    }

    /**
     * Get the time this event was received via gossip
     *
     * @return the time this event was received
     */
    public @NonNull Instant getTimeReceived() {
        return timeReceived;
    }

    /**
     * Set the time this event was received
     *
     * @param timeReceived the time this event was received
     */
    public void setTimeReceived(@NonNull final Instant timeReceived) {
        this.timeReceived = timeReceived;
    }

    /**
     * Get the id of the node which sent us this event
     *
     * @return the id of the node which sent us this event
     */
    @Nullable
    public NodeId getSenderId() {
        return senderId;
    }

    /**
     * Set the id of the node which sent us this event
     *
     * @param senderId the id of the node which sent us this event
     */
    public void setSenderId(@NonNull final NodeId senderId) {
        this.senderId = senderId;
    }

    /**
     * Signal that all transactions have been prehandled for this event.
     */
    public void signalPrehandleCompletion() {
        prehandleCompleted.countDown();
    }

    /**
     * Wait until all transactions have been prehandled for this event.
     */
    public void awaitPrehandleCompletion() {
        abortAndLogIfInterrupted(prehandleCompleted::await, "interrupted while waiting for prehandle completion");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.BIRTH_ROUND;
    }

    @Override
    public int getMinimumSupportedVersion() {
        return ClassVersion.BIRTH_ROUND;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder stringBuilder = new StringBuilder();

        stringBuilder.append(hashedData.getDescriptor());
        stringBuilder.append("\n");
        stringBuilder.append("    sp: ");

        final EventDescriptor selfParent = hashedData.getSelfParent();
        if (selfParent != null) {
            stringBuilder.append(selfParent);
        } else {
            stringBuilder.append("null");
        }
        stringBuilder.append("\n");

        int otherParentCount = 0;
        for (final EventDescriptor otherParent : hashedData.getOtherParents()) {
            stringBuilder.append("    op");
            stringBuilder.append(otherParentCount);
            stringBuilder.append(": ");
            stringBuilder.append(otherParent);

            otherParentCount++;
            if (otherParentCount != hashedData.getOtherParents().size()) {
                stringBuilder.append("\n");
            }
        }

        return stringBuilder.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final GossipEvent that = (GossipEvent) o;
        return Objects.equals(getHashedData(), that.getHashedData());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return hashedData.getHash().hashCode();
    }

    /**
     * Get the value used to determine if this event is ancient or not. Will be the event's generation prior to
     * migration, and the event's birth round after migration.
     *
     * @return the value used to determine if this event is ancient or not
     */
    public long getAncientIndicator(@NonNull final AncientMode ancientMode) {
        return switch (ancientMode) {
            case GENERATION_THRESHOLD -> hashedData.getGeneration();
            case BIRTH_ROUND_THRESHOLD -> hashedData.getBirthRound();
        };
    }
}
