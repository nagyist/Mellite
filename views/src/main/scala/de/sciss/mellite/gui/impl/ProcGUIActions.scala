package de.sciss.mellite
package gui
package impl

import de.sciss.synth.proc.{Timeline, Scan}
import de.sciss.mellite.gui.impl.timeline.ProcView
import de.sciss.lucre.synth.Sys
import de.sciss.lucre.swing._

/** These actions require being executed on the EDT. */
object ProcGUIActions {
  // scalac still has bug finding Timeline.Modifiable
  // private type TimelineMod[S <: Sys[S]] = Timeline.Modifiable[S]
  private type TimelineMod[S <: Sys[S]] = Timeline.Modifiable[S] // , Obj.T[S, Proc.Elem], Obj.UpdateT[S, Proc.Elem[S]]]

  def removeProcs[S <: Sys[S]](group: TimelineMod[S], views: TraversableOnce[ProcView[S]])(implicit tx: S#Tx): Unit = {
    requireEDT()
    views.foreach { pv =>
      val span  = pv.span()
      val proc  = pv.proc

      def deleteLinks(map: ProcView.LinkMap[S])(fun: (String, Scan[S], String, Scan[S]) => Unit): Unit =
        for {
          (thisKey, links)                  <- map
          ProcView.Link(thatView, thatKey)  <- links
          thisScan                          <- proc.elem.peer.scans.get(thisKey)
          thatScan                          <- thatView.proc.elem.peer.scans.get(thatKey)
        } {
          fun(thisKey, thisScan, thatKey, thatScan)
        }

      deleteLinks(pv.inputs ) { (thisKey, thisScan, thatKey, thatScan) =>
        ProcActions.removeLink(sourceKey = thatKey, source = thatScan, sinkKey = thisKey, sink = thisScan)
      }
      deleteLinks(pv.outputs) { (thisKey, thisScan, thatKey, thatScan) =>
        ProcActions.removeLink(sourceKey = thisKey, source = thisScan, sinkKey = thatKey, sink = thatScan)
      }

      group.remove(span, proc)
    }
  }
}
