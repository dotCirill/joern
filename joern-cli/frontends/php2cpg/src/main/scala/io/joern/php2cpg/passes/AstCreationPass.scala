package io.joern.php2cpg.passes

import better.files.File
import io.joern.php2cpg.Config
import io.joern.php2cpg.astcreation.AstCreator
import io.joern.php2cpg.parser.PhpParser
import io.joern.php2cpg.utils.PathFilter
import io.joern.x2cpg.SourceFiles
import io.joern.x2cpg.datastructures.Global
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.passes.ConcurrentWriterCpgPass
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._
import java.nio.file.PathMatcher

class AstCreationPass(config: Config, cpg: Cpg) extends ConcurrentWriterCpgPass[String](cpg) {

  private val logger = LoggerFactory.getLogger(this.getClass)
  val global         = new Global()

  val PhpSourceFileExtensions: Set[String] = Set(".php")
  private val pathFilter                   = PathFilter(config.excludeOverrides)

  override def generateParts(): Array[String] =
    SourceFiles.determine(config.inputPath, PhpSourceFileExtensions).toArray

  override def runOnPart(diffGraph: DiffGraphBuilder, filename: String): Unit = {
    val relativeFilename = File(config.inputPath).relativize(File(filename)).toString

    if (pathFilter.excluded(relativeFilename)) {
      logger.debug(s"Skipping file due to matched exclusion: $relativeFilename")
    } else {
      PhpParser.parseFile(filename, config.phpIni) match {
        case Some(parseResult) =>
          diffGraph.absorb(new AstCreator(relativeFilename, parseResult, global).createAst())

        case None =>
          logger.warn(s"Could not parse file $filename. Results will be missing!")
      }
    }
  }

  def allUsedTypes: List[String] = global.usedTypes.keys().asScala.toList
}
