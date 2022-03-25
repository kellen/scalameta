import java.io._
import scala.xml.{Node => XmlNode, NodeSeq => XmlNodeSeq, _}
import scala.xml.transform.{RewriteRule, RuleTransformer}
import org.scalajs.sbtplugin.ScalaJSCrossVersion
import sbtcrossproject.CrossPlugin.autoImport.crossProject
import org.scalameta.build._
import org.scalameta.build.Versions._
import complete.DefaultParsers._
import munit.sbtmunit.BuildInfo.munitVersion

lazy val LanguageVersions = Seq(LatestScala213, LatestScala212, LatestScala211)
lazy val LanguageVersion = LanguageVersions.head
def customVersion = sys.props.get("scalameta.version")
def parseTagVersion: String = {
  import scala.sys.process._
  // drop `v` prefix
  "git describe --abbrev=0 --tags".!!.drop(1).trim
}
def localSnapshotVersion: String = s"$parseTagVersion-SNAPSHOT"
def isCI = System.getenv("CI") != null

// ==========================================
// Projects
// ==========================================

sharedSettings
name := {
  println(s"[info] Welcome to scalameta ${version.value}")
  "scalametaRoot"
}
nonPublishableSettings
crossScalaVersions := Nil
enablePlugins(ScalaUnidocPlugin)
addCommandAlias("benchAll", benchAll.command)
addCommandAlias("benchLSP", benchLSP.command)
addCommandAlias("benchQuick", benchQuick.command)
commands += Command.command("ci-windows") { s =>
  s"testsJVM/all:testOnly -- --exclude-tags=SkipWindows" ::
    s
}
commands += Command.command("mima") { s =>
  "mimaReportBinaryIssues" ::
    "doc" ::
    s
}
commands += Command.command("download-scala-library") { s =>
  val out = file("target/scala-library")
  IO.unzipURL(
    new URL(s"https://github.com/scala/scala/archive/v$LatestScala213.zip"),
    toDirectory = out,
    filter = s"scala-$LatestScala213/src/library/*"
  )
  s
}
commands += Command.command("save-expect") { s =>
  "semanticdbScalacPlugin/compile" ::
    "semanticdbIntegration/clean" ::
    "semanticdbIntegration/compile" ::
    "testsJVM/test:runMain scala.meta.tests.semanticdb.SaveExpectTest" :: s
}
commands += Command.command("save-manifest") { s =>
  "testsJVM/test:runMain scala.meta.tests.semanticdb.SaveManifestTest" :: s
}
def helloContributor(): Unit = println(
  """Welcome to the Scalameta build! You probably don't want to run `sbt test` since
    |that will take a long time to complete.  More likely, you want to run `testsJVM/test`.
    |For more productivity tips, please read CONTRIBUTING.md.
    |""".stripMargin
)
test := helloContributor()
test / aggregate := false
testOnly := helloContributor()
testOnly / aggregate := false
packagedArtifacts := Map.empty
ScalaUnidoc / unidoc / unidocProjectFilter := inAnyProject
console := (scalameta.jvm / Compile / console).value
Global / resolvers += "scala-integration" at
  "https://scala-ci.typesafe.com/artifactory/scala-integration/"

val commonJsSettings = Seq(
  crossScalaVersions := List(LatestScala213, LatestScala212),
  scalacOptions ++= {
    if (isSnapshot.value) Seq.empty
    else {
      val localDir = (ThisBuild / baseDirectory).value.toURI.toString
      val githubDir = "https://raw.githubusercontent.com/scalameta/scalameta"
      Seq(s"-P:scalajs:mapSourceURI:$localDir->$githubDir/v${version.value}/")
    }
  }
)

lazy val nativeSettings = Seq(
  crossScalaVersions := List(LatestScala213, LatestScala212),
  nativeConfig ~= {
    _.withMode(scalanative.build.Mode.releaseFast)
  }
)

/* ======================== SEMANTICDB ======================== */
lazy val semanticdbScalacCore = project
  .in(file("semanticdb/scalac/library"))
  .settings(
    publishableSettings,
    fullCrossVersionSettings,
    mimaPreviousArtifacts := Set.empty,
    moduleName := "semanticdb-scalac-core",
    description := "Library to generate SemanticDB from Scalac 2.x internal data structures",
    libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value
  )
  .dependsOn(scalameta.jvm)

lazy val semanticdbScalacPlugin = project
  .in(file("semanticdb/scalac/plugin"))
  .settings(
    moduleName := "semanticdb-scalac",
    description := "Scalac 2.x compiler plugin that generates SemanticDB on compile",
    publishableSettings,
    mimaPreviousArtifacts := Set.empty,
    mergeSettings,
    fullCrossVersionSettings,
    pomPostProcess := { node =>
      new RuleTransformer(new RewriteRule {
        private def isAbsorbedDependency(node: XmlNode): Boolean = {
          def isArtifactId(node: XmlNode, fn: String => Boolean) =
            node.label == "artifactId" && fn(node.text)
          node.label == "dependency" && node.child.exists(child =>
            isArtifactId(child, _.startsWith("semanticdb-scalac-core"))
          )
        }
        override def transform(node: XmlNode): XmlNodeSeq = node match {
          case e: Elem if isAbsorbedDependency(node) =>
            Comment("the dependency that was here has been absorbed via sbt-assembly")
          case _ => node
        }
      }).transform(node).head
    }
  )
  .dependsOn(semanticdbScalacCore)

lazy val metac = project
  .in(file("semanticdb/metac"))
  .settings(
    publishableSettings,
    fullCrossVersionSettings,
    crossScalaVersions := LanguageVersions,
    mimaPreviousArtifacts := Set.empty,
    description := "Scalac 2.x launcher that generates SemanticDB on compile",
    libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    mainClass := Some("scala.meta.cli.Metac")
  )
  .dependsOn(semanticdbScalacPlugin)

/* ======================== SCALAMETA ======================== */
lazy val common = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("scalameta/common"))
  .settings(
    publishableSettings,
    libraryDependencies += "com.lihaoyi" %%% "sourcecode" % "0.2.7",
    description := "Bag of private and public helpers used in scalameta APIs and implementations",
    enableMacros,
    protobufSettings
  )
  .jsSettings(
    commonJsSettings
  )
  .nativeSettings(nativeSettings)

lazy val trees = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("scalameta/trees"))
  .settings(
    publishableSettings,
    description := "Scalameta abstract syntax trees",
    // NOTE: uncomment this to update ast.md
    // scalacOptions += "-Xprint:typer",
    enableHardcoreMacros,
    libraryDependencies ++= List(
      // NOTE(olafur): use shaded version of fastparse 2.3.1 to avoid any
      // binary incompatibilites with the v2.3.1 version used by Scalameta.
      "org.scalameta" %%% "fastparse-v2" % "2.3.1"
    ),
    mergedModule({ base =>
      val scalameta = base / "scalameta"
      List(
        scalameta / "io",
        scalameta / "tokenizers",
        scalameta / "tokens",
        scalameta / "dialects",
        scalameta / "inputs"
      )
    })
  )
  .jsSettings(
    commonJsSettings
  )
  .nativeSettings(nativeSettings)
  .dependsOn(common) // NOTE: tokenizers needed for Tree.tokens when Tree.pos.isEmpty

lazy val parsers = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("scalameta/parsers"))
  .settings(
    publishableSettings,
    description := "Scalameta APIs for parsing and their baseline implementation",
    enableHardcoreMacros,
    mergedModule({ base =>
      List(
        base / "scalameta" / "quasiquotes",
        base / "scalameta" / "transversers"
      )
    })
  )
  .jsSettings(
    commonJsSettings,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
  )
  .nativeSettings(nativeSettings)
  .dependsOn(trees)

def mergedModule(projects: File => List[File]): List[Setting[_]] = List(
  Compile / unmanagedSourceDirectories ++= {
    val base = (ThisBuild / baseDirectory).value
    val isNative = platformDepsCrossVersion.value == ScalaNativeCrossVersion.binary
    val isJS = SettingKey[Boolean]("scalaJSUseMainModuleInitializer").?.value.isDefined
    val platform =
      if (isJS) "js"
      else if (isNative) "native"
      else "jvm"
    val scalaBinary = "scala-" + scalaBinaryVersion.value
    projects(base).flatMap { project =>
      List(
        project / "shared" / "src" / "main" / scalaBinary,
        project / "shared" / "src" / "main" / "scala",
        project / platform / "src" / "main" / "scala"
      )
    }
  }
)

lazy val scalameta = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("scalameta/scalameta"))
  .settings(
    publishableSettings,
    description := "Scalameta umbrella module that includes all public APIs",
    libraryDependencies ++= List(
      "org.scala-lang" % "scalap" % scalaVersion.value
    ),
    Compile / unmanagedSourceDirectories ++= {
      val base = (ThisBuild / baseDirectory).value
      List(
        base / "semanticdb" / "metap",
        base / "semanticdb" / "cli",
        base / "semanticdb" / "semanticdb"
      )
    },
    mergedModule({ base =>
      List(
        base / "scalameta" / "contrib"
      )
    })
  )
  .jvmSettings(
    Compile / unmanagedSourceDirectories ++= List(
      (ThisBuild / baseDirectory).value / "semanticdb" / "metacp",
      (ThisBuild / baseDirectory).value / "semanticdb" / "symtab"
    )
  )
  .jsSettings(
    commonJsSettings
  )
  .nativeSettings(nativeSettings)
  .dependsOn(parsers)

/* ======================== TESTS ======================== */
lazy val semanticdbIntegration = project
  .in(file("semanticdb/integration"))
  .settings(
    description := "Sources to compile to build SemanticDB for tests.",
    sharedSettings,
    nonPublishableSettings,
    // the sources in this project intentionally produce warnings to test the
    // diagnostics pipeline in semanticdb-scalac.
    scalacOptions -= "-Xfatal-warnings",
    scalacOptions += "-deprecation",
    scalacOptions ++= {
      val pluginJar = (semanticdbScalacPlugin / Compile / Keys.`package`).value.getAbsolutePath
      val warnUnusedImports =
        if (isScala213.value) "-Wunused:imports"
        else "-Ywarn-unused-import"
      Seq(
        s"-Xplugin:$pluginJar",
        s"-Xplugin-require:semanticdb",
        warnUnusedImports,
        s"-Yrangepos",
        s"-P:semanticdb:text:on", // include text to print occurrences in expect suite
        s"-P:semanticdb:failures:error", // fail fast during development.
        s"-P:semanticdb:exclude:Exclude.scala",
        s"-P:semanticdb:sourceroot:${(ThisBuild / baseDirectory).value}",
        s"-P:semanticdb:synthetics:on"
      )
    },
    Compile / javaHome := {
      // force javac to fork by setting javaHome to workaround https://github.com/sbt/zinc/issues/520
      val home = file(sys.props("java.home"))
      val actualHome =
        if (System.getProperty("java.version").startsWith("1.8")) home.getParentFile
        else home
      Some(actualHome)
    },
    javacOptions += "-parameters"
  )
  .dependsOn(semanticdbIntegrationMacros)

lazy val semanticdbIntegrationMacros = project
  .in(file("semanticdb/integration-macros"))
  .settings(
    sharedSettings,
    nonPublishableSettings,
    enableMacros
  )

lazy val testkit = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("scalameta/testkit"))
  .settings(
    publishableSettings,
    hasLargeIntegrationTests,
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % munitVersion,
      "com.lihaoyi" %%% "geny" % "0.7.0"
    ),
    testFrameworks := List(new TestFramework("munit.Framework")),
    description := "Testing utilities for scalameta APIs"
  )
  .dependsOn(scalameta)
  .jvmSettings(
    libraryDependencies ++= {
      if (isScala213.value) List("org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4")
      else Nil
    },
    libraryDependencies ++= List(
      // These are used to download and extract a corpus tar.gz
      "org.rauschig" % "jarchivelib" % "1.2.0",
      "org.scala-lang" % "scala-compiler" % scalaVersion.value % Test
    )
  )
  .jsSettings(commonJsSettings)
  .nativeSettings(nativeSettings)

lazy val tests = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("tests"))
  .configs(Slow, All)
  .settings(
    sharedSettings,
    testFrameworks := List(new TestFramework("munit.Framework")),
    nonPublishableSettings,
    description := "Tests for scalameta APIs",
    exposePaths("tests", Test)
  )
  .settings(testSettings: _*)
  .jvmSettings(
    libraryDependencies ++= List(
      "io.get-coursier" %% "coursier" % "2.0.0-RC5-6"
    ),
    // Needed because some tests rely on the --usejavacp option
    Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat
  )
  .jvmConfigure(
    _.dependsOn(metac, semanticdbIntegration)
  )
  .jsSettings(
    commonJsSettings,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.CommonJSModule) }
  )
  .nativeSettings(
    nativeSettings,
    nativeConfig ~= {
      _.withMode(scalanative.build.Mode.debug)
        .withLinkStubs(true)
    }
  )
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(scalameta, testkit)

lazy val testSettings: List[Def.SettingsDefinition] = List(
  Test / fullClasspath := {
    val semanticdbScalacJar =
      (semanticdbScalacPlugin / Compile / Keys.`package`).value.getAbsolutePath
    sys.props("sbt.paths.semanticdb-scalac-plugin.compile.jar") = semanticdbScalacJar
    (Test / fullClasspath).value
  },
  buildInfoKeys := Seq[BuildInfoKey](
    scalaVersion,
    "databaseSourcepath" ->
      (ThisBuild / baseDirectory).value.getAbsolutePath,
    "commonJVMClassDirectory" -> (common.jvm / Compile / classDirectory).value.getAbsolutePath,
    "databaseClasspath" -> (semanticdbIntegration / Compile / classDirectory).value.getAbsolutePath,
    "integrationSourceDirectories" ->
      (semanticdbIntegration / Compile / sourceDirectories).value
  ),
  buildInfoPackage := "scala.meta.tests",
  libraryDependencies ++= List(
    "org.scalameta" %%% "munit" % munitVersion
  ),
  Test / testOptions += Tests.Argument("--exclude-tags=Slow"),
  inConfig(Slow)(Defaults.testTasks),
  inConfig(All)(Defaults.testTasks),
  All / testOptions := Nil,
  Slow / testOptions -= Tests.Argument("--exclude-tags=Slow"),
  Slow / testOptions += Tests.Argument("--include-tags=Slow")
)

lazy val communitytest = project
  .in(file("community-test"))
  .settings(
    nonPublishableSettings,
    sharedSettings,
    libraryDependencies += "org.scalameta" %% "munit" % munitVersion,
    testFrameworks := List(new TestFramework("munit.Framework"))
  )
  .dependsOn(scalameta.jvm)

/* ======================== BENCHES ======================== */
lazy val bench = project
  .in(file("bench/suite"))
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(JmhPlugin)
  .settings(
    sharedSettings,
    nonPublishableSettings,
    libraryDependencies += "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    buildInfoKeys := Seq[BuildInfoKey](
      "sourceroot" -> (ThisBuild / baseDirectory).value
    ),
    buildInfoPackage := "scala.meta.internal.bench",
    Jmh / run := (Def.inputTaskDyn {
      val args = spaceDelimited("<arg>").parsed
      val semanticdbScalacJar =
        (semanticdbScalacPlugin / Compile / Keys.`package`).value.getAbsolutePath
      val buf = List.newBuilder[String]
      buf += "org.openjdk.jmh.Main"
      buf ++= args
      buf += "-p"
      buf += s"semanticdbScalacJar=$semanticdbScalacJar"
      (Jmh / runMain).toTask(s"  ${buf.result.mkString(" ")}")
    }).evaluated
  )
  .dependsOn(tests.jvm)

// ==========================================
// Settings
// ==========================================

lazy val requiresMacrosSetting = Def.settings(
  scalacOptions += {
    val base = file("scalameta/common/shared/src/main/scala")
    val filesWithWhiteboxMacros =
      List(
        "org/scalameta" -> List(
          "adt/Adt.scala",
          "adt/Liftables.scala",
          "data/data.scala",
          "data/Macros.scala",
          "explore/Macros.scala",
          "internal/DebugFinder.scala",
          "internal/FreeLocalFinder.scala",
          "internal/ImplTransformers.scala",
          "internal/MacroHelpers.scala",
          "invariants/package.scala",
          "package.scala"
        ),
        "scala/meta/internal" -> List(
          "classifiers/Macros.scala",
          "prettyprinters/ShowMacros.scala",
          "tokens/root.scala",
          "tokens/token.scala",
          "transversers/transformer.scala",
          "transversers/transverser.scala",
          "transversers/traverser.scala",
          "trees/ast.scala",
          "trees/branch.scala",
          "trees/Liftables.scala",
          "trees/NamerMacros.scala",
          "trees/quasiquote.scala",
          "trees/registry.scala",
          "trees/root.scala",
          "trees/TyperMacros.scala"
        )
      )

    val flat = filesWithWhiteboxMacros.flatMap { case (k, vs) =>
      vs.map(v => (base / k / v).lastModified)
    }

    "-J" + flat.hashCode
  }
)

lazy val isScala211 = Def.setting {
  scalaVersion.value.startsWith("2.11")
}

lazy val isScala212 = Def.setting {
  scalaVersion.value.startsWith("2.12")
}

lazy val isScala213 = Def.setting {
  scalaVersion.value.startsWith("2.13")
}

lazy val sharedSettings = Def.settings(
  version ~= { dynVer =>
    customVersion.getOrElse {
      if (isCI) dynVer
      else localSnapshotVersion // only for local publishing
    }
  },
  scalaVersion := LanguageVersion,
  crossScalaVersions := LanguageVersions,
  organization := "org.scalameta",
  libraryDependencies ++= {
    if (isScala213.value) Nil
    else List(compilerPlugin("org.scalamacros" % s"paradise" % "2.1.1" cross CrossVersion.full))
  },
  scalacOptions ++= {
    if (isScala213.value) List("-Ymacro-annotations")
    else Nil
  },
  scalacOptions ++= Seq("-feature", "-unchecked"),
  Compile / doc / scalacOptions ++= Seq("-skip-packages", ""),
  Compile / doc / scalacOptions ++= Seq("-implicits", "-implicits-hide:."),
  Compile / doc / scalacOptions ++= Seq("-groups"),
  Test / parallelExecution := false, // hello, reflection sync!!
  logBuffered := false,
  updateOptions := updateOptions.value.withCachedResolution(true),
  ThisBuild / watchTriggeredMessage := Watch.clearScreenOnTrigger,
  incOptions := incOptions.value.withLogRecompileOnMacro(false)
)

lazy val mergeSettings = Def.settings(
  sharedSettings,
  assembly / test := {},
  assembly / logLevel := Level.Error,
  assembly / assemblyJarName :=
    name.value + "_" + scalaVersion.value + "-" + version.value + "-assembly.jar",
  assembly / assemblyOption ~= { _.withIncludeScala(false) },
  Compile / Keys.`package` := {
    val slimJar = (Compile / Keys.`package`).value
    val fatJar =
      new File(crossTarget.value + "/" + (assembly / assemblyJarName).value)
    val _ = assembly.value
    IO.copy(List(fatJar -> slimJar), CopyOptions().withOverwrite(true))
    slimJar
  },
  Compile / packageBin / packagedArtifact := {
    val temp = (Compile / packageBin / packagedArtifact).value
    val (art, slimJar) = temp
    val fatJar =
      new File(crossTarget.value + "/" + (assembly / assemblyJarName).value)
    val _ = assembly.value
    IO.copy(List(fatJar -> slimJar), CopyOptions().withOverwrite(true))
    (art, slimJar)
  },
  assembly / assemblyMergeStrategy := {
    case PathList("com", "sun", _*) => MergeStrategy.discard
    case PathList("sun", _*) => MergeStrategy.discard
    case x =>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
      oldStrategy(x)
  },
  mimaCurrentClassfiles := {
    (Compile / Keys.`package`).value
  }
)

lazy val protobufSettings = Def.settings(
  sharedSettings,
  Compile / PB.targets := Seq(
    protocbridge.Target(
      generator = PB.gens.plugin("scala"),
      outputPath = (Compile / sourceManaged).value,
      options = scalapb
        .gen(
          flatPackage = true // Don't append filename to package
        )
        ._2
    )
  ),
  Compile / PB.protoSources := Seq(file("semanticdb/semanticdb")),
  PB.additionalDependencies := Nil,
  libraryDependencies ++= {
    val scalapbVersion =
      if (scalaBinaryVersion.value == "2.11") {
        "0.9.7"
      } else if (scalaVersion.value == "2.13.0" || scalaVersion.value == "2.13.1") {
        "0.10.11"
      } else {
        scalapb.compiler.Version.scalapbVersion
      }
    Seq(
      "com.thesamet.scalapb" %%% "scalapb-runtime" % scalapbVersion,
      "com.thesamet.scalapb" %%% "scalapb-runtime" % scalapbVersion % "protobuf",
      "com.thesamet.scalapb" % "protoc-gen-scala" % scalapbVersion % "protobuf" artifacts (
        if (scala.util.Properties.isWin) {
          Artifact("protoc-gen-scala", PB.ProtocPlugin, "bat", "windows")
        } else {
          Artifact("protoc-gen-scala", PB.ProtocPlugin, "sh", "unix")
        }
      )
    )
  }
)

lazy val adhocRepoUri = sys.props("scalameta.repository.uri")
lazy val adhocRepoCredentials = sys.props("scalameta.repository.credentials")
lazy val isCustomRepository = adhocRepoUri != null && adhocRepoCredentials != null

lazy val publishableSettings = Def.settings(
  credentials ++= {
    val credentialsFile = if (adhocRepoCredentials != null) new File(adhocRepoCredentials) else null
    if (credentialsFile != null) List(new FileCredentials(credentialsFile))
    else Nil
  },
  sharedSettings,
  Compile / publishArtifact := true,
  Test / publishArtifact := false,
  publishMavenStyle := true,
  pomIncludeRepository := { x => false },
  versionScheme := Some("semver-spec"),
  mimaPreviousArtifacts := {
    if (organization.value == "org.scalameta") {
      val rxVersion = """^(\d+)\.(\d+)\.(\d+)(.+)?$""".r
      val previousVersion = version.value match {
        case rxVersion(major, "0", "0", suffix) if suffix != null =>
          if (suffix.startsWith("-M")) None
          else Some(s"$major.0.0")
        case rxVersion(major, minor, patch, suffix) if suffix != null =>
          Some(s"$major.$minor.$patch")
        case rxVersion(major, "0", "0", null) =>
          Some(s"$major.0.0")
        case rxVersion(major, minor, "0", null) =>
          Some(s"$major.${minor.toInt - 1}.0")
        case rxVersion(major, minor, patch, null) =>
          Some(s"$major.$minor.0")
        case _ =>
          sys.error(s"Invalid version number: ${version.value}")
      }
      val previousArtifact = {
        // NOTE: Here's what I'd like to do, but I can't because of deprecations:
        //   val isJVM = crossPlatform.value == JVMPlatform
        // Here's my second best guess, but it doesn't work due to some reason:
        //   val isJVM = platformDepsCrossVersion.value == CrossVersion.binary
        val isJVM = {
          val isJS = platformDepsCrossVersion.value == ScalaJSCrossVersion.binary
          val isNative = platformDepsCrossVersion.value == ScalaNativeCrossVersion.binary
          !isJS && !isNative
        }
        if (isJVM) {
          previousVersion.map { previousVersion =>
            organization.value % moduleName.value % previousVersion cross crossVersion.value
          }
        } else {
          None
        }
      }
      previousArtifact.toSet
    } else {
      Set()
    }
  },
  mimaBinaryIssueFilters += Mima.languageAgnosticCompatibilityPolicy,
  mimaBinaryIssueFilters += Mima.scalaSpecificCompatibilityPolicy,
  mimaBinaryIssueFilters ++= Mima.apiCompatibilityExceptions,
  licenses += "BSD" -> url("https://github.com/scalameta/scalameta/blob/main/LICENSE.md"),
  pomExtra := (
    <url>https://github.com/scalameta/scalameta</url>
    <inceptionYear>2014</inceptionYear>
    <issueManagement>
      <system>GitHub</system>
      <url>https://github.com/scalameta/scalameta/issues</url>
    </issueManagement>
    <developers>
      <developer>
        <id>xeno-by</id>
        <name>Eugene Burmako</name>
        <url>http://xeno.by</url>
      </developer>
      <developer>
        <id>DavidDudson</id>
        <name>David Dudson</name>
        <url>https://daviddudson.github.io/</url>
      </developer>
      <developer>
        <id>olafurpg</id>
        <name>Ólafur Páll Geirsson</name>
        <url>https://geirsson.com/</url>
      </developer>
      <developer>
        <id>kpbochenek</id>
        <name>Krzysztof Bochenek</name>
        <url>https://github.com/kpbochenek</url>
      </developer>
      <developer>
        <id>mutcianm</id>
        <name>Mikhail Mutcianko</name>
        <url>https://github.com/mutcianm</url>
      </developer>
      <developer>
        <id>maxov</id>
        <name>Max Ovsiankin</name>
        <url>https://github.com/maxov</url>
      </developer>
      <developer>
        <id>gabro</id>
        <name>Gabriele Petronella</name>
        <url>http://buildo.io</url>
      </developer>
      <developer>
        <id>densh</id>
        <name>Denys Shabalin</name>
        <url>http://den.sh</url>
      </developer>
    </developers>
  )
)

lazy val nonPublishableSettings = Seq(
  publish / skip := true,
  mimaPreviousArtifacts := Set.empty,
  Compile / packageDoc / publishArtifact := false,
  Compile / doc / sources := Seq.empty,
  publishArtifact := false,
  PgpKeys.publishSigned := {},
  publish := {}
)

def compatibilityPolicyViolation(ticket: String) = Seq(
  mimaPreviousArtifacts := Set.empty
)

lazy val fullCrossVersionSettings = Seq(
  crossVersion := CrossVersion.full,
  crossScalaVersions := LanguageVersions ++ LegacyScalaVersions,
  Compile / unmanagedSourceDirectories += {
    // NOTE: sbt 0.13.8 provides cross-version support for Scala sources
    // (http://www.scala-sbt.org/0.13/docs/sbt-0.13-Tech-Previews.html#Cross-version+support+for+Scala+sources).
    // Unfortunately, it only includes directories like "scala_2.11" or "scala_2.12",
    // not "scala_2.11.8" or "scala_2.12.1" that we need.
    // That's why we have to work around here.
    val base = (Compile / sourceDirectory).value
    val versionDir = scalaVersion.value.replaceAll("-.*", "")
    base / ("scala-" + versionDir)
  }
)

lazy val hasLargeIntegrationTests = Seq(
  Test / run / fork := true,
  Test / run / javaOptions += "-Xss4m"
)

def exposePaths(projectName: String, config: Configuration) = {
  def uncapitalize(s: String) =
    if (s.length == 0) ""
    else {
      val chars = s.toCharArray
      chars(0) = chars(0).toLower
      new String(chars)
    }
  val prefix = "sbt.paths." + projectName + "." + uncapitalize(config.name) + "."
  Seq(
    config / scalacOptions := {
      val defaultValue = (config / scalacOptions).value
      System.setProperty(prefix + "options", defaultValue.mkString(" "))
      defaultValue
    },
    config / sourceDirectory := {
      val defaultValue = (config / sourceDirectory).value
      System.setProperty(prefix + "sources", defaultValue.getAbsolutePath)
      defaultValue
    },
    config / resourceDirectory := {
      val defaultValue = (config / resourceDirectory).value
      System.setProperty(prefix + "resources", defaultValue.getAbsolutePath)
      defaultValue
    },
    config / fullClasspath := {
      val defaultValue = (config / fullClasspath).value
      val classpath = defaultValue.files.map(_.getAbsolutePath)
      System.setProperty(prefix + "classes", classpath.mkString(java.io.File.pathSeparator))
      defaultValue
    }
  )
}

lazy val enableMacros = macroDependencies(hardcore = false)

lazy val enableHardcoreMacros = macroDependencies(hardcore = true)

def macroDependencies(hardcore: Boolean) = libraryDependencies ++= {
  val scalaReflect =
    Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided")
  val scalaCompiler = {
    if (hardcore)
      Seq("org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided")
    else Nil
  }
  scalaReflect ++ scalaCompiler
}

lazy val docs = project
  .in(file("scalameta-docs"))
  .dependsOn(scalameta.jvm)
  .settings(
    sharedSettings,
    nonPublishableSettings,
    buildInfoKeys := Seq[BuildInfoKey](
      "scalameta" -> scalameta
    ),
    buildInfoPackage := "docs",
    moduleName := "scalameta-docs",
    mdocExtraArguments := List("--no-link-hygiene"),
    mdocVariables := Map(
      "VERSION" -> version.value.replaceFirst("\\+.*", ""),
      "SCALA_BINARY_VERSION" -> scalaBinaryVersion.value,
      "SCALA_VERSION" -> scalaVersion.value
    ),
    mdocOut := (ThisBuild / baseDirectory).value / "website" / "target" / "docs",
    mimaPreviousArtifacts := Set.empty
  )
  .enablePlugins(BuildInfoPlugin, DocusaurusPlugin)
