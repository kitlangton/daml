// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package speedy

import com.daml.lf.speedy.Speedy._
import com.daml.lf.speedy.SExpr._

object Classify { // classify the machine state w.r.t what step occurs next

  case class Counts(
      var steps: Int,
      var returns: Int,
      // expression classification (step)
      var evalue: Int,
      var evar: Int,
      var eapp: Int,
      var eclose: Int,
      var ebuiltin: Int,
      var eval: Int,
      var elocation: Int,
      var elet: Int,
      var ecase: Int,
      var ebuiltinrecursivedefinition: Int,
      var ecatch: Int,
      var eimportvalue: Int,
      // kont classification (returnValue)
      var kpop: Int,
      var karg: Int,
      var kfun: Int,
      var kpushto: Int,
      var kcacheval: Int,
      var klocation: Int,
      var kmatch: Int,
      var kcatch: Int,
  ) {
    def pp: String = {
      List(
        ("Steps:", steps),
        ("- evalue", evalue),
        ("- evar", evar),
        ("- eapp", eapp),
        ("- eclose", eclose),
        ("- ebuiltin", ebuiltin),
        ("- eval", eval),
        ("- elocation", elocation),
        ("- elet", elet),
        ("- ecase", ecase),
        ("- ebuiltinrecursivedefinition", ebuiltinrecursivedefinition),
        ("- ecatch", ecatch),
        ("- eimportvalue", eimportvalue),
        ("Returns:", returns),
        ("- kpop", kpop),
        ("- karg", karg),
        ("- kfun", kfun),
        ("- kpushto", kpushto),
        ("- kcacheval", kcacheval),
        ("- klocation", klocation),
        ("- kmatch", kmatch),
        ("- kcatch", kcatch),
      ).map { case (tag, n) => s"$tag : $n" }.mkString("\n")
    }
  }

  def newEmptyCounts(): Counts = {
    Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
  }

  def classifyStep(exp: SExpr, counts: Counts): Unit = {
    counts.steps += 1
    exp match {
      case SEValue(_) => counts.evalue += 1
      case SEVar(_) => counts.evar += 1
      case SEApp(_, _) => counts.eapp += 1
      case SEMakeClo(_, _, _) => counts.eclose += 1
      case SEBuiltin(_) => counts.ebuiltin += 1
      case SEVal(_, _) => counts.eval += 1
      case SELocation(_, _) => counts.elocation += 1
      case SELet(_, _) => counts.elet += 1
      case SECase(_, _) => counts.ecase += 1
      case SEBuiltinRecursiveDefinition(_) => counts.ebuiltinrecursivedefinition += 1
      case SECatch(_, _, _) => counts.ecatch += 1
      case SEWronglyTypeContractId(_, _, _) => ??? //not seen one yet
      case SEImportValue(_) => ??? //not seen one yet
      case SEAbs(_, _) => ??? //never expect these!
    }
  }

  def classifyReturn(kont: Kont, counts: Counts): Unit = {
    counts.returns += 1
    kont match {
      case KPop(_) => counts.kpop += 1
      case KArg(_) => counts.karg += 1
      case KFun(_, _, _) => counts.kfun += 1
      case KPushTo(_, _) => counts.kpushto += 1
      case KCacheVal(_, _) => counts.kcacheval += 1
      case KLocation(_) => counts.klocation += 1
      case KMatch(_) => counts.kmatch += 1
      case KCatch(_, _, _) => counts.kcatch += 1
    }
  }

  final case class ClassifyError(s: String) extends RuntimeException(s, null, false, false)

}
