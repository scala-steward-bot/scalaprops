import build._
import sbtrelease._
import ReleaseStateTransformations._
import sbtcrossproject.CrossProject

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / useSuperShell := false

val scalazVersion = SettingKey[String]("scalazVersion")

// avoid move files
val CustomCrossType = new sbtcrossproject.CrossType {
  override def projectDir(crossBase: File, projectType: String) =
    crossBase / projectType

  override def projectDir(crossBase: File, projectType: sbtcrossproject.Platform) = {
    val dir = projectType match {
      case JVMPlatform => "jvm"
      case JSPlatform => "js"
      case NativePlatform => "native"
    }
    crossBase / dir
  }

  def shared(projectBase: File, conf: String) =
    projectBase.getParentFile / "src" / conf / "scala"

  override def sharedSrcDir(projectBase: File, conf: String) =
    Some(shared(projectBase, conf))
}

def module(id: String): CrossProject =
  CrossProject(id, file(id))(JSPlatform, JVMPlatform, NativePlatform)
    .crossType(CustomCrossType)
    .settings(
      commonSettings,
      scalazVersion := "7.3.3",
      Seq(Compile, Test).map { c =>
        (c / unmanagedSourceDirectories) += {
          val base = baseDirectory.value.getParentFile / "src" / Defaults.nameForSrc(c.name)
          val dir = CrossVersion.partialVersion(scalaVersion.value) match {
            case Some((2, v)) if v <= 12 =>
              "scala-2.13-"
            case _ =>
              "scala-2.13+"
          }
          base / dir
        }
      },
      (console / initialCommands) += {
        "import scalaprops._;" + Seq(
          "Gen",
          "Cogen",
          "Rand"
        ).map(a => s"val $a = scalaprops.$a").mkString(";") // for tab completion
      }
    )
    .jsSettings(
      scalacOptions ++= {
        val a = (LocalRootProject / baseDirectory).value.toURI.toString
        val g = "https://raw.githubusercontent.com/scalaprops/scalaprops/" + tagOrHash.value
        if (isDottyJS.value) {
          Nil
        } else {
          Seq(s"-P:scalajs:mapSourceURI:$a->$g/")
        }
      }
    )
    .nativeSettings(
      scalapropsNativeSettings,
      crossScalaVersions -= Scala3_0,
      nativeGC := "immix"
    )

lazy val gen = module("gen")
  .settings(
    name := genName,
    description := "pure functional random value generator"
  )
  .platformsSettings(JSPlatform, NativePlatform)(
    (Compile / unmanagedSourceDirectories) += {
      baseDirectory.value.getParentFile / "js_native/src/main/scala/"
    }
  )
  .jvmSettings(
    Generator.settings
  )

lazy val core = module("core")
  .settings(
    name := coreName
  )
  .dependsOn(gen)

lazy val scalaz = module("scalaz")
  .settings(
    name := scalazName,
    libraryDependencies += "org.scalaz" %%% "scalaz-core" % scalazVersion.value withDottyCompat scalaVersion.value,
  )
  .dependsOn(
    core,
    scalaprops % "test"
  )

lazy val scalaprops = module(scalapropsName)
  .settings(
    name := scalapropsName
  )
  .dependsOn(
    core
  )
  .jvmSettings(
    libraryDependencies += "org.scala-sbt" % "test-interface" % "1.0"
  )
  .platformsSettings(JVMPlatform, NativePlatform)(
    (Compile / unmanagedSourceDirectories) += {
      baseDirectory.value.getParentFile / "jvm_native/src/main/scala/"
    }
  )
  .jsSettings(
    libraryDependencies += "org.scala-js" %% "scalajs-test-interface" % scalaJSVersion withDottyCompat scalaVersion.value
  )
  .nativeSettings(
    libraryDependencies += "org.scala-native" %%% "test-interface" % nativeVersion
  )

val tagName = Def.setting {
  s"v${if (releaseUseGlobalVersion.value) (ThisBuild / version).value
  else version.value}"
}
val tagOrHash = Def.setting {
  if (isSnapshot.value) gitHash() else tagName.value
}

def gitHash(): String =
  sys.process.Process("git rev-parse HEAD").lineStream_!.head

val unusedWarnings = Def.setting {
  PartialFunction
    .condOpt(CrossVersion.partialVersion(scalaVersion.value)) {
      case Some((2, v)) if v >= 12 =>
        Seq("-Ywarn-unused:imports,locals")
      case Some((2, 11)) =>
        Seq("-Ywarn-unused", "-Ywarn-unused-import")
    }
    .toList
    .flatten
}

val Scala211 = "2.11.12"
val Scala212 = "2.12.13"
val Scala3_0 = "3.0.0-RC1"

def stripPom(filter: scala.xml.Node => Boolean): Setting[_] =
  pomPostProcess := { node =>
    import scala.xml._
    import scala.xml.transform._
    val rule = new RewriteRule {
      override def transform(n: Node) =
        if (filter(n)) NodeSeq.Empty else n
    }
    new RuleTransformer(rule).transform(node)(0)
  }

val commonSettings = _root_.scalaprops.ScalapropsPlugin.autoImport.scalapropsCoreSettings ++ Seq(
  (Compile / unmanagedResources) += (LocalRootProject / baseDirectory).value / "LICENSE.txt",
  publishTo := sonatypePublishToBundle.value,
  commands += Command.command("SetScala3NightlyVersion") {
    s"""++ ${dottyLatestNightlyBuild.get}!""" :: _
  },
  (Test / sources) := {
    val old = (Test / sources).value
    if (isDotty.value) {
      val exclude = Set(
        "CaseClassExample",
        "CogenStateTest",
        "FreeTTest",
        "GenTest",
        "IdTest",
        "StreamTTest",
      ).map(_ + ".scala")
      old.filterNot(f => exclude(f.getName))
    } else {
      old
    }
  },
  scalaVersion := Scala212,
  crossScalaVersions := Scala212 :: Scala211 :: "2.13.4" :: Scala3_0 :: Nil,
  organization := "com.github.scalaprops",
  description := "property based testing library for Scala",
  fullResolvers ~= { _.filterNot(_.name == "jcenter") },
  homepage := Some(url("https://github.com/scalaprops/scalaprops")),
  licenses := Seq("MIT License" -> url("https://opensource.org/licenses/mit-license")),
  commands += Command.command("updateReadme")(UpdateReadme.updateReadmeTask),
  stripPom { node => node.label == "dependency" && (node \ "scope").text == "test" },
  (Compile / doc / scalacOptions) ++= {
    val tag = tagOrHash.value
    if (isDotty.value) {
      Seq(
        "-siteroot",
        baseDirectory.value.getAbsolutePath,
      )
    } else {
      Seq(
        "-sourcepath",
        (LocalRootProject / baseDirectory).value.getAbsolutePath,
        "-doc-source-url",
        s"https://github.com/scalaprops/scalaprops/tree/${tag}€{FILE_PATH}.scala"
      )
    }
  },
  pomExtra := (
    <developers>
      <developer>
        <id>xuwei-k</id>
        <name>Kenji Yoshida</name>
        <url>https://github.com/xuwei-k</url>
      </developer>
    </developers>
    <scm>
      <url>git@github.com:scalaprops/scalaprops.git</url>
      <connection>scm:git:git@github.com:scalaprops/scalaprops.git</connection>
      <tag>{tagOrHash.value}</tag>
    </scm>
  ),
  scalacOptions ++= Seq(
    "-deprecation",
    "-unchecked",
    "-language:existentials,higherKinds,implicitConversions",
  ),
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((0 | 3, _)) =>
        Seq(
          "-Ykind-projector",
        )
      case _ =>
        Seq(
          "-Xsource:3",
        )
    }
  },
  scalacOptions ++= PartialFunction
    .condOpt(CrossVersion.partialVersion(scalaVersion.value)) {
      case Some((2, v)) if v <= 12 =>
        Seq(
          "-Yno-adapted-args",
          "-Ypartial-unification",
          "-Xfuture"
        )
    }
    .toList
    .flatten,
  scalacOptions ++= unusedWarnings.value,
  releaseTagName := tagName.value,
  releaseCrossBuild := true,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    runTest,
    setReleaseVersion,
    commitReleaseVersion,
    UpdateReadme.updateReadmeProcess,
    tagRelease,
    ReleaseStep(
      action = { state =>
        val extracted = Project extract state
        extracted.runAggregated(PgpKeys.publishSigned in Global in extracted.get(thisProjectRef), state)
      },
      enableCrossBuild = true
    ),
    releaseStepCommandAndRemaining("+ rootNative/publishSigned"),
    releaseStepCommandAndRemaining("sonatypeBundleRelease"),
    setNextVersion,
    commitNextVersion,
    UpdateReadme.updateReadmeProcess,
    pushChanges
  ),
  credentials ++= PartialFunction
    .condOpt(sys.env.get("SONATYPE_USER") -> sys.env.get("SONATYPE_PASSWORD")) { case (Some(user), Some(pass)) =>
      Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", user, pass)
    }
    .toList
) ++ Seq(Compile, Test).flatMap(c => (c / console / scalacOptions) --= unusedWarnings.value)

lazy val notPublish = Seq(
  publishArtifact := false,
  publish := {},
  publishLocal := {},
  PgpKeys.publishSigned := {},
  PgpKeys.publishLocalSigned := {}
)

lazy val jvmProjects = Seq[ProjectReference](
  genJVM,
  coreJVM,
  scalapropsJVM,
  scalazJVM
)

lazy val jsProjects = Seq[ProjectReference](
  genJS,
  coreJS,
  scalapropsJS,
  scalazJS
)

lazy val nativeProjects = Seq[ProjectReference](
  genNative,
  coreNative,
  scalapropsNative,
  scalazNative
)

lazy val genJS = gen.js
lazy val genJVM = gen.jvm
lazy val genNative = gen.native
lazy val genRoot = project
  .aggregate(genJS, genJVM, genNative)
  .settings(
    commonSettings,
    notPublish
  )

lazy val coreJS = core.js
lazy val coreJVM = core.jvm
lazy val coreNative = core.native
lazy val coreRoot = project
  .aggregate(coreJS, coreJVM, genNative)
  .settings(
    commonSettings,
    notPublish
  )

lazy val scalazJS = scalaz.js
lazy val scalazJVM = scalaz.jvm
lazy val scalazNative = scalaz.native
lazy val scalazRoot = project
  .aggregate(scalazJS, scalazJVM, scalazNative)
  .settings(
    commonSettings,
    notPublish
  )

lazy val scalapropsJS = scalaprops.js
lazy val scalapropsJVM = scalaprops.jvm
lazy val scalapropsNative = scalaprops.native
lazy val scalapropsRoot = project
  .aggregate(scalapropsJS, scalapropsJVM, scalapropsNative)
  .settings(
    commonSettings,
    notPublish
  )

val root = Project("root", file("."))
  .settings(
    commonSettings,
    (
      coreJVM ::
        scalapropsJVM ::
        scalazJVM ::
        Nil
    ).map(p => libraryDependencies ++= (p / libraryDependencies).value)
  )
  .enablePlugins(
    ScalaUnidocPlugin
  )
  .settings(
    publish / skip := isDotty.value,
    name := allName,
    artifacts := Nil,
    ScalaUnidoc / unidoc / unidocProjectFilter := {
      (jsProjects ++ nativeProjects).foldLeft(inAnyProject)((acc, a) => acc -- inProjects(a))
    },
    packagedArtifacts := Map.empty,
    artifacts ++= Classpaths.artifactDefs(Seq(Compile / packageDoc)).value,
    packagedArtifacts ++= Classpaths.packaged(Seq(Compile / packageDoc)).value,
    description := "scalaprops unidoc",
    stripPom { _.label == "dependencies" },
    Sxr.settings1,
    Defaults.packageTaskSettings(
      (Compile / packageDoc),
      (Compile / unidoc).map { _.flatMap(Path.allSubpaths) }
    ),
    Sxr.settings2
  )
  .aggregate(
    jvmProjects ++ jsProjects: _* // ignore native
  )

lazy val rootJS = project
  .aggregate(jsProjects: _*)
  .settings(
    commonSettings,
    notPublish
  )
lazy val rootJVM = project
  .aggregate(jvmProjects: _*)
  .settings(
    commonSettings,
    notPublish
  )
lazy val rootNative = project
  .aggregate(nativeProjects: _*)
  .settings(
    commonSettings,
    notPublish
  )
