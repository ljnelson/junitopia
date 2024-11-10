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

import jakarta.enterprise.inject.se.SeContainer;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;

final class Cdi {

  private Cdi() {
    super();
  }

  static final BeanManager bm(final Instance<Object> i) {
    return
      i instanceof SeContainer ? ((SeContainer)i).getBeanManager() :
      i instanceof CDI ? ((CDI)i).getBeanManager() :
      i.select(BeanManager.class).get();
  }
  
}
