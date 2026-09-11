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

package org.springframework.boot.actuate.autoconfigure.cloudfoundry.reactive;

import java.lang.reflect.Method;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.boot.actuate.endpoint.web.EndpointMapping;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CloudFoundryWebFluxEndpointHandlerMapping}.
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
class CloudFoundryWebFluxEndpointHandlerMappingTests {

	@Test
	@SuppressWarnings("unchecked")
	void unmappedPathUnderCloudFoundryNamespaceIsClaimedByThisArtifact() {
		CloudFoundryWebFluxEndpointHandlerMapping mapping = createMapping();
		MockServerWebExchange exchange = MockServerWebExchange
			.from(MockServerHttpRequest.get("/cfApplication/unknown"));
		Object handler = mapping.getHandler(exchange).block();
		assertThat(handler).isNotNull();
		Object bean = ((HandlerMethod) handler).getBean();
		assertThat(bean.getClass().getEnclosingClass()).isEqualTo(CloudFoundryWebFluxEndpointHandlerMapping.class);
		Method handle = ReflectionUtils.findMethod(bean.getClass(), "handle", ServerWebExchange.class);
		ReflectionUtils.makeAccessible(handle);
		((Mono<Void>) ReflectionUtils.invokeMethod(handle, bean, exchange)).block();
		assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void pathOutsideCloudFoundryNamespaceIsNotClaimed() {
		CloudFoundryWebFluxEndpointHandlerMapping mapping = createMapping();
		MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/somewhere/else"));
		assertThat(mapping.getHandler(exchange).block()).isNull();
	}

	private CloudFoundryWebFluxEndpointHandlerMapping createMapping() {
		StaticApplicationContext context = new StaticApplicationContext();
		context.refresh();
		CloudFoundryWebFluxEndpointHandlerMapping mapping = new CloudFoundryWebFluxEndpointHandlerMapping(
				new EndpointMapping("/cfApplication"), Collections.emptyList(), EndpointMediaTypes.DEFAULT, null, null,
				Collections.emptyList());
		mapping.setApplicationContext(context);
		mapping.afterPropertiesSet();
		return mapping;
	}

}
