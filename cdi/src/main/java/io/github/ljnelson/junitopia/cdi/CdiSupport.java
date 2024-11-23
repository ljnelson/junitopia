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

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import java.util.function.Consumer;
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

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance.Lifecycle;
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
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.extension.TestInstanceFactory;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;
import org.junit.jupiter.api.extension.TestInstantiationException;

import static java.lang.System.getLogger;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.TRACE;
import static java.lang.System.Logger.Level.WARNING;

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
  public final void beforeTestExecution(final ExtensionContext methodLevelEc) throws Exception {
    // Enforce preconditions
    methodLevelEc.getRequiredTestClass();
    methodLevelEc.getRequiredTestInstance();

    final Store store = findStoreForSeContainer(methodLevelEc);
    Instance<Object> i = (Instance<Object>)store.get(Instance.class);
    if (i == null) {
      store.getOrComputeIfAbsent("SeContainerCloser", n -> new SeContainerCloser(() -> store.get(Instance.class)));
      i = (Instance<Object>)store.getOrComputeIfAbsent(Instance.class, __ -> newSeContainer(methodLevelEc, store));
      if (LOGGER.isLoggable(DEBUG)) {
        LOGGER.log(DEBUG, "Using new Instance<Object>: " + i);
      }
    } else {
      if (LOGGER.isLoggable(DEBUG)) {
        LOGGER.log(DEBUG, "Preexisting Instance<Object> found: " + i);
      }
    }
  }

  private final SeContainer newSeContainer(final ExtensionContext methodLevelEc, final Store store) {
    // Enforce preconditions
    methodLevelEc.getRequiredTestInstance();

    methodLevelExtensionContextSupplier(store).accept(methodLevelEc);
    final SeContainerInitializer sci = seContainerInitializer(methodLevelEc.getRequiredTestClass(), store);
    if (LOGGER.isLoggable(TRACE)) {
      LOGGER.log(TRACE, "Creating SeContainer using " + sci);
    }
    final SeContainer sec = sci.initialize();
    if (LOGGER.isLoggable(TRACE)) {
      LOGGER.log(TRACE, "Created SeContainer: " + sec);
    }
    return sec;
  }

  private final SeContainerInitializer seContainerInitializer(final ExtensionContext ec) {
    return seContainerInitializer(ec.getRequiredTestClass(), findStoreForSeContainer(ec));
  }

  private final SeContainerInitializer seContainerInitializer(final Class<?> testClass, final Store store) {
    return
      store.getOrComputeIfAbsent(SeContainerInitializer.class,
                                 __ -> newSeContainerInitializer(testClass, store),
                                 SeContainerInitializer.class);
  }

  private final SeContainerInitializer newSeContainerInitializer(final Class<?> testClass, final Store store) {
    return
      newSeContainerInitializer(testClass,
                                store,
                                methodLevelExtensionContextSupplier(store));
  }

  private final SeContainerInitializer newSeContainerInitializer(final Class<?> testClass,
                                                                 final Store store,
                                                                 final Supplier<? extends ExtensionContext> methodLevelEcs) {

    // If the lifecycle is PER_CLASS:
    // * instance is created by JUnit (!)
    // * beforeAll (static methods)
    // * beforeEach (instance methods)
    //
    // If the lifecycle is PER_METHOD:
    // * beforeAll (static methods)
    // * instance is created by JUnit
    // * beforeEach (instance methods)

    if (LOGGER.isLoggable(TRACE)) {
      LOGGER.log(TRACE, "Creating SeContainerInitializer");
    }
    SeContainerInitializer sci = this.s.get();
    return sci == null ? SeContainerInitializer.newInstance() : sci
      .addBeanClasses(testClass)
      .addExtensions(new JUnitPortableExtension(methodLevelEcs, store));
  }

  @Override // CdiArgumentResolver
  public final boolean supportsParameter(final ParameterContext parameterContext,
                                         final ExtensionContext extensionContext) {
    return
      SeContainerInitializer.class == parameterContext.getParameter().getType() ||
      super.supportsParameter(parameterContext, extensionContext);
  }

  @Override
  public final Object resolveParameter(final ParameterContext parameterContext,
                                       final ExtensionContext extensionContext) {
    if (SeContainerInitializer.class == parameterContext.getParameter().getType()) {
      return seContainerInitializer(extensionContext);
    }
    return super.resolveParameter(parameterContext, extensionContext);
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
      invocation.proceed();
      return;
    }

    if (i == null) {
      if (LOGGER.isLoggable(WARNING)) {
        LOGGER.log(WARNING, "No Instance<Object> found");
      }
      invocation.proceed();
      return;
    }

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
      invocation.proceed();
      return;
    }

    if (i2.isAmbiguous()) {
      if (LOGGER.isLoggable(WARNING)) {
        LOGGER.log(WARNING, "Multiple unresolvable contextual references found for " +
                   c +
                   " with qualifiers " +
                   Arrays.asList(qs));
      }
      invocation.proceed();
      return;
    }

    final Method m = invocationContext.getExecutable();
    if (!m.trySetAccessible()) {
      if (LOGGER.isLoggable(WARNING)) {
        LOGGER.log(WARNING, m + " could not be made accessible");
      }
      invocation.proceed();
      return;
    }

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
  }

  private static final MethodLevelExtensionContextSupplier methodLevelExtensionContextSupplier(final Store store) {
    return
      store.getOrComputeIfAbsent(MethodLevelExtensionContextSupplier.class,
                                 __ -> new MethodLevelExtensionContextSupplier(),
                                 MethodLevelExtensionContextSupplier.class);

  }

  private static final Store findStoreForSeContainer(final ExtensionContext ec) {
    // This method is called from resolveParameter, which can be called from almost anywhere.
    ec.getRequiredTestClass(); // enforce preconditions
    if (ec.getElement().orElse(null) instanceof Method) {
      ec.getRequiredTestInstance(); // enforce preconditions
      if (ec.getTestInstanceLifecycle().orElse(PER_METHOD) == Lifecycle.PER_METHOD &&
          "per_class".equalsIgnoreCase(ec.getConfigurationParameter(SeContainer.class.getName() +
                                                                    ".lifecycle").orElse(null))) {
        if (LOGGER.isLoggable(INFO)) {
          LOGGER.log(INFO, "Using class-level store for SeContainer in a test with TestInstance#PER_METHOD lifecycle");
        }
        return ec.getParent().orElse(ec).getStore(NAMESPACE);
      }
    }
    return ec.getStore(NAMESPACE);
  }

  private static final class MethodLevelExtensionContextSupplier
    implements CloseableResource, Consumer<ExtensionContext>, Supplier<ExtensionContext> {

    private static final Logger LOGGER = getLogger(MethodLevelExtensionContextSupplier.class.getName());

    private volatile ExtensionContext ec; // does this need to be a ThreadLocal?

    private MethodLevelExtensionContextSupplier() {
      super();
      if (LOGGER.isLoggable(TRACE)) {
        LOGGER.log(TRACE, "Creating");
      }
    }

    @Override // Consumer<ExtensionContext>
    public final void accept(final ExtensionContext ec) {
      final ExtensionContext oldEc = this.ec; // volatile read
      if (LOGGER.isLoggable(TRACE)) {
        LOGGER.log(TRACE, "Accepting: " + ec + "; this.ec: " + oldEc);
      }
      if (oldEc == null) {
        // Ensure the supplied ExtensionContext is "method level"
        ec.getRequiredTestInstance();
        ec.getRequiredTestMethod();
        this.ec = ec; // volatile write
      } else if (oldEc != ec) {
        if (LOGGER.isLoggable(TRACE)) {
          LOGGER.log(TRACE, "Replacing " + oldEc + " with " + ec);
        }
        this.ec = ec; // volatile write
      }
    }

    @Override // CloseableResource
    public final void close() {
      if (LOGGER.isLoggable(TRACE)) {
        LOGGER.log(TRACE, "Closing (" + this + "; this.ec: " + this.ec + ")");
      }
      this.ec = null; // volatile write
    }

    @Override // Supplier<ExtensionContext>
    public final ExtensionContext get() {
      final ExtensionContext ec = this.ec; // volatile read
      if (ec == null) {
        throw new IllegalStateException("this.ec: null");
      }
      return ec;
    }

  }

}
