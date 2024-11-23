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

import jakarta.enterprise.inject.Instance;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD;

@ExtendWith(CdiSupport.class)
@TestInstance(PER_METHOD)
class TestUseCase02 {

  @Inject
  private TestUseCase02() {
    super();
  }

  @Test
  void testContainerInjected(final Instance<Object> container) {
    assertNotNull(container);
    final Object contextualReference = container.select(this.getClass()).get();
    assertTrue(this.getClass().isInstance(contextualReference));
    assertNotSame(this, contextualReference);
  }

  @Test
  void testTestInstanceInjected(final TestUseCase02 contextualReference) {
    assertNotNull(contextualReference);
    assertNotSame(this, contextualReference);
  }

}
