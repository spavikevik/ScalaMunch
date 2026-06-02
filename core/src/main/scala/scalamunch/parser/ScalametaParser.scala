package scalamunch.parser

import scala.meta.*
import scalamunch.extract.SignatureExtractor
import scalamunch.model.*

import java.nio.file.Path
import java.security.MessageDigest

object ScalametaParser:

  def parseFile(file: Path, sv: ScalaVersion): Either[String, List[ScalaSymbol]] =
    // Provide a single concrete Dialect as local given — prevents ambiguity with
    // all dialect vals that scalameta exposes in scope.
    given Dialect = if sv == ScalaVersion.Scala3 then dialects.Scala3 else dialects.Scala213
    val source   = scala.io.Source.fromFile(file.toFile, "UTF-8")
    val text     = try source.mkString finally source.close()
    val hash     = sha256(text)

    Input.File(file.toFile).parse[Source] match
      case Parsed.Success(tree) =>
        Right(extractAll(tree, file.toString, hash, sv))
      case Parsed.Error(pos, msg, _) =>
        Left(s"${file}:${pos.startLine}: $msg")

  // ── traversal ─────────────────────────────────────────────────────────

  private def extractAll(
    tree: Source,
    file: String,
    hash: String,
    sv: ScalaVersion
  ): List[ScalaSymbol] =
    val buf = collection.mutable.ListBuffer.empty[ScalaSymbol]

    def visit(stats: List[Stat], pkgPrefix: String, enclosing: Option[String]): Unit =
      stats.foreach {
        case pkg: Pkg =>
          val newPkg = pkgRefStr(pkg.ref) + "/"
          visit(pkg.stats, newPkg, enclosing)

        case defn: Defn.Class =>
          val fqn = s"${pkgPrefix}${defn.name.value}#"
          buf += mkSymbol(
            fqn       = fqn,
            kind      = SymbolKind.Class,
            name      = defn.name.value,
            sig       = SignatureExtractor.forClass(defn),
            doc       = extractDoc(defn),
            tparams   = defn.tparams.map(_.name.value),
            annots    = extractAnnotations(defn.mods),
            parents   = defn.templ.inits.map(initStr),
            enclosing = enclosing,
            pos       = defn.pos,
            file, hash, sv
          )
          visit(defn.templ.stats, s"${pkgPrefix}${defn.name.value}.", Some(fqn))

        case defn: Defn.Trait =>
          val fqn = s"${pkgPrefix}${defn.name.value}#"
          buf += mkSymbol(
            fqn       = fqn,
            kind      = SymbolKind.Trait,
            name      = defn.name.value,
            sig       = SignatureExtractor.forTrait(defn),
            doc       = extractDoc(defn),
            tparams   = defn.tparams.map(_.name.value),
            annots    = extractAnnotations(defn.mods),
            parents   = defn.templ.inits.map(initStr),
            enclosing = enclosing,
            pos       = defn.pos,
            file, hash, sv
          )
          visit(defn.templ.stats, s"${pkgPrefix}${defn.name.value}.", Some(fqn))

        case defn: Defn.Object =>
          val fqn = s"${pkgPrefix}${defn.name.value}."
          buf += mkSymbol(
            fqn       = fqn,
            kind      = SymbolKind.Object,
            name      = defn.name.value,
            sig       = SignatureExtractor.forObject(defn),
            doc       = extractDoc(defn),
            tparams   = Nil,
            annots    = extractAnnotations(defn.mods),
            parents   = defn.templ.inits.map(initStr),
            enclosing = enclosing,
            pos       = defn.pos,
            file, hash, sv
          )
          visit(defn.templ.stats, s"${pkgPrefix}${defn.name.value}.", Some(fqn))

        case defn: Defn.Def =>
          val fqn = s"${pkgPrefix}${defn.name.value}()."
          buf += mkSymbol(
            fqn       = fqn,
            kind      = SymbolKind.Def,
            name      = defn.name.value,
            sig       = SignatureExtractor.forDef(defn),
            doc       = extractDoc(defn),
            tparams   = defn.tparams.map(_.name.value),
            annots    = extractAnnotations(defn.mods),
            parents   = Nil,
            enclosing = enclosing,
            pos       = defn.pos,
            file, hash, sv
          )

        case defn: Defn.Val =>
          defn.pats.collect { case Pat.Var(name) => name }.foreach { name =>
            val fqn = s"${pkgPrefix}${name.value}."
            buf += mkSymbol(
              fqn       = fqn,
              kind      = SymbolKind.Val,
              name      = name.value,
              sig       = SignatureExtractor.forVal(defn),
              doc       = extractDoc(defn),
              tparams   = Nil,
              annots    = extractAnnotations(defn.mods),
              parents   = Nil,
              enclosing = enclosing,
              pos       = defn.pos,
              file, hash, sv
            )
          }

        case defn: Defn.Type =>
          val fqn = s"${pkgPrefix}${defn.name.value}#"
          buf += mkSymbol(
            fqn       = fqn,
            kind      = SymbolKind.Type,
            name      = defn.name.value,
            sig       = srcSlice(defn),
            doc       = extractDoc(defn),
            tparams   = defn.tparams.map(_.name.value),
            annots    = Nil,
            parents   = Nil,
            enclosing = enclosing,
            pos       = defn.pos,
            file, hash, sv
          )

        case defn: Defn.Given =>
          val givenName = defn.name.value.ifEmpty("anon")
          val fqn       = s"${pkgPrefix}${givenName}."
          buf += mkSymbol(
            fqn       = fqn,
            kind      = SymbolKind.Given,
            name      = givenName,
            sig       = SignatureExtractor.forGiven(defn),
            doc       = extractDoc(defn),
            tparams   = defn.tparams.map(_.name.value),
            annots    = Nil,
            parents   = defn.templ.inits.map(initStr),
            enclosing = enclosing,
            pos       = defn.pos,
            file, hash, sv
          )

        // Scala 3 alias given: `given eqInt: Eq[Int] = expr`
        case defn: Defn.GivenAlias =>
          val givenName = defn.name.value.ifEmpty("anon")
          val fqn       = s"${pkgPrefix}${givenName}."
          val declType  = srcSlice(defn.decltpe)
          val sig       = s"given $givenName: $declType"
          buf += mkSymbol(
            fqn       = fqn,
            kind      = SymbolKind.Given,
            name      = givenName,
            sig       = sig,
            doc       = extractDoc(defn),
            tparams   = defn.tparams.map(_.name.value),
            annots    = Nil,
            parents   = List(srcSlice(defn.decltpe)),
            enclosing = enclosing,
            pos       = defn.pos,
            file, hash, sv
          )

        case _ => ()
      }

    visit(tree.stats, pkgFromTree(tree), None)
    buf.toList

  // ── helpers ───────────────────────────────────────────────────────────

  private def mkSymbol(
    fqn: String, kind: SymbolKind, name: String, sig: String,
    doc: Option[String], tparams: List[String], annots: List[String],
    parents: List[String], enclosing: Option[String], pos: Position,
    file: String, hash: String, sv: ScalaVersion
  ): ScalaSymbol =
    ScalaSymbol(
      fqn          = fqn,
      kind         = kind,
      name         = name,
      scalaVersion = sv,
      signature    = sig,
      doc          = doc,
      file         = file,
      lineStart    = pos.startLine + 1,
      lineEnd      = pos.endLine + 1,
      sourceHash   = hash,
      typeParams   = tparams,
      annotations  = annots,
      parentFqns   = parents,
      enclosingFqn = enclosing
    )

  private def pkgFromTree(tree: Source): String =
    tree.stats.collectFirst { case pkg: Pkg => pkgRefStr(pkg.ref) + "/" }.getOrElse("")

  /** Reconstruct package FQN without calling .syntax (which requires implicit Dialect). */
  private def pkgRefStr(ref: Term.Ref): String = ref match
    case Term.Select(qual: Term.Ref, name) => s"${pkgRefStr(qual)}/${name.value}"
    case Term.Name(n)                      => n
    case other                             => srcSlice(other)

  /** Init (parent type) as a source-extracted string. */
  private def initStr(init: Init): String = srcSlice(init.tpe)

  /** Slice source text for any tree node — no Dialect needed. */
  private def srcSlice(tree: Tree): String =
    val s = tree.pos.input.text
    if s.isEmpty then ""
    else
      val from = tree.pos.start.max(0)
      val to   = tree.pos.end.min(s.length)
      if from < to then s.substring(from, to).split('\n').map(_.strip).mkString(" ")
      else ""

  private def extractAnnotations(mods: List[Mod]): List[String] =
    mods.collect { case ann: Mod.Annot => s"@${srcSlice(ann.init.tpe)}" }

  /** Extracts the nearest preceding scaladoc comment by searching source text.
   *  No tokenizer needed — avoids Dialect implicit entirely.
   */
  private def extractDoc(tree: Tree): Option[String] =
    val src = tree.pos.input.text
    val nodeStart = tree.pos.start
    if nodeStart <= 0 then return None
    val before = src.substring(0, nodeStart).stripTrailing()
    if !before.endsWith("*/") then None
    else
      val docEnd = before.lastIndexOf("*/")
      val docStart = before.lastIndexOf("/**", docEnd)
      if docStart < 0 || docStart > docEnd then None
      else
        val raw = before.substring(docStart + 3, docEnd)
        val cleaned = raw.split('\n')
          .map(_.strip.stripPrefix("*").strip)
          .filter(_.nonEmpty)
          .mkString(" ")
        if cleaned.isEmpty then None else Some(cleaned)

  private def sha256(s: String): String =
    val d = MessageDigest.getInstance("SHA-256")
    d.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString

  extension (s: String)
    private def ifEmpty(fallback: String): String = if s.isEmpty then fallback else s
