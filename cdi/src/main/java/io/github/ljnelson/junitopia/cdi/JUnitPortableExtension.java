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

import java.lang.annotation.Annotation;

import java.lang.reflect.Method;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import java.util.function.Supplier;

import jakarta.enterprise.context.Dependent;

import jakarta.enterprise.event.Observes;

import jakarta.enterprise.inject.literal.InjectLiteral;

import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.AnnotatedConstructor;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessBeanAttributes;
import jakarta.enterprise.inject.spi.WithAnnotations;

import jakarta.enterprise.inject.spi.configurator.AnnotatedConstructorConfigurator;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestReporter;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store;

import static java.lang.System.getLogger;

import static java.lang.System.Logger.Level.DEBUG;

class JUnitPortableExtension implements Extension {

  private static final Logger LOGGER = getLogger(JUnitPortableExtension.class.getName());

  private final Supplier<? extends ExtensionContext> methodLevelEcs;

  private final Store cdiInstanceStore;

  JUnitPortableExtension(final Supplier<? extends ExtensionContext> methodLevelEcs,
                         final Store cdiInstanceStore) {
    super();
    this.methodLevelEcs = Objects.requireNonNull(methodLevelEcs, "methodLevelEcs");
    this.cdiInstanceStore = Objects.requireNonNull(cdiInstanceStore, "cdiInstanceStore");
  }

  private final <T> void addInjectToSoleConstructorIfNeeded(@Observes
                                                            @WithAnnotations(Test.class)
                                                            final ProcessAnnotatedType<T> event) {
    final AnnotatedType<T> t = event.getAnnotatedType();
    if (t.getJavaClass() == methodLevelEcs.get().getRequiredTestClass()) {
      final Set<AnnotatedConstructor<T>> constructors = t.getConstructors();
      if (constructors.size() == 1) {
        final AnnotatedConstructor<T> c = constructors.iterator().next();
        if (!c.isAnnotationPresent(Inject.class)) {
          // now do it all over again (?!)
          final AnnotatedConstructorConfigurator<T> acc = event.configureAnnotatedType()
            .constructors()
            .iterator()
            .next();
          if (LOGGER.isLoggable(DEBUG)) {
            LOGGER.log(DEBUG, "Adding @Inject to " + acc.getAnnotated());
          }
          acc.add(InjectLiteral.INSTANCE);
        }
      }
    }
  }

  private final <T> void putTestClassInTestScopeUnlessOtherwiseSpecified(@Observes
                                                                         final ProcessBeanAttributes<T> event,
                                                                         final BeanManager bm) {
    final Annotated a = event.getAnnotated();
    if (a instanceof AnnotatedType) {
      @SuppressWarnings("unchecked")
      final AnnotatedType<T> t = (AnnotatedType<T>)a;
      if (t.getJavaClass() == methodLevelEcs.get().getRequiredTestClass()) {
        for (final Annotation annotation : t.getAnnotations()) {
          final Class<? extends Annotation> annotationType = annotation.annotationType();
          if (bm.isScope(annotationType) || bm.isNormalScope(annotationType)) {
            // The user, or a portable extension, explicitly placed a scope annotation on the test class. This may or
            // may not be the scope registered in the BeanAttributes, but that doesn't matter: someone intended to
            // specify a scope, one way or another. Leave it alone.
            return;
          }
        }
        if (event.getBeanAttributes().getScope() == Dependent.class) {
          // CDI or some portable extension set the scope to Dependent; take this as an indication that the scope was
          // defaulted. Force the default scope here to be TestScoped instead.
          event.configureBeanAttributes().scope(TestScoped.class);
        }
      }
    }
  }

  private final void addTestContextAndPlatformBeans(@Observes
                                                    final AfterBeanDiscovery event,
                                                    final BeanManager bm) {
    event.addContext(new TestContext(this.cdiInstanceStore));

    // Provide support for, e.g.:
    //
    // @Inject
    // @Default
    // ExtensionContext extensionContext;
    event.addBean()
      .types(ExtensionContext.class)
      .scope(TestScoped.class)
      .createWith(cc -> {
          final ExtensionContext ec = this.methodLevelEcs.get();
          assert ec.getElement().orElse(null) instanceof Method;
          return ec;
        });

    // Provide support for, e.g.:
    //
    // @Inject
    // @Default
    // TestInfo testInfo;
    event.addBean()
      .types(TestInfo.class)
      .scope(TestScoped.class)
      .produceWith(i -> {
          final ExtensionContext ec = i.select(ExtensionContext.class).get();
          return new TestInfo() {
            @Override
            public final String getDisplayName() {
              return ec.getDisplayName();
            }
            @Override
            public final Set<String> getTags() {
              return ec.getTags();
            }
            @Override
            public final Optional<Class<?>> getTestClass() {
              return ec.getTestClass();
            }
            @Override
            public final Optional<Method> getTestMethod() {
              return ec.getTestMethod();
            }
          };
        });
    // Provide support for, e.g.:
    //
    // @Inject
    // @Default
    // TestReporter testReporter;
    event.<TestReporter>addBean()
      .types(TestReporter.class, Object.class)
      .scope(TestScoped.class)
      .produceWith(i -> i.select(ExtensionContext.class).get()::publishReportEntry);
    // Provide support for:
    //
    // @Inject
    // @Original // <-- note
    // MyTestClass junitCreatedTestInstance;
    event.addBean()
      .read(bm.createBeanAttributes(event.getAnnotatedType(methodLevelEcs.get().getRequiredTestClass(), null)))
      .scope(Dependent.class)
      .addQualifier(Original.Literal.INSTANCE)
      .produceWith(i -> i.select(ExtensionContext.class).get().getRequiredTestInstance());
  }

}
