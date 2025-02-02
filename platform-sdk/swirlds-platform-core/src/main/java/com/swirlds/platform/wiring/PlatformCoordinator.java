/*
 * Copyright (C) 2023-2024 Hedera Hashgraph, LLC
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

package com.swirlds.platform.wiring;

import com.swirlds.common.wiring.component.ComponentWiring;
import com.swirlds.common.wiring.counters.ObjectCounter;
import com.swirlds.platform.components.consensus.ConsensusEngine;
import com.swirlds.platform.event.GossipEvent;
import com.swirlds.platform.event.creation.EventCreationManager;
import com.swirlds.platform.event.deduplication.EventDeduplicator;
import com.swirlds.platform.event.orphan.OrphanBuffer;
import com.swirlds.platform.event.preconsensus.durability.RoundDurabilityBuffer;
import com.swirlds.platform.event.validation.EventSignatureValidator;
import com.swirlds.platform.event.validation.InternalEventValidator;
import com.swirlds.platform.eventhandling.TransactionPrehandler;
import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.system.events.BaseEventHashedData;
import com.swirlds.platform.wiring.components.ConsensusRoundHandlerWiring;
import com.swirlds.platform.wiring.components.GossipWiring;
import com.swirlds.platform.wiring.components.StateHasherWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;

/**
 * Responsible for coordinating the clearing of the platform wiring objects.
 */
public class PlatformCoordinator {
    /**
     * The object counter which spans the {@link com.swirlds.platform.event.hashing.EventHasher EventHasher} and the
     * postHashCollector
     * <p>
     * Used to flush the pair of components together.
     */
    private final ObjectCounter hashingObjectCounter;

    private final ComponentWiring<InternalEventValidator, GossipEvent> internalEventValidatorWiring;
    private final ComponentWiring<EventDeduplicator, GossipEvent> eventDeduplicatorWiring;
    private final ComponentWiring<EventSignatureValidator, GossipEvent> eventSignatureValidatorWiring;
    private final ComponentWiring<OrphanBuffer, List<GossipEvent>> orphanBufferWiring;
    private final GossipWiring gossipWiring;
    private final ComponentWiring<ConsensusEngine, List<ConsensusRound>> consensusEngineWiring;
    private final ComponentWiring<EventCreationManager, BaseEventHashedData> eventCreationManagerWiring;
    private final ComponentWiring<TransactionPrehandler, Void> applicationTransactionPrehandlerWiring;
    private final ComponentWiring<StateSignatureCollector, List<ReservedSignedState>> stateSignatureCollectorWiring;
    private final ConsensusRoundHandlerWiring consensusRoundHandlerWiring;
    private final ComponentWiring<RoundDurabilityBuffer, List<ConsensusRound>> roundDurabilityBufferWiring;
    private final StateHasherWiring stateHasherWiring;

    /**
     * Constructor
     *
     * @param hashingObjectCounter                   the hashing object counter
     * @param internalEventValidatorWiring           the internal event validator wiring
     * @param eventDeduplicatorWiring                the event deduplicator wiring
     * @param eventSignatureValidatorWiring          the event signature validator wiring
     * @param orphanBufferWiring                     the orphan buffer wiring
     * @param gossipWiring                           gossip wiring
     * @param consensusEngineWiring                  the consensus engine wiring
     * @param eventCreationManagerWiring             the event creation manager wiring
     * @param applicationTransactionPrehandlerWiring the application transaction prehandler wiring
     * @param stateSignatureCollectorWiring          the system transaction prehandler wiring
     * @param consensusRoundHandlerWiring            the consensus round handler wiring
     * @param roundDurabilityBufferWiring            the round durability buffer wiring
     * @param stateHasherWiring                      the state hasher wiring
     */
    public PlatformCoordinator(
            @NonNull final ObjectCounter hashingObjectCounter,
            @NonNull final ComponentWiring<InternalEventValidator, GossipEvent> internalEventValidatorWiring,
            @NonNull final ComponentWiring<EventDeduplicator, GossipEvent> eventDeduplicatorWiring,
            @NonNull final ComponentWiring<EventSignatureValidator, GossipEvent> eventSignatureValidatorWiring,
            @NonNull final ComponentWiring<OrphanBuffer, List<GossipEvent>> orphanBufferWiring,
            @NonNull final GossipWiring gossipWiring,
            @NonNull final ComponentWiring<ConsensusEngine, List<ConsensusRound>> consensusEngineWiring,
            @NonNull final ComponentWiring<EventCreationManager, BaseEventHashedData> eventCreationManagerWiring,
            @NonNull final ComponentWiring<TransactionPrehandler, Void> applicationTransactionPrehandlerWiring,
            @NonNull
                    final ComponentWiring<StateSignatureCollector, List<ReservedSignedState>>
                            stateSignatureCollectorWiring,
            @NonNull final ConsensusRoundHandlerWiring consensusRoundHandlerWiring,
            @NonNull final ComponentWiring<RoundDurabilityBuffer, List<ConsensusRound>> roundDurabilityBufferWiring,
            @NonNull final StateHasherWiring stateHasherWiring) {

        this.hashingObjectCounter = Objects.requireNonNull(hashingObjectCounter);
        this.internalEventValidatorWiring = Objects.requireNonNull(internalEventValidatorWiring);
        this.eventDeduplicatorWiring = Objects.requireNonNull(eventDeduplicatorWiring);
        this.eventSignatureValidatorWiring = Objects.requireNonNull(eventSignatureValidatorWiring);
        this.orphanBufferWiring = Objects.requireNonNull(orphanBufferWiring);
        this.gossipWiring = Objects.requireNonNull(gossipWiring);
        this.consensusEngineWiring = Objects.requireNonNull(consensusEngineWiring);
        this.eventCreationManagerWiring = Objects.requireNonNull(eventCreationManagerWiring);
        this.applicationTransactionPrehandlerWiring = Objects.requireNonNull(applicationTransactionPrehandlerWiring);
        this.stateSignatureCollectorWiring = Objects.requireNonNull(stateSignatureCollectorWiring);
        this.consensusRoundHandlerWiring = Objects.requireNonNull(consensusRoundHandlerWiring);
        this.roundDurabilityBufferWiring = Objects.requireNonNull(roundDurabilityBufferWiring);
        this.stateHasherWiring = Objects.requireNonNull(stateHasherWiring);
    }

    /**
     * Flushes the intake pipeline. After this method is called, all components in the intake pipeline (i.e. components
     * prior to the consensus engine) will have been flushed. Additionally, things will be flushed an order that
     * guarantees that there will be no remaining work in the intake pipeline (as long as there are no additional events
     * added to the intake pipeline, and as long as there are no events released by the orphan buffer).
     */
    public void flushIntakePipeline() {
        // Important: the order of the lines within this function matters. Do not alter the order of these
        // lines without understanding the implications of doing so. Consult the wiring diagram when deciding
        // whether to change the order of these lines.

        // it isn't possible to flush the event hasher and the post hash collector independently, since the framework
        // currently doesn't support flushing if multiple components share the same object counter. As a workaround,
        // we just wait for the shared object counter to be empty, which is equivalent to flushing both components.
        hashingObjectCounter.waitUntilEmpty();

        internalEventValidatorWiring.flush();
        eventDeduplicatorWiring.flush();
        eventSignatureValidatorWiring.flush();
        orphanBufferWiring.flush();
        gossipWiring.flush();
        consensusEngineWiring.flush();
        applicationTransactionPrehandlerWiring.flush();
        eventCreationManagerWiring.flush();
    }

    /**
     * Safely clears the system in preparation for reconnect. After this method is called, there should be no work
     * sitting in any of the wiring queues, and all internal data structures within wiring components that need to be
     * cleared to prepare for a reconnect should be cleared.
     */
    public void clear() {
        // Important: the order of the lines within this function are important. Do not alter the order of these
        // lines without understanding the implications of doing so. Consult the wiring diagram when deciding
        // whether to change the order of these lines.

        // Phase 1: squelch
        // Break cycles in the system. Flush squelched components just in case there is a task being executed when
        // squelch is activated.
        consensusEngineWiring.startSquelching();
        consensusEngineWiring.flush();
        eventCreationManagerWiring.startSquelching();
        eventCreationManagerWiring.flush();

        // Also squelch the consensus round handler. It isn't strictly necessary to do this to prevent dataflow through
        // the system, but it prevents the consensus round handler from wasting time handling rounds that don't need to
        // be handled.
        consensusRoundHandlerWiring.startSquelchingRunnable().run();
        consensusRoundHandlerWiring.flushRunnable().run();

        // Phase 2: flush
        // All cycles have been broken via squelching, so now it's time to flush everything out of the system.
        flushIntakePipeline();
        stateHasherWiring.flushRunnable().run();
        stateSignatureCollectorWiring.flush();
        roundDurabilityBufferWiring.flush();
        consensusRoundHandlerWiring.flushRunnable().run();

        // Phase 3: stop squelching
        // Once everything has been flushed out of the system, it's safe to stop squelching.
        consensusEngineWiring.stopSquelching();
        eventCreationManagerWiring.stopSquelching();
        consensusRoundHandlerWiring.stopSquelchingRunnable().run();

        // Phase 4: clear
        // Data is no longer moving through the system. Clear all the internal data structures in the wiring objects.
        eventDeduplicatorWiring.getInputWire(EventDeduplicator::clear).inject(NoInput.getInstance());
        orphanBufferWiring.getInputWire(OrphanBuffer::clear).inject(NoInput.getInstance());
        gossipWiring.getClearInput().inject(NoInput.getInstance());
        stateSignatureCollectorWiring
                .getInputWire(StateSignatureCollector::clear)
                .inject(NoInput.getInstance());
        eventCreationManagerWiring.getInputWire(EventCreationManager::clear).inject(NoInput.getInstance());
        roundDurabilityBufferWiring.getInputWire(RoundDurabilityBuffer::clear).inject(NoInput.getInstance());
    }
}
