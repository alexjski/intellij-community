// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.junit4;

import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import junit.framework.TestCase;
import org.junit.Test;
import org.junit.runner.Request;
import org.junit.runner.RunWith;
import org.junit.runner.notification.RunNotifier;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

public class JUnit4TestRunnerUtilTest extends TestCase {

  /**
   * {@link JUnit4TestRunnerUtil} class loaded with a custom loader.
   *
   * <p>This allows running this test from Intellij which will load {@link JUnit4TestRunnerUtil} to run the test itself. Custom loader lets
   * us test the local version {@link JUnit4TestRunnerUtil} as opposed to the one shipped with Intellij running the test.
   */
  private static final Class<?> RUNNER_CLASS = customLoadedRunner();
  private static final List<String> ourInvokedTests = new ArrayList<String>();

  @Override
  protected void setUp() {
    ourInvokedTests.clear();
  }

  @RunWith(TestParameterInjector.class)
  public static class ExampleTest {

    @Test
    public void oneParam(@TestParameter boolean value) {
      ourInvokedTests.add(String.format("oneParam[%s]", value));
    }

    @Test
    public void twoParams(@TestParameter boolean p1, @TestParameter({"1", "2"}) int p2) {
      ourInvokedTests.add(String.format("twoParams[%s,%d]", p1, p2));
    }

    @Test
    public void overloaded(@TestParameter({"value"}) String value) {
      ourInvokedTests.add(String.format("overloaded[%s]", value));
    }

    @Test
    public void overloaded() {
      ourInvokedTests.add("overloaded");
    }
  }

  @RunWith(TestParameterInjector.class)
  public static class ClassLevelParamTest {

    @TestParameter boolean classLevel;

    @Test
    public void noParams() {
      ourInvokedTests.add(String.format("noParams[%s]", classLevel));
    }

    @Test
    public void oneParam(@TestParameter({"1", "2"}) int value) {
      ourInvokedTests.add(String.format("oneParam[%s,%d]", classLevel, value));
    }
  }

  public void testRunsTestMethodWithSpecificParameterValue() throws Exception {
    Request request = buildRequestForTestClass(ExampleTest.class, "oneParam", "[false]");
    request.getRunner().run(new RunNotifier());
    assertEquals(ImmutableList.of("oneParam[false]"), ourInvokedTests);
  }

  public void testRunsTestMethodWithAllParameterValues() throws Exception {
    Request request = buildRequestForTestClass(ExampleTest.class, "oneParam", null);
    request.getRunner().run(new RunNotifier());
    assertEquals(ImmutableList.of("oneParam[false]", "oneParam[true]"), ourInvokedTests);
  }

  public void testRunsTestMethodWithMultipleParametersWithSpecifiedValues() throws Exception {
    Request request = buildRequestForTestClass(ExampleTest.class, "twoParams", "[true,2]");
    request.getRunner().run(new RunNotifier());
    assertEquals(ImmutableList.of("twoParams[true,2]"), ourInvokedTests);
  }

  public void testRunsTestMethodWithMultipleParametersWithAllParameterCombinations() throws Exception {
    Request request = buildRequestForTestClass(ExampleTest.class, "twoParams", null);
    request.getRunner().run(new RunNotifier());
    assertEquals(ImmutableList.of("twoParams[false,1]", "twoParams[false,2]", "twoParams[true,1]", "twoParams[true,2]"), ourInvokedTests);
  }

  public void testRunsNoTestForNonExistentParametersCombination() throws Exception {
    Request request = buildRequestForTestClass(ExampleTest.class, "twoParams", "[true,9]");
    request.getRunner().run(new RunNotifier());
    assertEquals(ImmutableList.of(), ourInvokedTests);
  }

  public void testRunsAllVariantsOfOverloadedTest() throws Exception {
    Request request = buildRequestForTestClass(ExampleTest.class, "overloaded", null);
    request.getRunner().run(new RunNotifier());
    assertEquals(ImmutableList.of("overloaded", "overloaded[value]"), ourInvokedTests);
  }

  public void testRunsOnlyParameterizedVersionOfOverloadedTestWhenParameterIsSpecified() throws Exception {
    Request request = buildRequestForTestClass(ExampleTest.class, "overloaded", "[value]");
    request.getRunner().run(new RunNotifier());
    assertEquals(ImmutableList.of("overloaded[value]"), ourInvokedTests);
  }

  public void testRunsTestParameterizedAtClassLevel() throws Exception {
    Request request = buildRequestForTestClass(ClassLevelParamTest.class, "noParams", null);
    request.getRunner().run(new RunNotifier());
    assertEquals(ImmutableList.of("noParams[false]", "noParams[true]"), ourInvokedTests);
  }

  public void testRunsTestWithClassLevelParameterWithSpecifiedValue() throws Exception {
    Request request = buildRequestForTestClass(ClassLevelParamTest.class, "noParams", "[true]");
    request.getRunner().run(new RunNotifier());
    assertEquals(ImmutableList.of("noParams[true]"), ourInvokedTests);
  }

  public void testRunsTestWithClassLevelAndMethodParameter() throws Exception {
    Request request = buildRequestForTestClass(ClassLevelParamTest.class, "oneParam", null);
    request.getRunner().run(new RunNotifier());
    assertEquals(ImmutableList.of("oneParam[false,1]", "oneParam[false,2]", "oneParam[true,1]", "oneParam[true,2]"), ourInvokedTests);
  }

  private static Request buildRequestForTestClass(Class<?> testClass, String methodName, @Nullable String name)
    throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    String className = JUnit4TestRunnerUtilTest.class.getCanonicalName() + "$" + testClass.getSimpleName();
    return (Request)RUNNER_CLASS.getMethod("buildRequest", String[].class, String.class, boolean.class).invoke(
      null, new String[]{className + "," + methodName}, name, /* notForked= */ true);
  }

  /**
   * Creates a {@link JUnit4TestRunnerUtil} class loaded with a custom loader pointed to local build outputs.
   */
  private static Class<?> customLoadedRunner() {
    URL testLocation = JUnit4TestRunnerUtilTest.class.getProtectionDomain().getCodeSource().getLocation();
    URL runnerLocation;
    try {
      runnerLocation = new URL(testLocation.getProtocol() + ":// " + testLocation.getPath().replace("/test/", "/production/"));
    }
    catch (MalformedURLException e) {
      throw new IllegalArgumentException(e);
    }

    //noinspection IOResourceOpenedButNotSafelyClosed
    ClassLoader customLoader = new URLClassLoader(new URL[]{runnerLocation}) {
      @Override
      public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(JUnit4TestRunnerUtil.class.getPackage().getName())) {
          Class<?> clazz = findLoadedClass(name);
          if (clazz != null) {
            return clazz;
          }
          try {
            return findClass(name);
          }
          catch (ClassNotFoundException e) {
            // Ignored
          }
        }
        return super.loadClass(name, resolve);
      }
    };

    try {
      return customLoader.loadClass(JUnit4TestRunnerUtil.class.getCanonicalName());
    }
    catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
