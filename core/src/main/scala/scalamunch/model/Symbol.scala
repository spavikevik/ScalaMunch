package scalamunch.model

import java.time.Instant

enum SymbolKind derives CanEqual:
  case Class, Trait, Object, Def, Val, Var, Type, Given, Extension, Package, TypeParam

enum ScalaVersion derives CanEqual:
  case Scala2, Scala3

/** Compressed representation of a Scala symbol. Full source stored separately (compressed). */
case class ScalaSymbol(
  fqn: String,
  kind: SymbolKind,
  name: String,
  scalaVersion: ScalaVersion,
  /** Reconstructed declaration — no body, no imports. Core token-reduction unit. */
  signature: String,
  doc: Option[String],
  file: String,
  lineStart: Int,
  lineEnd: Int,
  sourceHash: String,
  typeParams: List[String],
  annotations: List[String],
  parentFqns: List[String],
  enclosingFqn: Option[String]
)

case class TypeDep(fromFqn: String, toFqn: String, rel: TypeRel)

enum TypeRel:
  case Extends, Uses, ImplicitParam, Bound, Param, Return, TypeArg

/** Links a typeclass instance to its type argument. Used for implicit/given resolution. */
case class ImplicitEntry(
  typeFqn: String,    // e.g. "cats/Show#"
  paramFqn: String,   // e.g. "com/example/Foo#"
  instanceFqn: String,
  scopeFqn: String
)

case class FileEntry(
  path: String,
  digest: String,
  scalaVersion: ScalaVersion,
  symbolFqns: List[String],
  indexedAt: Instant
)

case class IndexStats(
  symbolCount: Int,
  fileCount: Int,
  implicitCount: Int,
  typDepCount: Int,
  lastUpdated: Instant
)
