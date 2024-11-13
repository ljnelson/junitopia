/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2024 Laird Nelson.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.github.ljnelson.junitopia.cdi;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;

import jakarta.enterprise.inject.Produces;

import jakarta.enterprise.inject.se.SeContainerInitializer;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;

import org.junit.jupiter.api.extension.ExtendWith;

import static io.github.ljnelson.junitopia.cdi.CdiSupport.Original;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD;

@ExtendWith(CdiSupport.class)
@TestInstance(PER_METHOD)
class TestUseCase01 {
  
  @Produces
  private static int staticFortyTwo;

  @Inject
  private int i;

  @Inject
  private TestUseCase01 clientProxy;

  @Inject
  private TestUseCase01(final TestInfo testInfo) {
    super();
    assertNotNull(testInfo);
  }

  @BeforeAll
  private static final void setStaticFortyTwo() {
    staticFortyTwo = 42;
  }

  @BeforeEach
  private void disableDiscovery(final SeContainerInitializer sci) {
    sci.disableDiscovery();
  }

  @BeforeEach
  private final void seti() {
    this.i = 37; // note
  }

  @Test
  void testArgumentResolved(@Original final TestUseCase01 junitCreatedTestInstance,
                            final int fortyTwoSourcedFromStaticProducerField,
                            final Event<Integer> e) {
    assertNotSame(this, junitCreatedTestInstance);
    assertNotSame(this, this.clientProxy);
    assertTrue(this.clientProxy.getClass().getName().contains("Proxy")); // Weld, OpenWebBeans
    assertEquals(37, junitCreatedTestInstance.i); // note
    assertEquals(42, fortyTwoSourcedFromStaticProducerField);
    assertEquals(42, this.i); // note
    e.fire(fortyTwoSourcedFromStaticProducerField);
  }

  private static final void observeInt(@Observes final int fortyTwo) {
    assertEquals(42, fortyTwo);
  }

}
