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

package org.springframework.boot.devtools.remote.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests for {@link HttpHeaderAccessManager}.
 *
 * @author Rob Winch
 * @author Phillip Webb
 */
class HttpHeaderAccessManagerTests {

	private static final String HEADER = "X-AUTH_TOKEN";

	private static final String SECRET = "password";

	private MockHttpServletRequest request;

	private ServerHttpRequest serverRequest;

	private HttpHeaderAccessManager manager;

	@BeforeEach
	void setup() {
		this.request = new MockHttpServletRequest("GET", "/");
		this.serverRequest = new ServletServerHttpRequest(this.request);
		this.manager = new HttpHeaderAccessManager(HEADER, SECRET);
	}

	@Test
	void headerNameMustNotBeNull() {
		assertThatIllegalArgumentException().isThrownBy(() -> new HttpHeaderAccessManager(null, SECRET))
			.withMessageContaining("HeaderName must not be empty");
	}

	@Test
	void headerNameMustNotBeEmpty() {
		assertThatIllegalArgumentException().isThrownBy(() -> new HttpHeaderAccessManager("", SECRET))
			.withMessageContaining("HeaderName must not be empty");
	}

	@Test
	void expectedSecretMustNotBeNull() {
		assertThatIllegalArgumentException().isThrownBy(() -> new HttpHeaderAccessManager(HEADER, null))
			.withMessageContaining("ExpectedSecret must not be empty");
	}

	@Test
	void expectedSecretMustNotBeEmpty() {
		assertThatIllegalArgumentException().isThrownBy(() -> new HttpHeaderAccessManager(HEADER, ""))
			.withMessageContaining("ExpectedSecret must not be empty");
	}

	@Test
	void allowsMatching() {
		this.request.addHeader(HEADER, SECRET);
		assertThat(this.manager.isAllowed(this.serverRequest)).isTrue();
	}

	@Test
	void disallowsWrongSecret() {
		this.request.addHeader(HEADER, "wrong");
		assertThat(this.manager.isAllowed(this.serverRequest)).isFalse();
	}

	@Test
	void disallowsNoSecret() {
		assertThat(this.manager.isAllowed(this.serverRequest)).isFalse();
	}

	@Test
	void disallowsWrongHeader() {
		this.request.addHeader("X-WRONG", SECRET);
		assertThat(this.manager.isAllowed(this.serverRequest)).isFalse();
	}

	@Test
	void allowsMatchingNonAsciiSecret() {
		String secret = "pässwörd";
		this.request.addHeader(HEADER, secret);
		assertThat(new HttpHeaderAccessManager(HEADER, secret).isAllowed(this.serverRequest)).isTrue();
	}

	@Test
	void comparesSecretsWithoutShortCircuiting() throws IOException {
		assertThat(invocationsIn("isAllowed")).contains("java/security/MessageDigest.isEqual")
			.doesNotContain("java/lang/String.equals");
	}

	private Set<String> invocationsIn(String methodName) throws IOException {
		Set<String> invocations = new LinkedHashSet<>();
		try (InputStream classFile = HttpHeaderAccessManager.class
			.getResourceAsStream("HttpHeaderAccessManager.class")) {
			new ClassReader(classFile).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {

				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
						String[] exceptions) {
					if (!methodName.equals(name)) {
						return null;
					}
					return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {

						@Override
						public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
								boolean isInterface) {
							invocations.add(owner + "." + name);
						}

					};
				}

			}, ClassReader.SKIP_FRAMES);
		}
		return invocations;
	}

}
