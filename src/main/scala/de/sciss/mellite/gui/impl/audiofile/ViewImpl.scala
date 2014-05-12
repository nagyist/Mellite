/*
 *  ViewImpl.scala
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
package audiofile

import de.sciss.synth.proc.{ArtifactLocation, Obj, AudioGraphemeElem, AuralSystem, Grapheme, ExprImplicits}
import de.sciss.lucre.stm
import scala.swing.{Button, BoxPanel, Orientation, Swing, BorderPanel, Component}
import java.awt.Color
import Swing._
import de.sciss.span.Span
import de.sciss.sonogram
import javax.swing.{TransferHandler, ImageIcon}
import javax.swing.TransferHandler.TransferSupport
import de.sciss.synth.proc
import de.sciss.audiowidgets.impl.TimelineModelImpl
import de.sciss.audiowidgets.TimelineModel
import de.sciss.lucre.synth.Sys
import de.sciss.lucre.swing._
import de.sciss.lucre.swing.impl.ComponentHolder
import de.sciss.file.File

object ViewImpl {
  def apply[S <: Sys[S]](doc: Document[S], obj0: Obj.T[S, AudioGraphemeElem])
                        (implicit tx: S#Tx, aural: AuralSystem): AudioFileView[S] = {
    val f             = obj0.elem.peer.value // .artifact // store.resolve(element.entity.value.artifact)
    val sampleRate    = f.spec.sampleRate
    type I            = doc.I
    implicit val itx  = doc.inMemoryBridge(tx)
    val group         = proc.ProcGroup.Modifiable[I]
    val fullSpan      = Span(0L, f.spec.numFrames)

    // ---- we go through a bit of a mess here to convert S -> I ----
    val graphemeV     = f // elem.entity.value
    val imp = ExprImplicits[I]
    import imp._
    val artifact      = obj0.elem.peer.artifact
    val artifDir      = artifact.location.directory
    val iLoc          = ArtifactLocation.Modifiable[I](artifDir)
    val iArtifact     = iLoc.add(artifact.value)
    val iGrapheme     = Grapheme.Expr.Audio[I](iArtifact, graphemeV.spec, graphemeV.offset, graphemeV.gain)
    ProcActions.insertAudioRegion[I](group, time = 0L, track = 0, grapheme = iGrapheme, selection = fullSpan,
      bus = None)

    import doc.inMemoryCursor

    val res: Impl[S, I] = new Impl[S, I] {
      val timelineModel = new TimelineModelImpl(fullSpan, sampleRate)
      val document      = doc.folder
      val holder        = tx.newHandle(obj0)
      val transportView: TransportView[I] = TransportView[I, I](group, sampleRate, timelineModel)
    }

    deferTx {
      res.guiInit(f)
    } (tx)
    res
  }

  private abstract class Impl[S <: Sys[S], I <: Sys[I]]
    extends AudioFileView[S] with ComponentHolder[Component] { impl =>

    protected def holder       : stm.Source[S#Tx, Obj.T[S, AudioGraphemeElem]]
    val document               : File // Document[S]
    protected def transportView: TransportView[I]
    protected def timelineModel: TimelineModel

    private var _sono: sonogram.Overview = _

    def dispose()(implicit tx: S#Tx): Unit =
      deferTx {
        SonogramManager.release(_sono)
      }

    def guiInit(snapshot: Grapheme.Value.Audio): Unit = {
      // println("AudioFileView guiInit")
      _sono = SonogramManager.acquire(snapshot.artifact)
      // import SonogramManager.executionContext
      //      sono.onComplete {
      //        case x => println(s"<view> $x")
      //      }
      val sonoView  = new ViewJ(_sono, timelineModel)

      val ggDragRegion = new DnD.Button(document, holder, snapshot, timelineModel)

      val topPane = new BoxPanel(Orientation.Horizontal) {
        contents ++= Seq(
          HStrut(4),
          ggDragRegion,
          new BusSinkButton[S](impl, ggDragRegion),
          HGlue,
          HStrut(4),
          transportView.component,
          HStrut(4)
        )
      }

      val pane = new BorderPanel {
        layoutManager.setVgap(2)
        add(topPane,            BorderPanel.Position.North )
        add(sonoView.component, BorderPanel.Position.Center)
      }

      component = pane
    }

    def obj(implicit tx: S#Tx): Obj.T[S, AudioGraphemeElem] = holder()
  }

  private final class BusSinkButton[S <: Sys[S]](view: AudioFileView[S], export: DnD.Button[S])
    extends Button("Drop bus") {

    icon        = new ImageIcon(Mellite.getClass.getResource("dropicon16.png"))
    // this doesn't have any effect?
    // GUI.fixWidth(this)
    foreground  = Color.gray
    focusable   = false

    // private var item = Option.empty[stm.Source[S#Tx, Element.Int[S]]]

    private val trns = new TransferHandler {
      // how to enforce a drop action: https://weblogs.java.net/blog/shan_man/archive/2006/02/choosing_the_dr.html
      override def canImport(support: TransferSupport): Boolean =
        if (support.isDataFlavorSupported(FolderView.selectionFlavor) &&
           ((support.getSourceDropActions & TransferHandler.COPY) != 0)) {
          support.setDropAction(TransferHandler.COPY)
          true
        } else false

      override def importData(support: TransferSupport): Boolean = {
        val t     = support.getTransferable
        val data  = t.getTransferData(FolderView.selectionFlavor).asInstanceOf[FolderView.SelectionDnDData[S]]
        (data.document == view.document) && {
          data.selection.exists { nodeView =>
            nodeView.renderData match {
              case ev: ObjView.Int[S] =>
                export.bus  = Some(ev.obj)
                text        = ev.name
                foreground  = null
                repaint()
                true

              case _ => false
            }
          }
        }
      }
    }
    peer.setTransferHandler(trns)
  }
}