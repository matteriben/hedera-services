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

package com.hedera.services.bdd.suites.contract.hapi;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.ContractInfoAsserts.contractWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCustomCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.FULLY_NONDETERMINISTIC;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_CONTRACT_CALL_RESULTS;
import static com.hedera.services.bdd.spec.utilops.records.SnapshotMatchMode.NONDETERMINISTIC_TRANSACTION_FEES;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.captureChildCreate2MetaFor;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EXPIRATION_REDUCTION_NOT_ALLOWED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MODIFYING_IMMUTABLE_CONTRACT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestSuite;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.assertions.ContractInfoAsserts;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.suites.HapiSuite;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;

@HapiTestSuite(fuzzyMatch = true)
@Tag(SMART_CONTRACT)
public class ContractUpdateSuite extends HapiSuite {

    private static final Logger log = LogManager.getLogger(ContractUpdateSuite.class);

    private static final long DEFAULT_MAX_LIFETIME =
            Long.parseLong(HapiSpecSetup.getDefaultNodeProps().get("entities.maxLifetime"));
    private static final long ONE_DAY = 60L * 60L * 24L;
    private static final long ONE_MONTH = 30 * ONE_DAY;
    public static final String ADMIN_KEY = "adminKey";
    public static final String NEW_ADMIN_KEY = "newAdminKey";
    private static final String CONTRACT = "Multipurpose";
    public static final String INITIAL_ADMIN_KEY = "initialAdminKey";

    public static void main(String... args) {
        new ContractUpdateSuite().runSuiteAsync();
    }

    @Override
    public boolean canRunConcurrent() {
        return true;
    }

    @Override
    public List<HapiSpec> getSpecsInSuite() {
        return List.of(
                updateWithBothMemoSettersWorks(),
                updatingExpiryWorks(),
                rejectsExpiryTooFarInTheFuture(),
                updateAutoRenewWorks(),
                updateAdminKeyWorks(),
                canMakeContractImmutableWithEmptyKeyList(),
                givenAdminKeyMustBeValid(),
                fridayThe13thSpec(),
                updateDoesNotChangeBytecode(),
                eip1014AddressAlwaysHasPriority(),
                immutableContractKeyFormIsStandard(),
                updateAutoRenewAccountWorks(),
                updateStakingFieldsWorks(),
                cannotUpdateMaxAutomaticAssociations());
    }

    @HapiTest
    public HapiSpec idVariantsTreatedAsExpected() {
        return defaultHapiSpec("idVariantsTreatedAsExpected")
                .given(
                        newKeyNamed("adminKey"),
                        cryptoCreate("a"),
                        cryptoCreate("b"),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT).autoRenewAccountId("a").stakedAccountId("b"))
                .when()
                .then(submitModified(
                        withSuccessivelyVariedBodyIds(),
                        () -> contractUpdate(CONTRACT).newAutoRenewAccount("b").newStakedAccountId("a")));
    }

    @HapiTest
    final HapiSpec updateStakingFieldsWorks() {
        return defaultHapiSpec("updateStakingFieldsWorks", FULLY_NONDETERMINISTIC)
                .given(
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT).declinedReward(true).stakedNodeId(0),
                        getContractInfo(CONTRACT)
                                .has(contractWith()
                                        .isDeclinedReward(true)
                                        .noStakedAccountId()
                                        .stakedNodeId(0))
                                .logged())
                .when(
                        contractUpdate(CONTRACT).newDeclinedReward(false).newStakedAccountId("0.0.10"),
                        getContractInfo(CONTRACT)
                                .has(contractWith()
                                        .isDeclinedReward(false)
                                        .noStakingNodeId()
                                        .stakedAccountId("0.0.10"))
                                .logged(),

                        /* --- reset the staking account */
                        contractUpdate(CONTRACT).newDeclinedReward(false).newStakedAccountId("0.0.0"),
                        getContractInfo(CONTRACT)
                                .has(contractWith()
                                        .isDeclinedReward(false)
                                        .noStakingNodeId()
                                        .noStakedAccountId())
                                .logged(),
                        contractCreate(CONTRACT).declinedReward(true).stakedNodeId(0),
                        getContractInfo(CONTRACT)
                                .has(contractWith()
                                        .isDeclinedReward(true)
                                        .noStakedAccountId()
                                        .stakedNodeId(0))
                                .logged(),

                        /* --- reset the staking account */
                        contractUpdate(CONTRACT).newDeclinedReward(false).newStakedNodeId(-1L),
                        getContractInfo(CONTRACT)
                                .has(contractWith()
                                        .isDeclinedReward(false)
                                        .noStakingNodeId()
                                        .noStakedAccountId())
                                .logged())
                .then();
    }

    // https://github.com/hashgraph/hedera-services/issues/2877
    @HapiTest
    final HapiSpec eip1014AddressAlwaysHasPriority() {
        final var contract = "VariousCreate2Calls";
        final var creationTxn = "creationTxn";
        final var callTxn = "callTxn";
        final var callcodeTxn = "callcodeTxn";
        final var staticcallTxn = "staticcallTxn";
        final var delegatecallTxn = "delegatecallTxn";

        final AtomicReference<String> childMirror = new AtomicReference<>();
        final AtomicReference<String> childEip1014 = new AtomicReference<>();

        return defaultHapiSpec(
                        "Eip1014AddressAlwaysHasPriority",
                        NONDETERMINISTIC_CONTRACT_CALL_RESULTS,
                        NONDETERMINISTIC_TRANSACTION_FEES)
                .given(uploadInitCode(contract), contractCreate(contract).via(creationTxn))
                .when(captureChildCreate2MetaFor(2, 0, "setup", creationTxn, childMirror, childEip1014))
                .then(
                        contractCall(contract, "makeNormalCall").via(callTxn),
                        sourcing(() -> getTxnRecord(callTxn)
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(FUNCTION, "makeNormalCall", contract),
                                                        isLiteralResult(
                                                                new Object[] {asHeadlongAddress(childEip1014.get())
                                                                }))))),
                        contractCall(contract, "makeStaticCall").via(staticcallTxn),
                        sourcing(() -> getTxnRecord(staticcallTxn)
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(FUNCTION, "makeStaticCall", contract),
                                                        isLiteralResult(
                                                                new Object[] {asHeadlongAddress(childEip1014.get())
                                                                }))))),
                        contractCall(contract, "makeDelegateCall").via(delegatecallTxn),
                        sourcing(() -> getTxnRecord(delegatecallTxn)
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(FUNCTION, "makeDelegateCall", contract),
                                                        isLiteralResult(
                                                                new Object[] {asHeadlongAddress(childEip1014.get())
                                                                }))))),
                        contractCall(contract, "makeCallCode").via(callcodeTxn),
                        sourcing(() -> getTxnRecord(callcodeTxn)
                                .logged()
                                .hasPriority(recordWith()
                                        .contractCallResult(resultWith()
                                                .resultThruAbi(
                                                        getABIFor(FUNCTION, "makeCallCode", contract),
                                                        isLiteralResult(
                                                                new Object[] {asHeadlongAddress(childEip1014.get())
                                                                }))))));
    }

    @HapiTest
    final HapiSpec updateWithBothMemoSettersWorks() {
        final var firstMemo = "First";
        final var secondMemo = "Second";
        final var thirdMemo = "Third";

        return defaultHapiSpec("UpdateWithBothMemoSettersWorks", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(
                        newKeyNamed(ADMIN_KEY),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT).adminKey(ADMIN_KEY).entityMemo(firstMemo))
                .when(
                        contractUpdate(CONTRACT).newMemo(secondMemo),
                        contractUpdate(CONTRACT).newMemo(ZERO_BYTE_MEMO).hasPrecheck(INVALID_ZERO_BYTE_IN_STRING),
                        getContractInfo(CONTRACT).has(contractWith().memo(secondMemo)))
                .then(
                        contractUpdate(CONTRACT).useDeprecatedMemoField().newMemo(thirdMemo),
                        getContractInfo(CONTRACT).has(contractWith().memo(thirdMemo)));
    }

    @HapiTest
    final HapiSpec updatingExpiryWorks() {
        final var newExpiry = Instant.now().getEpochSecond() + 5 * ONE_MONTH;
        return defaultHapiSpec("UpdatingExpiryWorks", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .when(contractUpdate(CONTRACT).newExpirySecs(newExpiry))
                .then(getContractInfo(CONTRACT).has(contractWith().expiry(newExpiry)));
    }

    @HapiTest
    final HapiSpec rejectsExpiryTooFarInTheFuture() {
        final var smallBuffer = 12_345L;
        final var excessiveExpiry = DEFAULT_MAX_LIFETIME + Instant.now().getEpochSecond() + smallBuffer;

        return defaultHapiSpec("RejectsExpiryTooFarInTheFuture", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT))
                .when()
                .then(contractUpdate(CONTRACT).newExpirySecs(excessiveExpiry).hasKnownStatus(INVALID_EXPIRATION_TIME));
    }

    @HapiTest
    final HapiSpec updateAutoRenewWorks() {
        return defaultHapiSpec("UpdateAutoRenewWorks", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(
                        newKeyNamed(ADMIN_KEY),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT).adminKey(ADMIN_KEY).autoRenewSecs(THREE_MONTHS_IN_SECONDS))
                .when(contractUpdate(CONTRACT).newAutoRenew(THREE_MONTHS_IN_SECONDS + ONE_DAY))
                .then(getContractInfo(CONTRACT).has(contractWith().autoRenew(THREE_MONTHS_IN_SECONDS + ONE_DAY)));
    }

    @HapiTest
    final HapiSpec updateAutoRenewAccountWorks() {
        final var autoRenewAccount = "autoRenewAccount";
        final var newAutoRenewAccount = "newAutoRenewAccount";
        return defaultHapiSpec("UpdateAutoRenewAccountWorks", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(
                        newKeyNamed(ADMIN_KEY),
                        cryptoCreate(autoRenewAccount),
                        cryptoCreate(newAutoRenewAccount),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT).adminKey(ADMIN_KEY).autoRenewAccountId(autoRenewAccount),
                        getContractInfo(CONTRACT)
                                .has(ContractInfoAsserts.contractWith().autoRenewAccountId(autoRenewAccount))
                                .logged())
                .when(
                        contractUpdate(CONTRACT)
                                .newAutoRenewAccount(newAutoRenewAccount)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        contractUpdate(CONTRACT)
                                .newAutoRenewAccount(newAutoRenewAccount)
                                .signedBy(DEFAULT_PAYER, ADMIN_KEY, newAutoRenewAccount))
                .then(getContractInfo(CONTRACT)
                        .has(ContractInfoAsserts.contractWith().autoRenewAccountId(newAutoRenewAccount))
                        .logged());
    }

    @HapiTest
    final HapiSpec updateAdminKeyWorks() {
        return defaultHapiSpec("UpdateAdminKeyWorks", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(NEW_ADMIN_KEY),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT).adminKey(ADMIN_KEY))
                .when(contractUpdate(CONTRACT).newKey(NEW_ADMIN_KEY))
                .then(
                        contractUpdate(CONTRACT).newMemo("some new memo"),
                        getContractInfo(CONTRACT)
                                .has(contractWith().adminKey(NEW_ADMIN_KEY).memo("some new memo")));
    }

    // https://github.com/hashgraph/hedera-services/issues/3037
    @HapiTest
    final HapiSpec immutableContractKeyFormIsStandard() {
        return defaultHapiSpec("ImmutableContractKeyFormIsStandard", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(uploadInitCode(CONTRACT), contractCreate(CONTRACT).immutable())
                .when()
                .then(getContractInfo(CONTRACT).has(contractWith().immutableContractKey(CONTRACT)));
    }

    @HapiTest
    final HapiSpec canMakeContractImmutableWithEmptyKeyList() {
        return defaultHapiSpec("CanMakeContractImmutableWithEmptyKeyList", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(NEW_ADMIN_KEY),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT).adminKey(ADMIN_KEY))
                .when(
                        contractUpdate(CONTRACT).improperlyEmptyingAdminKey().hasPrecheck(INVALID_ADMIN_KEY),
                        contractUpdate(CONTRACT).properlyEmptyingAdminKey())
                .then(contractUpdate(CONTRACT).newKey(NEW_ADMIN_KEY).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT));
    }

    @HapiTest
    final HapiSpec givenAdminKeyMustBeValid() {
        final var contract = "BalanceLookup";
        return defaultHapiSpec("GivenAdminKeyMustBeValid", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(uploadInitCode(contract), contractCreate(contract))
                .when(getContractInfo(contract).logged())
                .then(contractUpdate(contract)
                        .useDeprecatedAdminKey()
                        .signedBy(GENESIS, contract)
                        .hasPrecheck(INVALID_ADMIN_KEY));
    }

    @HapiTest
    HapiSpec fridayThe13thSpec() {
        final var contract = "SimpleStorage";
        final var suffix = "Clone";
        final var newExpiry = Instant.now().getEpochSecond() + DEFAULT_PROPS.defaultExpirationSecs() * 2;
        final var betterExpiry = Instant.now().getEpochSecond() + DEFAULT_PROPS.defaultExpirationSecs() * 3;
        final var INITIAL_MEMO = "This is a memo string with only Ascii characters";
        final var NEW_MEMO = "Turning and turning in the widening gyre, the falcon cannot hear the falconer...";
        final var BETTER_MEMO = "This was Mr. Bleaney's room...";
        final var initialKeyShape = KeyShape.SIMPLE;
        final var newKeyShape = listOf(3);
        final var payer = "payer";

        return defaultHapiSpec("FridayThe13thSpec", FULLY_NONDETERMINISTIC)
                .given(
                        newKeyNamed(INITIAL_ADMIN_KEY).shape(initialKeyShape),
                        newKeyNamed(NEW_ADMIN_KEY).shape(newKeyShape),
                        cryptoCreate(payer).balance(10 * ONE_HUNDRED_HBARS),
                        uploadInitCode(contract))
                .when(
                        contractCreate(contract).payingWith(payer).omitAdminKey(),
                        contractCustomCreate(contract, suffix)
                                .payingWith(payer)
                                .adminKey(INITIAL_ADMIN_KEY)
                                .entityMemo(INITIAL_MEMO),
                        getContractInfo(contract + suffix)
                                .payingWith(payer)
                                .logged()
                                .has(contractWith().memo(INITIAL_MEMO).adminKey(INITIAL_ADMIN_KEY)))
                .then(
                        contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .newKey(NEW_ADMIN_KEY)
                                .signedBy(payer, INITIAL_ADMIN_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .newKey(NEW_ADMIN_KEY)
                                .signedBy(payer, NEW_ADMIN_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        contractUpdate(contract + suffix).payingWith(payer).newKey(NEW_ADMIN_KEY),
                        contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .newExpirySecs(newExpiry)
                                .newMemo(NEW_MEMO),
                        getContractInfo(contract + suffix)
                                .payingWith(payer)
                                .logged()
                                .has(contractWith()
                                        .solidityAddress(contract + suffix)
                                        .memo(NEW_MEMO)
                                        .expiry(newExpiry)),
                        contractUpdate(contract + suffix).payingWith(payer).newMemo(BETTER_MEMO),
                        getContractInfo(contract + suffix)
                                .payingWith(payer)
                                .logged()
                                .has(contractWith().memo(BETTER_MEMO).expiry(newExpiry)),
                        contractUpdate(contract + suffix).payingWith(payer).newExpirySecs(betterExpiry),
                        getContractInfo(contract + suffix)
                                .payingWith(payer)
                                .logged()
                                .has(contractWith().memo(BETTER_MEMO).expiry(betterExpiry)),
                        contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .signedBy(payer)
                                .newExpirySecs(newExpiry)
                                .hasKnownStatus(EXPIRATION_REDUCTION_NOT_ALLOWED),
                        contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .signedBy(payer)
                                .newMemo(NEW_MEMO)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        contractUpdate(contract + suffix)
                                .payingWith(payer)
                                .signedBy(payer, INITIAL_ADMIN_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        contractUpdate(contract)
                                .payingWith(payer)
                                .newMemo(BETTER_MEMO)
                                .hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                        contractDelete(contract).payingWith(payer).hasKnownStatus(MODIFYING_IMMUTABLE_CONTRACT),
                        contractUpdate(contract).payingWith(payer).newExpirySecs(betterExpiry),
                        contractDelete(contract + suffix)
                                .payingWith(payer)
                                .signedBy(payer, INITIAL_ADMIN_KEY)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        contractDelete(contract + suffix)
                                .payingWith(payer)
                                .signedBy(payer)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        contractDelete(contract + suffix).payingWith(payer).hasKnownStatus(SUCCESS));
    }

    @HapiTest
    final HapiSpec updateDoesNotChangeBytecode() {
        // HSCS-DCPR-001
        final var simpleStorageContract = "SimpleStorage";
        final var emptyConstructorContract = "EmptyConstructor";
        return defaultHapiSpec("updateDoesNotChangeBytecode", NONDETERMINISTIC_TRANSACTION_FEES)
                .given(
                        uploadInitCode(simpleStorageContract, emptyConstructorContract),
                        contractCreate(simpleStorageContract),
                        getContractBytecode(simpleStorageContract).saveResultTo("initialBytecode"))
                .when(contractUpdate(simpleStorageContract).bytecode(emptyConstructorContract))
                .then(withOpContext((spec, log) -> {
                    var op = getContractBytecode(simpleStorageContract)
                            .hasBytecode(spec.registry().getBytes("initialBytecode"));
                    allRunFor(spec, op);
                }));
    }

    @HapiTest
    private HapiSpec cannotUpdateMaxAutomaticAssociations() {
        return defaultHapiSpec("cannotUpdateMaxAutomaticAssociations")
                .given(
                        newKeyNamed(ADMIN_KEY),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT).adminKey(ADMIN_KEY))
                .when()
                .then(contractUpdate(CONTRACT).newMaxAutomaticAssociations(20).hasKnownStatus(NOT_SUPPORTED));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
