/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

import akka.cli.cloudflow._

import org.scalatest._
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.time.Span

import ItSetup._

trait ItSpec
    extends AnyFreeSpec
    with Matchers
    with TryValues
    with Eventually
    with AppendedClues
    with ItMatchers
    with ItSetup {
  override implicit lazy val patienceConfig: PatienceConfig = PatienceConfig(resource.patience, resource.interval)
}

class ItGlobalSpec
    extends ItSpec
    with ItDeploySpec
    with ItBaseSpec
    with ItSecretsSpec
    with ItPvcSpec
    with ItCliConfigSpec
    with ItFrameworkConfigSpec
    with ItStreamletScaleSpec
    with BeforeAndAfterAll {
  override def beforeAll() = {
    Setup.init()
    logger.debug("Init done")
    assumeClusterExists()
    logger.debug("Cluster exists, going to cleanup")
    undeployApp(failIfNotPresent = false, namespace = appName)
    undeployApp(failIfNotPresent = false, namespace = "my-ns")
  }

  override def afterAll() = {
    undeployApp(namespace = appName)
  }
}

trait ItDeploySpec extends ItSpec {

  "PVC setup" - {
    "should create namespace to hold pvcs" in {
      withK8s { k8s =>
        noException should be thrownBy k8s.namespaces().create(resource.namespace)
      }
      withK8s { k8s =>
        noException should be thrownBy k8s.namespaces().create(resource.alternativeNamespace)
      }
    }
  }

  "The application" - {
    "should deploy" in {
      val res = cli.run(commands.Deploy(crFile = resource.cr, confs = Seq(resource.defaultConfiguration)))
      assertSuccess(res)
    }

    "should be listed" in {
      eventually {
        val res = cli.run(commands.List())
        assertSuccess(res).withClue("List command failed.")
        (res.get.summaries.size shouldBe 1).withClue("Expected only 1 app.")
        (res.get.summaries.head.name shouldBe appName).withClue("Wrong app name.")
      }
    }

    "should eventually be 'Running'" in {
      eventually {
        val res = cli.run(commands.Status(appName))
        assertSuccess(res).withClue("Status command failed.")
        (res.get.status.summary.name shouldBe appName).withClue("Wrong app name.")
        (res.get.status.status shouldBe "Running").withClue("App not running.")
      }
    }

    "should undeploy" in {
      val res = cli.run(commands.Undeploy(appName))
      assertSuccess(res).withClue("Application undeploy failed.")
      eventually {
        val res = cli.run(commands.List())
        assertSuccess(res).withClue("List command failed.")
        (res.get.summaries.size shouldBe 0).withClue("App still listed.")
        withK8s { k8s =>
          (k8s.pods().inNamespace(appName).list().getItems().isEmpty() shouldBe true)
            .withClue(s"Pods for app ($appName) still exist.")
        }
      }
    }

    "should deploy in a different namespace" in {
      val res =
        cli.run(
          commands.Deploy(crFile = resource.cr, namespace = Some("my-ns"), confs = Seq(resource.defaultConfiguration)))
      assertSuccess(res)
    }

    "should be listed in a different namespace" in {
      eventually {
        val res = cli.run(commands.List(namespace = Some("my-ns")))
        assertSuccess(res).withClue("List command failed.")
        (res.get.summaries.size shouldBe 1).withClue("Expected only 1 app.")
        (res.get.summaries.head.name shouldBe appName).withClue("Wrong app name.")
      }
    }

    "should eventually be 'Running' in a different namespace" in {
      eventually {
        val res = cli.run(commands.Status(appName, namespace = Some("my-ns")))
        assertSuccess(res).withClue("Status command failed.")
        (res.get.status.summary.name shouldBe appName).withClue("Wrong app name.")
        (res.get.status.status shouldBe "Running").withClue("App not running.")
      }
    }

    "should undeploy in a different namespace" in {
      val res = cli.run(commands.Undeploy(appName, namespace = Some("my-ns")))
      assertSuccess(res).withClue("Application undeploy failed.")
      eventually {
        val res = cli.run(commands.List(Some("my-ns")))
        assertSuccess(res).withClue("List command failed.")
        (res.get.summaries.size shouldBe 0).withClue("App still listed.")
        withK8s { k8s =>
          (k8s.pods().inNamespace("my-ns").list().getItems().isEmpty() shouldBe true)
            .withClue(s"Pods for app ($appName) still exist.")
        }
      }
    }

    "should re-deploy to continue testing" in {
      val deploy = cli.run(commands.Deploy(crFile = resource.cr, confs = Seq(resource.defaultConfiguration)))
      assertSuccess(deploy)
      eventually {
        val res = cli.run(commands.Status(appName))
        (res.get.status.status shouldBe "Running").withClue("App not running.")
      }
    }
  }
}

trait ItBaseSpec extends ItSpec {
  "should contain these processes:" - {
    def check(proc: String) = withRunningApp { _ should containStreamlet(proc) }
    "akka" in check("akka-process")
  }

  "should write counter data to these output logs:" - {
    def check(streamlet: String) = withRunningApp { status =>
      eventually {
        streamletPodLog(status, streamlet) should include("count:")
      }
    }
    "raw" in check("raw-egress")
    "akka" in check("akka-egress")
  }
}

trait ItSecretsSpec extends ItSpec {
  "should deploy a secret" in {
    withRunningApp { _ =>
      withK8s { k8s =>
        noException should be thrownBy loadResource(k8s, appName, resource.secret)
      }
    }
  }

  "should reconfigure akka streamlets to add a secret as mounting file" in {
    configureApp() { _ =>
      cli.run(commands.Configure(appName, confs = Seq(resource.updateMountingSecret, resource.defaultConfiguration)))
    }
  }

  "should find specific content in the secret mounted file in any akka streamlet" in {
    withRunningApp { status =>
      streamletPodFileContent(status, "akka-process", resource.secretFileMountPath) shouldBe resource.secretFilePassword
    }
  }

  "should delete the secret" in {
    withRunningApp { _ =>
      withK8s { k8s =>
        noException should be thrownBy k8s.secrets().inNamespace(appName).withName(resource.secretName).delete()
      }
    }
  }
}

trait ItPvcSpec extends ItSpec {
  "should deploy a pvc" in {
    withRunningApp { _ =>
      withK8s { k8s =>
        noException should be thrownBy loadResource(k8s, appName, resource.pvc)
      }
    }
  }

  "should reconfigure streamlets to add a pvc and mount it" in {
    configureApp() { _ =>
      cli.run(commands.Configure(appName, confs = Seq(resource.updateMountingPvc, resource.defaultConfiguration)))
    }
  }

  "should write specific content in any streamlet" in {
    withRunningApp { status =>
      withStreamletPod(status, "akka-process") {
        noException should be thrownBy _.file(resource.pvcResourceAkkaFileMountPath)
          .upload(resource.pvcResourceLocal.toPath())
      }
    }
  }

  "should delete the pvc" in {
    withRunningApp { _ =>
      withK8s { k8s =>
        noException should be thrownBy k8s
          .persistentVolumeClaims()
          .inNamespace(appName)
          .withName(resource.pvcName)
          .delete()
      }
    }
  }
}

trait ItCliConfigSpec extends ItSpec {
  def getResources() = withRunningApp { status =>
    withStreamletPod(status, "akka-process")(podResources)
  }

  "should reconfigure the pods of an akka application" in {
    note("get current cpu and memory for akka pods")
    val (oldCpu, oldMem) = getResources()

    note("reconfigure a single akka streamlet")
    configureApp() { _ =>
      cli.run(
        commands.Configure(appName, confs = Seq(resource.updateAkkaProcessResources, resource.defaultConfiguration)))
    }

    note("get new resource configuration")
    val (cpu, mem) = getResources()
    cpu shouldNot be(oldCpu)
    mem shouldNot be(oldMem)
    cpu shouldBe "550m"
    mem shouldBe "612M"
  }

  "should reconfigure the akka runtime of the complete application" in {
    val streamlets = Seq("akka-process", "akka-egress", "raw-egress")

    note("register current cpu and memory for all akka streamlets")
    val resourceConfigMap = withRunningApp { status =>
      streamlets.map { s =>
        val res = withStreamletPod(status, s)(podResources)
        (s, res)
      }.toMap
    }

    note("reconfigure akka kubernetes runtime")
    configureApp() { _ =>
      cli.run(
        commands.Configure(appName, confs = Seq(resource.updateAkkaRuntimeResources, resource.defaultConfiguration)))
    }

    note("get new resource configuration")
    withRunningApp { status =>
      streamlets.foreach { s =>
        eventually {
          val (cpu, mem) = withStreamletPod(status, s)(podResources)
          val (oldCpu, oldMem) = resourceConfigMap(s)
          cpu shouldNot be(oldCpu)
          mem shouldNot be(oldMem)
          cpu shouldBe "665m"
          mem shouldBe "655M"
        }
      }
    }
  }
}

trait ItFrameworkConfigSpec extends ItSpec {
  "should reconfigure an akka application" in {
    note("reconfigure akka-specific configuration")
    configureApp() { _ =>
      cli.run(commands.Configure(appName, confs = Seq(resource.updateAkkaConfiguration, resource.defaultConfiguration)))
    }

    note("verifying configuration update")
    eventually {
      withRunningApp { status =>
        streamletPodLog(status, "akka-config-output") should include("log-dead-letters=[15]")
      }
    }
  }
}

trait ItStreamletScaleSpec extends ItSpec {
  def podCount(streamletName: String) = withRunningApp { status => streamletPodCount(status, streamletName) }

  def noCorrection(scale: Int) = scale

  def coordinatorCorrection(scale: Int) = scale - 1

  def scaleCheck(streamletName: String, scalePodCorrection: Int => Int) = {
    note("determining initials scale factor")
    val initialPodCount = podCount(streamletName)
    val initialScale = scalePodCorrection(initialPodCount)

    note("issuing a +1 scale up")
    val newScale = initialScale + 1
    val expectedPodCount = initialPodCount + 1
    configureApp() { _ =>
      cli.run(commands.Scale(appName, scales = Map(streamletName -> newScale)))
    }
    eventually {
      podCount(streamletName) shouldBe expectedPodCount
    }

    note("issuing a scale back to the original value")
    configureApp() { _ =>
      cli.run(commands.Scale(appName, scales = Map(streamletName -> initialScale)))
    }
    eventually {
      podCount(streamletName) shouldBe initialPodCount
    }
  }

  "should scale an akka streamlet up and down" in {
    scaleCheck("akka-process", noCorrection)
  }
}
