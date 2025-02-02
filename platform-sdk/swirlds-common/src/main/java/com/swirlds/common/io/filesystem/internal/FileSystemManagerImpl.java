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

package com.swirlds.common.io.filesystem.internal;

import static com.swirlds.common.io.utility.FileUtils.rethrowIO;
import static java.nio.file.Files.exists;

import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Manages the file system operations and organizes file creation within a specified root directory.
 * <p>
 * This implementation of {@link FileSystemManager} organizes file creation within a specified root directory in the
 * following structure:
 * <pre>
 * root
 * ├── USER
 * ├── TMP
 * └── BIN
 * </pre>
 * The name of the directories can be provided by configuration
 * <p>
 * If the root directory already exists, it is used. Otherwise, it is created. Similarly, if the 'USER' directory
 * already exists, it is used; otherwise, it is created. The 'TMP' directory is always recreated, while the 'BIN'
 * directory is created if it doesn't exist.
 * <p>
 * All {@link Path}s provided by this class are handled within the same filesystem as indicated by the
 * {@code rootLocation} parameter.
 * </p>
 * <p>
 * Note: Two different instances of {@link FileSystemManagerImpl} created on the same root location can create paths using the same name.
 * </p>
 */
public class FileSystemManagerImpl implements FileSystemManager {

    private final Path rootPath;
    private final Path tempPath;
    private final Path savedPath;
    private final Path recycleBinPath;
    private final RecycleBin bin;
    private final AtomicLong tmpFileNameIndex = new AtomicLong(0);

    /**
     * Creates a {@link FileSystemManager} and a {@link com.swirlds.common.io.utility.RecycleBin} by searching {@code root}
     * path in the {@link Configuration} class using
     * {@code FileSystemManagerConfig} record
     *
     * @param rootLocation      the location to be used as root path. It should not exist.
     * @param dataDirName       the name of the user data file directory
     * @param tmpDirName        the name of the tmp file directory
     * @param recycleBinDirName the name of the recycle bin directory
     * @param binSupplier       for building the recycle bin.
     * @throws UncheckedIOException if the dir structure to rootLocation cannot be created
     */
    FileSystemManagerImpl(
            @NonNull final String rootLocation,
            final String dataDirName,
            final String tmpDirName,
            final String recycleBinDirName,
            @NonNull final Function<Path, RecycleBin> binSupplier) {
        this.rootPath = Path.of(rootLocation).normalize();
        if (!exists(rootPath)) {
            rethrowIO(() -> Files.createDirectories(rootPath));
        }

        this.tempPath = rootPath.resolve(tmpDirName);
        this.savedPath = rootPath.resolve(dataDirName);
        this.recycleBinPath = rootPath.resolve(recycleBinDirName);

        if (!exists(savedPath)) {
            rethrowIO(() -> Files.createDirectory(savedPath));
        }
        if (exists(tempPath)) {
            rethrowIO(() -> FileUtils.deleteDirectory(tempPath));
        }
        rethrowIO(() -> Files.createDirectory(tempPath));

        // FUTURE-WORK: --MIGRATION-- Remove this logic after the fs manager was deployed.
        // Moves files in the old location of the recycle bin to the new one
        final Path oldRecyclePath = savedPath.resolve("swirlds-recycle-bin");
        if (!exists(recycleBinPath) && exists(oldRecyclePath)) {
            rethrowIO(() -> Files.move(oldRecyclePath, recycleBinPath, StandardCopyOption.ATOMIC_MOVE));
        }

        if (!exists(recycleBinPath)) {
            rethrowIO(() -> Files.createDirectory(recycleBinPath));
        }
        this.bin = binSupplier.apply(recycleBinPath);
    }

    /**
     * Resolve a path relative to the{@code savedPath} of this file system manager.
     *
     * @param relativePath the path to resolve against the root directory
     * @return the resolved path
     * @throws IllegalArgumentException if the path is "above" the root directory (e.g. resolve("../foo")
     */
    @NonNull
    @Override
    public Path resolve(@NonNull final Path relativePath) {
        return requireValidSubPathOf(savedPath, savedPath.resolve(relativePath));
    }

    /**
     * Creates a path relative to the {@code tempPath} directory of the file system manager.
     * There is no file or directory actually being created after the invocation of this method.
     * All calls to this method will return a different path even if {@code tag} is not set.
     * A separate instance pointing to the same {@code rootPath} can create the same paths and should be managed outside this class.
     *
     * @param tag if indicated, will be suffixed to the returned path
     * @return the resolved path
     * @throws IllegalArgumentException if the path is "above" the root directory (e.g. resolve("../foo")
     */
    @NonNull
    @Override
    public Path resolveNewTemp(@Nullable final String tag) {
        final StringBuilder nameBuilder = new StringBuilder();
        nameBuilder.append(System.currentTimeMillis());
        nameBuilder.append(tmpFileNameIndex.getAndIncrement());
        if (tag != null) {
            nameBuilder.append("-");
            nameBuilder.append(tag);
        }

        return requireValidSubPathOf(tempPath, tempPath.resolve(nameBuilder.toString()));
    }

    /**
     * Remove the file or directory tree at the specified absolute path. A best effort attempt is made to relocate the
     * file or directory tree to a temporary location where it may persist for an amount of time. No guarantee on the
     * amount of time the file or directory tree will persist is provided.
     *
     * @param absolutePath the path to recycle
     * @throws IllegalArgumentException if the path is "above" the root directory (e.g. recycle("../../foo")
     */
    @Override
    public void recycle(@NonNull final Path absolutePath) throws IOException {
        bin.recycle(requireValidSubPathOf(rootPath, absolutePath));
    }

    /**
     * Checks that the specified {@code path} reference is "below" {@code parent} and is not {@code parent} itself.
     * throws IllegalArgumentException if this condition is not true.
     *
     * @param parent the path to check against.
     * @param path   the path to check if is
     * @return {@code path} if it represents a valid path inside {@code parent}
     * @throws IllegalArgumentException if the reference is "above" {@code parent} or is {@code parent} itself
     */
    @NonNull
    private static Path requireValidSubPathOf(@NonNull final Path parent, @NonNull final Path path) {
        final Path relativePath = parent.relativize(path);
        // Check if path is not parent itself and if is contained in parent
        if (relativePath.startsWith("") || relativePath.startsWith("..")) {
            throw new IllegalArgumentException(
                    "Requested path is cannot be converted to valid relative path inside of:" + parent);
        }
        return path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() {
        bin.stop();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        bin.start();
    }
}
