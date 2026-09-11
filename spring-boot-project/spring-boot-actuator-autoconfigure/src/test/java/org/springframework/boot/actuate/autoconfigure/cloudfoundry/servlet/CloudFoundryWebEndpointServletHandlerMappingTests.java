/*
 * Copyright 2012-2023 the original author or authors.
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

package org.springframework.boot.actuate.autoconfigure.cloudfoundry.servlet;

import java.lang.reflect.Method;
import java.util.Collections;

import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

import org.springframework.boot.actuate.endpoint.web.EndpointMapping;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.util.ServletRequestPathUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CloudFoundryWebEndpointServletHandlerMapping}.
 * <p>
 * These construct the mapping directly rather than through an application context so that
 * they can name the class that declares the catch-all handler. CVE-2026-22733 is fixed by
 * claiming the whole CloudFoundry namespace, and this artifact has to do that using only
 * members that the released spring-boot-actuator 2.7.18 already has: a consumer pins
 * spring-boot-actuator-autoconfigure on its own, so a catch-all that depends on a new
 * member of the superclass in the other artifact fails at mapping initialisation.
 *
 * @author Peter Streef
 */
class CloudFoundryWebEndpointServletHandlerMappingTests {

	@Test
	void unmappedPathUnderCloudFoundryNamespaceIsClaimedByThisArtifact() throws Exception {
		CloudFoundryWebEndpointServletHandlerMapping mapping = createMapping();
		HandlerExecutionChain chain = getHandler(mapping, "/cfApplication/unknown");
		assertThat(chain).isNotNull();
		Object handler = ((HandlerMethod) chain.getHandler()).getBean();
		assertThat(handler.getClass().getEnclosingClass())
			.isEqualTo(CloudFoundryWebEndpointServletHandlerMapping.class);
		MockHttpServletResponse response = new MockHttpServletResponse();
		Method handle = ReflectionUtils.findMethod(handler.getClass(), "handle", HttpServletResponse.class);
		ReflectionUtils.makeAccessible(handle);
		ReflectionUtils.invokeMethod(handle, handler, response);
		assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
	}

	@Test
	void pathOutsideCloudFoundryNamespaceIsNotClaimed() throws Exception {
		CloudFoundryWebEndpointServletHandlerMapping mapping = createMapping();
		assertThat(getHandler(mapping, "/somewhere/else")).isNull();
	}

	private CloudFoundryWebEndpointServletHandlerMapping createMapping() throws Exception {
		StaticWebApplicationContext context = new StaticWebApplicationContext();
		context.setServletContext(new MockServletContext());
		context.refresh();
		CloudFoundryWebEndpointServletHandlerMapping mapping = new CloudFoundryWebEndpointServletHandlerMapping(
				new EndpointMapping("/cfApplication"), Collections.emptyList(), EndpointMediaTypes.DEFAULT, null, null,
				Collections.emptyList());
		mapping.setApplicationContext(context);
		mapping.afterPropertiesSet();
		return mapping;
	}

	private HandlerExecutionChain getHandler(CloudFoundryWebEndpointServletHandlerMapping mapping, String path)
			throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		ServletRequestPathUtils.parseAndCache(request);
		return mapping.getHandler(request);
	}

}
