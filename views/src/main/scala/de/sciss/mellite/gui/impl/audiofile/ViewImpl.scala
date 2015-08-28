/*
 *  ViewImpl.scala
 *  (Mellite)
 *
 *  Copyright (c) 2012-2015 Hanns Holger Rutz. All rights reserved.
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
package audiofile

import java.awt.datatransfer.Transferable

import de.sciss.audiowidgets.TimelineModel
import de.sciss.audiowidgets.impl.TimelineModelImpl
import de.sciss.desktop.impl.UndoManagerImpl
import de.sciss.file.File
import de.sciss.lucre.artifact.{Artifact, ArtifactLocation}
import de.sciss.lucre.stm
import de.sciss.lucre.swing._
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.lucre.synth.Sys
import de.sciss.mellite.gui.impl.component.DragSourceButton
import de.sciss.span.Span
import de.sciss.synth.SynthGraph
import de.sciss.synth.proc.graph.ScanIn
import de.sciss.synth.proc.gui.TransportView
import de.sciss.synth.proc.{AuralSystem, Grapheme, Proc, Timeline, Transport, WorkspaceHandle}
import de.sciss.{sonogram, synth}

import scala.swing.Swing._
import scala.swing.{BorderPanel, BoxPanel, Component, Label, Orientation, Swing}

object ViewImpl {
  def apply[S <: Sys[S]](obj0: Grapheme.Expr.Audio[S])
                        (implicit tx: S#Tx, _workspace: Workspace[S], _cursor: stm.Cursor[S],
                         aural: AuralSystem): AudioFileView[S] = {
    val grapheme      = obj0
    val graphemeV     = grapheme.value // .artifact // store.resolve(element.entity.value.artifact)
    // val sampleRate    = f.spec.sampleRate
    type I            = _workspace.I
    implicit val itx  = _workspace.inMemoryBridge(tx)
    val timeline      = Timeline[I] // proc.ProcGroup.Modifiable[I]
    // val groupObj      = Obj(ProcGroupElem(group))
    val srRatio       = graphemeV.spec.sampleRate / Timeline.SampleRate
    // val fullSpanFile  = Span(0L, f.spec.numFrames)
    val numFramesTL   = (graphemeV.spec.numFrames / srRatio).toLong
    val fullSpanTL    = Span(0L, numFramesTL)

    // ---- we go through a bit of a mess here to convert S -> I ----
    val artifact      = obj0.value.artifact
    val artifDir      = ??? : File // RRR artifact.location.directory
    val iLoc          = ArtifactLocation.newVar[I](artifDir)
    val iArtifact     = Artifact(iLoc, artifact) // iLoc.add(artifact.value)
    val iGrapheme     = Grapheme.Expr.Audio[I](iArtifact, graphemeV.spec, graphemeV.offset, graphemeV.gain)
    val (_, procObj)  = ProcActions.insertAudioRegion[I](timeline, time = Span(0L, numFramesTL),
      /* track = 0, */ grapheme = iGrapheme, gOffset = 0L /* , bus = None */)

    val diff = Proc[I]
    diff.graph() = SynthGraph {
      import synth._
      import ugen._
      val in0 = ScanIn(Proc.scanMainIn)
      // in.poll(1, "audio-file-view")
      val in = if (graphemeV.numChannels == 1) Pan2.ar(in0) else in0  // XXX TODO
      Out.ar(0, in) // XXX TODO
    }
    val diffObj = diff // Obj(Proc.Elem(diff))
    procObj.outputs.get(Proc.scanMainOut).foreach { scanOut =>
      scanOut.add(diff.inputs.add(Proc.scanMainIn))
    }

    import _workspace.inMemoryCursor
    // val transport     = Transport[I, I](group, sampleRate = sampleRate)
    import WorkspaceHandle.Implicits._
    val transport = Transport[I](aural)
    transport.addObject(timeline) // Obj(Timeline(timeline)))
    transport.addObject(diffObj)

    implicit val undoManager = new UndoManagerImpl
    // val offsetView  = LongSpinnerView  (grapheme.offset, "Offset")
    val gainView    = DoubleSpinnerView[S](grapheme.value.gain /* RRR */, "Gain", width = 90)

    import _workspace.inMemoryBridge
    val res: Impl[S, I] = new Impl[S, I](gainView = gainView) {
      val timelineModel = new TimelineModelImpl(fullSpanTL, Timeline.SampleRate)
      val workspace     = _workspace
      val cursor        = _cursor
      val holder        = tx.newHandle(obj0)
      val transportView: TransportView[I] = TransportView[I](transport, timelineModel, hasMillis = true, hasLoop = true)
    }

    deferTx {
      res.guiInit(graphemeV)
    } (tx)
    res
  }

  private abstract class Impl[S <: Sys[S], I <: Sys[I]](gainView: View[S])(implicit inMemoryBridge: S#Tx => I#Tx)
    extends AudioFileView[S] with ComponentHolder[Component] { impl =>

    protected def holder       : stm.Source[S#Tx, Grapheme.Expr.Audio[S]]
    protected def transportView: TransportView[I]
    protected def timelineModel: TimelineModel

    private var _sonogram: sonogram.Overview = _

    def dispose()(implicit tx: S#Tx): Unit = {
      val itx: I#Tx = tx
      transportView.transport.dispose()(itx)
      transportView.dispose()(itx)
      gainView     .dispose()
      deferTx {
        SonogramManager.release(_sonogram)
      }
    }

    def guiInit(snapshot: Grapheme.Value.Audio): Unit = {
      // println("AudioFileView guiInit")
      _sonogram = SonogramManager.acquire(snapshot.artifact)
      // import SonogramManager.executionContext
      //      sono.onComplete {
      //        case x => println(s"<view> $x")
      //      }
      val sonogramView = new ViewJ(_sonogram, timelineModel)

      // val ggDragRegion = new DnD.Button(holder, snapshot, timelineModel)
      val ggDragRegion = new DragSourceButton() {
        protected def createTransferable(): Option[Transferable] = {
          val sp    = timelineModel.selection match {
            case sp0: Span if sp0.nonEmpty =>  sp0
            case _ => timelineModel.bounds
          }
          val drag  = timeline.DnD.AudioDrag(workspace, holder, selection = sp)
          val t     = DragAndDrop.Transferable(timeline.DnD.flavor)(drag)
          Some(t)
        }
        tooltip = "Drag Selected Region"
      }

      val topPane = new BoxPanel(Orientation.Horizontal) {
        contents ++= Seq(
          HStrut(4),
          ggDragRegion,
          // new BusSinkButton[S](impl, ggDragRegion),
          HStrut(4),
          new Label("Gain:"),
          gainView.component,
          HGlue,
          HStrut(4),
          transportView.component,
          HStrut(4)
        )
      }

      val pane = new BorderPanel {
        layoutManager.setVgap(2)
        add(topPane,                BorderPanel.Position.North )
        add(sonogramView.component, BorderPanel.Position.Center)
      }

      component = pane
      // sonogramView.component.requestFocus()
    }

    def obj(implicit tx: S#Tx): Grapheme.Expr.Audio[S] = holder()
  }

  //  private final class BusSinkButton[S <: Sys[S]](view: AudioFileView[S], export: Button)
  //    extends Button("Drop bus") {
  //
  //    icon        = new ImageIcon(Mellite.getClass.getResource("dropicon16.png"))
  //    // this doesn't have any effect?
  //    // GUI.fixWidth(this)
  //    foreground  = Color.gray
  //    focusable   = false
  //
  //    // private var item = Option.empty[stm.Source[S#Tx, Element.Int[S]]]
  //
  //    private val trns = new TransferHandler {
  //      // how to enforce a drop action: https://weblogs.java.net/blog/shan_man/archive/2006/02/choosing_the_dr.html
  //      override def canImport(support: TransferSupport): Boolean =
  //        if (support.isDataFlavorSupported(FolderView.SelectionFlavor) &&
  //           ((support.getSourceDropActions & TransferHandler.COPY) != 0)) {
  //          support.setDropAction(TransferHandler.COPY)
  //          true
  //        } else false
  //
  //      override def importData(support: TransferSupport): Boolean = {
  //        val t     = support.getTransferable
  //        val data  = t.getTransferData(FolderView.SelectionFlavor).asInstanceOf[FolderView.SelectionDnDData[S]]
  //        (data.workspace == view.workspace) && {
  //          data.selection.exists { nodeView =>
  //            nodeView.renderData match {
  //              case ev: ObjView.Int[S] =>
  //                // export.bus  = Some(ev.obj)
  //                text        = ev.name
  //                foreground  = null
  //                repaint()
  //                true
  //
  //              case _ => false
  //            }
  //          }
  //        }
  //      }
  //    }
  //    peer.setTransferHandler(trns)
  //  }
}