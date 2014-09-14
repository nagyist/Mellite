/*
 *  TrackTools.scala
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
package gui

import java.awt.Cursor
import javax.swing.undo.UndoableEdit
import de.sciss.lucre.stm

import annotation.switch
import de.sciss.model.{Change, Model}
import de.sciss.synth.proc.FadeSpec
import scala.swing.Component
import javax.swing.Icon
import collection.immutable.{IndexedSeq => Vec}
import de.sciss.mellite.gui.impl.tracktool.{PatchImpl, FunctionImpl, CursorImpl, PaletteImpl, ToolsImpl, ResizeImpl, MuteImpl, MoveImpl, GainImpl, FadeImpl}
import de.sciss.span.Span
import de.sciss.mellite.gui.impl.timeline.ProcView
import de.sciss.lucre.synth.Sys

object TrackTools {
  sealed trait Update[S <: Sys[S]]
  final case class ToolChanged          [S <: Sys[S]](change: Change[TrackTool[S, _]]) extends Update[S]
  final case class VisualBoostChanged   [S <: Sys[S]](change: Change[Float          ]) extends Update[S]
  final case class FadeViewModeChanged  [S <: Sys[S]](change: Change[FadeViewMode   ]) extends Update[S]
  final case class RegionViewModeChanged[S <: Sys[S]](change: Change[RegionViewMode ]) extends Update[S]

  def apply  [S <: Sys[S]](canvas: TimelineProcCanvas[S]): TrackTools[S] = new ToolsImpl(canvas)
  def palette[S <: Sys[S]](control: TrackTools[S], tools: Vec[TrackTool[S, _]]): Component =
    new PaletteImpl[S](control, tools)
}

object RegionViewMode {
  /** No visual indicator for region borders */
  case object None      extends RegionViewMode { final val id = 0 }
  /** Rounded box for region borders */
  case object Box       extends RegionViewMode { final val id = 1 }
  /** Rounded box with region name for region borders */
  case object TitledBox extends RegionViewMode { final val id = 2 }

  def apply(id: Int): RegionViewMode = (id: @switch) match {
    case None     .id => None
    case Box      .id => Box
    case TitledBox.id => TitledBox
  }
}
sealed trait RegionViewMode { def id: Int }

object FadeViewMode {
  /** No visual indicator for fades */
  case object None     extends FadeViewMode { final val id = 0 }
  /** Curve overlays to indicate fades */
  case object Curve    extends FadeViewMode { final val id = 1 }
  /** Gain adjustments to sonogram to indicate fades */
  case object Sonogram extends FadeViewMode { final val id = 2 }

  def apply(id: Int): FadeViewMode = (id: @switch) match {
    case None    .id  => None
    case Curve   .id  => Curve
    case Sonogram.id  => Sonogram
  }
}
sealed trait FadeViewMode {
  def id: Int
}

trait TrackTools[S <: Sys[S]] extends Model[TrackTools.Update[S]] {
  var currentTool   : TrackTool[S, _]
  var visualBoost   : Float
  var fadeViewMode  : FadeViewMode
  var regionViewMode: RegionViewMode
}

object TrackTool {
  sealed trait Update[+A]
  case object DragBegin  extends Update[Nothing]
  final case class DragAdjust[A](value: A) extends Update[A]
  case object DragEnd    extends Update[Nothing] // (commit: AbstractCompoundEdit)
  case object DragCancel extends Update[Nothing]
  /** Direct adjustment without drag period. */
  case class Adjust[A](value: A) extends Update[A]

  final case class Move  (deltaTime : Long, deltaTrack: Int, copy: Boolean)
  type Resize = ProcActions.Resize
  val  Resize = ProcActions.Resize
  final case class Gain    (factor: Float)
  final case class Mute    (engaged: Boolean)
  final case class Fade    (deltaFadeIn: Long, deltaFadeOut: Long, deltaFadeInCurve: Float, deltaFadeOutCurve: Float)
  final case class Function(trackIndex: Int, trackHeight: Int, span: Span) {
    def isValid: Boolean = trackIndex >= 0
  }
  final case class Cursor  (name: Option[String])

  object Patch {
    sealed trait Sink[+S]
    case class Linked[S <: Sys[S]](proc: ProcView[S]) extends Sink[S]
    case class Unlinked(frame: Long, y: Int) extends Sink[Nothing]
  }
  final case class Patch[S <: Sys[S]](source: ProcView[S], sink: Patch.Sink[S])

  final val EmptyFade = FadeSpec(numFrames = 0L)

  type Listener = Model.Listener[Update[Any]]

  def cursor  [S <: Sys[S]](canvas: TimelineProcCanvas[S]): TrackTool[S, Cursor  ] = new CursorImpl  (canvas)
  def move    [S <: Sys[S]](canvas: TimelineProcCanvas[S]): TrackTool[S, Move    ] = new MoveImpl    (canvas)
  def resize  [S <: Sys[S]](canvas: TimelineProcCanvas[S]): TrackTool[S, Resize  ] = new ResizeImpl  (canvas)
  def gain    [S <: Sys[S]](canvas: TimelineProcCanvas[S]): TrackTool[S, Gain    ] = new GainImpl    (canvas)
  def mute    [S <: Sys[S]](canvas: TimelineProcCanvas[S]): TrackTool[S, Mute    ] = new MuteImpl    (canvas)
  def fade    [S <: Sys[S]](canvas: TimelineProcCanvas[S]): TrackTool[S, Fade    ] = new FadeImpl    (canvas)
  def function[S <: Sys[S]](canvas: TimelineProcCanvas[S]): TrackTool[S, Function] = new FunctionImpl(canvas)
  def patch   [S <: Sys[S]](canvas: TimelineProcCanvas[S]): TrackTool[S, Patch[S]] = new PatchImpl   (canvas)
}

trait TrackTool[S <: Sys[S], A] extends Model[TrackTool.Update[A]] {
  def defaultCursor: Cursor
  def icon: Icon
  def name: String

  def install  (component: Component): Unit
  def uninstall(component: Component): Unit
  // def handleSelect(e: MouseEvent, hitTrack: Int, pos: Long, regionOpt: Option[TimelineProcView[S]]): Unit

  def commit(drag: A)(implicit tx: S#Tx, cursor: stm.Cursor[S]): Option[UndoableEdit]
}

//object TrackSlideTool {
//  case class Slide(deltaOuter: Long, deltaInner: Long)
//}
//
//class TrackSlideTool(trackList: TrackList, timelineModel: TimelineView)
//  extends BasicTrackRegionTool[TrackSlideTool.Slide](trackList, timelineModel) {
//
//  import TrackSlideTool._
//
//  def defaultCursor = Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
//
//  val name = "Slide"
//
//  protected def dialog: Option[Slide] = None // not yet supported
//
//  protected def dragToParam(d: Drag): Slide = {
//    val amt = d.currentPos - d.firstPos
//    if (d.firstEvent.isAltDown)
//      Slide(0L, -amt)
//    else
//      Slide(amt, 0L)
//  }
//}

//object TrackAuditionTool {
//  private lazy val cursor = {
//    val tk  = Toolkit.getDefaultToolkit
//    val img = tk.createImage(classOf[TrackMuteTool].getResource("cursor-audition.png"))
//    tk.createCustomCursor(img, new Point(4, 4), "Audition")
//  }
//}
//
//class TrackAuditionTool(doc: Session, protected val trackList: TrackList, protected val timelineModel: TimelineView)
//  extends TrackRegionTool {
//
//  protected def handleSelect(e: MouseEvent, hitTrack: Int, pos: Long, region: TimelineProcView): Unit = {
//    val fromStart = e.isAltDown
//    if (!fromStart) {
//      timelineModel.editor.foreach { ed =>
//        val ce = ed.editBegin("Adjust timeline position")
//        ed.editPosition(ce, pos)
//        ed.editEnd(ce)
//      }
//    }
//    val trackPlayerO = SuperColliderClient.instance.getPlayer(doc).flatMap(_.session)
//      .map(_.timeline(timelineModel.timeline).track(tle.track))
//    (region, trackPlayerO) match {
//      case (ar: AudioRegion, Some(atp: SCAudioTrackPlayer)) =>
//        val frameOffset = if (fromStart) 0L
//        else {
//          ar.span.clip(pos - (ar.audioFile.sampleRate * 0.1).toLong) - ar.span.start
//        }
//        val stopper = atp.play(ar, frameOffset)
//        val comp = e.getComponent
//        comp.addMouseListener(new MouseAdapter {
//          override def mouseReleased(e2: MouseEvent): Unit = {
//            stopper.stop()
//            comp.removeMouseListener(this)
//          }
//        })
//        comp.requestFocus()
//
//      case _ =>
//    }
//  }
//
//  val name          = "Audition"
//  val defaultCursor = TrackAuditionTool.cursor
//}