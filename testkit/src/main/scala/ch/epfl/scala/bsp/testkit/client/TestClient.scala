package ch.epfl.scala.bsp.testkit.client

import java.io.File
import java.util.concurrent.CompletableFuture

import ch.epfl.scala.bsp.testkit.client.mock.{MockCommunications, MockSession}
import ch.epfl.scala.bsp4j._

import scala.collection.convert.ImplicitConversions.`collection asJava`
import scala.collection.mutable
import scala.compat.java8.FutureConverters._
import scala.concurrent.Await
import scala.concurrent.duration.{Duration, DurationInt}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Try}

case class TestClient(
    workspacePath: String,
    compilerOutputDir: String,
    session: MockSession,
    timeoutDuration: Duration = 30.seconds
) {
  private val server = session.connection.server

  private val unitTests: Map[TestClient.ClientUnitTest.Value, () => Unit] = Map(
    TestClient.ClientUnitTest.ResolveProjectTest -> resolveProject,
    TestClient.ClientUnitTest.TargetCapabilities -> targetCapabilities,
    TestClient.ClientUnitTest.CompileSuccessfully -> targetsCompileSuccessfully,
    TestClient.ClientUnitTest.CompileUnsuccessfully -> targetsCompileUnsuccessfully,
    TestClient.ClientUnitTest.RunSuccessfully -> targetsRunSuccessfully,
    TestClient.ClientUnitTest.RunUnsuccessfully -> targetsRunUnsuccessfully,
    TestClient.ClientUnitTest.TestSuccessfully -> targetsTestSuccessfully,
    TestClient.ClientUnitTest.TestUnsuccessfully -> targetsTestUnsuccessfully,
    TestClient.ClientUnitTest.CleanCacheSuccessfully -> cleanCacheSuccessfully,
    TestClient.ClientUnitTest.CleanCacheUnsuccessfully -> cleanCacheUnsuccessfully,
  )

  private def await[T](future: CompletableFuture[T]): T = {
    Await.result(future.toScala, timeoutDuration)
  }

  private def testSessionInitialization(): Unit = {
    val initializeBuildResult: InitializeBuildResult = await(
      server.buildInitialize(session.initializeBuildParams)
    )

    val bspVersion = Try(initializeBuildResult.getBspVersion)
    assert(
      bspVersion.isSuccess,
      s"Bsp version must be a number, got ${initializeBuildResult.getBspVersion}"
    )
    server.onBuildInitialized()
  }

  private def testShutdown(): Unit = {
    await(server.buildShutdown())
    val failedRequest =
      Await.ready(server.workspaceBuildTargets().toScala, timeoutDuration).value.get
    assert(failedRequest.isFailure, "Server is still accepting requests after shutdown")
  }

  private def wrapTest(body: () => Unit): Unit = {
    testSessionInitialization()
    body()
    testShutdown()
  }

  def testMultipleUnitTests(tests: List[TestClient.ClientUnitTest.Value]): Unit = {
    testSessionInitialization()
    tests.map(unitTests.get).foreach(test => test.foreach(_()))
    testShutdown()
  }

  private def testIfSuccessful[T](value: CompletableFuture[T]): T = {
    val result = Await.ready(value.toScala, timeoutDuration).value.get
    assert(result.isSuccess, "Failed to compile targets that are compilable")
    result.get
  }

  private def testIfFailure[T](value: CompletableFuture[T]): Unit = {
    val compileResult = Await.ready(value.toScala, timeoutDuration).value.get
    assert(compileResult.isFailure, "Compiled successfully supposedly uncompilable targets")
  }

  private def resolveProject(): Unit = {
    val languages = new mutable.HashSet[String]()
    val targets = await(server.workspaceBuildTargets()).getTargets.asScala

    targets
      .map(target => {
        languages.addAll(target.getLanguageIds)
      })
      .toList

    val targetsId = targets.map(_.getId).asJava

    await(server.buildTargetSources(new SourcesParams(targetsId)))
    await(server.buildTargetDependencySources(new DependencySourcesParams(targetsId)))
    await(server.buildTargetResources(new ResourcesParams(targetsId)))
  }

  def testResolveProject(): Unit = wrapTest(resolveProject)

  private def targetCapabilities(): Unit = {
    val targets = await(server.workspaceBuildTargets()).getTargets.asScala

    val (compilableTargets, uncompilableTargets) =
      targets.partition(_.getCapabilities.getCanCompile)
    val (runnableTargets, unrunnableTargets) = targets.partition(_.getCapabilities.getCanRun)
    val (testableTargets, untestableTargets) = targets.partition(_.getCapabilities.getCanTest)

    testIfSuccessful(
      server.buildTargetCompile(new CompileParams(compilableTargets.map(_.getId).asJava))
    )
    testIfFailure(
      server.buildTargetCompile(new CompileParams(uncompilableTargets.map(_.getId).asJava))
    )

    runnableTargets.foreach(
      target => testIfSuccessful(server.buildTargetRun(new RunParams(target.getId)))
    )
    unrunnableTargets.foreach(
      target => testIfFailure(server.buildTargetRun(new RunParams(target.getId)))
    )

    testIfSuccessful(server.buildTargetTest(new TestParams(testableTargets.map(_.getId).asJava)))
    testIfFailure(server.buildTargetTest(new TestParams(untestableTargets.map(_.getId).asJava)))
  }

  def testTargetCapabilities(): Unit = wrapTest(targetCapabilities)

  private def compileTarget(targets: mutable.Buffer[BuildTarget]) =
    testIfSuccessful(
      server.buildTargetCompile(
        new CompileParams(targets.filter(_.getCapabilities.getCanCompile).map(_.getId).asJava)
      )
    )

  private def targetsCompileSuccessfully(targets: mutable.Buffer[BuildTarget]): Unit = {
    val compileResult: CompileResult = compileTarget(targets)
    assert(compileResult.getStatusCode == StatusCode.OK, "Targets failed to compile!")
  }

  private def targetsCompileUnsuccessfully(): Unit = {
    val compileResult: CompileResult = compileTarget(
      await(server.workspaceBuildTargets()).getTargets.asScala
    )
    assert(
      compileResult.getStatusCode != StatusCode.OK,
      "Targets compiled successfully when they should have failed!"
    )
  }

  def testTargetsCompileUnsuccessfully(): Unit = wrapTest(() => targetsCompileUnsuccessfully())

  def testTargetsCompileSuccessfully(): Unit =
    wrapTest(
      targetsCompileSuccessfully
    )

  private def targetsCompileSuccessfully: () => Unit = { () =>
    {
      val targets = await(server.workspaceBuildTargets()).getTargets.asScala
      targetsCompileSuccessfully(targets)
    }
  }

  def testTargetsCompileSuccessfully(targets: java.util.List[BuildTarget]): Unit =
    wrapTest(() => targetsCompileSuccessfully(targets.asScala))

  private def testTargets(targets: mutable.Buffer[BuildTarget]) =
    testIfSuccessful(
      server.buildTargetTest(
        new TestParams(targets.filter(_.getCapabilities.getCanCompile).map(_.getId).asJava)
      )
    )

  private def targetsTestSuccessfully(targets: mutable.Buffer[BuildTarget]): Unit = {
    val testResult: TestResult = testTargets(targets)
    assert(testResult.getStatusCode == StatusCode.OK, "Tests to targets failed!")
  }

  private def targetsTestUnsuccessfully(): Unit = {
    val testResult: TestResult = testTargets(
      await(server.workspaceBuildTargets()).getTargets.asScala
    )
    assert(testResult.getStatusCode != StatusCode.OK, "Tests pass when they should have failed!")
  }

  def testTargetsTestUnsuccessfully(): Unit =
    wrapTest(() => targetsTestUnsuccessfully())

  def testTargetsTestSuccessfully(): Unit = {
    wrapTest(
      targetsTestSuccessfully
    )
  }

  private def targetsTestSuccessfully: () => Unit = { () =>
    {
      val targets = await(server.workspaceBuildTargets()).getTargets.asScala
      targetsTestSuccessfully(targets)
    }
  }

  def testTargetsTestSuccessfully(targets: java.util.List[BuildTarget]): Unit =
    wrapTest(() => targetsTestSuccessfully(targets.asScala))

  private def targetsRunSuccessfully(targets: mutable.Buffer[BuildTarget]): Unit = {
    val runResults = runTargets(targets)

    runResults.foreach(
      runResult =>
        assert(runResult.getStatusCode == StatusCode.OK, "Target did not run successfully!")
    )
  }

  private def targetsRunUnsuccessfully(): Unit = {
    val runResults = runTargets(await(server.workspaceBuildTargets()).getTargets.asScala)

    runResults.foreach(
      runResult =>
        assert(
          runResult.getStatusCode != StatusCode.OK,
          "Target ran successfully when it was supposed to fail!"
        )
    )
  }

  def testTargetsRunUnsuccessfully(): Unit = wrapTest(() => targetsRunUnsuccessfully())

  private def runTargets(targets: mutable.Buffer[BuildTarget]) =
    targets
      .filter(_.getCapabilities.getCanCompile)
      .map(
        target =>
          testIfSuccessful(
            server.buildTargetRun(
              new RunParams(target.getId)
            )
          )
      )

  def testTargetsRunSuccessfully(): Unit = {
    wrapTest(
      targetsRunSuccessfully
    )
  }

  private def targetsRunSuccessfully: () => Unit = { () =>
    {
      val targets = await(server.workspaceBuildTargets()).getTargets.asScala
      targetsRunSuccessfully(targets)
    }
  }

  def testTargetsRunSuccessfully(targets: java.util.List[BuildTarget]): Unit =
    wrapTest(() => targetsRunSuccessfully(targets.asScala))

  private def compareWorkspaceTargetsResults(
      expectedWorkspaceBuildTargetsResult: WorkspaceBuildTargetsResult
  ): WorkspaceBuildTargetsResult = {
    val workspaceBuildTargetsResult = await(server.workspaceBuildTargets())
    assert(
      workspaceBuildTargetsResult == expectedWorkspaceBuildTargetsResult,
      s"Workspace Build Targets did not match! Expected: $expectedWorkspaceBuildTargetsResult, got $workspaceBuildTargetsResult"
    )
    workspaceBuildTargetsResult
  }

  private def compareResults[T](
      getResults: java.util.List[BuildTargetIdentifier] => CompletableFuture[T],
      expectedResults: T,
      expectedWorkspaceBuildTargetsResult: WorkspaceBuildTargetsResult
  ): Unit = {
    val targets =
      compareWorkspaceTargetsResults(expectedWorkspaceBuildTargetsResult).getTargets.asScala
        .map(_.getId)
    val result = await(getResults(targets.asJava))
    assert(
      expectedResults == result,
      s"Expected $expectedResults, got $result"
    )
  }

  def testCompareWorkspaceTargetsResults(
      expectedWorkspaceBuildTargetsResult: WorkspaceBuildTargetsResult
  ): Unit = wrapTest(() => compareWorkspaceTargetsResults(expectedWorkspaceBuildTargetsResult))

  def testSourcesResults(
      expectedWorkspaceBuildTargetsResult: WorkspaceBuildTargetsResult,
      expectedWorkspaceSourcesResult: SourcesResult
  ): Unit =
    compareResults(
      targets => server.buildTargetSources(new SourcesParams(targets)),
      expectedWorkspaceSourcesResult,
      expectedWorkspaceBuildTargetsResult
    )

  def testDependencySourcesResults(
      expectedWorkspaceBuildTargetsResult: WorkspaceBuildTargetsResult,
      expectedWorkspaceDependencySourcesResult: DependencySourcesResult
  ): Unit =
    wrapTest(
      () =>
        compareResults(
          targets => server.buildTargetDependencySources(new DependencySourcesParams(targets)),
          expectedWorkspaceDependencySourcesResult,
          expectedWorkspaceBuildTargetsResult
        )
    )

  def testResourcesResults(
      expectedWorkspaceBuildTargetsResult: WorkspaceBuildTargetsResult,
      expectedResourcesResult: ResourcesResult
  ): Unit =
    wrapTest(
      () =>
        compareResults(
          targets => server.buildTargetResources(new ResourcesParams(targets)),
          expectedResourcesResult,
          expectedWorkspaceBuildTargetsResult
        )
    )

  def testInverseSourcesResults(
      textDocument: TextDocumentIdentifier,
      expectedInverseSourcesResult: InverseSourcesResult
  ): Unit =
    wrapTest(() => {
      val inverseSourcesResult =
        await(server.buildTargetInverseSources(new InverseSourcesParams(textDocument)))
      assert(
        inverseSourcesResult == expectedInverseSourcesResult,
        s"Expected $expectedInverseSourcesResult, got $inverseSourcesResult"
      )
    })

  private def cleanCacheSuccessfully(): Unit = {
    val cleanCacheResult: CleanCacheResult = cleanCache
    assert(cleanCacheResult.getCleaned, "Did not clean cache successfully")
  }

  def testCleanCacheSuccessfully(): Unit = wrapTest(() => cleanCacheSuccessfully())

  private def cleanCacheUnsuccessfully(): Unit = {
    val cleanCacheResult: CleanCacheResult = cleanCache
    assert(!cleanCacheResult.getCleaned, "Cleaned cache successfully, when it should have failed")
  }

  def testCleanCacheUnsuccessfully(): Unit = wrapTest(() => cleanCacheUnsuccessfully())

  private def cleanCache = {
    val targets = await(server.workspaceBuildTargets()).getTargets.asScala.map(_.getId).asJava
    val cleanCacheResult = await(server.buildTargetCleanCache(new CleanCacheParams(targets)))
    cleanCacheResult
  }
}

object TestClient {
  object ClientUnitTest extends Enumeration {
    val ResolveProjectTest, TargetCapabilities, CompileSuccessfully, RunSuccessfully,
        TestSuccessfully, CompileUnsuccessfully, RunUnsuccessfully, TestUnsuccessfully,
        CleanCacheSuccessfully, CleanCacheUnsuccessfully = Value
  }

  def testInitialStructure(workspacePath: String, compilerOutputDir: String): TestClient = {
    val workspace = new File(workspacePath)
    val compilerOutput = new File(workspace, compilerOutputDir)
    val (capabilities, connectionFiles) = MockCommunications.prepareSession(workspace)
    val failedConnections = connectionFiles.collect {
      case Failure(x) => x
    }
    assert(
      failedConnections.isEmpty,
      s"Found configuration files with errors: ${failedConnections.mkString("\n - ", "\n - ", "\n")}"
    )

    val session =
      MockCommunications.connect(workspace, compilerOutput, capabilities, connectionFiles.head.get)
    TestClient(workspacePath, compilerOutputDir, session)
  }

}
