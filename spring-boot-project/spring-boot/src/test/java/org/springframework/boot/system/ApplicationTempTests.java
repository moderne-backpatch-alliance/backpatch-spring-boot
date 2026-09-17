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

package org.springframework.boot.system;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import org.springframework.util.FileSystemUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Tests for {@link ApplicationTemp}.
 *
 * @author Phillip Webb
 */
class ApplicationTempTests {

	@BeforeEach
	@AfterEach
	void cleanup() {
		FileSystemUtils.deleteRecursively(new ApplicationTemp().getDir());
	}

	@Test
	void generatesConsistentTemp() {
		ApplicationTemp t1 = new ApplicationTemp();
		ApplicationTemp t2 = new ApplicationTemp();
		assertThat(t1.getDir()).isNotNull();
		assertThat(t1.getDir()).isEqualTo(t2.getDir());
	}

	@Test
	void differentBasedOnUserDir() {
		String userDir = System.getProperty("user.dir");
		try {
			File t1 = new ApplicationTemp().getDir();
			System.setProperty("user.dir", "abc");
			File t2 = new ApplicationTemp().getDir();
			assertThat(t1).isNotEqualTo(t2);
		}
		finally {
			System.setProperty("user.dir", userDir);
		}
	}

	@Test
	void getSubDir() {
		ApplicationTemp temp = new ApplicationTemp();
		assertThat(temp.getDir("abc")).isEqualTo(new File(temp.getDir(), "abc"));
	}

	@Test
	void posixPermissions() throws IOException {
		ApplicationTemp temp = new ApplicationTemp();
		Path path = temp.getDir().toPath();
		FileSystem fileSystem = path.getFileSystem();
		if (fileSystem.supportedFileAttributeViews().contains("posix")) {
			assertDirectoryPermissions(path);
			assertDirectoryPermissions(temp.getDir("sub").toPath());
		}
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void whenDirectoryExistsWithWrongPermissionsGetDirThrows() throws IOException {
		Path path = new ApplicationTemp().getDir().toPath();
		Files.getFileAttributeView(path, PosixFileAttributeView.class)
			.setPermissions(EnumSet.allOf(PosixFilePermission.class));
		assertThatIllegalStateException().isThrownBy(new ApplicationTemp()::getDir)
			.withMessageContaining("does not have the permissions");
		FileSystemUtils.deleteRecursively(path);
	}

	@Test
	@DisabledOnOs(OS.WINDOWS)
	void whenDirectoryIsOwnedByAnotherUserGetDirThrows() throws IOException {
		Path path = new ApplicationTemp().getDir().toPath();
		UserPrincipal owner = Files.getOwner(path);
		giveAwayOwnership(path);
		try {
			assertThatIllegalStateException().isThrownBy(new ApplicationTemp()::getDir)
				.withMessageContaining("is not owned by");
		}
		finally {
			Files.setOwner(path, owner);
		}
		FileSystemUtils.deleteRecursively(path);
	}

	@Test
	void whenSymlinkExistsInDirectoryLocationGetDirThrows() throws IOException {
		Path path = new ApplicationTemp().getDir().toPath();
		FileSystemUtils.deleteRecursively(path);
		Path linkTarget = Files.createTempDirectory("application-temp-tests");
		try {
			Files.createSymbolicLink(path, linkTarget);
		}
		catch (Exception ex) {
			Assumptions.assumeTrue(false, "Symlink creation not supported");
		}
		try {
			assertThatIllegalStateException().isThrownBy(new ApplicationTemp()::getDir)
				.withMessageContaining("already exists but it is not a directory");
		}
		finally {
			Files.delete(path);
			FileSystemUtils.deleteRecursively(linkTarget);
		}
	}

	private void giveAwayOwnership(Path path) {
		try {
			Files.setOwner(path, path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("nobody"));
		}
		catch (IOException ex) {
			Assumptions.assumeTrue(false, "Ownership cannot be given away to another user");
		}
	}

	private void assertDirectoryPermissions(Path path) throws IOException {
		Set<PosixFilePermission> permissions = Files.getFileAttributeView(path, PosixFileAttributeView.class)
			.readAttributes()
			.permissions();
		assertThat(permissions).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
				PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
	}

}
