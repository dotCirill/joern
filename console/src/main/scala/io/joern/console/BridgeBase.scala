package io.joern.console

import better.files.*
import dotty.tools.Settings
import dotty.tools.dotc.core.Contexts.{Context, ctx}
import dotty.tools.io.{ClassPath, Directory, PlainDirectory}
import dotty.tools.repl.State
import dotty.tools.scripting.{ScriptingDriver, Util}
import io.joern.console.cpgqlserver.CPGQLServer
import io.joern.console.embammonite.EmbeddedAmmonite

import java.io.{InputStream, PrintStream, File as JFile}
import java.net.URLClassLoader
import java.nio.file.{Files, Path, Paths}
import java.util
import java.util.stream

case class Config(
  scriptFile: Option[os.Path] = None,
  command: Option[String] = None,
  params: Seq[String] = Seq.empty,
  additionalImports: List[os.Path] = Nil,
  addPlugin: Option[String] = None,
  rmPlugin: Option[String] = None,
  pluginToRun: Option[String] = None,
  listPlugins: Boolean = false,
  src: Option[String] = None,
  language: Option[String] = None,
  overwrite: Boolean = false,
  store: Boolean = false,
  server: Boolean = false,
  serverHost: String = "localhost",
  serverPort: Int = 8080,
  serverAuthUsername: String = "",
  serverAuthPassword: String = "",
  nocolors: Boolean = false,
  cpgToLoad: Option[File] = None,
  forInputPath: Option[String] = None,
  frontendArgs: Array[String] = Array.empty,
  verbose: Boolean = false,
  dependencies: Seq[String] = Seq.empty
)

/** Base class for Ammonite Bridge, split by topic into multiple self types.
  */
trait BridgeBase extends ScriptExecution with PluginHandling with ServerHandling {

  def slProduct: SLProduct

  protected def parseConfig(args: Array[String]): Config = {
    implicit def pathRead: scopt.Read[os.Path] =
      scopt.Read.stringRead.map(os.Path(_, os.pwd)) // support both relative and absolute paths

    val parser = new scopt.OptionParser[Config](slProduct.name) {
      override def errorOnUnknownArgument = false

      note("Script execution")

      opt[os.Path]("script")
        .action((x, c) => c.copy(scriptFile = Some(x)))
        .text("path to script file: will execute and exit")

      opt[Seq[String]]('p', "params")
        .valueName("value1,value2")
        .action((x, c) => c.copy(params = x))
        .text("parameter values for main function in script")

      opt[Seq[os.Path]]("import")
        .valueName("script1.sc,script2.sc,...")
        .action((x, c) => c.copy(additionalImports = x.toList))
        .text("import additional additional script(s): will execute and keep console open")

      opt[Seq[String]]("dependency")
        .valueName("com.michaelpollmeier:versionsort:1.0.7,...")
        .action((x, c) => c.copy(dependencies = x.toList))
        .text("resolve dependency (and it's transitive dependencies) for given maven coordinate(s)")

      opt[String]("command")
        .action((x, c) => c.copy(command = Some(x)))
        .text("select one of multiple @main methods")

      note("Plugin Management")

      opt[String]("add-plugin")
        .action((x, c) => c.copy(addPlugin = Some(x)))
        .text("Plugin zip file to add to the installation")

      opt[String]("remove-plugin")
        .action((x, c) => c.copy(rmPlugin = Some(x)))
        .text("Name of plugin to remove from the installation")

      opt[Unit]("plugins")
        .action((_, c) => c.copy(listPlugins = true))
        .text("List available plugins and layer creators")

      opt[String]("run")
        .action((x, c) => c.copy(pluginToRun = Some(x)))
        .text("Run layer creator. Get a list via --plugins")

      opt[String]("src")
        .action((x, c) => c.copy(src = Some(x)))
        .text("Source code directory to run layer creator on")

      opt[String]("language")
        .action((x, c) => c.copy(language = Some(x)))
        .text("Language to use in CPG creation")

      opt[Unit]("overwrite")
        .action((_, c) => c.copy(overwrite = true))
        .text("Overwrite CPG if it already exists")

      opt[Unit]("store")
        .action((_, c) => c.copy(store = true))
        .text("Store graph changes made by layer creator")

      note("REST server mode")

      opt[Unit]("server")
        .action((_, c) => c.copy(server = true))
        .text("run as HTTP server")

      opt[String]("server-host")
        .action((x, c) => c.copy(serverHost = x))
        .text("Hostname on which to expose the CPGQL server")

      opt[Int]("server-port")
        .action((x, c) => c.copy(serverPort = x))
        .text("Port on which to expose the CPGQL server")

      opt[String]("server-auth-username")
        .action((x, c) => c.copy(serverAuthUsername = x))
        .text("Basic auth username for the CPGQL server")

      opt[String]("server-auth-password")
        .action((x, c) => c.copy(serverAuthPassword = x))
        .text("Basic auth password for the CPGQL server")

      note("Misc")

      arg[java.io.File]("<cpg.bin>")
        .optional()
        .action((x, c) => c.copy(cpgToLoad = Some(x.toScala)))
        .text("CPG to load")

      opt[String]("for-input-path")
        .action((x, c) => c.copy(forInputPath = Some(x)))
        .text("Open CPG for given input path - overrides <cpg.bin>")

      opt[Unit]("nocolors")
        .action((_, c) => c.copy(nocolors = true))
        .text("turn off colors")

      opt[Unit]("verbose")
        .action((_, c) => c.copy(verbose = true))
        .text("enable verbose output")

      help("help")
        .text("Print this help text")
    }

    // note: if config is really `None` an error message would have been displayed earlier
    parser.parse(args, Config()).get
  }

  /** Entry point for Joern's integrated ammonite shell
    */
  protected def runAmmonite(config: Config): Unit = {
    if (config.listPlugins) {
      printPluginsAndLayerCreators(config)
    } else if (config.addPlugin.isDefined) {
      new PluginManager(InstallConfig().rootPath).add(config.addPlugin.get)
    } else if (config.rmPlugin.isDefined) {
      new PluginManager(InstallConfig().rootPath).rm(config.rmPlugin.get)
    } else {
      config.scriptFile match {
        case None =>
          if (config.server) {
            startHttpServer(config)
          } else if (config.pluginToRun.isDefined) {
            runPlugin(config, slProduct.name)
          } else {
            startInteractiveShell(config)
          }
        case Some(scriptFile) =>
          runScript(scriptFile, config)
      }
    }
  }

  protected def additionalImportCode(config: Config): List[String] =
    config.additionalImports.flatMap { importScript =>
      val file = importScript.toIO
      assert(file.canRead, s"unable to read $file")
      readScript(file.toScala)
    }

  /** Override this method to implement script decryption
    */
  protected def decryptedScript(scriptFile: os.Path): os.Path =
    scriptFile

  private def readScript(scriptFile: File): List[String] = {
    val code = scriptFile.lines.toList
    println(s"importing $scriptFile (${code.size} lines)")
    code
  }

  protected def predefPlus(lines: List[String]): String

  protected def greeting: String

  protected def promptStr: String

  protected def onExitCode: String
}

// TODO rename - why is this called `ScriptExecution` if it really implements scripts and repl?
trait ScriptExecution { this: BridgeBase =>

  protected def startInteractiveShell(config: Config) = {
    val replConfig = config.cpgToLoad.map { cpgFile =>
      "importCpg(\"" + cpgFile + "\")"
    } ++ config.forInputPath.map { name =>
      s"""
         |openForInputPath(\"$name\")
         |""".stripMargin
    }

    val classLoader: ClassLoader = {
      import java.net.{URL, URLClassLoader}
      val parent = this.getClass.getClassLoader
      new ClassLoader(parent) {

        override def loadClass(name: String): Class[_]  = {
         val ret = super.loadClass(name)
         println("XXXX1 " + ret)
         ret
        }

        override def findClass(name: String): Class[_]  = {
         val ret = super.findClass(name)
         println("XXXX3 " + ret)
         ret
        }

        override def findClass(moduleName: String, name: String): Class[_]  = {
         val ret = super.findClass(moduleName, name)
         println("XXXX4 " + ret)
         ret
        }

        override def findResource(moduleName: String, name: String): URL  = {
         val ret = super.findResource(moduleName, name)
         println("XXXX5 " + ret)
         ret
        }

        override def getResource(name: String): URL  = {
         val ret = super.getResource(name)
         println("XXXX6 " + ret)
         ret
        }

        override def getResources(name: String): util.Enumeration[URL]  = {
         val ret = super.getResources(name)
         println("XXXX7 " + ret)
         ret
        }

        override def resources(name: String): stream.Stream[URL]  = {
         val ret = super.resources(name)
         println("XXXX8 " + ret)
         ret
        }

        override def findResource(name: String): URL  = {
         val ret = super.findResource(name)
         println("XXXX9 " + ret)
         ret
        }

        override def findResources(name: String): util.Enumeration[URL]  = {
         val ret = super.findResources(name)
         println("XXXX10 " + ret)
         ret
        }

        override def getResourceAsStream(name: String): InputStream  = {
         val ret = super.getResourceAsStream(name)
         println("XXXX11 " + ret)
         ret
        }

        override def loadClass(name: String, resolve: Boolean): Class[_] = {
          val ret = super.loadClass(name, resolve)
//          os.write.append(os.Path("/home/mp/Projects/shiftleft/joern/foo.txt"), s"$name\n")
          println(s"XXXX loadClass resolve=$resolve $name=$ret")
          ret
        }
      }
    }

//    val scalacArgs0 = {
//      val hppcJar = "/home/mp/.cache/coursier/v1/https/repo1.maven.org/maven2/com/carrotsearch/hppc/0.7.1/hppc-0.7.1.jar"
//      val compilerArgs = Array.newBuilder[String]
//      val dependencyFiles = Seq(Paths.get(hppcJar).toFile)
//      compilerArgs ++= Array("-classpath", replClasspath(dependencyFiles))
//      compilerArgs += "-explain" // verbose scalac error messages
//      if (config.nocolors) compilerArgs ++= Array("-color", "never")
//      compilerArgs.result()
//    }

//    HackyGlobalState.classloader = classLoader
    val replDriver = new ReplDriver(
      compilerArgs(config),
//      scalacArgs0,
      onExitCode = Option(onExitCode),
      greeting = greeting,
      prompt = promptStr,
      maxPrintElements = Int.MaxValue,
//      Option(classLoader)
    )
//    replDriver.addDependency(hppcJar)

    // TODO cleanup
//    val versionSortJar = "/home/mp/.cache/coursier/v1/https/repo1.maven.org/maven2/com/michaelpollmeier/versionsort/1.0.7/versionsort-1.0.7.jar"
//    replDriver.addDependency(versionSortJar)
    val initialState: State = replDriver.initialState

    // when upgrading to Scala 3.2.1: change to `given State`
    val predefCode = predefPlus(additionalImportCode(config) ++ replConfig)
    // TODO proper predef code again...
    val predef0 =
      """
        |import overflowdb._
      """.stripMargin
    val state: State = replDriver.run(predef0)(using initialState)
//    val state: State =
//      if (config.verbose) {
//        println(predefCode)
//        replDriver.run(predefCode)(using initialState)
//      } else {
//        replDriver.runQuietly(predefCode)(using initialState)
//      }

  // TODO test idea: if quit with C-c: restart with old state but new classloader - go via addDependency
    val state2 = replDriver.runUntilQuit(state)
//    println("running again...")
//    val scalacArgs1 = {
//      val versionSortJar = "/home/mp/.cache/coursier/v1/https/repo1.maven.org/maven2/com/michaelpollmeier/versionsort/1.0.7/versionsort-1.0.7.jar"
//      val compilerArgs = Array.newBuilder[String]
//      val dependencyFiles = Seq(Paths.get(versionSortJar).toFile)
//      compilerArgs ++= Array("-classpath", replClasspath(dependencyFiles))
//      compilerArgs += "-explain" // verbose scalac error messages
//      if (config.nocolors) compilerArgs ++= Array("-color", "never")
//      compilerArgs.result()
//    }
//    val replDriver2 = new ReplDriver(
//      scalacArgs1,
//      onExitCode = Option(onExitCode),
//      greeting = greeting,
//      prompt = promptStr,
//      maxPrintElements = Int.MaxValue,
////      Option(classLoader)
//    )
////    replDriver2.addDependency(versionSortJar)
//    replDriver2.runUntilQuit(state2)
  }

  protected def runScript(scriptFile: os.Path, config: Config): Unit = {
    val isEncryptedScript = scriptFile.ext == "enc"
    System.err.println(s"executing $scriptFile with params=${config.params}")

    val scriptArgs: Array[String] = config.command.toArray ++ config.params

    val decodedScriptFile =
      if (isEncryptedScript) decryptedScript(scriptFile)
      else scriptFile

    // Our predef code includes import statements... I didn't find a nice way to add them to the context of the
    // script file, so instead we'll just write it to the beginning of the script file.
    // That's obviously suboptimal, e.g. because it messes with the line numbers.
    // Therefor, we'll display the temp script file name to the user and not delete it, in case the script errors.
    val predefCode = predefPlus(additionalImportCode(config) ++ importCpgCode(config))
    val predefPlusScriptFileTmp = Files.createTempFile("joern-script-with-predef", ".sc")
    val scriptCode = Files.readString(decodedScriptFile.toNIO)
    Files.writeString(
      predefPlusScriptFileTmp,
      s"""$predefCode
         |$scriptCode
         |""".stripMargin
    )

    try {
      new ScriptingDriver(
        compilerArgs = compilerArgs(config),
        scriptFile = predefPlusScriptFileTmp.toFile,
        scriptArgs = scriptArgs
      ).compileAndRun()

      // if the script failed: don't delete the temporary file which includes the predef,
      // so that the line numbers are accurate and the user can properly debug
      predefPlusScriptFileTmp.toFile.delete()
      System.err.println(s"script finished successfully")
    } catch {
      case t: Throwable =>
        if (isEncryptedScript) {
          /* minimizing exposure time by deleting the decrypted script straight away */
          decodedScriptFile.toIO.delete()
          predefPlusScriptFileTmp.toFile.delete()
        }
        throw t
    }
  }

  /** For the given config, generate a list of commands to import the CPG
    */
  private def importCpgCode(config: Config): List[String] = {
    config.cpgToLoad.map { cpgFile =>
      "importCpg(\"" + cpgFile + "\")"
    }.toList ++ config.forInputPath.map { name =>
      s"""
         |openForInputPath(\"$name\")
         |""".stripMargin
    }
  }

  private def compilerArgs(config: Config): Array[String] = {
    val compilerArgs = Array.newBuilder[String]

    val dependencyFiles = Dependencies.resolveOptimistically(config.dependencies)
    compilerArgs ++= Array("-classpath", replClasspath(dependencyFiles))
    compilerArgs += "-explain" // verbose scalac error messages
    if (config.nocolors) compilerArgs ++= Array("-color", "never")
    compilerArgs.result()
  }

  private def replClasspath(dependencies: Seq[java.io.File]): String = {
    val inheritedClasspath = System.getProperty("java.class.path")
    val separator = System.getProperty("path.separator")

    val entriesForDeps = dependencies.mkString(separator)
    s"$inheritedClasspath$separator$entriesForDeps"
  }

}

trait PluginHandling { this: BridgeBase =>

  /** Print a summary of the available plugins and layer creators to the terminal.
    */
  protected def printPluginsAndLayerCreators(config: Config): Unit = {
    println("Installed plugins:")
    println("==================")
    new PluginManager(InstallConfig().rootPath).listPlugins().foreach(println)
    println("Available layer creators")
    println()
    withTemporaryScript(codeToListPlugins(), slProduct.name) { file =>
      runScript(os.Path(file.path.toString), config)
    }
  }

  private def codeToListPlugins(): String = {
    """
      |println(run)
      |
      |""".stripMargin
  }

  /** Run plugin by generating a temporary script based on the given config and executing the script via ammonite.
    */
  protected def runPlugin(config: Config, productName: String): Unit = {
    if (config.src.isEmpty) {
      println("You must supply a source directory with the --src flag")
      return
    }
    val code = loadOrCreateCpg(config, productName)
    withTemporaryScript(code, productName) { file =>
      runScript(os.Path(file.path.toString), config)
    }
  }

  /** Create a command that loads an existing CPG or creates it, based on the given `config`.
    */
  private def loadOrCreateCpg(config: Config, productName: String): String = {

    val bundleName = config.pluginToRun.get
    val src        = better.files.File(config.src.get).path.toAbsolutePath.toString
    val language   = languageFromConfig(config, src)

    val storeCode = if (config.store) { "save" }
    else { "" }
    val runDataflow = if (productName == "ocular") { "run.dataflow" }
    else { "run.ossdataflow" }
    val argsString = argsStringFromConfig(config)

    s"""
       | if (${config.overwrite} || !workspace.projectExists("$src")) {
       |   workspace.projects
       |   .filter(_.inputPath == "$src")
       |   .map(_.name).foreach(n => workspace.removeProject(n))
       |   importCode.$language("$src"$argsString)
       |   $runDataflow
       |   save
       | } else {
       |    println("Using existing CPG - Use `--overwrite` if this is not what you want")
       |    openForInputPath(\"$src\")
       | }
       | run.$bundleName
       | $storeCode
       |""".stripMargin

  }

  private def languageFromConfig(config: Config, src: String): String = {
    config.language.getOrElse(
      io.joern.console.cpgcreation
        .guessLanguage(src)
        .map { x =>
          val lang = x.toLowerCase
          lang match {
            case "newc" => "c"
            case _      => lang
          }
        }
        .getOrElse("c")
    )
  }

  private def argsStringFromConfig(config: Config): String = {
    config.frontendArgs match {
      case Array() => ""
      case args =>
        val quotedArgs = args.map { arg =>
          "\"" ++ arg ++ "\""
        }
        val argsString = quotedArgs.mkString(", ")
        s", args=List($argsString)"
    }
  }

  private def withTemporaryScript(code: String, prefix: String)(f: File => Unit): Unit = {
    File.usingTemporaryDirectory(prefix + "-bundle") { dir =>
      val file = dir / "script.sc"
      file.write(code)
      f(file)
    }
  }

}

trait ServerHandling {

  this: BridgeBase =>

  protected def startHttpServer(config: Config): Unit = {
    val predef   = predefPlus(additionalImportCode(config))
    val ammonite = new EmbeddedAmmonite(predef)
    ammonite.start()
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      ammonite.shutdown()
    }))
    val server = new CPGQLServer(
      ammonite,
      config.serverHost,
      config.serverPort,
      config.serverAuthUsername,
      config.serverAuthPassword
    )
    println("Starting CPGQL server ...")
    try {
      server.main(Array.empty)
    } catch {
      case _: java.net.BindException => {
        println("Could not bind socket for CPGQL server, exiting.")
        ammonite.shutdown()
        System.exit(1)
      }
      case e: Throwable =>
        println("Unhandled exception thrown while attempting to start CPGQL server: ")
        println(e.getMessage)
        println("Exiting.")

        ammonite.shutdown()
        System.exit(1)
    }
  }

}
