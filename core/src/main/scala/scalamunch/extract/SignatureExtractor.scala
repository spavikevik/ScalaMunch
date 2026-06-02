package scalamunch.extract

import scala.meta.*

/** Extracts declaration-only signatures from Scalameta tree nodes.
 *  Strategy: slice source text from node start → body start, normalize whitespace.
 *  This preserves exact Scala syntax without needing to reconstruct from AST parts.
 */
object SignatureExtractor:

  def forDef(defn: Defn.Def): String =
    val src      = defn.pos.input.text
    val bodyStart = defn.body.pos.start
    slice(src, defn.pos.start, bodyStart)
      .stripSuffix("=")
      .strip()
      .normalizeWs

  def forClass(defn: Defn.Class): String =
    val src    = defn.pos.input.text
    val cutAt  = templateBodyStart(defn.templ).getOrElse(defn.templ.pos.end)
    slice(src, defn.pos.start, cutAt)
      .trimBodyOpeners.normalizeWs

  def forTrait(defn: Defn.Trait): String =
    val src   = defn.pos.input.text
    val cutAt = templateBodyStart(defn.templ).getOrElse(defn.templ.pos.end)
    slice(src, defn.pos.start, cutAt)
      .trimBodyOpeners.normalizeWs

  def forObject(defn: Defn.Object): String =
    val src   = defn.pos.input.text
    val cutAt = templateBodyStart(defn.templ).getOrElse(defn.templ.pos.end)
    slice(src, defn.pos.start, cutAt)
      .trimBodyOpeners.normalizeWs

  def forType(defn: Defn.Type): String =
    srcSlice(defn)

  def forDecl(decl: Decl.Def): String =
    srcSlice(decl)

  def forVal(defn: Defn.Val): String =
    val src = defn.pos.input.text
    // slice up to the `=` (body start)
    val bodyStart = defn.rhs.pos.start
    slice(src, defn.pos.start, bodyStart)
      .stripSuffix("=")
      .strip()
      .normalizeWs

  /** Scala 3 given definition */
  def forGiven(defn: Defn.Given): String =
    val src   = defn.pos.input.text
    val cutAt = templateBodyStart(defn.templ).getOrElse(defn.templ.pos.end)
    slice(src, defn.pos.start, cutAt)
      .trimBodyOpeners.normalizeWs

  // ── helpers ────────────────────────────────────────────────────────────

  private def srcSlice(tree: Tree): String =
    val s    = tree.pos.input.text
    val from = tree.pos.start.max(0)
    val to   = tree.pos.end.min(s.length)
    if from < to then s.substring(from, to).normalizeWs else ""

  private def templateBodyStart(templ: Template): Option[Int] =
    templ.stats.headOption.map { firstStat =>
      val src    = templ.pos.input.text
      val from   = templ.pos.start.max(0)
      val to     = firstStat.pos.start.min(src.length)
      val region = if from < to then src.substring(from, to) else ""
      // Walk backwards to find the last `{` or `:` that opens the body.
      // This skips over scaladoc comments that precede the first member.
      val lastBrace = region.lastIndexOf('{')
      val lastColon = region.lastIndexOf(':')
      val bodyOpen  = lastBrace.max(lastColon)
      if bodyOpen >= 0 then from + bodyOpen + 1 else firstStat.pos.start
    }

  private def slice(src: String, from: Int, to: Int): String =
    if from >= 0 && to <= src.length && from < to then src.substring(from, to) else ""

  extension (s: String)
    private def normalizeWs: String =
      s.split('\n').map(_.strip).filter(_.nonEmpty).mkString(" ").replaceAll(" {2,}", " ")
    private def trimBodyOpeners: String =
      s.strip().stripSuffix("{").stripSuffix(":").strip()
