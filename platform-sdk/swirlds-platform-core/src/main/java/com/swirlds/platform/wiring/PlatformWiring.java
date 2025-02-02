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

import static com.swirlds.common.wiring.model.diagram.HyperlinkBuilder.platformCoreHyperlink;
import static com.swirlds.common.wiring.schedulers.builders.TaskSchedulerConfiguration.NO_OP_CONFIGURATION;
import static com.swirlds.common.wiring.wires.SolderType.INJECT;
import static com.swirlds.common.wiring.wires.SolderType.OFFER;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;

import com.hedera.hapi.platform.event.StateSignaturePayload;
import com.swirlds.base.state.Startable;
import com.swirlds.base.state.Stoppable;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.stream.RunningEventHashOverride;
import com.swirlds.common.utility.Clearable;
import com.swirlds.common.wiring.component.ComponentWiring;
import com.swirlds.common.wiring.counters.BackpressureObjectCounter;
import com.swirlds.common.wiring.counters.ObjectCounter;
import com.swirlds.common.wiring.model.WiringModel;
import com.swirlds.common.wiring.model.WiringModelBuilder;
import com.swirlds.common.wiring.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.common.wiring.schedulers.builders.TaskSchedulerType;
import com.swirlds.common.wiring.transformers.WireTransformer;
import com.swirlds.common.wiring.wires.input.InputWire;
import com.swirlds.common.wiring.wires.output.OutputWire;
import com.swirlds.common.wiring.wires.output.StandardOutputWire;
import com.swirlds.platform.StateSigner;
import com.swirlds.platform.builder.PlatformComponentBuilder;
import com.swirlds.platform.components.AppNotifier;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.components.appcomm.CompleteStateNotificationWithCleanup;
import com.swirlds.platform.components.appcomm.LatestCompleteStateNotifier;
import com.swirlds.platform.components.consensus.ConsensusEngine;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import com.swirlds.platform.components.transaction.system.SystemTransactionExtractionUtils;
import com.swirlds.platform.consensus.ConsensusSnapshot;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.event.AncientMode;
import com.swirlds.platform.event.GossipEvent;
import com.swirlds.platform.event.creation.EventCreationConfig;
import com.swirlds.platform.event.creation.EventCreationManager;
import com.swirlds.platform.event.deduplication.EventDeduplicator;
import com.swirlds.platform.event.hashing.EventHasher;
import com.swirlds.platform.event.orphan.OrphanBuffer;
import com.swirlds.platform.event.preconsensus.PcesConfig;
import com.swirlds.platform.event.preconsensus.PcesReplayer;
import com.swirlds.platform.event.preconsensus.PcesSequencer;
import com.swirlds.platform.event.preconsensus.PcesWriter;
import com.swirlds.platform.event.preconsensus.durability.RoundDurabilityBuffer;
import com.swirlds.platform.event.runninghash.RunningEventHasher;
import com.swirlds.platform.event.signing.SelfEventSigner;
import com.swirlds.platform.event.stream.ConsensusEventStream;
import com.swirlds.platform.event.validation.AddressBookUpdate;
import com.swirlds.platform.event.validation.EventSignatureValidator;
import com.swirlds.platform.event.validation.InternalEventValidator;
import com.swirlds.platform.eventhandling.ConsensusRoundHandler;
import com.swirlds.platform.eventhandling.EventConfig;
import com.swirlds.platform.eventhandling.TransactionPool;
import com.swirlds.platform.eventhandling.TransactionPrehandler;
import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.publisher.PlatformPublisher;
import com.swirlds.platform.state.iss.IssDetector;
import com.swirlds.platform.state.iss.IssHandler;
import com.swirlds.platform.state.nexus.LatestCompleteStateNexus;
import com.swirlds.platform.state.nexus.SignedStateNexus;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedStateFileManager;
import com.swirlds.platform.state.signed.SignedStateHasher;
import com.swirlds.platform.state.signed.SignedStateSentinel;
import com.swirlds.platform.state.signed.StateDumpRequest;
import com.swirlds.platform.state.signed.StateGarbageCollector;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import com.swirlds.platform.system.events.BaseEventHashedData;
import com.swirlds.platform.system.events.BirthRoundMigrationShim;
import com.swirlds.platform.system.state.notifications.IssNotification;
import com.swirlds.platform.system.status.PlatformStatus;
import com.swirlds.platform.system.status.PlatformStatusConfig;
import com.swirlds.platform.system.status.PlatformStatusNexus;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.system.status.StatusStateMachine;
import com.swirlds.platform.util.HashLogger;
import com.swirlds.platform.wiring.components.ConsensusRoundHandlerWiring;
import com.swirlds.platform.wiring.components.GossipWiring;
import com.swirlds.platform.wiring.components.HashLoggerWiring;
import com.swirlds.platform.wiring.components.IssHandlerWiring;
import com.swirlds.platform.wiring.components.PassThroughWiring;
import com.swirlds.platform.wiring.components.PcesReplayerWiring;
import com.swirlds.platform.wiring.components.RunningEventHashOverrideWiring;
import com.swirlds.platform.wiring.components.StateAndRound;
import com.swirlds.platform.wiring.components.StateHasherWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Encapsulates wiring for {@link com.swirlds.platform.SwirldsPlatform}.
 */
public class PlatformWiring implements Startable, Stoppable, Clearable {

    private static final Logger logger = LogManager.getLogger(PlatformWiring.class);

    private final WiringModel model;

    private final PlatformContext platformContext;
    private final PlatformSchedulersConfig config;

    private final ComponentWiring<EventHasher, GossipEvent> eventHasherWiring;
    private final PassThroughWiring<GossipEvent> postHashCollectorWiring;
    private final ComponentWiring<InternalEventValidator, GossipEvent> internalEventValidatorWiring;
    private final ComponentWiring<EventDeduplicator, GossipEvent> eventDeduplicatorWiring;
    private final ComponentWiring<EventSignatureValidator, GossipEvent> eventSignatureValidatorWiring;
    private final ComponentWiring<OrphanBuffer, List<GossipEvent>> orphanBufferWiring;
    private final ComponentWiring<ConsensusEngine, List<ConsensusRound>> consensusEngineWiring;
    private final ComponentWiring<EventCreationManager, BaseEventHashedData> eventCreationManagerWiring;
    private final ComponentWiring<SelfEventSigner, GossipEvent> selfEventSignerWiring;
    private final SignedStateFileManagerWiring signedStateFileManagerWiring;
    private final StateSignerWiring stateSignerWiring;
    private final PcesReplayerWiring pcesReplayerWiring;
    private final ComponentWiring<PcesWriter, Long> pcesWriterWiring;
    private final ComponentWiring<RoundDurabilityBuffer, List<ConsensusRound>> roundDurabilityBufferWiring;
    private final ComponentWiring<PcesSequencer, GossipEvent> pcesSequencerWiring;
    private final ComponentWiring<TransactionPrehandler, Void> applicationTransactionPrehandlerWiring;
    private final ComponentWiring<StateSignatureCollector, List<ReservedSignedState>> stateSignatureCollectorWiring;
    private final GossipWiring gossipWiring;
    private final ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring;
    private final ConsensusRoundHandlerWiring consensusRoundHandlerWiring;
    private final ComponentWiring<ConsensusEventStream, Void> consensusEventStreamWiring;
    private final RunningEventHashOverrideWiring runningEventHashOverrideWiring;
    private final ComponentWiring<IssDetector, List<IssNotification>> issDetectorWiring;
    private final IssHandlerWiring issHandlerWiring;
    private final HashLoggerWiring hashLoggerWiring;
    private final ComponentWiring<LatestCompleteStateNotifier, CompleteStateNotificationWithCleanup>
            latestCompleteStateNotifierWiring;
    private final ComponentWiring<SignedStateNexus, Void> latestImmutableStateNexusWiring;
    private final ComponentWiring<LatestCompleteStateNexus, Void> latestCompleteStateNexusWiring;
    private final ComponentWiring<SavedStateController, StateAndRound> savedStateControllerWiring;
    private final StateHasherWiring signedStateHasherWiring;
    private final PlatformCoordinator platformCoordinator;
    private final ComponentWiring<BirthRoundMigrationShim, GossipEvent> birthRoundMigrationShimWiring;
    private final ComponentWiring<AppNotifier, Void> notifierWiring;
    private final ComponentWiring<StateGarbageCollector, Void> stateGarbageCollectorWiring;
    private final ComponentWiring<SignedStateSentinel, Void> signedStateSentinelWiring;

    private final ComponentWiring<PlatformPublisher, Void> platformPublisherWiring;
    private final boolean publishPreconsensusEvents;
    private final boolean publishSnapshotOverrides;
    private final ComponentWiring<RunningEventHasher, Void> runningEventHasherWiring;
    private final ComponentWiring<StatusStateMachine, PlatformStatus> statusStateMachineWiring;
    private final ComponentWiring<PlatformStatusNexus, Void> statusNexusWiring;

    /**
     * Constructor.
     *
     * @param platformContext           the platform context
     * @param publishPreconsensusEvents whether to publish preconsensus events (i.e. if a handler is registered). Extra
     *                                  things need to be wired together if we are publishing preconsensus events.
     * @param publishSnapshotOverrides  whether to publish snapshot overrides. Extra things need to be wired together if
     *                                  we are publishing snapshot overrides.
     */
    public PlatformWiring(
            @NonNull final PlatformContext platformContext,
            final boolean publishPreconsensusEvents,
            final boolean publishSnapshotOverrides) {

        this.platformContext = Objects.requireNonNull(platformContext);

        config = platformContext.getConfiguration().getConfigData(PlatformSchedulersConfig.class);

        final int coreCount = Runtime.getRuntime().availableProcessors();
        final int parallelism =
                (int) Math.max(1, config.defaultPoolMultiplier() * coreCount + config.defaultPoolConstant());
        final ForkJoinPool defaultPool = platformContext.getExecutorFactory().createForkJoinPool(parallelism);
        logger.info(STARTUP.getMarker(), "Default platform pool parallelism: {}", parallelism);

        model = WiringModelBuilder.create(platformContext)
                .withDefaultPool(defaultPool)
                .build();

        // This counter spans both the event hasher and the post hash collector. This is a workaround for the current
        // inability of concurrent schedulers to handle backpressure from an immediately subsequent scheduler.
        // This counter is the on-ramp for the event hasher, and the off-ramp for the post hash collector.
        final ObjectCounter hashingObjectCounter = new BackpressureObjectCounter(
                "hashingObjectCounter",
                platformContext
                        .getConfiguration()
                        .getConfigData(PlatformSchedulersConfig.class)
                        .eventHasherUnhandledCapacity(),
                Duration.ofNanos(100));

        final PlatformSchedulers schedulers = PlatformSchedulers.create(platformContext, model, hashingObjectCounter);

        final AncientMode ancientMode = platformContext
                .getConfiguration()
                .getConfigData(EventConfig.class)
                .getAncientMode();
        if (ancientMode == AncientMode.BIRTH_ROUND_THRESHOLD) {
            birthRoundMigrationShimWiring = new ComponentWiring<>(
                    model,
                    BirthRoundMigrationShim.class,
                    model.schedulerBuilder("birthRoundMigrationShim")
                            .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                            .build()
                            .cast());
        } else {
            birthRoundMigrationShimWiring = null;
        }

        eventHasherWiring = new ComponentWiring<>(model, EventHasher.class, schedulers.eventHasherScheduler());
        postHashCollectorWiring =
                new PassThroughWiring<>(model, "GossipEvent", schedulers.postHashCollectorScheduler());
        internalEventValidatorWiring =
                new ComponentWiring<>(model, InternalEventValidator.class, config.internalEventValidator());
        eventDeduplicatorWiring = new ComponentWiring<>(model, EventDeduplicator.class, config.eventDeduplicator());
        eventSignatureValidatorWiring =
                new ComponentWiring<>(model, EventSignatureValidator.class, config.eventSignatureValidator());
        orphanBufferWiring = new ComponentWiring<>(model, OrphanBuffer.class, config.orphanBuffer());
        consensusEngineWiring = new ComponentWiring<>(model, ConsensusEngine.class, config.consensusEngine());

        eventCreationManagerWiring =
                new ComponentWiring<>(model, EventCreationManager.class, config.eventCreationManager());
        selfEventSignerWiring = new ComponentWiring<>(model, SelfEventSigner.class, config.selfEventSigner());
        pcesSequencerWiring = new ComponentWiring<>(model, PcesSequencer.class, config.pcesSequencer());

        applicationTransactionPrehandlerWiring =
                new ComponentWiring<>(model, TransactionPrehandler.class, config.applicationTransactionPrehandler());
        stateSignatureCollectorWiring =
                new ComponentWiring<>(model, StateSignatureCollector.class, config.stateSignatureCollector());
        signedStateFileManagerWiring =
                SignedStateFileManagerWiring.create(model, schedulers.signedStateFileManagerScheduler());
        stateSignerWiring = StateSignerWiring.create(schedulers.stateSignerScheduler());
        consensusRoundHandlerWiring = ConsensusRoundHandlerWiring.create(schedulers.consensusRoundHandlerScheduler());
        consensusEventStreamWiring =
                new ComponentWiring<>(model, ConsensusEventStream.class, config.consensusEventStream());
        runningEventHashOverrideWiring = RunningEventHashOverrideWiring.create(schedulers.runningHashUpdateScheduler());

        signedStateHasherWiring = StateHasherWiring.create(schedulers.stateHasherScheduler());

        gossipWiring = new GossipWiring(platformContext, model);

        pcesReplayerWiring = PcesReplayerWiring.create(schedulers.pcesReplayerScheduler());

        pcesWriterWiring = new ComponentWiring<>(model, PcesWriter.class, config.pcesWriter());
        roundDurabilityBufferWiring =
                new ComponentWiring<>(model, RoundDurabilityBuffer.class, config.roundDurabilityBuffer());

        eventWindowManagerWiring = new ComponentWiring<>(
                model,
                EventWindowManager.class,
                model.schedulerBuilder("eventWindowManager")
                        .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                        .withHyperlink(platformCoreHyperlink(EventWindowManager.class))
                        .build()
                        .cast());

        issDetectorWiring = new ComponentWiring<>(model, IssDetector.class, config.issDetector());
        issHandlerWiring = IssHandlerWiring.create(schedulers.issHandlerScheduler());
        hashLoggerWiring = HashLoggerWiring.create(schedulers.hashLoggerScheduler());

        latestCompleteStateNotifierWiring = new ComponentWiring<>(
                model,
                LatestCompleteStateNotifier.class,
                schedulers.latestCompleteStateNotifierScheduler().cast());

        latestImmutableStateNexusWiring = new ComponentWiring<>(
                model,
                SignedStateNexus.class,
                model.schedulerBuilder("latestImmutableStateNexus")
                        .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                        .build()
                        .cast());
        latestCompleteStateNexusWiring = new ComponentWiring<>(
                model,
                LatestCompleteStateNexus.class,
                model.schedulerBuilder("latestCompleteStateNexus")
                        .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                        .build()
                        .cast());
        savedStateControllerWiring = new ComponentWiring<>(
                model,
                SavedStateController.class,
                model.schedulerBuilder("savedStateController")
                        .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                        .build()
                        .cast());

        notifierWiring = new ComponentWiring<>(
                model,
                AppNotifier.class,
                model.schedulerBuilder("notifier")
                        .withType(TaskSchedulerType.DIRECT_THREADSAFE)
                        .build()
                        .cast());

        this.publishPreconsensusEvents = publishPreconsensusEvents;
        this.publishSnapshotOverrides = publishSnapshotOverrides;

        final TaskSchedulerConfiguration publisherConfiguration;
        if (publishPreconsensusEvents || publishSnapshotOverrides) {
            publisherConfiguration = config.platformPublisher();
        } else {
            publisherConfiguration = NO_OP_CONFIGURATION;
        }
        platformPublisherWiring = new ComponentWiring<>(model, PlatformPublisher.class, publisherConfiguration);

        stateGarbageCollectorWiring =
                new ComponentWiring<>(model, StateGarbageCollector.class, config.stateGarbageCollector());
        signedStateSentinelWiring =
                new ComponentWiring<>(model, SignedStateSentinel.class, config.signedStateSentinel());
        runningEventHasherWiring = new ComponentWiring<>(model, RunningEventHasher.class, config.runningEventHasher());
        statusStateMachineWiring = new ComponentWiring<>(model, StatusStateMachine.class, config.statusStateMachine());
        statusNexusWiring = new ComponentWiring<>(model, PlatformStatusNexus.class, config.platformStatusNexus());

        platformCoordinator = new PlatformCoordinator(
                hashingObjectCounter,
                internalEventValidatorWiring,
                eventDeduplicatorWiring,
                eventSignatureValidatorWiring,
                orphanBufferWiring,
                gossipWiring,
                consensusEngineWiring,
                eventCreationManagerWiring,
                applicationTransactionPrehandlerWiring,
                stateSignatureCollectorWiring,
                consensusRoundHandlerWiring,
                roundDurabilityBufferWiring,
                signedStateHasherWiring);

        wire();
    }

    /**
     * Get the wiring model.
     *
     * @return the wiring model
     */
    @NonNull
    public WiringModel getModel() {
        return model;
    }

    /**
     * Solder the EventWindow output to all components that need it.
     */
    private void solderEventWindow() {
        final OutputWire<EventWindow> eventWindowOutputWire = eventWindowManagerWiring.getOutputWire();

        eventWindowOutputWire.solderTo(eventDeduplicatorWiring.getInputWire(EventDeduplicator::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(
                eventSignatureValidatorWiring.getInputWire(EventSignatureValidator::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(gossipWiring.getEventWindowInput(), INJECT);
        eventWindowOutputWire.solderTo(
                pcesWriterWiring.getInputWire(PcesWriter::updateNonAncientEventBoundary), INJECT);
        eventWindowOutputWire.solderTo(
                eventCreationManagerWiring.getInputWire(EventCreationManager::setEventWindow), INJECT);
        eventWindowOutputWire.solderTo(
                latestCompleteStateNexusWiring.getInputWire(LatestCompleteStateNexus::updateEventWindow));
    }

    /**
     * Solder notifications into the notifier.
     */
    private void solderNotifier() {
        latestCompleteStateNotifierWiring
                .getOutputWire()
                .solderTo(notifierWiring.getInputWire(AppNotifier::sendLatestCompleteStateNotification));
        signedStateFileManagerWiring
                .stateWrittenToDiskNotificationOutput()
                .solderTo(notifierWiring.getInputWire(AppNotifier::sendStateWrittenToDiskNotification));

        final OutputWire<IssNotification> issNotificationOutputWire = issDetectorWiring.getSplitOutput();
        issNotificationOutputWire.solderTo(notifierWiring.getInputWire(AppNotifier::sendIssNotification));
        statusStateMachineWiring
                .getOutputWire()
                .solderTo(notifierWiring.getInputWire(AppNotifier::sendPlatformStatusChangeNotification));
    }

    /**
     * Wire the components together.
     */
    private void wire() {
        final InputWire<GossipEvent> pipelineInputWire;
        if (birthRoundMigrationShimWiring != null) {
            birthRoundMigrationShimWiring
                    .getOutputWire()
                    .solderTo(eventHasherWiring.getInputWire(EventHasher::hashEvent));
            pipelineInputWire = birthRoundMigrationShimWiring.getInputWire(BirthRoundMigrationShim::migrateEvent);
        } else {
            pipelineInputWire = eventHasherWiring.getInputWire(EventHasher::hashEvent);
        }

        gossipWiring.getEventOutput().solderTo(pipelineInputWire);
        eventHasherWiring.getOutputWire().solderTo(postHashCollectorWiring.getInputWire());
        postHashCollectorWiring
                .getOutputWire()
                .solderTo(internalEventValidatorWiring.getInputWire(InternalEventValidator::validateEvent));
        internalEventValidatorWiring
                .getOutputWire()
                .solderTo(eventDeduplicatorWiring.getInputWire(EventDeduplicator::handleEvent));
        eventDeduplicatorWiring
                .getOutputWire()
                .solderTo(eventSignatureValidatorWiring.getInputWire(EventSignatureValidator::validateSignature));
        eventSignatureValidatorWiring
                .getOutputWire()
                .solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::handleEvent));
        final OutputWire<GossipEvent> splitOrphanBufferOutput = orphanBufferWiring.getSplitOutput();
        splitOrphanBufferOutput.solderTo(pcesSequencerWiring.getInputWire(PcesSequencer::assignStreamSequenceNumber));
        pcesSequencerWiring.getOutputWire().solderTo(pcesWriterWiring.getInputWire(PcesWriter::writeEvent));

        pcesSequencerWiring.getOutputWire().solderTo(consensusEngineWiring.getInputWire(ConsensusEngine::addEvent));

        splitOrphanBufferOutput.solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::registerEvent));

        // This must use injection to avoid cyclical back pressure. There is a risk of OOM if gossip can't ingest
        // events fast enough, but we have no other choice until we implement the platform health monitor.
        splitOrphanBufferOutput.solderTo(gossipWiring.getEventInput(), INJECT);

        final double eventCreationHeartbeatFrequency = platformContext
                .getConfiguration()
                .getConfigData(EventCreationConfig.class)
                .creationAttemptRate();
        model.buildHeartbeatWire(eventCreationHeartbeatFrequency)
                .solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::maybeCreateEvent));
        model.buildHeartbeatWire(platformContext
                        .getConfiguration()
                        .getConfigData(PlatformStatusConfig.class)
                        .statusStateMachineHeartbeatPeriod())
                .solderTo(statusStateMachineWiring.getInputWire(StatusStateMachine::heartbeat), OFFER);

        eventCreationManagerWiring
                .getOutputWire()
                .solderTo(selfEventSignerWiring.getInputWire(SelfEventSigner::signEvent));
        selfEventSignerWiring
                .getOutputWire()
                .solderTo(internalEventValidatorWiring.getInputWire(InternalEventValidator::validateEvent), INJECT);
        splitOrphanBufferOutput.solderTo(applicationTransactionPrehandlerWiring.getInputWire(
                TransactionPrehandler::prehandleApplicationTransactions));

        // From the orphan buffer, extract signatures from preconsensus events for input to the StateSignatureCollector.
        final WireTransformer<GossipEvent, List<ScopedSystemTransaction<StateSignaturePayload>>>
                preConsensusTransformer = new WireTransformer<>(
                        model,
                        "extractPreconsensusSignatureTransactions",
                        "preconsensus signatures",
                        event -> SystemTransactionExtractionUtils.extractFromEvent(event, StateSignaturePayload.class));
        splitOrphanBufferOutput.solderTo(preConsensusTransformer.getInputWire());
        preConsensusTransformer
                .getOutputWire()
                .solderTo(stateSignatureCollectorWiring.getInputWire(
                        StateSignatureCollector::handlePreconsensusSignatures));

        // Split output of StateSignatureCollector into single ReservedSignedStates.
        final OutputWire<ReservedSignedState> splitReservedSignedStateWire = stateSignatureCollectorWiring
                .getOutputWire()
                .buildSplitter("reservedStateSplitter", "reserved state lists");
        // Add another reservation to the signed states since we are soldering to two different input wires
        final OutputWire<ReservedSignedState> allReservedSignedStatesWire =
                splitReservedSignedStateWire.buildAdvancedTransformer(new SignedStateReserver("allStatesReserver"));
        // All reserved signed states are saved to disk.
        allReservedSignedStatesWire.solderTo(signedStateFileManagerWiring.saveToDiskFilter());
        // Filter to complete states only and add a 3rd reservation since completes states are used in two input wires.
        final OutputWire<ReservedSignedState> completeReservedSignedStatesWire = allReservedSignedStatesWire
                .buildFilter("completeStateFilter", "states", rs -> {
                    if (rs.get().isComplete()) {
                        return true;
                    } else {
                        // close the second reservation on states that are not passed on.
                        rs.close();
                        return false;
                    }
                })
                .buildAdvancedTransformer(new SignedStateReserver("completeStatesReserver"));
        completeReservedSignedStatesWire.solderTo(
                latestCompleteStateNexusWiring.getInputWire(LatestCompleteStateNexus::setStateIfNewer));

        solderEventWindow();

        pcesReplayerWiring
                .doneStreamingPcesOutputWire()
                .solderTo(pcesWriterWiring.getInputWire(PcesWriter::beginStreamingNewEvents));
        pcesReplayerWiring.eventOutput().solderTo(pipelineInputWire);

        // Create the transformer that extracts keystone event sequence number from consensus rounds.
        // This is done here instead of in ConsensusEngineWiring, since the transformer needs to be soldered with
        // specified ordering, relative to the wire carrying consensus rounds to the round handler
        final WireTransformer<ConsensusRound, Long> keystoneEventSequenceNumberTransformer = new WireTransformer<>(
                model, "getKeystoneEventSequenceNumber", "rounds", round -> round.getKeystoneEvent()
                        .getBaseEvent()
                        .getStreamSequenceNumber());
        keystoneEventSequenceNumberTransformer
                .getOutputWire()
                .solderTo(pcesWriterWiring.getInputWire(PcesWriter::submitFlushRequest));

        final OutputWire<ConsensusRound> consensusRoundOutputWire = consensusEngineWiring.getSplitOutput();

        // The request to flush the keystone event for a round must be sent to the PCES writer before the consensus
        // round is passed to the round handler. This prevents a deadlock scenario where the consensus round
        // handler has a full queue and won't accept additional rounds, and is waiting on a keystone event to be
        // durably flushed to disk. Meanwhile, the PCES writer hasn't even received the flush request yet, so the
        // necessary keystone event is *never* flushed.
        consensusRoundOutputWire.orderedSolderTo(List.of(
                keystoneEventSequenceNumberTransformer.getInputWire(),
                roundDurabilityBufferWiring.getInputWire(RoundDurabilityBuffer::addRound)));
        consensusRoundOutputWire.solderTo(
                eventWindowManagerWiring.getInputWire(EventWindowManager::extractEventWindow));

        final OutputWire<ConsensusRound> splitRoundDurabilityBufferOutput =
                roundDurabilityBufferWiring.getSplitOutput();
        splitRoundDurabilityBufferOutput.solderTo(consensusRoundHandlerWiring.roundInput());

        consensusEngineWiring
                .getSplitAndTransformedOutput(ConsensusEngine::getConsensusEvents)
                .solderTo(consensusEventStreamWiring.getInputWire(ConsensusEventStream::addEvents));

        consensusRoundOutputWire.solderTo(
                runningEventHasherWiring.getInputWire(RunningEventHasher::computeRunningEventHash));

        consensusRoundHandlerWiring
                .stateOutput()
                .solderTo(latestImmutableStateNexusWiring.getInputWire(SignedStateNexus::setState));
        consensusRoundHandlerWiring
                .stateAndRoundOutput()
                .solderTo(savedStateControllerWiring.getInputWire(SavedStateController::markSavedState));
        savedStateControllerWiring.getOutputWire().solderTo(signedStateHasherWiring.stateAndRoundInput());

        consensusRoundHandlerWiring
                .stateAndRoundOutput()
                .solderTo(stateGarbageCollectorWiring.getInputWire(StateGarbageCollector::registerState));
        model.buildHeartbeatWire(config.stateGarbageCollectorHeartbeatPeriod())
                .solderTo(stateGarbageCollectorWiring.getInputWire(StateGarbageCollector::heartbeat), OFFER);
        model.buildHeartbeatWire(config.signedStateSentinelHeartbeatPeriod())
                .solderTo(signedStateSentinelWiring.getInputWire(SignedStateSentinel::checkSignedStates), OFFER);

        signedStateHasherWiring.stateOutput().solderTo(hashLoggerWiring.hashLoggerInputWire());
        signedStateHasherWiring.stateOutput().solderTo(stateSignerWiring.signState());
        signedStateHasherWiring
                .stateAndRoundOutput()
                .solderTo(issDetectorWiring.getInputWire(IssDetector::handleStateAndRound));

        // FUTURE WORK: combine the signedStateHasherWiring State and Round outputs into a single StateAndRound output.
        // FUTURE WORK: Split the single StateAndRound output into separate State and Round wires.

        // Extract signatures from post-consensus events for input to the StateSignatureCollector.
        final WireTransformer<ConsensusRound, List<ScopedSystemTransaction<StateSignaturePayload>>>
                postConsensusTransformer = new WireTransformer<>(
                        model,
                        "extractConsensusSignatureTransactions",
                        "consensus events",
                        round -> SystemTransactionExtractionUtils.extractFromRound(round, StateSignaturePayload.class));
        signedStateHasherWiring.roundOutput().solderTo(postConsensusTransformer.getInputWire());
        postConsensusTransformer
                .getOutputWire()
                .solderTo(stateSignatureCollectorWiring.getInputWire(
                        StateSignatureCollector::handlePostconsensusSignatures));
        // Solder the state output as input to the state signature collector.
        signedStateHasherWiring
                .stateOutput()
                .solderTo(stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::addReservedState));

        pcesWriterWiring
                .getOutputWire()
                .solderTo(
                        roundDurabilityBufferWiring.getInputWire(RoundDurabilityBuffer::setLatestDurableSequenceNumber),
                        INJECT);
        model.buildHeartbeatWire(platformContext
                        .getConfiguration()
                        .getConfigData(PcesConfig.class)
                        .roundDurabilityBufferHeartbeatPeriod())
                .solderTo(roundDurabilityBufferWiring.getInputWire(RoundDurabilityBuffer::checkForStaleRounds));

        signedStateFileManagerWiring
                .oldestMinimumGenerationOnDiskOutputWire()
                .solderTo(pcesWriterWiring.getInputWire(PcesWriter::setMinimumAncientIdentifierToStore), INJECT);
        signedStateFileManagerWiring
                .stateWrittenToDiskOutputWire()
                .solderTo(statusStateMachineWiring.getInputWire(StatusStateMachine::submitStatusAction));

        runningEventHashOverrideWiring
                .runningHashUpdateOutput()
                .solderTo(consensusRoundHandlerWiring.overrideLegacyRunningEventHashInput());
        runningEventHashOverrideWiring
                .runningHashUpdateOutput()
                .solderTo(consensusEventStreamWiring.getInputWire(ConsensusEventStream::legacyHashOverride));
        runningEventHashOverrideWiring
                .runningHashUpdateOutput()
                .solderTo(runningEventHasherWiring.getInputWire(RunningEventHasher::overrideRunningEventHash));

        final OutputWire<IssNotification> splitIssDetectorOutput = issDetectorWiring.getSplitOutput();
        splitIssDetectorOutput.solderTo(issHandlerWiring.issNotificationInput());
        issDetectorWiring
                .getSplitAndTransformedOutput(IssDetector::getStatusAction)
                .solderTo(statusStateMachineWiring.getInputWire(StatusStateMachine::submitStatusAction));

        completeReservedSignedStatesWire.solderTo(latestCompleteStateNotifierWiring.getInputWire(
                LatestCompleteStateNotifier::latestCompleteStateHandler));

        statusStateMachineWiring
                .getOutputWire()
                .solderTo(statusNexusWiring.getInputWire(PlatformStatusNexus::setCurrentStatus));
        statusStateMachineWiring
                .getOutputWire()
                .solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::updatePlatformStatus));

        solderNotifier();

        if (publishPreconsensusEvents) {
            splitOrphanBufferOutput.solderTo(
                    platformPublisherWiring.getInputWire(PlatformPublisher::publishPreconsensusEvent));
        }

        buildUnsolderedWires();
    }

    /**
     * {@link ComponentWiring} objects build their input wires when you first request them. Normally that happens when
     * we are soldering things together, but there are a few wires that aren't soldered and aren't used until later in
     * the lifecycle. This method forces those wires to be built.
     */
    private void buildUnsolderedWires() {
        eventDeduplicatorWiring.getInputWire(EventDeduplicator::clear);
        consensusEngineWiring.getInputWire(ConsensusEngine::outOfBandSnapshotUpdate);
        if (publishSnapshotOverrides) {
            platformPublisherWiring.getInputWire(PlatformPublisher::publishSnapshotOverride);
        }
        eventCreationManagerWiring.getInputWire(EventCreationManager::clear);
        notifierWiring.getInputWire(AppNotifier::sendStateLoadedFromDiskNotification);
        notifierWiring.getInputWire(AppNotifier::sendReconnectCompleteNotification);
        notifierWiring.getInputWire(AppNotifier::sendPlatformStatusChangeNotification);
        eventSignatureValidatorWiring.getInputWire(EventSignatureValidator::updateAddressBooks);
        eventWindowManagerWiring.getInputWire(EventWindowManager::updateEventWindow);
        orphanBufferWiring.getInputWire(OrphanBuffer::clear);
        roundDurabilityBufferWiring.getInputWire(RoundDurabilityBuffer::clear);
        pcesWriterWiring.getInputWire(PcesWriter::registerDiscontinuity);
        stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::clear);
        issDetectorWiring.getInputWire(IssDetector::overridingState);
        issDetectorWiring.getInputWire(IssDetector::signalEndOfPreconsensusReplay);
    }

    /**
     * Wire components that adhere to the framework to components that don't
     * <p>
     * Future work: as more components are moved to the framework, this method should shrink, and eventually be
     * removed.
     *
     * @param transactionPool the transaction pool to wire
     */
    public void wireExternalComponents(@NonNull final TransactionPool transactionPool) {
        stateSignerWiring
                .stateSignature()
                .solderTo("transactionPool", "signature transactions", transactionPool::submitPayload);
    }

    /**
     * Bind components to the wiring.
     *
     * @param builder                   builds platform components that need to be bound to wires
     * @param signedStateFileManager    the signed state file manager to bind
     * @param stateSigner               the state signer to bind
     * @param pcesReplayer              the PCES replayer to bind
     * @param stateSignatureCollector   the signed state manager to bind
     * @param eventWindowManager        the event window manager to bind
     * @param consensusRoundHandler     the consensus round handler to bind
     * @param issHandler                the ISS handler to bind
     * @param hashLogger                the hash logger to bind
     * @param birthRoundMigrationShim   the birth round migration shim to bind, ignored if birth round migration has not
     *                                  yet happened, must not be null if birth round migration has happened
     * @param completeStateNotifier     the latest complete state notifier to bind
     * @param latestImmutableStateNexus the latest immutable state nexus to bind
     * @param latestCompleteStateNexus  the latest complete state nexus to bind
     * @param savedStateController      the saved state controller to bind
     * @param signedStateHasher         the signed state hasher to bind
     * @param notifier                  the notifier to bind
     * @param platformPublisher         the platform publisher to bind
     * @param platformStatusNexus       the platform status nexus to bind
     */
    public void bind(
            @NonNull final PlatformComponentBuilder builder,
            @NonNull final SignedStateFileManager signedStateFileManager,
            @NonNull final StateSigner stateSigner,
            @NonNull final PcesReplayer pcesReplayer,
            @NonNull final StateSignatureCollector stateSignatureCollector,
            @NonNull final EventWindowManager eventWindowManager,
            @NonNull final ConsensusRoundHandler consensusRoundHandler,
            @NonNull final IssHandler issHandler,
            @NonNull final HashLogger hashLogger,
            @Nullable final BirthRoundMigrationShim birthRoundMigrationShim,
            @NonNull final LatestCompleteStateNotifier completeStateNotifier,
            @NonNull final SignedStateNexus latestImmutableStateNexus,
            @NonNull final LatestCompleteStateNexus latestCompleteStateNexus,
            @NonNull final SavedStateController savedStateController,
            @NonNull final SignedStateHasher signedStateHasher,
            @NonNull final AppNotifier notifier,
            @NonNull final PlatformPublisher platformPublisher,
            @NonNull final PlatformStatusNexus platformStatusNexus) {

        eventHasherWiring.bind(builder::buildEventHasher);
        internalEventValidatorWiring.bind(builder::buildInternalEventValidator);
        eventDeduplicatorWiring.bind(builder::buildEventDeduplicator);
        eventSignatureValidatorWiring.bind(builder::buildEventSignatureValidator);
        orphanBufferWiring.bind(builder::buildOrphanBuffer);
        consensusEngineWiring.bind(builder::buildConsensusEngine);
        signedStateFileManagerWiring.bind(signedStateFileManager);
        stateSignerWiring.bind(stateSigner);
        pcesReplayerWiring.bind(pcesReplayer);
        pcesWriterWiring.bind(builder::buildPcesWriter);
        roundDurabilityBufferWiring.bind(builder::buildRoundDurabilityBuffer);
        pcesSequencerWiring.bind(builder::buildPcesSequencer);
        eventCreationManagerWiring.bind(builder::buildEventCreationManager);
        selfEventSignerWiring.bind(builder::buildSelfEventSigner);
        stateSignatureCollectorWiring.bind(stateSignatureCollector);
        eventWindowManagerWiring.bind(eventWindowManager);
        applicationTransactionPrehandlerWiring.bind(builder::buildTransactionPrehandler);
        consensusRoundHandlerWiring.bind(consensusRoundHandler);
        runningEventHasherWiring.bind(builder::buildRunningEventHasher);
        consensusEventStreamWiring.bind(builder::buildConsensusEventStream);
        issDetectorWiring.bind(builder::buildIssDetector);
        issHandlerWiring.bind(issHandler);
        hashLoggerWiring.bind(hashLogger);
        if (birthRoundMigrationShimWiring != null) {
            birthRoundMigrationShimWiring.bind(Objects.requireNonNull(birthRoundMigrationShim));
        }
        latestCompleteStateNotifierWiring.bind(completeStateNotifier);
        latestImmutableStateNexusWiring.bind(latestImmutableStateNexus);
        latestCompleteStateNexusWiring.bind(latestCompleteStateNexus);
        savedStateControllerWiring.bind(savedStateController);
        signedStateHasherWiring.bind(signedStateHasher);
        notifierWiring.bind(notifier);
        platformPublisherWiring.bind(platformPublisher);
        stateGarbageCollectorWiring.bind(builder::buildStateGarbageCollector);
        statusStateMachineWiring.bind(builder::buildStatusStateMachine);
        statusNexusWiring.bind(platformStatusNexus);
        signedStateSentinelWiring.bind(builder::buildSignedStateSentinel);
        gossipWiring.bind(builder.buildGossip());
    }

    /**
     * Start gossiping.
     */
    public void startGossip() {
        gossipWiring.getStartInput().inject(NoInput.getInstance());
    }

    /**
     * Stop gossiping (permanently).
     */
    public void stopGossip() {
        gossipWiring.getStopInput().inject(NoInput.getInstance());
    }

    /**
     * Get the input wire for the address book update.
     * <p>
     * Future work: this is a temporary hook to update the address book in the new intake pipeline.
     *
     * @return the input method for the address book update
     */
    @NonNull
    public InputWire<AddressBookUpdate> getAddressBookUpdateInput() {
        return eventSignatureValidatorWiring.getInputWire(EventSignatureValidator::updateAddressBooks);
    }

    /**
     * Get the input wire for dumping a state to disk
     * <p>
     * Future work: this is a temporary hook to allow the components to dump a state to disk, prior to the whole system
     * being migrated to the new framework.
     *
     * @return the input wire for dumping a state to disk
     */
    @NonNull
    public InputWire<StateDumpRequest> getDumpStateToDiskInput() {
        return signedStateFileManagerWiring.dumpStateToDisk();
    }

    /**
     * @return the input wire for states that need their signatures collected
     */
    @NonNull
    public InputWire<ReservedSignedState> getSignatureCollectorStateInput() {
        return stateSignatureCollectorWiring.getInputWire(StateSignatureCollector::addReservedState);
    }

    /**
     * Get the input wire for passing a PCES iterator to the replayer.
     *
     * @return the input wire for passing a PCES iterator to the replayer
     */
    @NonNull
    public InputWire<IOIterator<GossipEvent>> getPcesReplayerIteratorInput() {
        return pcesReplayerWiring.pcesIteratorInputWire();
    }

    /**
     * Get the output wire that the replayer uses to pass events from file into the intake pipeline.
     *
     * @return the output wire that the replayer uses to pass events from file into the intake pipeline
     */
    @NonNull
    public StandardOutputWire<GossipEvent> getPcesReplayerEventOutput() {
        return pcesReplayerWiring.eventOutput();
    }

    /**
     * Get the input wire that the hashlogger uses to accept the signed state.
     *
     * @return the input wire that the hashlogger uses to accept the signed state
     */
    @NonNull
    public InputWire<ReservedSignedState> getHashLoggerInput() {
        return hashLoggerWiring.hashLoggerInputWire();
    }

    /**
     * Get the input wire for the PCES writer minimum generation to store
     *
     * @return the input wire for the PCES writer minimum generation to store
     */
    @NonNull
    public InputWire<Long> getPcesMinimumGenerationToStoreInput() {
        return pcesWriterWiring.getInputWire(PcesWriter::setMinimumAncientIdentifierToStore);
    }

    /**
     * Get the input wire for the PCES writer to register a discontinuity
     *
     * @return the input wire for the PCES writer to register a discontinuity
     */
    @NonNull
    public InputWire<Long> getPcesWriterRegisterDiscontinuityInput() {
        return pcesWriterWiring.getInputWire(PcesWriter::registerDiscontinuity);
    }

    /**
     * Get the wiring for the app notifier
     *
     * @return the wiring for the app notifier
     */
    @NonNull
    public ComponentWiring<AppNotifier, Void> getNotifierWiring() {
        return notifierWiring;
    }

    /**
     * Get a supplier for the number of unprocessed tasks at the front of the intake pipeline. This is for the purpose
     * of applying backpressure to the event creator and gossip when the intake pipeline is overloaded.
     * <p>
     * Technically, the first component of the intake pipeline is the hasher, but tasks to be passed along actually
     * accumulate in the post hash collector. This is due to how the concurrent hasher handles backpressure.
     *
     * @return a supplier for the number of unprocessed tasks in the PostHashCollector
     */
    @NonNull
    public LongSupplier getIntakeQueueSizeSupplier() {
        return () -> postHashCollectorWiring.getScheduler().getUnprocessedTaskCount();
    }

    /**
     * Update the running hash for all components that need it.
     *
     * @param runningHashUpdate the object containing necessary information to update the running hash
     */
    public void updateRunningHash(@NonNull final RunningEventHashOverride runningHashUpdate) {
        runningEventHashOverrideWiring.runningHashUpdateInput().inject(runningHashUpdate);
    }

    /**
     * Pass an overriding state to the ISS detector.
     *
     * @param state the overriding state
     */
    @NonNull
    public void overrideIssDetectorState(@NonNull final ReservedSignedState state) {
        issDetectorWiring.getInputWire(IssDetector::overridingState).put(state);
    }

    /**
     * Signal the end of the preconsensus replay to the ISS detector.
     */
    public void signalEndOfPcesReplay() {
        issDetectorWiring
                .getInputWire(IssDetector::signalEndOfPreconsensusReplay)
                .put(NoInput.getInstance());
    }

    /**
     * Get the status action submitter.
     *
     * @return the status action submitter
     */
    @NonNull
    public StatusActionSubmitter getStatusActionSubmitter() {
        return action -> statusStateMachineWiring
                .getInputWire(StatusStateMachine::submitStatusAction)
                .put(action);
    }

    /**
     * Inject a new event window into all components that need it.
     *
     * @param eventWindow the new event window
     */
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        // Future work: this method can merge with consensusSnapshotOverride
        eventWindowManagerWiring
                .getInputWire(EventWindowManager::updateEventWindow)
                .inject(eventWindow);

        // Since there is asynchronous access to the shadowgraph, it's important to ensure that
        // it has fully ingested the new event window before continuing.
        gossipWiring.flush();
    }

    /**
     * Inject a new consensus snapshot into all components that need it. This will happen at restart and reconnect
     * boundaries.
     *
     * @param consensusSnapshot the new consensus snapshot
     */
    public void consensusSnapshotOverride(@NonNull final ConsensusSnapshot consensusSnapshot) {
        consensusEngineWiring
                .getInputWire(ConsensusEngine::outOfBandSnapshotUpdate)
                .inject(consensusSnapshot);

        if (publishSnapshotOverrides) {
            platformPublisherWiring
                    .getInputWire(PlatformPublisher::publishSnapshotOverride)
                    .inject(consensusSnapshot);
        }
    }

    /**
     * Flush the intake pipeline.
     */
    public void flushIntakePipeline() {
        platformCoordinator.flushIntakePipeline();
    }

    /**
     * Flush the consensus round handler.
     */
    public void flushConsensusRoundHandler() {
        consensusRoundHandlerWiring.flushRunnable().run();
    }

    /**
     * Flush the state hasher.
     */
    public void flushStateHasher() {
        signedStateHasherWiring.flushRunnable().run();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        model.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        model.stop();
    }

    /**
     * Clear all the wiring objects.
     */
    @Override
    public void clear() {
        platformCoordinator.clear();
    }
}
