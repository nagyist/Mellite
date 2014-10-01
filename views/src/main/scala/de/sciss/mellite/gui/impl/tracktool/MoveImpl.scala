/*
 *  MoveImpl.scala
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

package de.sciss.mellite
package gui
package impl
package tracktool

import javax.swing.undo.UndoableEdit

import de.sciss.desktop.edit.CompoundEdit
import de.sciss.lucre.bitemp.{SpanLike => SpanLikeEx}
import de.sciss.lucre.stm
import de.sciss.lucre.swing.edit.EditVar
import de.sciss.mellite.gui.edit.EditAttrMap
import de.sciss.synth.proc.{IntElem, Obj, ExprImplicits}
import java.awt.Cursor
import de.sciss.span.{SpanLike, Span}
import de.sciss.lucre.expr.{Expr, Int => IntEx}
import de.sciss.lucre.synth.Sys

final class MoveImpl[S <: Sys[S]](protected val canvas: TimelineProcCanvas[S])
  extends BasicRegion[S, TrackTool.Move] {

  import TrackTool.{Cursor => _, _}
  import BasicRegion.MinDur

  def defaultCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
  val name          = "Move"
  val icon          = ToolsImpl.getIcon("openhand")

  protected def dragToParam(d: Drag): Move = {
    val eNow  = d.currentEvent
    val dTim0 = d.currentPos - d.firstPos
    val dTrk0 = d.currentTrack - d.firstTrack
    val (dTim, dTrk) = if (eNow.isShiftDown) { // constrain movement to either horizontal or vertical
      val eBefore = d.firstEvent
      if (math.abs(eNow.getX - eBefore.getX) > math.abs(eNow.getY - eBefore.getY)) {  // horizontal
        (dTim0, 0)
      } else {  // vertical
        (0L, dTrk0)
      }
    } else {  // unconstrained
      (dTim0, dTrk0)
    }

    Move(deltaTime = dTim, deltaTrack = dTrk, copy = d.currentEvent.isAltDown)
  }

  protected def commitObj(drag: Move)(span: Expr[S, SpanLike], obj: Obj[S])
                         (implicit tx: S#Tx, cursor: stm.Cursor[S]): Option[UndoableEdit] = {
    var edits = List.empty[UndoableEdit]

    import drag._
    if (deltaTrack != 0) {
      // in the case of const, just overwrite, in the case of
      // var, check the value stored in the var, and update the var
      // instead (recursion). otherwise, it will be some combinatorial
      // expression, and we could decide to construct a binary op instead!
      val expr = ExprImplicits[S]
      import expr._

      val newTrack: Expr[S, Int] = obj.attr[IntElem](TimelineObjView.attrTrackIndex) match {
        case Some(Expr.Var(vr)) => vr() + deltaTrack
        case other => other.fold(0)(_.value) + deltaTrack
      }
      val newTrackOpt = if (newTrack == IntEx.newConst[S](0)) None else Some(newTrack)

      import IntEx.serializer
      val edit = EditAttrMap.expr("Adjust Track Placement", obj, TimelineObjView.attrTrackIndex, newTrackOpt) { ex =>
        IntElem(IntEx.newVar(ex))
      }
      edits ::= edit
    }

    val oldSpan   = span.value
    val minStart  = canvas.timelineModel.bounds.start
    val deltaC    = if (deltaTime >= 0) deltaTime else oldSpan match {
      case Span.HasStart(oldStart)  => math.max(-(oldStart - minStart)         , deltaTime)
      case Span.HasStop (oldStop)   => math.max(-(oldStop  - minStart + MinDur), deltaTime)
    }
    if (deltaC != 0L) {
      val imp = ExprImplicits[S]
      import imp._
      span match {
        case Expr.Var(vr) =>
          // s.transform(_ shift deltaC)
          val newSpan = vr() shift deltaC
          import SpanLikeEx.{serializer, varSerializer}
          val edit    = EditVar.Expr(name, vr, newSpan)
          edits ::= edit
        case _ =>
      }
    }

    CompoundEdit(edits, name)
  }

  protected def dialog(): Option[Move] = {
    println("Not yet implemented - movement dialog")
    //    val box             = Box.createHorizontalBox
    //    val timeTrans       = new DefaultUnitTranslator()
    //    val ggTime          = new BasicParamField(timeTrans)
    //    val spcTimeHHMMSSD  = new ParamSpace(Double.NegativeInfinity, Double.PositiveInfinity, 0.0, 1, 3, 0.0,
    //      ParamSpace.TIME | ParamSpace.SECS | ParamSpace.HHMMSS | ParamSpace.OFF)
    //    ggTime.addSpace(spcTimeHHMMSSD)
    //    ggTime.addSpace(ParamSpace.spcTimeSmpsD)
    //    ggTime.addSpace(ParamSpace.spcTimeMillisD)
    //    GUI.setInitialDialogFocus(ggTime)
    //    box.add(new JLabel("Move by:"))
    //    box.add(Box.createHorizontalStrut(8))
    //    box.add(ggTime)
    //
    //    val tl = timelineModel.timeline
    //    timeTrans.setLengthAndRate(tl.span.length, tl.rate)
    //    if (showDialog(box)) {
    //      val delta = timeTrans.translate(ggTime.value, ParamSpace.spcTimeSmpsD).value.toLong
    //      Some(Move(delta, 0, copy = false))
    //    } else
    None
  }
}
