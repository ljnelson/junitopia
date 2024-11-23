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

import jakarta.enterprise.context.Dependent;

import jakarta.enterprise.inject.se.SeContainerInitializer;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import org.junit.jupiter.api.extension.ExtendWith;

import org.junit.jupiter.api.parallel.Execution;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD;

import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD;

@Execution(SAME_THREAD) // default is SAME_THREAD
@ExtendWith(CdiSupport.class)
@TestInstance(PER_METHOD)
class TestUseCase04 {

  // Sequence with PER_METHOD, SAME_THREAD is:
  //
  // 1. @BeforeAll (creates SeContainerInitialzer, puts it in class-level extension context store.
  // 2. Test instance is constructed. It isn't stored anywhere. Well, rather, JUnit stores it.

  @BeforeAll
  private static void configure(final SeContainerInitializer sci) {
    sci.disableDiscovery()
      .addBeanClasses(TestUseCase04.class)
      .addBeanClasses(MyBean.class);
  }
  
  @Inject
  private MyBean bean;
  
  @Inject
  private TestUseCase04() {
    super();
  }

  @Test
  void testMyBeanInjected() {
    assertNotNull(this.bean);
  }

  @Test
  void testSelfInjection(final TestUseCase04 contextualReference) {
    assertNotNull(contextualReference);
  }

  @Dependent
  private static final class MyBean {

    @Inject
    private MyBean() {
      super();
    }
    
  }

}
