package io.joern.rubysrc2cpg.passes

import better.files.File
import io.joern.rubysrc2cpg.utils.PackageTable
import io.joern.x2cpg.Defines as XDefines
import io.joern.x2cpg.passes.frontend.ImportsPass.*
import io.joern.x2cpg.passes.frontend.XImportResolverPass
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.Call
import io.shiftleft.semanticcpg.language.*

import java.io.File as JFile
import java.util.regex.{Matcher, Pattern}
class ImportResolverPass(cpg: Cpg, packageTableInfo: PackageTable) extends XImportResolverPass(cpg) {

  private val pathPattern = Pattern.compile("[\"']([\\w/.]+)[\"']")

  override protected def optionalResolveImport(
    fileName: String,
    importCall: Call,
    importedEntity: String,
    importedAs: String,
    diffGraph: DiffGraphBuilder
  ): Unit = {

    resolveEntities(importedEntity, importedAs, fileName).foreach(x => resolvedImportToTag(x, importCall, diffGraph))
  }

  private def resolveEntities(expEntity: String, alias: String, fileName: String): Set[ResolvedImport] = {

    // TODO
    /* Currently we are considering only case where exposed module are Classes,
    and the only way to consume them is by creating a new object as we encounter more cases,
     This needs to be handled accordingly
     */

    val expResolvedPath =
      if (packageTableInfo.getModule(expEntity).nonEmpty || packageTableInfo.getTypeDecl(expEntity).nonEmpty)
        expEntity
      else if (expEntity.contains("."))
        getResolvedPath(expEntity, fileName)
      else if (cpg.file.name(s".*$expEntity.rb").nonEmpty)
        getResolvedPath(s"$expEntity.rb", fileName)
      else
        expEntity

    // TODO Limited ResolvedMethod exposure for now, will open up after looking at more concrete examples
    val finalResolved = {
      if (
        packageTableInfo.getModule(expResolvedPath).nonEmpty || packageTableInfo.getTypeDecl(expResolvedPath).nonEmpty
      ) {
        val importNodesFromTypeDecl = packageTableInfo
          .getTypeDecl(expEntity)
          .flatMap { typeDeclModel =>
            Seq(
              ResolvedMethod(s"${typeDeclModel.fullName}.${XDefines.ConstructorMethodName}", "new"),
              ResolvedTypeDecl(typeDeclModel.fullName, alias)
            )
          }
          .distinct

        val importNodesFromModule = packageTableInfo.getModule(expEntity).flatMap { moduleModel =>
          Seq(ResolvedTypeDecl(moduleModel.fullName, alias))
        }
        (importNodesFromTypeDecl ++ importNodesFromModule).toSet
      } else {
        val filePattern = s"${Pattern.quote(expResolvedPath)}\\.?.*"
        val resolvedTypeDecls = cpg.typeDecl
          .where(_.file.name(filePattern))
          .fullName
          .flatMap(fullName =>
            Seq(
              ResolvedTypeDecl(fullName, alias),
              ResolvedMethod(s"$fullName.${XDefines.ConstructorMethodName}", "new")
            )
          )
          .toSet

        val resolvedModules = cpg.namespaceBlock
          .whereNot(_.nameExact("<global>"))
          .where(_.file.name(filePattern))
          .flatMap(module => Seq(ResolvedTypeDecl(module.fullName, alias)))
          .toSet

        // Expose methods which are directly present in a file, without any module, TypeDecl
        val resolvedMethods = cpg.method
          .where(_.file.name(filePattern))
          .where(_.nameExact(":program"))
          .astChildren
          .astChildren
          .isMethod
          .flatMap(method => Seq(ResolvedMethod(method.fullName, method.name)))
          .toSet
        resolvedTypeDecls ++ resolvedModules ++ resolvedMethods
      }
    }

    finalResolved
  }

  def getResolvedPath(expEntity: String, fileName: String) = {
    val rawEntity   = expEntity.stripPrefix("./")
    val matcher     = pathPattern.matcher(rawEntity)
    val sep         = Matcher.quoteReplacement(JFile.separator)
    val root        = s"$codeRoot${JFile.separator}"
    val currentFile = s"$root$fileName"
    val entity      = if (matcher.find()) matcher.group(1) else rawEntity
    val resolvedPath = better.files
      .File(
        currentFile.stripSuffix(currentFile.split(sep).lastOption.getOrElse("")),
        entity.split("\\.").headOption.getOrElse(entity)
      )
      .pathAsString match {
      case resPath if entity.endsWith(".rb") => s"$resPath.rb"
      case resPath                           => resPath
    }
    resolvedPath.stripPrefix(root)
  }

}
