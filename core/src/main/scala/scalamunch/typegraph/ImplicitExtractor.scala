package scalamunch.typegraph

import scala.meta.internal.semanticdb.*
import scalamunch.model.*

/** Extracts ImplicitEntry records from SemanticDB SymbolInformation.
 *  An entry links a typeclass (e.g. cats/Show#) to a concrete type argument
 *  (e.g. scala/Int#) via a given/implicit instance symbol.
 */
object ImplicitExtractor:

  def extract(docs: Seq[TextDocument]): List[ImplicitEntry] =
    docs.flatMap(doc => doc.symbols.flatMap(extractFromSymbol(_, doc.uri))).toList

  def extractFromSymbol(info: SymbolInformation, sourceFile: String): List[ImplicitEntry] =
    val isImplicitOrGiven =
      (info.properties & SymbolInformation.Property.IMPLICIT.value) != 0 ||
      (info.properties & SymbolInformation.Property.GIVEN.value) != 0
    if !isImplicitOrGiven then Nil
    else
      info.signature match
        case cs: ClassSignature  => fromClassSig(info.symbol, cs)
        case vs: ValueSignature  => fromValueSig(info.symbol, vs)
        case ms: MethodSignature => fromMethodSig(info.symbol, ms)
        case _                   => Nil

  // ── handlers ──────────────────────────────────────────────────────────────

  /** `given Show[Foo] with ...` → ClassSignature with parent TypeRef(Show, [Foo]) */
  private def fromClassSig(instanceFqn: String, cs: ClassSignature): List[ImplicitEntry] =
    cs.parents.flatMap { parentType =>
      parentType match
        case tr: TypeRef if tr.symbol.nonEmpty =>
          val typeFqn = tr.symbol
          tr.typeArguments.flatMap(TypeDepExtractor.typeSymbols).map { paramFqn =>
            ImplicitEntry(
              typeFqn     = typeFqn,
              paramFqn    = paramFqn,
              instanceFqn = instanceFqn,
              scopeFqn    = packageOf(instanceFqn)
            )
          }
        case _ => Nil
    }.toList

  /** `implicit val showInt: Show[Int] = ...` → ValueSignature with TypeRef(Show, [Int]) */
  private def fromValueSig(instanceFqn: String, vs: ValueSignature): List[ImplicitEntry] =
    vs.tpe match
      case tr: TypeRef if tr.symbol.nonEmpty && tr.typeArguments.nonEmpty =>
        val typeFqn = tr.symbol
        tr.typeArguments.flatMap(TypeDepExtractor.typeSymbols).map { paramFqn =>
          ImplicitEntry(
            typeFqn     = typeFqn,
            paramFqn    = paramFqn,
            instanceFqn = instanceFqn,
            scopeFqn    = packageOf(instanceFqn)
          )
        }.toList
      case _ => Nil

  /** `def given_Show_Foo[A](using ...): Show[Foo]` → MethodSignature return type */
  private def fromMethodSig(instanceFqn: String, ms: MethodSignature): List[ImplicitEntry] =
    ms.returnType match
      case tr: TypeRef if tr.symbol.nonEmpty && tr.typeArguments.nonEmpty =>
        val typeFqn = tr.symbol
        tr.typeArguments.flatMap(TypeDepExtractor.typeSymbols).map { paramFqn =>
          ImplicitEntry(
            typeFqn     = typeFqn,
            paramFqn    = paramFqn,
            instanceFqn = instanceFqn,
            scopeFqn    = packageOf(instanceFqn)
          )
        }.toList
      case _ => Nil

  private def packageOf(fqn: String): String =
    val slash = fqn.lastIndexOf('/')
    if slash > 0 then fqn.substring(0, slash + 1) else ""
