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

package com.hedera.node.app.state.merkle.disk;

import static com.hedera.node.app.state.logging.TransactionStateLogger.logMapGet;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logMapGetSize;
import static com.hedera.node.app.state.logging.TransactionStateLogger.logMapIterate;

import com.hedera.node.app.spi.state.ReadableKVState;
import com.hedera.node.app.spi.state.ReadableKVStateBase;
import com.hedera.node.app.state.merkle.StateMetadata;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualLeafNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * An implementation of {@link ReadableKVState} backed by a {@link VirtualMap}, resulting in a state
 * that is stored on disk.
 *
 * @param <K> The type of key for the state
 * @param <V> The type of value for the state
 */
public final class OnDiskReadableKVState<K, V> extends ReadableKVStateBase<K, V> {

    /** The backing merkle data structure to use */
    private final VirtualMap<OnDiskKey<K>, OnDiskValue<V>> virtualMap;

    private final StateMetadata<K, V> md;

    /**
     * Create a new instance
     *
     * @param md the state metadata
     * @param virtualMap the backing merkle structure to use
     */
    public OnDiskReadableKVState(
            @NonNull final StateMetadata<K, V> md, @NonNull final VirtualMap<OnDiskKey<K>, OnDiskValue<V>> virtualMap) {
        super(md.stateDefinition().stateKey());
        this.md = md;
        this.virtualMap = Objects.requireNonNull(virtualMap);
    }

    /** {@inheritDoc} */
    @Override
    protected V readFromDataSource(@NonNull K key) {
        final var k = new OnDiskKey<>(md, key);
        final var v = virtualMap.get(k);
        final var value = v == null ? null : v.getValue();
        // Log to transaction state log, what was read
        logMapGet(getStateKey(), key, value);
        return value;
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    protected Iterator<K> iterateFromDataSource() {
        // Log to transaction state log, what was iterated
        logMapIterate(getStateKey(), virtualMap);

        final var itr = virtualMap.treeIterator();
        return new Iterator<>() {
            private K next = null;

            @Override
            public boolean hasNext() {
                if (next != null) return true;
                while (itr.hasNext()) {
                    final var merkleNode = itr.next();
                    if (merkleNode instanceof VirtualLeafNode<?, ?> leaf) {
                        final var k = leaf.getKey();
                        if (k instanceof OnDiskKey<?> onDiskKey) {
                            this.next = (K) onDiskKey.getKey();
                            return true;
                        }
                    }
                }
                return false;
            }

            @Override
            public K next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                final var k = next;
                next = null;
                return k;
            }
        };
    }

    /** {@inheritDoc} */
    @Override
    public long size() {
        final var size = virtualMap.size();
        // Log to transaction state log, size of map
        logMapGetSize(getStateKey(), size);
        return size;
    }

    @Override
    public void warm(@NonNull final K key) {
        final var k = new OnDiskKey<>(md, key);
        virtualMap.warm(k);
    }
}
