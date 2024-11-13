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

import java.lang.System.Logger;

import java.lang.reflect.Parameter;

import java.util.Set;

import java.util.function.Function;

import jakarta.enterprise.context.spi.CreationalContext;

import jakarta.enterprise.inject.Instance;

import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.InjectionPoint;

import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestReporter;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import static java.lang.System.getLogger;

import static java.lang.System.Logger.Level.DEBUG;

public class CdiArgumentResolver extends AbstractCdiExtension implements ParameterResolver {

  private static final Logger LOGGER = getLogger(CdiArgumentResolver.class.getName());
  
  private static final Set<Class<?>> SPECIAL_CLASSES = Set.of(RepetitionInfo.class, TestInfo.class, TestReporter.class);
  
  public CdiArgumentResolver() {
    super();
  }

  public CdiArgumentResolver(final Function<? super ExtensionContext, ? extends Instance<Object>> fallback) {
    super(fallback);
  }

  @Override
  public boolean supportsParameter(final ParameterContext parameterContext,
                                   final ExtensionContext extensionContext) {
    final Parameter p = parameterContext.getParameter();
    if (SPECIAL_CLASSES.contains(p.getType())) {
      // There is no technical restriction on this ParameterResolver implementation that prevents it from supporting the
      // parameter in question. But there cannot be two ParameterResolvers that claim to support the same parameter, and
      // it is impossible for one ParameterResolver to discover the existence of another. As of this writing, JUnit's
      // built-in ParameterResolver is always present and always handles the types in SPECIAL_TYPES, so we bow out.
      if (LOGGER.isLoggable(DEBUG)) {
        LOGGER.log(DEBUG, "Not supporing CDI typesafe resolution of parameter " +
                   p +
                   " because it is a built-in JUnit type");
      }
      return false;
    }
    BeanManager bm;
    try {
      bm = bm(extensionContext);
    } catch (final IllegalStateException e) {
      // Likely something like CDI.current() failed.
      if (LOGGER.isLoggable(DEBUG)) {
        LOGGER.log(DEBUG, e.getMessage(), e);
      }
      bm = null;
    }
    if (bm == null) {
      if (LOGGER.isLoggable(DEBUG)) {
        LOGGER.log(DEBUG, "No BeanManager present");
      }
      return false;
    }
    return
      bm.resolve(bm.getBeans(p.getParameterizedType(),
                             qs(p.getDeclaringExecutable(), parameterContext.getIndex(), bm))) != null;
  }

  @Override
  public Object resolveParameter(final ParameterContext parameterContext,
                                 final ExtensionContext extensionContext) {
    final BeanManager bm = bm(extensionContext);
    final InjectionPoint ip = ip(parameterContext.getParameter().getDeclaringExecutable(),
                                 parameterContext.getIndex(),
                                 bm);
    final CreationalContext<Object> cc = new CloseableCreationalContext<>(bm.createCreationalContext(null));
    extensionContext.getStore(NAMESPACE).put(cc, cc); // will auto-release when test is over
    return bm.getInjectableReference(ip, cc);
  }

}
