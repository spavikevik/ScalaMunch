package scalamunch.parser

import scala.meta.internal.semanticdb.*
import scalamunch.model.*

import java.nio.file.{Files, Path}

/** Reads .semanticdb protobuf files produced by semanticdb-scalac (Scala 2/3 compiler plugin).
 *  Returns resolved type information to augment Scalameta-parsed signatures.
 *
 *  SemanticDB files live at: target/scala-VERSION/meta/RELATIVE_SOURCE_PATH.semanticdb
 *  One .semanticdb file per .scala source file.
 */
object SemanticDbReader:

  def readFile(path: Path): Either[String, List[SemanticSymbol]] =
    readDocs(path).map(_.flatMap(extractSymbols))

  /** Read raw TextDocuments for Phase 3 type/implicit extraction. */
  def readDocs(path: Path): Either[String, List[TextDocument]] =
    if !Files.exists(path) then Left(s"Not found: $path")
    else
      val bytes = Files.readAllBytes(path)
      Right(TextDocuments.parseFrom(bytes).documents.toList)

  def findSemanticDbFiles(root: Path): List[Path] =
    import java.nio.file.FileVisitOption
    import scala.jdk.CollectionConverters.*
    Files.walk(root, FileVisitOption.FOLLOW_LINKS)
      .iterator()
      .asScala
      .filter(p => p.toString.endsWith(".semanticdb"))
      .toList

  /** Maps source path → corresponding .semanticdb path under target/. */
  def semanticDbPath(sourceRoot: Path, sourceFile: Path, scalaVer: String): Path =
    val rel = sourceRoot.relativize(sourceFile)
    sourceRoot
      .resolve(s"target/scala-$scalaVer/meta")
      .resolve(rel.toString + ".semanticdb")

  // ── extraction ────────────────────────────────────────────────────────

  private def extractSymbols(doc: TextDocument): List[SemanticSymbol] =
    doc.symbols.toList.flatMap { info =>
      semanticKind(info.kind).map { k =>
        SemanticSymbol(
          fqn         = info.symbol,
          kind        = k,
          displayName = info.displayName,
          resolvedSig = signatureText(info.signature),
          docString   = info.documentation.map(_.message).filter(_.nonEmpty),
          isImplicit  = (info.properties & SymbolInformation.Property.IMPLICIT.value) != 0,
          isGiven     = (info.properties & SymbolInformation.Property.GIVEN.value) != 0,
          sourceFile  = doc.uri
        )
      }
    }

  private def semanticKind(k: SymbolInformation.Kind): Option[SymbolKind] =
    import SymbolInformation.Kind.*
    k match
      case CLASS         => Some(SymbolKind.Class)
      case TRAIT         => Some(SymbolKind.Trait)
      case INTERFACE     => Some(SymbolKind.Trait)
      case OBJECT        => Some(SymbolKind.Object)
      case METHOD        => Some(SymbolKind.Def)
      case FIELD         => Some(SymbolKind.Val)
      case TYPE          => Some(SymbolKind.Type)
      case PACKAGE       => Some(SymbolKind.Package)
      case PACKAGE_OBJECT => Some(SymbolKind.Object)
      case _             => None

  /** Best-effort readable signature from SemanticDB Signature oneof.
   *  Pattern: concrete message classes directly extend Signature.NonEmpty.
   */
  private def signatureText(sig: Signature): Option[String] =
    sig match
      case cs: ClassSignature  =>
        val parents = cs.parents.map(typeText).filter(_.nonEmpty)
        if parents.isEmpty then None
        else Some(s"extends ${parents.mkString(" with ")}")
      case ms: MethodSignature =>
        val ret = typeText(ms.returnType)
        if ret.nonEmpty then Some(s": $ret") else None
      case vs: ValueSignature  =>
        val t = typeText(vs.tpe)
        if t.nonEmpty then Some(s": $t") else None
      case ts: TypeSignature   =>
        val lo = typeText(ts.lowerBound)
        val hi = typeText(ts.upperBound)
        val parts = List(if lo.nonEmpty then s">: $lo" else "",
                         if hi.nonEmpty then s"<: $hi" else "").filter(_.nonEmpty)
        if parts.isEmpty then None else Some(parts.mkString(" "))
      case _                   => None

  private def typeText(t: Type): String =
    t match
      case tr: TypeRef        => displayFqn(tr.symbol)
      case st: SingleType     => displayFqn(st.symbol)
      case it: IntersectionType => it.types.map(typeText).mkString(" & ")
      case ut: UnionType      => ut.types.map(typeText).mkString(" | ")
      case bt: ByNameType     => s"=> ${typeText(bt.tpe)}"
      case rt: RepeatedType   => s"${typeText(rt.tpe)}*"
      case _                  => ""

  private def displayFqn(sym: String): String =
    sym.replace("/", ".").stripSuffix("#").stripSuffix(".")

/** Resolved symbol from SemanticDB — complements ScalaSymbol from Scalameta. */
case class SemanticSymbol(
  fqn: String,
  kind: SymbolKind,
  displayName: String,
  resolvedSig: Option[String],
  docString: Option[String],
  isImplicit: Boolean,
  isGiven: Boolean,
  sourceFile: String
)
