/*
 *  ProcActions.scala
 *  (Mellite)
 *
 *  Copyright (c) 2012-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss
package mellite

import lucre.expr.Expr
import span.{Span, SpanLike}
import de.sciss.synth.proc.{ObjKeys, Timeline, Obj, SynthGraphs, ExprImplicits, Scan, Grapheme, Proc, StringElem, DoubleElem, IntElem, BooleanElem}
import de.sciss.lucre.bitemp.BiExpr
import de.sciss.synth.proc
import scala.util.control.NonFatal
import collection.breakOut
import de.sciss.lucre.expr.{Int => IntEx, Boolean => BooleanEx, Double => DoubleEx, Long => LongEx, String => StringEx}
import de.sciss.lucre.bitemp.{Span => SpanEx}
import proc.Implicits._
import de.sciss.lucre.event.Sys
import scala.language.existentials

object ProcActions {
  private val MinDur    = 32

  // scalac still has bug finding Timeline.Modifiable
  // private type TimelineMod[S <: Sys[S]] = Timeline.Modifiable[S] // BiGroup.Modifiable[S, Proc[S], Proc.Update[S]]
  private type TimelineMod[S <: Sys[S]] = Timeline.Modifiable[S]

  final case class Resize(deltaStart: Long, deltaStop: Long)

  /** Queries the audio region's grapheme segment start and audio element. */
  def getAudioRegion[S <: Sys[S]](/* span: Expr[S, SpanLike], */ proc: Proc.Obj[S])
                                 (implicit tx: S#Tx): Option[(Expr[S, Long], Grapheme.Expr.Audio[S])] = {
    for {
      scan <- proc.elem.peer.scans.get(Proc.Obj.graphAudio)
      Scan.Link.Grapheme(g) <- scan.sources.toList.headOption
      BiExpr(time, audio: Grapheme.Expr.Audio[S]) <- g.at(0L)
    } yield (time, audio)
  }

  def resize[S <: Sys[S]](span: Expr[S, SpanLike], obj: Obj[S],
                          amount: Resize, minStart: Long /* timelineModel: TimelineModel */)
                         (implicit tx: S#Tx): Unit = {
    import amount._

    val oldSpan   = span.value
    // val minStart  = timelineModel.bounds.start
    val dStartC   = if (deltaStart >= 0) deltaStart else oldSpan match {
      case Span.HasStart(oldStart)  => math.max(-(oldStart - minStart)         , deltaStart)
      case _ => 0L
    }
    val dStopC   = if (deltaStop >= 0) deltaStop else oldSpan match {
      case Span.HasStop (oldStop)   => math.max(-(oldStop  - minStart + MinDur), deltaStop)
      case _ => 0L
    }

    if (dStartC != 0L || dStopC != 0L) {
      val imp = ExprImplicits[S]
      import imp._

      val (dStartCC, dStopCC) = (dStartC, dStopC)

      span match {
        case Expr.Var(s) =>
          s.transform { oldSpan =>
            oldSpan.value match {
              case Span.From (start)  => Span.From (start + dStartCC)
              case Span.Until(stop )  => Span.Until(stop  + dStopCC )
              case Span(start, stop)  =>
                val newStart = start + dStartCC
                Span(newStart, math.max(newStart + MinDur, stop + dStopCC))
              case other => other
            }
          }

        case _ =>
      }
    }
  }

  /** Changes or removes the name of a process.
    *
    * @param obj the proc to rename
    * @param name the new name or `None` to remove the name attribute
    */
  def rename[S <: Sys[S]](obj: Obj[S], name: Option[String])(implicit tx: S#Tx): Unit = {
    val attr  = obj.attr
    val imp   = ExprImplicits[S]
    import imp._
    name match {
      case Some(n) =>
        attr[StringElem](ObjKeys.attrName) match {
          case Some(Expr.Var(vr)) => vr() = n
          case _                  => attr.put(ObjKeys.attrName, Obj(StringElem(StringEx.newVar(n))))
        }

      case _ => attr.remove(ObjKeys.attrName)
    }
  }

  // @param span the process span. if given, tries to copy the audio grapheme as well.
  /** Makes a copy of a proc. Copies the graph and all attributes, creates scans with the same keys
    * and connects _outgoing_ scans.
    *
    * @param obj the process to copy
    * @return
    */
  def copy[S <: Sys[S]](obj: Obj[S])(implicit tx: S#Tx): Obj[S] = {
    val elemNew = obj.elem.mkCopy()
    val res     = Obj(elemNew)
    val resAttr = res.attr
    obj.attr.iterator.foreach { case (key, value) =>
      val valueCopy = copy(value)
      resAttr.put(key, valueCopy)
    }

    res match {
      case Proc.Obj(procObj) =>
        ProcActions.getAudioRegion(procObj).foreach { case (time, audio) =>
          val imp = ExprImplicits[S]
          import imp._
          val pNew        = procObj.elem.peer
          val scanW       = pNew.scans.add(Proc.Obj.graphAudio)
          scanW.sources.toList.foreach(scanW.removeSource)
          val grw         = Grapheme[S](audio.spec.numChannels)
          val gStart      = LongEx  .newVar(time        .value)
          val audioOffset = LongEx  .newVar(audio.offset.value) // XXX TODO
          val audioGain   = DoubleEx.newVar(audio.gain  .value)
          val gElem       = Grapheme.Expr.Audio(audio.artifact, audio.value.spec, audioOffset, audioGain)
          val bi: Grapheme.TimedElem[S] = BiExpr(gStart, gElem)
          grw.add(bi)
          scanW addSource grw
        }

      case _ =>
    }

    //    // connect outgoing scans
    //    obj.elem.peer.scans.iterator.foreach {
    //      case (key, scan) =>
    //        val sinks = scan.sinks
    //        if (sinks.nonEmpty) {
    //          pNew.scans.get(key).foreach { scan2 =>
    //            scan.sinks.foreach { link =>
    //              scan2.addSink(link)
    //            }
    //          }
    //        }
    //    }

    res
  }

  def setGain[S <: Sys[S]](proc: Proc.Obj[S], gain: Double)(implicit tx: S#Tx): Unit = {
    val attr  = proc.attr
    val imp   = ExprImplicits[S]
    import imp._

    if (gain == 1.0) {
      attr.remove(ObjKeys.attrGain)
    } else {
      attr[DoubleElem](ObjKeys.attrGain) match {
        case Some(Expr.Var(vr)) => vr() = gain
        case _                  => attr.put(ObjKeys.attrGain, Obj(DoubleElem(DoubleEx.newVar(gain))))
      }
    }
  }

  def adjustGain[S <: Sys[S]](obj: Obj[S], factor: Double)(implicit tx: S#Tx): Unit = {
    if (factor == 1.0) return

    val attr  = obj.attr
    val imp   = ExprImplicits[S]
    import imp._

    attr[DoubleElem](ObjKeys.attrGain) match {
      case Some(Expr.Var(vr)) => vr.transform(_ * factor)
      case other =>
        val newGain = other.fold(1.0)(_.value) * factor
        attr.put(ObjKeys.attrGain, Obj(DoubleElem(DoubleEx.newVar(newGain))))
    }
  }

  def setBus[S <: Sys[S]](objects: Iterable[Obj[S]], intExpr: Expr[S, Int])(implicit tx: S#Tx): Unit = {
    val attr    = IntElem(intExpr)
    objects.foreach { proc =>
      proc.attr.put(ObjKeys.attrBus, Obj(attr))
    }
  }

  def toggleMute[S <: Sys[S]](obj: Obj[S])(implicit tx: S#Tx): Unit = {
    val imp   = ExprImplicits[S]
    import imp._

    val attr = obj.attr
    attr[BooleanElem](ObjKeys.attrMute) match {
      // XXX TODO: BooleanEx should have `not` operator
      case Some(Expr.Var(vr)) => vr.transform { old => val vOld = old.value; !vOld: Expr[S, Boolean] }
      case _                  => attr.put(ObjKeys.attrMute, Obj(BooleanElem(BooleanEx.newVar(true))))
    }
  }

  def setSynthGraph[S <: Sys[S]](procs: Iterable[Proc.Obj[S]], codeElem: Code.Obj[S])
                                (implicit tx: S#Tx): Boolean = {
    val code = codeElem.elem.peer.value
    code match {
      case csg: Code.SynthGraph =>
        try {
          val sg = csg.execute {}  // XXX TODO: compilation blocks, not good!

          val scanKeys: Set[String] = sg.sources.collect {
            case proc.graph.ScanIn   (key)    => key
            case proc.graph.ScanOut  (key, _) => key
            // case proc.graph.ScanInFix(key, _) => key
          } (breakOut)
          // sg.sources.foreach(println)
          if (scanKeys.nonEmpty) log(s"SynthDef has the following scan keys: ${scanKeys.mkString(", ")}")

          val attrNameOpt = codeElem.attr.get(ObjKeys.attrName)
          procs.foreach { p =>
            p.elem.peer.graph() = SynthGraphs.newConst[S](sg)  // XXX TODO: ideally would link to code updates
            attrNameOpt.foreach(attrName => p.attr.put(ObjKeys.attrName, attrName))
            val scans = p.elem.peer.scans
            val toRemove = scans.iterator.collect {
              case (key, scan) if !scanKeys.contains(key) && scan.sinks.isEmpty && scan.sources.isEmpty => key
            }
            toRemove.foreach(scans.remove(_)) // unconnected scans which are not referred to from synth def
            val existing = scans.iterator.collect {
              case (key, _) if scanKeys contains key => key
            }
            val toAdd = scanKeys -- existing.toSet
            toAdd.foreach(scans.add)
          }
          true

        } catch {
          case NonFatal(e) =>
            e.printStackTrace()
            false
        }

      case _ => false
    }
  }

  def mkAudioRegion[S <: Sys[S]](
      time      : Span,
      grapheme  : Grapheme.Expr.Audio[S],
      gOffset   : Long,
      bus       : Option[Expr[S, Int]]) // stm.Source[S#Tx, Element.Int[S]]])
     (implicit tx: S#Tx): (Expr[S, Span], Proc.Obj[S]) = {

    val imp = ExprImplicits[S]
    import imp._

    // val srRatio = grapheme.spec.sampleRate / Timeline.SampleRate
    val spanV   = time // Span(time, time + (selection.length / srRatio).toLong)
    val span    = SpanEx.newVar[S](spanV)
    val proc    = Proc[S]
    val obj     = Obj(Proc.Elem(proc))
    val attr    = obj.attr
//    if (track >= 0) attr.put(TimelineObjView.attrTrackIndex, Obj(IntElem(IntEx.newVar(track))))
    bus.foreach { busEx =>
      val bus = IntElem(busEx)
      attr.put(ObjKeys.attrBus, Obj(bus))
    }

    val scanIn  = proc.scans.add(Proc.Obj.graphAudio )
    /*val sOut=*/ proc.scans.add(Proc.Obj.scanMainOut)
    val grIn    = Grapheme[S](grapheme.spec.numChannels)

    // we preserve data.source(), i.e. the original audio file offset
    // ; therefore the grapheme element must start `selection.start` frames
    // before the insertion position `drop.frame`

    // val gStart  = LongEx.newVar(time - selection.start)
    val gStart = LongEx.newVar(-gOffset)
    val bi: Grapheme.TimedElem[S] = BiExpr(gStart, grapheme)
    grIn.add(bi)
    scanIn addSource grIn
    proc.graph() = SynthGraphs.tape
    (span, obj)
  }

  /** Inserts a new audio region proc into a given group.
    *
    * @param group      the group to insert the proc into
    * @param time       the time span on the outer timeline
    * @param grapheme   the grapheme carrying the underlying audio file
    * @param gOffset    the selection start with respect to the grapheme.
    *                   This is inside the underlying audio file (but using timeline sample-rate),
    *                   whereas the proc will be placed in the group aligned with `time`.
    * @param bus        an optional bus to assign
    * @return           a tuple consisting of the span expression and the newly created proc.
    */
  def insertAudioRegion[S <: Sys[S]](group     : TimelineMod[S],
                                     time      : Span,
                                     grapheme  : Grapheme.Expr.Audio[S],
                                     gOffset   : Long,
                                     bus       : Option[Expr[S, Int]]) // stm.Source[S#Tx, Element.Int[S]]])
                                    (implicit tx: S#Tx): (Expr[S, Span], Proc.Obj[S]) = {
    val res @ (span, obj) = mkAudioRegion(time, grapheme, gOffset, bus)
    group.add(span, obj)
    res
  }

  def insertGlobalRegion[S <: Sys[S]](
      group     : TimelineMod[S],
      name      : String,
      bus       : Option[Expr[S, Int]]) // stm.Source[S#Tx, Element.Int[S]]])
     (implicit tx: S#Tx): Proc.Obj[S] = {

    val imp = ExprImplicits[S]
    import imp._

    val proc    = Proc[S]
    val obj     = Obj(Proc.Elem(proc))
    val attr    = obj.attr
    val nameEx  = StringEx.newVar[S](StringEx.newConst(name))
    attr.put(ObjKeys.attrName, Obj(StringElem(nameEx)))

    group.add(Span.All, obj) // constant span expression
    obj
  }

  private def addLink[S <: Sys[S]](sourceKey: String, source: Scan[S], sinkKey: String, sink: Scan[S])
                                  (implicit tx: S#Tx): Unit = {
    log(s"Link $sourceKey / $source to $sinkKey / $sink")
    source.addSink(Scan.Link.Scan(sink))
  }

  def removeLink[S <: Sys[S]](sourceKey: String, source: Scan[S], sinkKey: String, sink: Scan[S])
                             (implicit tx: S#Tx): Unit = {
    log(s"Unlink $sourceKey / $source from $sinkKey / $sink")
    source.removeSink(Scan.Link.Scan(sink))
  }

  def linkOrUnlink[S <: Sys[S]](out: Proc.Obj[S], in: Proc.Obj[S])(implicit tx: S#Tx): Boolean = {
    val outsIt  = out.elem.peer.scans.iterator // .toList
    val insSeq0 = in .elem.peer.scans.iterator.toIndexedSeq

    // if there is already a link between the two, take the drag gesture as a command to remove it
    val existIt = outsIt.flatMap { case (srcKey, srcScan) =>
      srcScan.sinks.toList.flatMap {
        case Scan.Link.Scan(peer) => insSeq0.find(_._2 == peer).map {
          case (sinkKey, sinkScan) => (srcKey, srcScan, sinkKey, sinkScan)
        }

        case _ => None
      }
    }

    if (existIt.hasNext) {
      val (srcKey, srcScan, sinkKey, sinkScan) = existIt.next()
      removeLink(srcKey, srcScan, sinkKey, sinkScan)
      true

    } else {
      // XXX TODO cheesy way to distinguish ins and outs now :-E ... filter by name
      val outsSeq = out.elem.peer.scans.iterator.filter(_._1.startsWith("out")).toIndexedSeq
      val insSeq  = insSeq0                     .filter(_._1.startsWith("in"))

      if (outsSeq.isEmpty || insSeq.isEmpty) return false   // nothing to patch

      if (outsSeq.size == 1 && insSeq.size == 1) {    // exactly one possible connection, go ahead
        val (srcKey , src ) = outsSeq.head
        val (sinkKey, sink) = insSeq .head
        addLink(srcKey, src, sinkKey, sink)
        true

      } else {  // present dialog to user
        log(s"Possible outs: ${outsSeq.map(_._1).mkString(", ")}; possible ins: ${insSeq.map(_._1).mkString(", ")}")
        println(s"Woop. Multiple choice... Dialog not yet implemented...")
        false
      }
    }
  }
}