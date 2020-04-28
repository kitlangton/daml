// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package speedy

import java.util
import scala.collection.JavaConverters._

import com.daml.lf.speedy.Speedy._
import com.daml.lf.speedy.SExpr._
import com.daml.lf.speedy.SValue._

object PrettyLightweight { // lightweight pretty printer for CEK machine states

  def ppMachine(m: Machine): String = {
    s"${ppEnv(m.env)} -- ${pp(m.ctrl)} -- ${ppKontStack(m.kontStack)}"
  }

  def ppEnv(env: Env): String = {
    s"{${commas(env.asScala.map(pp))}}"
  }

  def ppKontStack(ks: util.ArrayList[Kont]): String = {
    ks.asScala.reverse.map(ppKont).mkString(" -- ")
  }

  def ppKont(k: Kont): String = k match {
    case KPop(n) => s"KPop($n)"
    case KArg(es) => s"KArg(${commas(es.map(pp))})"
    case KFun(prim, extendedArgs, arity) =>
      s"KFun(${pp(prim)}/$arity,[${commas(extendedArgs.asScala.map(pp))}])"
    case KPushTo(_, e) => s"KPushTo(_, ${pp(e)})"
    case KCacheVal(_, _) => "KCacheVal"
    case KLocation(_) => "KLocation"
    case KMatch(_) => "KMatch"
    case KCatch(_, _, _) => "KCatch" //never seen
  }

  def ppVarRef(n: Int): String = {
    s"#$n"
  }

  def pp(e: SExpr): String = e match {
    case SEValue(v) => pp(v)
    case SEVar(n) => ppVarRef(n)

    //case SEApp(func, args) => s"@(${pp(func)},${commas(args.map(pp))})"
    case SEApp(_, _) => s"@(...)"

    //case SEMakeClo(fvs, arity, body) => s"[${commas(fvs.map(ppVarRef))}]lam/$arity->${pp(body)}"
    case SEMakeClo(fvs, arity, _) => s"[${commas(fvs.map(ppVarRef))}]lam/$arity->..."

    case SEBuiltin(b) => s"${b}"
    case SEVal(_, _) => "<SEVal...>"
    case SELocation(_, _) => "<SELocation...>"
    case SELet(_, _) => "<SELet...>"
    case SECase(_, _) => "<SECase...>"
    case SEBuiltinRecursiveDefinition(_) => "<SEBuiltinRecursiveDefinition...>"
    case SECatch(_, _, _) => ??? //not seen one yet
    case SEWronglyTypeContractId(_, _, _) => ??? //not seen one yet
    case SEImportValue(_) => ??? //not seen one yet
    case SEAbs(_, _) => "<SEAbs...>" // will never get these on running machine
  }

  def pp(v: SValue): String = v match {
    case SInt64(n) => s"$n"
    case SPAP(prim, args, arity) =>
      s"PAP[${args.size}/$arity](${pp(prim)}(${commas(args.asScala.map(pp))})))"
    case SToken => "SToken"
    case SText(s) => s"'$s'"
    case SParty(_) => "<SParty>"
    case SStruct(_, _) => "<SStruct...>"
    case SUnit => "SUnit"
    case SList(_) => "SList"
    case _ => throw UnknownV(v)
  }

  def pp(prim: Prim): String = prim match {
    case PBuiltin(b) => s"$b"
    case PClosure(expr, fvs) =>
      s"clo[${commas(fvs.map(pp))}]:${pp(expr)}"
  }

  def commas(xs: Seq[String]): String = xs.mkString(",")

  final case class UnknownV(v: SValue) extends RuntimeException(v.toString)

}
