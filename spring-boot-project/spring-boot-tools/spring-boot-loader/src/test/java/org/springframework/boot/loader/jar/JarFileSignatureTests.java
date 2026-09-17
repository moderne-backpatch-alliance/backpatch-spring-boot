/*
 * Copyright 2012-2024 the original author or authors.
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

package org.springframework.boot.loader.jar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.util.FileCopyUtils;
import org.springframework.util.StreamUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Tests for the certificates and code signers that {@link JarFile} reports for the
 * entries of a signed nested jar.
 *
 * @author Jonathan Schneider
 */
class JarFileSignatureTests {

	private static final String PASSWORD = "backpatch";

	private static final int CENTRAL_DIRECTORY_HEADER_SIZE = 46;

	private static final int END_RECORD_SIZE = 22;

	private static final int END_RECORD_SIGNATURE = 0x06054b50;

	private static final int CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;

	@TempDir
	File tempDir;

	@Test
	void getCertificatesAndCodeSignersUseTheSignerOfTheEntry() throws Exception {
		File keyStore = createKeyStore("alice");
		File signed = createSignedJar(keyStore, "alice", "alice's content");
		try (java.util.jar.JarFile expected = new java.util.jar.JarFile(signed)) {
			java.util.jar.JarEntry expectedEntry = expected.getJarEntry("content");
			StreamUtils.drain(expected.getInputStream(expectedEntry));
			try (JarFile outer = new JarFile(nest(signed));
					JarFile inner = outer.getNestedJarFile(outer.getJarEntry("inner.jar"))) {
				JarEntry entry = inner.getJarEntry("content");
				assertThat(entry.getCertificates()).isNotEmpty().isEqualTo(expectedEntry.getCertificates());
				assertThat(entry.getCodeSigners()).isNotEmpty().isEqualTo(expectedEntry.getCodeSigners());
			}
		}
	}

	@Test
	void getCodeSignersWhenStreamedEntriesMismatchThrowsException() throws Exception {
		File keyStore = createKeyStore("alice", "bob");
		File streamed = createSignedJar(keyStore, "alice", "alice's content");
		File listed = createSignedJar(keyStore, "bob", "bob's content, which alice never signed");
		try (JarFile outer = new JarFile(nest(forge(streamed, listed)));
				JarFile inner = outer.getNestedJarFile(outer.getJarEntry("inner.jar"))) {
			JarEntry entry = inner.getJarEntry("content");
			assertThat(StreamUtils.copyToByteArray(inner.getInputStream(entry)))
				.isEqualTo("bob's content, which alice never signed".getBytes(StandardCharsets.UTF_8));
			assertThatIllegalStateException().isThrownBy(entry::getCodeSigners)
				.withMessage("Content mismatch when reading security info for entry 'content' (content check)");
		}
	}

	/**
	 * Build a jar whose streamed entries and central directory entries describe different
	 * content. The streamed entries of {@code streamed} are followed by a header that is
	 * not a local file header, which stops a {@link java.util.jar.JarInputStream} before
	 * the entries of {@code listed}, which only the central directory points at.
	 * @param streamed the jar seen by a stream over the result
	 * @param listed the jar seen through the central directory of the result
	 * @return the forged jar
	 * @throws IOException on IO error
	 */
	private File forge(File streamed, File listed) throws IOException {
		byte[] streamedBytes = Files.readAllBytes(streamed.toPath());
		byte[] listedBytes = Files.readAllBytes(listed.toPath());
		ByteArrayOutputStream forged = new ByteArrayOutputStream();
		forged.write(streamedBytes, 0, centralDirectoryOffset(streamedBytes));
		forged.write(new byte[] { 'P', 'K', 1, 2 });
		int relocation = forged.size();
		int listedCentralDirectoryOffset = centralDirectoryOffset(listedBytes);
		forged.write(listedBytes, 0, listedCentralDirectoryOffset);
		int centralDirectoryOffset = forged.size();
		ByteArrayOutputStream centralDirectory = new ByteArrayOutputStream();
		int records = 0;
		int offset = listedCentralDirectoryOffset;
		while (offset < listedBytes.length && intAt(listedBytes, offset) == CENTRAL_DIRECTORY_SIGNATURE) {
			int length = CENTRAL_DIRECTORY_HEADER_SIZE + shortAt(listedBytes, offset + 28)
					+ shortAt(listedBytes, offset + 30) + shortAt(listedBytes, offset + 32);
			byte[] record = Arrays.copyOfRange(listedBytes, offset, offset + length);
			setInt(record, 42, intAt(record, 42) + relocation);
			centralDirectory.write(record);
			offset += length;
			records++;
		}
		centralDirectory.writeTo(forged);
		byte[] endRecord = new byte[END_RECORD_SIZE];
		setInt(endRecord, 0, END_RECORD_SIGNATURE);
		setShort(endRecord, 8, records);
		setShort(endRecord, 10, records);
		setInt(endRecord, 12, centralDirectory.size());
		setInt(endRecord, 16, centralDirectoryOffset);
		forged.write(endRecord);
		File file = new File(this.tempDir, "forged.jar");
		Files.write(file.toPath(), forged.toByteArray());
		return file;
	}

	private int centralDirectoryOffset(byte[] jar) {
		for (int offset = jar.length - END_RECORD_SIZE; offset >= 0; offset--) {
			if (intAt(jar, offset) == END_RECORD_SIGNATURE) {
				return intAt(jar, offset + 16);
			}
		}
		throw new IllegalStateException("No end record found");
	}

	private File nest(File inner) throws IOException {
		File outer = new File(this.tempDir, "outer.jar");
		byte[] bytes = Files.readAllBytes(inner.toPath());
		CRC32 crc = new CRC32();
		crc.update(bytes);
		try (JarOutputStream out = new JarOutputStream(new FileOutputStream(outer), createManifest())) {
			java.util.jar.JarEntry entry = new java.util.jar.JarEntry("inner.jar");
			entry.setMethod(ZipEntry.STORED);
			entry.setSize(bytes.length);
			entry.setCompressedSize(bytes.length);
			entry.setCrc(crc.getValue());
			out.putNextEntry(entry);
			out.write(bytes);
			out.closeEntry();
		}
		return outer;
	}

	private File createKeyStore(String... aliases) throws Exception {
		File keyStore = new File(this.tempDir, "signers.p12");
		for (String alias : aliases) {
			runTool("keytool", "-genkeypair", "-keystore", keyStore.getAbsolutePath(), "-storetype", "PKCS12",
					"-storepass", PASSWORD, "-alias", alias, "-dname", "CN=" + alias, "-keyalg", "RSA", "-keysize",
					"2048", "-validity", "1");
		}
		return keyStore;
	}

	private File createSignedJar(File keyStore, String alias, String content) throws Exception {
		File unsigned = new File(this.tempDir, alias + "-unsigned.jar");
		try (JarOutputStream out = new JarOutputStream(new FileOutputStream(unsigned), createManifest())) {
			out.putNextEntry(new java.util.jar.JarEntry("content"));
			out.write(content.getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		File signed = new File(this.tempDir, alias + ".jar");
		runTool("jarsigner", "-keystore", keyStore.getAbsolutePath(), "-storepass", PASSWORD, "-signedjar",
				signed.getAbsolutePath(), unsigned.getAbsolutePath(), alias);
		return signed;
	}

	private Manifest createManifest() {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
		return manifest;
	}

	private void runTool(String name, String... arguments) throws Exception {
		List<String> command = new ArrayList<>();
		command.add(new File(new File(System.getProperty("java.home"), "bin"), name).getAbsolutePath());
		command.addAll(Arrays.asList(arguments));
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(FileCopyUtils.copyToByteArray(process.getInputStream()), StandardCharsets.UTF_8);
		assertThat(process.waitFor()).as(output).isZero();
	}

	private static int intAt(byte[] bytes, int offset) {
		return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
	}

	private static int shortAt(byte[] bytes, int offset) {
		return ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
	}

	private static void setInt(byte[] bytes, int offset, int value) {
		ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value);
	}

	private static void setShort(byte[] bytes, int offset, int value) {
		ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) value);
	}

}
