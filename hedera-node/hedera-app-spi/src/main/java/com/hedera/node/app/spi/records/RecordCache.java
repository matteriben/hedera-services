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

package com.hedera.node.app.spi.records;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNKNOWN;
import static com.hedera.node.app.spi.HapiUtils.TIMESTAMP_COMPARATOR;
import static java.util.Collections.emptyList;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.spi.Service;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Supplies {@link Service}s access to records and receipts.
 *
 * <p>A receipt is added when this node ingests a new transaction, or when this node pre-handles a transaction ingested
 * on another node. A receipt in this state will have a status of
 * {@link com.hedera.hapi.node.base.ResponseCodeEnum#UNKNOWN}.
 *
 * <p>Later, during {@code handle}, a final record and receipt is added based on the result of handling that
 * transaction. The receipt will no longer be "UNKNOWN" unless an unhandled exception occurred.
 */
public interface RecordCache {
    /**
     * For mono-service fidelity, records with these statuses do not prevent valid transactions with
     * the same id from reaching consensus and being handled.
     */
    Set<ResponseCodeEnum> DUE_DILIGENCE_FAILURES = EnumSet.of(INVALID_NODE_ACCOUNT, INVALID_PAYER_SIGNATURE);
    /**
     * And when ordering records for queries, we treat records with unclassifiable statuses as the
     * lowest "priority"; so that e.g. if a transaction with id {@code X} resolves to {@link ResponseCodeEnum#SUCCESS}
     * <i>after</i> we previously resolved an {@link ResponseCodeEnum#INVALID_NODE_ACCOUNT} for {@code X},
     * then {@link com.hedera.hapi.node.base.HederaFunctionality#TRANSACTION_GET_RECEIPT} will return
     * the success record.
     */
    // nested ternary expressions
    @SuppressWarnings("java:S3358")
    Comparator<TransactionRecord> RECORD_COMPARATOR = Comparator.<TransactionRecord, ResponseCodeEnum>comparing(
                    rec -> rec.receiptOrThrow().status(),
                    (a, b) -> DUE_DILIGENCE_FAILURES.contains(a) == DUE_DILIGENCE_FAILURES.contains(b)
                            ? 0
                            : (DUE_DILIGENCE_FAILURES.contains(b) ? -1 : 1))
            .thenComparing(rec -> rec.consensusTimestampOrElse(Timestamp.DEFAULT), TIMESTAMP_COMPARATOR);

    /**
     * An item stored in the cache.
     *
     * <p>There is a new {@link History} instance created for each original user transaction that comes to consensus.
     * The {@code records} list contains every {@link TransactionRecord} for the original (first) user transaction with
     * a given transaction ID that came to consensus, as well as all duplicate transaction records for duplicate
     * transactions with the same ID that also came to consensus.
     *
     * <p>The {@code childRecords} list contains a list of all child transactions of the original user transaction.
     * Duplicate transactions never have child transactions.
     *
     * @param nodeIds The IDs of every node that submitted a transaction with the txId that came to consensus and was
     * handled. This is an unordered set, since deterministic ordering is not required for this in-memory
     * data structure
     * @param records Every {@link TransactionRecord} handled for every user transaction that came to consensus
     * @param childRecords The list of child records
     */
    record History(
            @NonNull Set<Long> nodeIds,
            @NonNull List<TransactionRecord> records,
            @NonNull List<TransactionRecord> childRecords) {

        /**
         * This receipt is returned whenever we know there is a transaction pending (i.e. we have a history for a
         * transaction ID), but we do not yet have a record for it.
         */
        private static final TransactionReceipt PENDING_RECEIPT =
                TransactionReceipt.newBuilder().status(UNKNOWN).build();

        /**
         * Create a new {@link History} instance with empty lists.
         */
        public History() {
            this(new HashSet<>(), new ArrayList<>(), new ArrayList<>());
        }

        /**
         * Gets the primary record, that is, the record associated with the user transaction itself. This record
         * will be associated with a transaction ID with a nonce of 0 and no parent consensus timestamp.
         *
         * @return The primary record, if there is one.
         */
        @Nullable
        public TransactionRecord userTransactionRecord() {
            return records.isEmpty() ? null : sortedRecords().getFirst();
        }

        /**
         * Gets the primary receipt, that is, the receipt associated with the user transaction itself. This receipt will
         * be null if there is no such record.
         *
         * @return The primary receipt, if there is one.
         */
        @Nullable
        public TransactionReceipt userTransactionReceipt() {
            return records.isEmpty()
                    ? PENDING_RECEIPT
                    : sortedRecords().getFirst().receipt();
        }

        /**
         * Gets the list of all duplicate records, as a view. Should the list of records change, this view will reflect
         * those changes.
         *
         * @return The list of all duplicate records.
         */
        @NonNull
        public List<TransactionRecord> duplicateRecords() {
            return records.isEmpty() ? emptyList() : sortedRecords().subList(1, records.size());
        }

        /**
         * Gets the number of duplicate records.
         *
         * @return The number of duplicate records.
         */
        public int duplicateCount() {
            return records.isEmpty() ? 0 : records.size() - 1;
        }

        /**
         * Returns a list of all records, ordered by consensus timestamp. Some elements of {@link #childRecords} may
         * come before those in {@link #records}, while some may come after some elements in {@link #records}.
         *
         * @return The list of all records, ordered by consensus timestamp.
         */
        public List<TransactionRecord> orderedRecords() {
            final var ordered = new ArrayList<>(records);
            ordered.addAll(childRecords);
            ordered.sort(RECORD_COMPARATOR);
            return ordered;
        }

        private List<TransactionRecord> sortedRecords() {
            return records.stream().sorted(RECORD_COMPARATOR).toList();
        }
    }

    /**
     * Gets the known history of the given {@link TransactionID} in this cache.
     *
     * @param transactionID The transaction ID to look up
     * @return the history, if any, stored in this cache for the given transaction ID. If the history does not exist
     * (i.e. it is null), then we have never heard of this transactionID. If the history is not null, but there
     * are no records within it, then we have heard of this transactionID (i.e. in pre-handle or ingest), but
     * we do not yet have a record for it (i.e. in handle). If there are records, then the first record will
     * be the "primary" or user-transaction record, and the others will be the duplicates.
     */
    @Nullable
    History getHistory(@NonNull TransactionID transactionID);

    /**
     * Gets a list of all records for the given {@link AccountID}. The {@link AccountID} is the account of the Payer of
     * the transaction.
     *
     * @param accountID The accountID of the Payer of the transactions
     * @return The {@link TransactionRecord}s, if any, or else an empty list.
     */
    @NonNull
    List<TransactionRecord> getRecords(@NonNull AccountID accountID);
}
