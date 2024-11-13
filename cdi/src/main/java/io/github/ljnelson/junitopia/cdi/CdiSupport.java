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
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import java.util.function.Supplier;

import jakarta.enterprise.context.Dependent;

import jakarta.enterprise.context.spi.AlterableContext;

import jakarta.enterprise.event.Observes;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;

import jakarta.enterprise.inject.literal.InjectLiteral;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import jakarta.enterprise.inject.spi.Annotated;
import jakarta.enterprise.inject.spi.AnnotatedConstructor;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.AfterBeanDiscovery;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;
import jakarta.enterprise.inject.spi.ProcessBeanAttributes;
import jakarta.enterprise.inject.spi.WithAnnotations;

import jakarta.enterprise.inject.spi.configurator.AnnotatedConstructorConfigurator;

import jakarta.enterprise.util.AnnotationLiteral;

import jakarta.inject.Inject;
import jakarta.inject.Qualifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestReporter;

import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExecutableInvoker;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor.Invocation;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.TestInstanceFactory;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;
import org.junit.jupiter.api.extension.TestInstantiationException;

import static java.lang.System.getLogger;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.TRACE;
import static java.lang.System.Logger.Level.WARNING;

import static java.lang.annotation.ElementType.PARAMETER;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_METHOD;

public class CdiSupport extends CdiArgumentResolver
  implements BeforeTestExecutionCallback, InvocationInterceptor, TestInstanceFactory {

  private static final Logger LOGGER = getLogger(CdiSupport.class.getName());

  // Must create a new one each time or undefined behavior results
  private final Supplier<? extends SeContainerInitializer> s;

  public CdiSupport() {
    this(SeContainerInitializer::newInstance);
  }

  public CdiSupport(final Supplier<? extends SeContainerInitializer> s) {
    super(null);
    this.s = s == null ? SeContainerInitializer::newInstance : s;
  }

  // Testcontainers use case:
  // Testcontainers happen in beforeAll

  // JUnit for Reasons creates the test instance even when running static beforeAll callbacks in PER_CLASS lifecycle

  // For Reasons we don't want the CDI container to start until after the beforeAll callbacks (maybe they'll be producer
  // fields)

  @Override // TestInstanceFactory
  public final Object createTestInstance(final TestInstanceFactoryContext factoryContext,
                                         final ExtensionContext extensionContext) {
    final Class<?> testClass = factoryContext.getTestClass();

    // First see if there's a CDI installation already set up. If so, ask it to get a contextual reference. This will
    // use the @Inject-annotated constructor if one exists. If one does not, that's a problem, so let it fail.
    final Instance<Object> i = this.i(extensionContext);
    if (i != null) {
      try {
        return i.select(testClass, qs(testClass, bm(i))).get();
      } catch (final IllegalStateException | UnsatisfiedResolutionException e) {
        if (LOGGER.isLoggable(DEBUG)) {
          LOGGER.log(DEBUG, e.getMessage(), e);
        }
      }
    }

    // Otherwise proceed through the constructors, ordered from most number of parameters to least, then by declaration
    // order, and invoke each one, stopping once a constructor returns successfully. JUnit's native argument resolution
    // will be used.
    final List<Constructor<?>> cs = Arrays.asList(testClass.getDeclaredConstructors());
    Collections.sort(cs, Comparator.<Constructor<?>>comparingInt(Constructor::getParameterCount).reversed());
    final ExecutableInvoker invoker = extensionContext.getExecutableInvoker();
    final Object outerInstance = factoryContext.getOuterInstance().orElse(null);
    TestInstantiationException t = null;
    for (final Constructor<?> c : cs) {
      if (LOGGER.isLoggable(DEBUG)) {
        LOGGER.log(DEBUG, "Invoking " + c);
      }
      try {
        return invoker.invoke(c, outerInstance);
      } catch (final ParameterResolutionException e) {
        if (LOGGER.isLoggable(DEBUG)) {
          LOGGER.log(DEBUG, e.getMessage(), e);
        }
        if (t == null) {
          t = new TestInstantiationException(e.getMessage(), e);
        } else {
          t.addSuppressed(e);
        }
      }
    }
    throw t;
  }

  @Override // BeforeTestExecutionCallback
  @SuppressWarnings("unchecked")
  public final void beforeTestExecution(final ExtensionContext ec) throws Exception {
    final Store store = findStoreForSeContainer(ec);
    Instance<Object> i = (Instance<Object>)store.get(Instance.class);
    if (i == null) {
      store.getOrComputeIfAbsent("SeContainerCloser", n -> (CloseableResource)() -> {
          final Object sec = store.get(Instance.class);
          if (sec instanceof SeContainer) {
            if (LOGGER.isLoggable(DEBUG)) {
              LOGGER.log(DEBUG, "Closing " + sec);
            }
            ((SeContainer)sec).close();
          }
        });
      i = (Instance<Object>)store.getOrComputeIfAbsent(Instance.class,
                                                       x -> {
                                                         final SeContainerInitializer sci = computeSeContainerInitializerIfAbsent(ec, store);
                                                         if (LOGGER.isLoggable(TRACE)) {
                                                           LOGGER.log(TRACE, "Creating SeContainer using " + sci);
                                                         }
                                                         final SeContainer sec =
                                                           computeSeContainerInitializerIfAbsent(ec, store).initialize();
                                                         if (LOGGER.isLoggable(TRACE)) {
                                                           LOGGER.log(TRACE, "Created SeContainer: " + sec);
                                                         }
                                                         return sec;
                                                       });
      if (LOGGER.isLoggable(DEBUG)) {
        LOGGER.log(DEBUG, "Using new Instance<Object>: " + i);
      }
    } else {
      if (LOGGER.isLoggable(DEBUG)) {
        LOGGER.log(DEBUG, "Preexisting Instance<Object> found: " + i);
      }
    }
  }

  private final SeContainerInitializer computeSeContainerInitializerIfAbsent(final ExtensionContext ec) {
    return computeSeContainerInitializerIfAbsent(ec, findStoreForSeContainer(ec));
  }

  private final SeContainerInitializer computeSeContainerInitializerIfAbsent(final ExtensionContext ec, final Store store) {
    return
      store.getOrComputeIfAbsent(SeContainerInitializer.class,
                                 x -> createSeContainerInitializer(ec),
                                 SeContainerInitializer.class);
  }

  private final SeContainerInitializer createSeContainerInitializer(final ExtensionContext extensionContext) {
    if (LOGGER.isLoggable(TRACE)) {
      LOGGER.log(TRACE, "Creating SeContainerInitializer");
    }
    final Class<?> testClass = extensionContext.getRequiredTestClass();
    final AlterableContext testContext = new TestContext(extensionContext.getStore(NAMESPACE));

    // If the lifecycle is PER_CLASS:
    // * instance is created by JUnit (!)
    // * beforeAll (static methods)
    // * beforeEach (instance methods)
    //
    // If the lifecycle is PER_METHOD:
    // * beforeAll (static methods)
    // * instance is created by JUnit
    // * beforeEach (instance methods)

    SeContainerInitializer sci = this.s.get();
    if (sci == null) {
      sci = SeContainerInitializer.newInstance();
    }
    return sci
      .addBeanClasses(testClass)
      .addExtensions(new Extension() {
          private final <T> void addInjectToSoleConstructorIfNeeded(@Observes
                                                                    @WithAnnotations(Test.class)
                                                                    final ProcessAnnotatedType<T> event) {
            final AnnotatedType<T> t = event.getAnnotatedType();
            if (t.getJavaClass() == testClass) {
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
              if (t.getJavaClass() == testClass) {
                for (final Annotation annotation : t.getAnnotations()) {
                  final Class<? extends Annotation> annotationType = annotation.annotationType();
                  if (bm.isScope(annotationType) || bm.isNormalScope(annotationType)) {
                    // The user, or a portable extension, explicitly placed a scope annotation on the test class. This
                    // may or may not be the scope registered in the BeanAttributes, but that doesn't matter: someone
                    // intended to specify a scope, one way or another. Leave it alone.
                    return;
                  }
                }
                if (event.getBeanAttributes().getScope() == Dependent.class) {
                  // CDI or some portable extension set the scope to Dependent; take this as an indication that the
                  // scope was defaulted. Force the default scope here to be TestScoped instead.
                  event.configureBeanAttributes().scope(TestScoped.class);
                }
              }
            }
          }
          private final void addTestContextAndPlatformBeans(@Observes
                                                            final AfterBeanDiscovery event,
                                                            final BeanManager bm) {
            event.addContext(testContext);
            // Provide support for, e.g.:
            //
            // @Inject
            // @Default
            // ExtensionContext extensionContext;
            event.addBean()
              .addTransitiveTypeClosure(extensionContext.getClass())
              .scope(TestScoped.class)
              .createWith(cc -> extensionContext);
            // Provide support for, e.g.:
            //
            // @Inject
            // @Default
            // TestInfo testInfo;
            event.addBean()
              .types(TestInfo.class, Object.class)
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
              .read(bm.createBeanAttributes(event.getAnnotatedType(testClass, null)))
              .scope(Dependent.class)
              .addQualifier(Original.Literal.INSTANCE)
              .createWith(cc -> extensionContext.getRequiredTestInstance());
          }
        });
  }

  @Override // CdiArgumentResolver
  public final boolean supportsParameter(final ParameterContext parameterContext,
                                         final ExtensionContext extensionContext) {
    return
      SeContainerInitializer.class == parameterContext.getParameter().getType() &&
      extensionContext.getTestInstance().isPresent() ||
      super.supportsParameter(parameterContext, extensionContext);
  }

  @Override
  public final Object resolveParameter(final ParameterContext parameterContext,
                                       final ExtensionContext extensionContext) {
    return
      SeContainerInitializer.class == parameterContext.getParameter().getType() ?
      computeSeContainerInitializerIfAbsent(extensionContext) :
      super.resolveParameter(parameterContext, extensionContext);
  }

  @Override // InvocationInterceptor
  public final void interceptTestMethod(final Invocation<Void> invocation,
                                        final ReflectiveInvocationContext<Method> invocationContext,
                                        final ExtensionContext extensionContext)
    throws Throwable {
    Instance<Object> i;
    try {
      i = i(extensionContext);
    } catch (final IllegalStateException e) {
      // Likely something like CDI.current() failed.
      if (LOGGER.isLoggable(DEBUG)) {
        LOGGER.log(DEBUG, e.getMessage(), e);
      }
      i = null;
    }
    if (i == null) {
      if (LOGGER.isLoggable(WARNING)) {
        LOGGER.log(WARNING, "No Instance<Object> found");
      }
    } else {
      final Class<?> c = invocationContext.getTargetClass();
      final Annotation[] qs = qs(c, bm(i));
      final Instance<?> i2 = i.select(c, qs);
      if (i2.isUnsatisfied()) {
        if (LOGGER.isLoggable(WARNING)) {
          LOGGER.log(WARNING, "No contextual reference found for " +
                     c +
                     " with qualifiers " +
                     Arrays.asList(qs));
        }
      } else if (i2.isAmbiguous()) {
        if (LOGGER.isLoggable(WARNING)) {
          LOGGER.log(WARNING, "Multiple unresolvable contextual references found for " +
                     c +
                     " with qualifiers " +
                     Arrays.asList(qs));
        }
      } else {
        final Method m = invocationContext.getExecutable();
        if (m.trySetAccessible()) {
          final Object testReference = i2.get();
          if (LOGGER.isLoggable(DEBUG)) {
            LOGGER.log(DEBUG,
                       "Using contextual reference (" +
                       testReference +
                       "; class: " +
                       testReference.getClass().getName() +
                       ") to invoke test method (" +
                       m +
                       ")");
          }
          m.invoke(testReference, invocationContext.getArguments().toArray(Object[]::new));
          invocation.skip();
          return; // EXIT POINT
        } else {
          if (LOGGER.isLoggable(WARNING)) {
            LOGGER.log(WARNING, m + " could not be made accessible");
          }
        }
      }
    }
    invocation.proceed();
  }

  private static final Store findStoreForSeContainer(final ExtensionContext ec) {
    if (ec.getTestInstanceLifecycle().orElse(PER_METHOD) == PER_METHOD &&
        "per_class".equalsIgnoreCase(ec.getConfigurationParameter(SeContainer.class.getName() +
                                                                  ".lifecycle")
                                     .orElse("per_method"))) {
      if (LOGGER.isLoggable(INFO)) {
        LOGGER.log(INFO, "Using class-level store for SeContainer in a test with TestInstance#PER_METHOD lifecycle");
      }
      return ec.getParent().orElse(ec).getStore(NAMESPACE);
    }
    return ec.getStore(NAMESPACE);
  }

  @Qualifier
  @Retention(RUNTIME)
  @Target(PARAMETER)
  public static @interface Original {

    public static final class Literal extends AnnotationLiteral<Original> implements Original {

      private static final long serialVersionUID = 1L;

      public static final Literal INSTANCE = new Literal();

      private Literal() {
        super();
      }

    }

  }

}
