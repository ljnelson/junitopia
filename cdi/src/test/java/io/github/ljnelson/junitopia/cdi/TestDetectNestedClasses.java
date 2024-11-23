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

import jakarta.enterprise.event.Observes;

import jakarta.enterprise.inject.UnsatisfiedResolutionException;

import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TestDetectNestedClasses {

  private TestDetectNestedClasses() {
    super();
  }

  @Test
  void testNestedClassNotAddedWhenTestClassAddedAsBeanClass() {
    UnsatisfiedResolutionException expected = null;
    try (final SeContainer c = SeContainerInitializer.newInstance()
         .disableDiscovery()
         .addBeanClasses(this.getClass())
         .initialize()) {
      final MyBean b = c.select(MyBean.class).get();      
    } catch (final UnsatisfiedResolutionException e) {
      expected = e;
    }
    assertNotNull(expected);
  }

  @Test
  void testNestedClassAddedWhenTestClassAddedViaPortableExtension() {
    UnsatisfiedResolutionException expected = null;
    try (final SeContainer c = SeContainerInitializer.newInstance()
         .disableDiscovery()
         .addBeanClasses(WorkAroundMissingBeansXmlBug.class)
         .addExtensions(new Extension() {
             private final void addBeanClass(@Observes final BeforeBeanDiscovery e) {
               e.addAnnotatedType(TestDetectNestedClasses.this.getClass(), TestDetectNestedClasses.this.getClass().getName());
             }
           })
         .initialize()) {
      final MyBean b = c.select(MyBean.class).get();
    } catch (final UnsatisfiedResolutionException e) {
      expected = e;
    }
    assertNotNull(expected);
  }

  private static final class WorkAroundMissingBeansXmlBug {}
  
  @Dependent
  private static final class MyBean {

    @Inject
    private MyBean() {
      super();
    }
    
  }

}
