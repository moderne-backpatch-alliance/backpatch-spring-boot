/*
 * Copyright 2012-2022 the original author or authors.
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

package org.springframework.boot.json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.springframework.util.StreamUtils;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.spy;

/**
 * Tests for {@link JacksonJsonParser}.
 *
 * @author Dave Syer
 * @author Stephane Nicoll
 */
class JacksonJsonParserTests extends AbstractJsonParserTests {

	@Override
	protected JsonParser getParser() {
		return new JacksonJsonParser();
	}

	@Test
	@SuppressWarnings("unchecked")
	void instanceWithSpecificObjectMapper() throws IOException {
		ObjectMapper objectMapper = spy(new ObjectMapper());
		new JacksonJsonParser(objectMapper).parseMap("{}");
		then(objectMapper).should().readValue(eq("{}"), any(TypeReference.class));
	}

	@Override
	@Test
	void listWithRepeatedOpenArray() throws IOException {
		String input = StreamUtils.copyToString(
				AbstractJsonParserTests.class.getResourceAsStream("repeated-open-array.txt"), StandardCharsets.UTF_8);
		assertThatExceptionOfType(JsonParseException.class).isThrownBy(() -> getParser().parseList(input))
			.havingCause()
			.withMessageContaining("exceeds the maximum allowed nesting depth");
	}

}
