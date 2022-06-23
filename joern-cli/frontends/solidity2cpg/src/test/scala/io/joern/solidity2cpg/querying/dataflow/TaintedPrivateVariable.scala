package io.joern.solidity2cpg.querying.dataflow

import io.joern.dataflowengineoss.language.toExtendedCfgNode
import io.joern.solidity2cpg.testfixtures.{SolidityCodeToCpgFixture, SolidityDataflowFixture}
import io.shiftleft.codepropertygraph.generated.{ModifierTypes, Operators}
import io.shiftleft.semanticcpg.language.{ICallResolver, NoResolve, toNodeTypeStarters, _}

class TaintedPrivateVariable extends SolidityDataflowFixture {
  override val code: String = {
    """
      |
      |pragma solidity ^0.8.0;
      |
      |contract Foo {
      |
      |  uint private a ;
      |
      |  function editVariable(uint _a) public {
      |      a = _a;
      |  }
      |
      |}""".stripMargin
  }



  it should "should find a is tainted" in {

    {
      println(cpg.typeDecl.dotAst.head)
      def sink = cpg.method
        .where(_.hasModifier(ModifierTypes.PUBLIC))
        .call
        .name(s"${Operators.assignment}.*")
        .where(_.argument(1).isCall.nameExact(Operators.fieldAccess))
      println(cpg.method.where(_.hasModifier(ModifierTypes.PUBLIC)).name.l)
      println(cpg.method.where(_.hasModifier(ModifierTypes.PUBLIC)).call.name.l)
      println(sink.name.l)

      def source = cpg.method.parameter

      println(sink.reachableByFlows(source).p)
    }

  }
}




