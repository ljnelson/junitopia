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

import jakarta.enterprise.inject.Produces;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestReporter;

import org.junit.jupiter.api.extension.ExtendWith;

import static io.github.ljnelson.junitopia.cdi.CdiSeContainerStarter.Original;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD;

@ExtendWith(CdiArgumentResolver.class) // provides test methods with arguments
@ExtendWith(CdiSeContainerStarter.class) // starts container
@ExtendWith(StringSetter.class) // stupid BeforeAllCallback stub that sets static String fields to "Test"
@ExtendWith(ClientProxyInvocationInterceptor.class) // forces all test methods to go through CDI invocation
@TestInstance(PER_METHOD)
class TestSpike {

  @Produces
  @Singleton
  private static String gorp; // note that it is null to start; StringSetter sets it

  private Integer fortyTwo;

  // Required for application scoped beans, which this will be.
  TestSpike() {
    super();
    assertEquals("Test", gorp); // if this class is extended with StringSetter
  }

  @BeforeEach
  void setFortyTwo(final TestInfo testInfo, final TestReporter testReporter) {
    this.fortyTwo = Integer.valueOf(42);
    System.out.println("*** testInfo.getClass(): " + testInfo.getClass());
  }

  @Inject
  TestSpike(final String gorp, @Original TestSpike original, TestInfo ti) {
    super();
    System.out.println("*** proxy inflated; gorp: " + gorp);
    System.out.println("*** this: " + this);
    System.out.println("*** original: " + original);
    System.out.println("*** this.fortyTwo: " + this.fortyTwo);
    System.out.println("*** original.fortyTwo: " + original.fortyTwo);
  }
  
  @Test
  void frob(TestSpike me,
            org.jboss.weld.environment.se.beans.ParametersFactory parametersFactory) {
    System.out.println("*** me.getClass(): " + me.getClass());
    System.out.println("*** me: " + me);
    System.out.println("*** this: " + this);
    System.out.println("*** frob: " + parametersFactory);
  }

  @Test
  void blat() {
    System.out.println("*** blat");
  }
  
}
