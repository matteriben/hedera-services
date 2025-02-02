/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
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

package com.swirlds.common.io.filesystem;

import com.swirlds.base.state.Startable;
import com.swirlds.base.state.Stoppable;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Responsible for organizing and managing access to the file system.
 */
public interface FileSystemManager extends Startable, Stoppable {

    /**
     * Resolve a path relative to the root directory of the file system manager.
     * Implementations can choose the convenient subfolder inside the root directory.
     *
     * @param relativePath the path to resolve against the root directory
     * @return the resolved path
     * @throws IllegalArgumentException if the path is "above" the root directory (e.g. resolve("../foo")
     */
    @NonNull
    Path resolve(@NonNull Path relativePath);

    /**
     * Creates a path relative to the root directory of the file system manager.
     * Implementations can choose the convenient subfolder inside the root directory.
     * There is no file or directory actually being created after the invocation of this method.
     *
     * @param tag if indicated, will be suffixed to the returned path
     * @return the resolved path
     * @throws IllegalArgumentException if the path is "above" the root directory (e.g. resolve("../foo")
     */
    @NonNull
    Path resolveNewTemp(@Nullable String tag);

    /**
     * Remove the file or directory tree at the specified path. A best effort attempt is made to relocate the file or
     * directory tree to a temporary location where it may persist for an amount of time. No guarantee on the amount of
     * time the file or directory tree will persist is provided.
     * Implementations can choose to validate if the provided absolute path is above the root of this file-system-manager.
     *
     *  @param path the absolute path to recycle.
     */
    void recycle(@NonNull Path path) throws IOException;
}
