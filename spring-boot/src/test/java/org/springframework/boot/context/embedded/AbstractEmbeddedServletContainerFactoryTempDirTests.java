/*
 * Copyright 2012-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.embedded;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

import org.springframework.boot.web.servlet.ServletContextInitializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

/**
 * Tests for {@link AbstractEmbeddedServletContainerFactory#createTempDir(String)} that
 * cover CVE-2022-27772: the temp directory was created by deleting a uniquely-named
 * temporary file and then calling {@code mkdir()} on the released name without looking at
 * the result, so a directory another local user had already put there was adopted and
 * used as the container's work directory.
 *
 * @author Moderne Backpatch Alliance
 */
public class AbstractEmbeddedServletContainerFactoryTempDirTests {

	private final TestEmbeddedServletContainerFactory factory = new TestEmbeddedServletContainerFactory();

	private final List<File> created = new ArrayList<File>();

	@After
	public void deleteCreatedDirs() {
		for (File dir : this.created) {
			dir.delete();
		}
	}

	@Test
	public void createTempDirReturnsANewEmptyDirectory() {
		File tempDir = createTempDir();
		assertThat(tempDir).exists();
		assertThat(tempDir.isDirectory()).isTrue();
		assertThat(tempDir.list()).isEmpty();
	}

	@Test
	public void createTempDirReturnsADistinctDirectoryEachTime() {
		assertThat(createTempDir()).isNotEqualTo(createTempDir());
	}

	/**
	 * The property that distinguishes the fixed code. The vulnerable version let
	 * {@code mkdir()} apply the process umask, so the work directory was typically
	 * world-readable and group-writable on a shared host; a directory another user had
	 * pre-created kept whatever permissions that user gave it.
	 */
	@Test
	public void createTempDirIsRestrictedToItsOwner() {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		File tempDir = createTempDir();
		Set<PosixFilePermission> permissions = readPosixPermissions(tempDir);
		assertThat(permissions).containsOnly(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
				PosixFilePermission.OWNER_EXECUTE);
	}

	private File createTempDir() {
		File tempDir = this.factory.createTempDir("backpatch-test");
		this.created.add(tempDir);
		return tempDir;
	}

	private Set<PosixFilePermission> readPosixPermissions(File dir) {
		try {
			return Files.getPosixFilePermissions(dir.toPath());
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to read the permissions of " + dir, ex);
		}
	}

	static class TestEmbeddedServletContainerFactory extends AbstractEmbeddedServletContainerFactory {

		@Override
		public EmbeddedServletContainer getEmbeddedServletContainer(ServletContextInitializer... initializers) {
			throw new UnsupportedOperationException();
		}

		@Override
		public File createTempDir(String prefix) {
			return super.createTempDir(prefix);
		}

	}

}
