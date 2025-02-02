/*
 * Copyright (C) 2020-2024 Hedera Hashgraph, LLC
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

package com.hedera.services.bdd.suites.schedule;

import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sendModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedQueryIds;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.ADMIN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SCHEDULING_WHITELIST;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SENDER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.VALID_SCHEDULED_TXN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.withAndWithoutLongTermEnabled;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_IS_IMMUTABLE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestSuite;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.suites.HapiSuite;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@HapiTestSuite
public class ScheduleDeleteSpecs extends HapiSuite {
    private static final Logger log = LogManager.getLogger(ScheduleDeleteSpecs.class);

    public static void main(String... args) {
        new ScheduleDeleteSpecs().runSuiteAsync();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    public List<HapiSpec> getSpecsInSuite() {
        return withAndWithoutLongTermEnabled(() -> List.of(
                deleteWithNoAdminKeyFails(),
                unauthorizedDeletionFails(),
                deletingAlreadyDeletedIsObvious(),
                deletingNonExistingFails(),
                deletingExecutedIsPointless()));
    }

    @HapiTest
    final HapiSpec deleteWithNoAdminKeyFails() {
        return defaultHapiSpec("DeleteWithNoAdminKeyFails")
                .given(
                        overriding(SCHEDULING_WHITELIST, "CryptoTransfer,CryptoCreate"),
                        cryptoCreate(SENDER),
                        cryptoCreate(RECEIVER),
                        scheduleCreate(VALID_SCHEDULED_TXN, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1))))
                .when()
                .then(scheduleDelete(VALID_SCHEDULED_TXN).hasKnownStatus(SCHEDULE_IS_IMMUTABLE));
    }

    @HapiTest
    final HapiSpec unauthorizedDeletionFails() {
        return defaultHapiSpec("UnauthorizedDeletionFails")
                .given(
                        overriding(SCHEDULING_WHITELIST, "CryptoTransfer,CryptoCreate"),
                        newKeyNamed(ADMIN),
                        newKeyNamed("non-admin-key"),
                        cryptoCreate(SENDER),
                        cryptoCreate(RECEIVER),
                        scheduleCreate(VALID_SCHEDULED_TXN, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                                .adminKey(ADMIN))
                .when()
                .then(scheduleDelete(VALID_SCHEDULED_TXN)
                        .signedBy(DEFAULT_PAYER, "non-admin-key")
                        .hasKnownStatus(INVALID_SIGNATURE));
    }

    @HapiTest
    final HapiSpec deletingAlreadyDeletedIsObvious() {
        return defaultHapiSpec("DeletingAlreadyDeletedIsObvious")
                .given(
                        overriding(SCHEDULING_WHITELIST, "CryptoTransfer,CryptoCreate"),
                        cryptoCreate(SENDER),
                        cryptoCreate(RECEIVER),
                        newKeyNamed(ADMIN),
                        scheduleCreate(VALID_SCHEDULED_TXN, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                                .adminKey(ADMIN),
                        scheduleDelete(VALID_SCHEDULED_TXN).signedBy(ADMIN, DEFAULT_PAYER))
                .when()
                .then(scheduleDelete(VALID_SCHEDULED_TXN)
                        .fee(ONE_HBAR)
                        .signedBy(ADMIN, DEFAULT_PAYER)
                        .hasKnownStatus(SCHEDULE_ALREADY_DELETED));
    }

    @HapiTest
    final HapiSpec idVariantsTreatedAsExpected() {
        return defaultHapiSpec("idVariantsTreatedAsExpected")
                .given(
                        newKeyNamed(ADMIN),
                        cryptoCreate(SENDER),
                        scheduleCreate(VALID_SCHEDULED_TXN, cryptoTransfer(tinyBarsFromTo(SENDER, FUNDING, 1)))
                                .adminKey(ADMIN))
                .when()
                .then(submitModified(withSuccessivelyVariedBodyIds(), () -> scheduleDelete(VALID_SCHEDULED_TXN)
                        .signedBy(DEFAULT_PAYER, ADMIN)));
    }

    @HapiTest
    public HapiSpec getScheduleInfoIdVariantsTreatedAsExpected() {
        return defaultHapiSpec("getScheduleInfoIdVariantsTreatedAsExpected")
                .given(
                        newKeyNamed(ADMIN),
                        cryptoCreate(SENDER),
                        scheduleCreate(VALID_SCHEDULED_TXN, cryptoTransfer(tinyBarsFromTo(SENDER, FUNDING, 1)))
                                .adminKey(ADMIN))
                .when()
                .then(sendModified(withSuccessivelyVariedQueryIds(), () -> getScheduleInfo(VALID_SCHEDULED_TXN)));
    }

    @HapiTest
    final HapiSpec deletingNonExistingFails() {
        return defaultHapiSpec("DeletingNonExistingFails")
                .given()
                .when()
                .then(
                        scheduleDelete("0.0.534").fee(ONE_HBAR).hasKnownStatus(INVALID_SCHEDULE_ID),
                        scheduleDelete("0.0.0").fee(ONE_HBAR).hasKnownStatus(INVALID_SCHEDULE_ID));
    }

    @HapiTest
    final HapiSpec deletingExecutedIsPointless() {
        return defaultHapiSpec("DeletingExecutedIsPointless")
                .given(
                        overriding(SCHEDULING_WHITELIST, "CryptoTransfer,CryptoCreate,ConsensusSubmitMessage"),
                        createTopic("ofGreatInterest"),
                        newKeyNamed(ADMIN),
                        scheduleCreate(VALID_SCHEDULED_TXN, submitMessageTo("ofGreatInterest"))
                                .adminKey(ADMIN))
                .when()
                .then(scheduleDelete(VALID_SCHEDULED_TXN)
                        .signedBy(ADMIN, DEFAULT_PAYER)
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED));
    }
}
