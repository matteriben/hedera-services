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

package com.swirlds.common.test.fixtures;

import static com.swirlds.common.io.utility.FileUtils.rethrowIO;

import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.RecycleBin;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link FileSystemManager} that uses {@link TestRecycleBin}
 */
public class TestFileSystemManager implements FileSystemManager {

    private static final String TMP = "tmp";
    private static final String USER = "usr";
    private static final String BIN = "recycle-bin";
    private final Path rootPath;
    private final Path tempPath;
    private final Path userPath;
    private final Path recycleBinPath;
    private RecycleBin bin;
    private static final AtomicLong TMP_FIELD_INDEX = new AtomicLong(0);

    public TestFileSystemManager(@NonNull final Path rootLocation) {
        this.rootPath = rootLocation.normalize();
        if (Files.notExists(rootPath)) {
            rethrowIO(() -> Files.createDirectory(rootPath));
        }
        this.tempPath = rootPath.resolve(TMP);
        this.userPath = rootPath.resolve(USER);
        this.recycleBinPath = rootPath.resolve(BIN);

        if (Files.notExists(userPath)) {
            rethrowIO(() -> Files.createDirectory(userPath));
        }

        if (Files.notExists(tempPath)) {
            rethrowIO(() -> Files.createDirectory(tempPath));
        }

        if (Files.notExists(recycleBinPath)) {
            rethrowIO(() -> Files.createDirectory(recycleBinPath));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> rethrowIO(() -> FileUtils.deleteDirectory(rootPath))));
    }

    /**
     * Resolve a path relative to the root directory of the file system manager.
     *
     * @param relativePath the path to resolve against the root directory
     * @return the resolved path
     * @throws IllegalArgumentException if the path cannot be relative to or scape the root directory
     */
    @NonNull
    @Override
    public Path resolve(@NonNull final Path relativePath) {
        return userPath.resolve(relativePath);
    }

    /**
     * Creates a temporary file relative to the root directory of the file system manager. Implementations can choose to
     * use an index to assure the path will be unique inside the directory
     *
     * @param tag if indicated, will be suffixed to the path
     * @return the resolved path
     * @throws IllegalArgumentException if the path is "below" the root directory (e.g. resolve("../foo")
     */
    @NonNull
    @Override
    public Path resolveNewTemp(@Nullable final String tag) {
        final StringBuilder nameBuilder = new StringBuilder();
        nameBuilder.append(System.currentTimeMillis());
        nameBuilder.append(TMP_FIELD_INDEX.getAndIncrement());
        if (tag != null) {
            nameBuilder.append("-");
            nameBuilder.append(tag);
        }
        return tempPath.resolve(nameBuilder.toString());
    }

    /**
     * Remove the file or directory tree at the specified path. A best effort attempt is made to relocate the file or
     * directory tree to a temporary location where it may persist for an amount of time. No guarantee on the amount of
     * time the file or directory tree will persist is provided.
     *
     * @param path the relative path to recycle. Can be relative to the root dir or absolute
     * @throws IllegalArgumentException if the path cannot be relative to or scape the root directory
     */
    @Override
    public void recycle(@NonNull final Path path) throws IOException {
        bin.recycle(path);
    }

    /**
     * Start this object.
     */
    @Override
    public void start() {}

    /**
     * Stop this object.
     */
    @Override
    public void stop() {}

    public void setBin(final RecycleBin bin) {
        this.bin = bin;
    }
}
