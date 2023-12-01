package io.joern.x2cpg.passes.frontend

import io.joern.x2cpg.passes.frontend.TypeNodePass.fullToShortName
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.PropertyKeys
import io.shiftleft.codepropertygraph.generated.nodes.NewType
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.types.structure.NamespaceTraversal

import scala.collection.mutable

/** Creates a `TYPE` node for each type in `usedTypes` as well as all inheritsFrom type names in the CPG
  *
  * Alternatively, set `getTypesFromCpg = true`. If this is set, the `registeredTypes` argument will be ignored.
  * Instead, type nodes will be created for every unique `TYPE_FULL_NAME` value in the CPG.
  */
class TypeNodePass private (registeredTypes: List[String], cpg: Cpg, getTypesFromCpg: Boolean)
  extends CpgPass(cpg, "types") {

  private def getTypeDeclTypes(): mutable.Set[String] = {
    val typeDeclTypes = mutable.Set[String]()
    cpg.typeDecl.foreach { typeDecl =>
      typeDeclTypes += typeDecl.fullName
      typeDeclTypes ++= typeDecl.inheritsFromTypeFullName
    }
    typeDeclTypes
  }

  def getTypeFullNamesFromCpg(): Set[String] = {
    cpg.all
      .map(_.property(PropertyKeys.TypeFullName))
      .filter(_ != null)
      .toSet
  }

  override def run(diffGraph: DiffGraphBuilder): Unit = {
    val typeFullNameValues =
      if (getTypesFromCpg)
        getTypeFullNamesFromCpg()
      else
        registeredTypes.toSet

    val usedTypesSet = getTypeDeclTypes() ++ typeFullNameValues
    usedTypesSet.remove("<empty>")
    val usedTypes = usedTypesSet.filterInPlace(!_.endsWith(NamespaceTraversal.globalNamespaceName)).toArray.sorted

    diffGraph.addNode(
      NewType()
        .name("ANY")
        .fullName("ANY")
        .typeDeclFullName("ANY")
    )

    usedTypes.foreach { typeName =>
      val shortName = fullToShortName(typeName)
      val node = NewType()
        .name(shortName)
        .fullName(typeName)
        .typeDeclFullName(typeName)
      diffGraph.addNode(node)
    }
  }
}

object TypeNodePass {
  // Lambda typeDecl type names fit the structure
  // `a.b.c.d.ClassName.lambda$method$name:returnType(paramTypes)`
  // so this regex works by greedily matching the package and class names
  // at the start and cutting off the matched group before the signature.
  private val lambdaTypeRegex = raw".*\.(.*):.*\(.*\)".r

  def withTypesFromCpg(cpg: Cpg): TypeNodePass = {
    new TypeNodePass(Nil, cpg, getTypesFromCpg = true)
  }

  def withRegisteredTypes(registeredTypes: List[String], cpg: Cpg): TypeNodePass = {
    new TypeNodePass(registeredTypes, cpg, getTypesFromCpg = false)
  }

  def fullToShortName(typeName: String): String = {
    typeName match {
      case lambdaTypeRegex(methodName) => methodName
      case _                           => typeName.split('.').lastOption.getOrElse(typeName)
    }
  }
}
