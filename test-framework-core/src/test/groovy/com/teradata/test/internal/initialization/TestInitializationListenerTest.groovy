/*
 * Copyright 2013-2015, Teradata, Inc. All rights reserved.
 */
package com.teradata.test.internal.initialization

import com.google.common.collect.Iterables
import com.google.inject.Inject
import com.teradata.test.*
import com.teradata.test.context.State
import com.teradata.test.context.TestContext
import com.teradata.test.context.TestContextCloseCallback
import com.teradata.test.fulfillment.RequirementFulfiller
import org.testng.ITestClass
import org.testng.ITestContext
import org.testng.ITestNGMethod
import org.testng.ITestResult
import org.testng.internal.ConstructorOrMethod
import spock.lang.Specification

import java.lang.reflect.Method

import static com.google.common.collect.Iterables.getOnlyElement
import static com.teradata.test.context.ThreadLocalTestContextHolder.assertTestContextNotSet
import static com.teradata.test.context.ThreadLocalTestContextHolder.assertTestContextSet
import static com.teradata.test.internal.configuration.EmptyConfiguration.emptyConfiguration
import static com.teradata.test.internal.initialization.RequirementsExtender.resolveTestSpecificRequirements
import static java.util.Collections.emptySet

class TestInitializationListenerTest
        extends Specification
{
  static final A = 'A'
  static final B = 'B'
  static final C = 'C'

  static final SUITE_A_FULFILL = 'AFULFILL'
  static final TEST_B_FULFILL = 'BFULFILL'
  static final THROWING_TEST_C_FULFILL = 'CFULFILL'

  static final SUITE_A_CLEANUP = 'ACLEANUP'
  static final TEST_B_CLEANUP = 'BCLEANUP'
  static final THROWING_TEST_C_CLEANUP = 'CCLEANUP'

  static final SUITE_A_CALLBACK = 'ACALLBACK'
  static final TEST_B_CALLBACK = 'BCALLBACK'
  static final THROWING_TEST_C_CALLBACK = 'CCALLBACK'

  static final A_REQUIREMENT = new DummyRequirement(A)
  static final B_REQUIREMENT = new DummyRequirement(B)
  static final C_REQUIREMENT = new DummyRequirement(C)

  static List<Event> EVENTS

  def setup()
  {
    EVENTS = []
  }

  def 'positive flow'()
  {
    setup:
    def testClass = new TestClass()
    def listener = new TestInitializationListener([], [], [AFulfiller], [BFulfiller], emptyConfiguration())
    def iTestContext = getITestContext(successMethod, testClass)
    def iTestResult = getITestResult(successMethod, testClass)

    when:
    listener.onStart(iTestContext)
    assertTestContextNotSet()
    listener.onTestStart(iTestResult)
    assertTestContextSet()
    assert testClass.testContext != null
    listener.onTestSuccess(iTestResult)
    assertTestContextNotSet()
    listener.onFinish(iTestContext)

    then:
    EVENTS[0].name == SUITE_A_FULFILL
    EVENTS[1].name == TEST_B_FULFILL
    EVENTS[2].name == "beforeMethod";
    EVENTS[3].name == "afterMethod";
    EVENTS[4].name == TEST_B_CLEANUP
    EVENTS[5].name == TEST_B_CALLBACK
    EVENTS[6].name == SUITE_A_CLEANUP
    EVENTS[7].name == SUITE_A_CALLBACK

    EVENTS[1].object == EVENTS[4].object
    EVENTS[0].object == EVENTS[6].object
  }

  def 'failure during fulfillment'()
  {
    setup:
    def testClass = new TestClass()
    def listener = new TestInitializationListener([], [], [AFulfiller], [BFulfiller, CFulfiller], emptyConfiguration())
    def iTestContext = getITestContext(failMethod, testClass)
    def iTestResult = getITestResult(failMethod, testClass)

    when:
    listener.onStart(iTestContext)
    try {
      listener.onTestStart(iTestResult)
      assert false
    }
    catch (RuntimeException _) {
    }
    listener.onTestFailure(iTestResult)
    listener.onFinish(iTestContext)

    then:
    EVENTS[0].name == SUITE_A_FULFILL
    EVENTS[1].name == TEST_B_FULFILL
    EVENTS[2].name == THROWING_TEST_C_FULFILL
    EVENTS[3].name == THROWING_TEST_C_CALLBACK
    EVENTS[4].name == TEST_B_CLEANUP
    EVENTS[5].name == TEST_B_CALLBACK
    EVENTS[6].name == SUITE_A_CLEANUP
    EVENTS[7].name == SUITE_A_CALLBACK

    EVENTS[1].object == EVENTS[4].object
    EVENTS[0].object == EVENTS[6].object
  }

  def getITestContext(Method method, TestClass testClass)
  {
    ITestContext suiteContext = Mock(ITestContext)

    suiteContext.allTestMethods >> [getITestNGMethod(method, testClass)]

    return suiteContext
  }

  def getITestResult(Method method, TestClass testClass)
  {
    ITestResult testResult = Mock(ITestResult)
    testResult.method >> getITestNGMethod(method, testClass)
    ITestClass iTestClass = Mock()
    testResult.testClass >> iTestClass
    testResult.instance >> testResult.method.instance
    iTestClass.realClass >> TestClass
    return testResult
  }

  def getITestNGMethod(Method method, TestClass testClass)
  {
    ITestNGMethod testMethod = Mock(ITestNGMethod)
    testMethod.method >> method
    testMethod.instance >> testClass
    testMethod.getConstructorOrMethod() >> new ConstructorOrMethod(method)
    def requirements = resolveTestSpecificRequirements(testMethod);
    return new RequirementsAwareTestNGMethod(testMethod, getOnlyElement(requirements))
  }

  def getSuccessMethod()
  {
    return TestClass.getMethod('testMethodSuccess')
  }

  def getFailMethod()
  {
    return TestClass.getMethod('testMethodFailed')
  }

  @Requires(ARequirement)
  static class TestClass
          implements RequirementsProvider
  {
    @Inject
    TestContext testContext

    @BeforeTestWithContext
    public void beforeMethod()
    {
      EVENTS.add(new Event("beforeMethod", this));
    }

    @AfterTestWithContext
    public void afterMethod()
    {
      EVENTS.add(new Event("afterMethod", this));
    }

    public void testMethodSuccess()
    {
    }

    @Requires(CRequirement)
    public void testMethodFailed()
    {
    }

    @Override
    Requirement getRequirements()
    {
      return B_REQUIREMENT
    }
  }

  static class ARequirement
          implements RequirementsProvider
  {
    @Override
    public Requirement getRequirements()
    {
      return A_REQUIREMENT
    }
  }

  static class AFulfiller
          extends DummyFulfiller
  {
    @Inject
    AFulfiller(TestContext testContext)
    {
      super(A, SUITE_A_FULFILL, SUITE_A_CLEANUP, SUITE_A_CALLBACK, testContext)
    }
  }

  static class BFulfiller
          extends DummyFulfiller
  {
    @Inject
    BFulfiller(TestContext testContext)
    {
      super(B, TEST_B_FULFILL, TEST_B_CLEANUP, TEST_B_CALLBACK, testContext)
    }
  }

  static class CRequirement
          implements RequirementsProvider
  {
    @Override
    public Requirement getRequirements()
    {
      return C_REQUIREMENT
    }
  }

  static class CFulfiller
          extends DummyFulfiller
  {
    @Inject
    CFulfiller(TestContext testContext)
    {
      super(C, THROWING_TEST_C_FULFILL, THROWING_TEST_C_CLEANUP, THROWING_TEST_C_CALLBACK, testContext)
    }

    Set<State> fulfill(Set<Requirement> requirements)
    {
      super.fulfill(requirements)
      throw new RuntimeException()
    }
  }

  static class DummyFulfiller
          implements RequirementFulfiller
  {
    final String requirementName
    final String fulfillEventName
    final String cleanupEventName

    DummyFulfiller(
            String requirementName,
            String fulfillEventName,
            String cleanupEventName,
            String callbackEventName,
            TestContext testContext)
    {
      this.requirementName = requirementName
      this.fulfillEventName = fulfillEventName
      this.cleanupEventName = cleanupEventName

      registerCallback(testContext, callbackEventName)
    }

    void registerCallback(TestContext testContext, String callbackEventName)
    {
      testContext.registerCloseCallback(new TestContextCloseCallback() {
        @Override
        void testContextClosed(TestContext _)
        {
          EVENTS.add(new Event(callbackEventName, this))
        }
      })
    }

    Set<State> fulfill(Set<Requirement> requirements)
    {
      if (!requirements.contains(new DummyRequirement(requirementName))) {
        return [];
      }

      EVENTS.add(new Event(fulfillEventName, this))
      return []
    }

    void cleanup()
    {
      EVENTS.add(new Event(cleanupEventName, this))
    }
  }

  static class DummyRequirement
          implements Requirement
  {
    final String name

    DummyRequirement(String name)
    {
      this.name = name
    }

    boolean equals(o)
    {
      if (this.is(o)) {
        return true
      }
      if (getClass() != o.class) {
        return false
      }

      DummyRequirement that = (DummyRequirement) o

      if (name != that.name) {
        return false
      }

      return true
    }

    int hashCode()
    {
      return name.hashCode()
    }
  }

  static class Event
  {
    final String name
    final Object object

    Event(String name, Object object)
    {
      this.name = name
      this.object = object
    }
  }
}
