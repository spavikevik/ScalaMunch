package scalamunch.typegraph

import scala.meta.internal.semanticdb.*
import scalamunch.model.*

/** Extracts TypeDep entries from SemanticDB SymbolInformation.
 *  Populates the type dependency graph used by get_type_context.
 */
object TypeDepExtractor:

  def extract(docs: Seq[TextDocument]): List[TypeDep] =
    docs.flatMap(doc => doc.symbols.flatMap(extractFromSymbol)).toList

  def extractFromSymbol(info: SymbolInformation): List[TypeDep] =
    val fqn = info.symbol
    info.signature match
      case cs: ClassSignature  => fromClassSig(fqn, cs)
      case ms: MethodSignature => fromMethodSig(fqn, ms)
      case vs: ValueSignature  => fromValueSig(fqn, vs)
      case ts: TypeSignature   => fromTypeSig(fqn, ts)
      case _                   => Nil

  // ── signature handlers ────────────────────────────────────────────────────

  private def fromClassSig(fqn: String, cs: ClassSignature): List[TypeDep] =
    val extends_ = cs.parents.toList.flatMap(typeSymbols).map(TypeDep(fqn, _, TypeRel.Extends))
    // Type parameters contribute Bound deps via their upper bounds
    val tpDeps = cs.typeParameters.toList
      .flatMap(scope => scope.symlinks.toList)
      .map(TypeDep(fqn, _, TypeRel.Bound))
    extends_ ++ tpDeps

  private def fromMethodSig(fqn: String, ms: MethodSignature): List[TypeDep] =
    // Return type deps — most important for type context
    val returnDeps = typeSymbols(ms.returnType).map(TypeDep(fqn, _, TypeRel.Return))
    // Type parameter bounds
    val tpDeps = ms.typeParameters.toList
      .flatMap(scope => scope.symlinks.toList)
      .map(TypeDep(fqn, _, TypeRel.Bound))
    (returnDeps ++ tpDeps).distinctBy(d => (d.toFqn, d.rel.toString))

  private def fromValueSig(fqn: String, vs: ValueSignature): List[TypeDep] =
    typeSymbols(vs.tpe).map(TypeDep(fqn, _, TypeRel.Uses))

  private def fromTypeSig(fqn: String, ts: TypeSignature): List[TypeDep] =
    val loDeps = typeSymbols(ts.lowerBound).map(TypeDep(fqn, _, TypeRel.Bound))
    val hiDeps = typeSymbols(ts.upperBound).map(TypeDep(fqn, _, TypeRel.Bound))
    (loDeps ++ hiDeps).distinctBy(d => (d.toFqn, d.rel.toString))

  // ── type symbol extraction ────────────────────────────────────────────────

  def typeSymbols(t: Type): List[String] = t match
    case tr: TypeRef =>
      val self = if tr.symbol.nonEmpty then List(tr.symbol) else Nil
      self ++ tr.typeArguments.toList.flatMap(typeSymbols)
    case st: SingleType       => if st.symbol.nonEmpty then List(st.symbol) else Nil
    case it: IntersectionType => it.types.toList.flatMap(typeSymbols)
    case ut: UnionType        => ut.types.toList.flatMap(typeSymbols)
    case at: AnnotatedType    => typeSymbols(at.tpe)
    case bt: ByNameType       => typeSymbols(bt.tpe)
    case rt: RepeatedType     => typeSymbols(rt.tpe)
    case _                    => Nil
