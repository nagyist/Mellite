/*
 *  TimelineFrame.scala
 *  (Mellite)
 *
 *  Copyright (c) 2012-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss
package mellite
package gui

import desktop.Window
import de.sciss.synth.proc.AuralSystem
import impl.timeline.{FrameImpl => Impl}
import lucre.stm
import de.sciss.lucre.stm.Disposable
import de.sciss.lucre.synth.Sys

object TimelineFrame {
  def apply[S <: Sys[S]](document: Document[S], group: Element.ProcGroup[S])
                        (implicit tx: S#Tx, cursor: stm.Cursor[S],
                         aural: AuralSystem): TimelineFrame[S] =
    Impl(document, group)
}
trait TimelineFrame[S <: Sys[S]] extends Disposable[S#Tx] {
  def window: Window
  def view: TimelineView[S]
}